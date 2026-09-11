package ru.partsflow.sales;

import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.shared.RetailCustomer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиенты: найти позвонившего, завести нового, вести карточку.
 *
 * <p><b>Транзакция здесь обязательна, хотя запросы читающие.</b> Схему
 * арендатора выставляет провайдер соединений Hibernate, и делает это только
 * внутри транзакции. {@code JdbcTemplate}, взявший соединение прямо из пула,
 * смотрит в {@code public}, где никакой {@code customer} нет.
 */
@Service
public class CustomerService {

    private final JdbcTemplate jdbc;

    public CustomerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Поиск по имени и телефону одной строкой.
     *
     * <p>Режим продавец не выбирает — он набирает то, что услышал. Телефон
     * сравнивается по цифрам: один и тот же номер записывают и с +7, и с 8,
     * и со скобками, и точное сравнение не найдёт ничего.
     */
    @Transactional(readOnly = true)
    public List<Customer> search(String query, int limit) {
        String term = query == null ? "" : query.strip();
        if (term.isEmpty()) {
            return jdbc.query("""
                    SELECT id, name, phone, email, customer_type
                      FROM customer ORDER BY id DESC LIMIT ?""", CustomerService::map, limit);
        }
        String digits = term.replaceAll("\\D", "");

        return jdbc.query("""
                SELECT id, name, phone, email, customer_type
                  FROM customer
                 WHERE name ILIKE '%' || ? || '%'
                    OR (? <> '' AND regexp_replace(COALESCE(phone, ''), '\\D', '', 'g')
                                    LIKE '%' || ? || '%')
                 ORDER BY id DESC
                 LIMIT ?""", CustomerService::map, term, digits, digits, limit);
    }

    @Transactional
    public Customer create(String name, String phone, String email, String customerType) {
        // Второго «Частного лица» не заводим ни при каком входе: это
        // контрагент розничной продажи, он один на арендатора. Сюда можно
        // попасть, набрав то же имя руками (подсказка поиска не пришла —
        // сеть моргнула), и вторая строка с тем же именем сделала бы
        // подстановку по умолчанию неоднозначной, а отчёт по клиентам —
        // двумя строками об одном.
        if (RetailCustomer.NAME.equalsIgnoreCase(name.strip())) {
            return retail();
        }
        return jdbc.queryForObject("""
                INSERT INTO customer (name, phone, email, customer_type)
                VALUES (?, ?, ?, ?)
                RETURNING id, name, phone, email, customer_type""",
                CustomerService::map,
                name.strip(), blankToNull(phone), blankToNull(email),
                customerType == null || customerType.isBlank() ? "PERSON" : customerType);
    }

    /**
     * Контрагент розничной продажи: заводится один раз на арендатора.
     *
     * <p>Половина продаж на разборке — человек с улицы, которому нечего
     * заводить в справочник. Пока клиент был обязателен, продавец набирал
     * имя и жал «Завести клиента», и справочник зарастал «мужиками
     * на приоре»; теперь на его месте стоит один контрагент, на который
     * ложатся печатные формы, возвраты и лицевой счёт, — {@code NULL}
     * заставил бы ветвиться каждое из этих мест.
     *
     * <p><b>Заводит его провижининг</b> ({@code TenantProvisioning}), а этот
     * метод — тот же контрагент для арендаторов, заведённых раньше правки:
     * наполнить их схемы миграцией нельзя, не тронув {@code db/changelog}.
     *
     * <p><b>Блокировка нужна, потому что уникального индекса по имени нет.</b>
     * Два продавца, открывшие экран продажи одновременно, прочитали бы «нет
     * такого» оба и завели бы двух — то самое удвоение, ради которого
     * контрагент и один. Условие в {@code WHERE} тут не помогает: строка
     * ещё не существует, блокировать нечего, — та же причина, по которой
     * остаток лицевого счёта проверяется под блокировкой строки клиента,
     * а не внутри {@code INSERT}. Блокировка на время транзакции, ключ —
     * имя схемы: у соседнего арендатора свой.
     */
    @Transactional
    public Customer retail() {
        List<Customer> existing = findRetail();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null,
                ru.partsflow.platform.tenant.TenantContext.require() + "/retail-customer");

        // Перечитываем под блокировкой: пока её ждали, контрагента мог
        // завести сосед.
        List<Customer> afterLock = findRetail();
        if (!afterLock.isEmpty()) {
            return afterLock.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO customer (name, customer_type)
                VALUES (?, 'PERSON')
                RETURNING id, name, phone, email, customer_type""",
                CustomerService::map, RetailCustomer.NAME);
    }

    /**
     * Номер контрагента розничной продажи или {@code null}, если его ещё нет.
     *
     * <p>Только чтение: отчёты спрашивают «это он?» и заводить его не должны —
     * арендатор, в котором ни разу не продавали, не обязан обзаводиться
     * контрагентом от того, что владелец открыл отчёт.
     */
    @Transactional(readOnly = true)
    public Long retailCustomerId() {
        List<Customer> found = findRetail();
        return found.isEmpty() ? null : found.get(0).id();
    }

    /**
     * Самый ранний контрагент с этим именем.
     *
     * <p>{@code ORDER BY id} — на случай, если второй всё же появился
     * (прямым SQL или переносом с прежней системы): все читатели тогда
     * возьмут одного и того же, а не разных в зависимости от плана запроса.
     */
    private List<Customer> findRetail() {
        return jdbc.query("""
                SELECT id, name, phone, email, customer_type
                  FROM customer WHERE name = ? ORDER BY id LIMIT 1""",
                CustomerService::map, RetailCustomer.NAME);
    }

    /**
     * Раздел «Клиенты»: список с балансом, растущий предел вместо курсора.
     *
     * <p><b>Отбор идёт по имени, почте и телефону.</b> Почта — потому что
     * у постоянного покупателя-юрлица её и называют вместо телефона, и потому
     * что колонка «Почта» в списке есть: колонка, по которой нельзя найти,
     * читается как «поиск сломан». Телефон сравнивается по цифрам (один и тот
     * же номер пишут и с +7, и с 8), имя и почта — вхождением без учёта
     * регистра.
     *
     * <p>Тот же приём, что и у реестра возвратов (задача 0021): список читают
     * с конца и не листают вглубь, поэтому «Показать ещё» дороже первой
     * загрузки только на добавленные строки, а не на всю пройденную глубину,
     * как было бы с {@code OFFSET}.
     *
     * <p><b>Баланс здесь — не остаток лицевого счёта</b> (тот показывает
     * карточка отдельно, подписью «На счету», и он никогда не уходит в минус —
     * зачесть больше остатка нельзя ни одной операцией). Это чистая позиция,
     * как у ориентира: аванс на счёте минус долг по выданным и неоплаченным
     * сделкам, — только так «клиент с долгом» вообще может показаться
     * отрицательным числом. Формула та же, что в {@code v_customer_settlement}
     * (`account_balance - debt`), только оба слагаемых остаются одним SQL-
     * агрегатом на всю страницу, а не вызовом {@link SalesService#accountBalance}
     * на строку: список — это сотни клиентов, и запрос на строку был бы N+1.
     */
    @Transactional(readOnly = true)
    public CustomersPage directory(String query, int limit) {
        String term = query == null ? "" : query.strip();
        String digits = term.replaceAll("\\D", "");
        boolean filtered = !term.isEmpty();

        String from = " FROM customer c " + BALANCE_JOIN;
        String where = filtered
                ? " WHERE c.name ILIKE '%' || ? || '%'"
                        + " OR c.email ILIKE '%' || ? || '%'"
                        + " OR (? <> '' AND regexp_replace(COALESCE(c.phone, ''), '\\D', '', 'g')"
                        + " LIKE '%' || ? || '%')"
                : "";
        List<Object> args = new ArrayList<>();
        if (filtered) {
            args.add(term);
            args.add(term);
            args.add(digits);
            args.add(digits);
        }

        long total = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(limit);
        List<CustomerDetail> items = jdbc.query(
                "SELECT c.id, c.name, c.phone, c.email, c.customer_type, c.note, c.public_note,"
                        + " c.inn, c.company_name, " + NET_BALANCE_EXPR + " AS balance"
                        + from + where + " ORDER BY c.id DESC LIMIT ?",
                CustomerService::mapDetail, rowArgs.toArray());

        return new CustomersPage(items, total);
    }

    /**
     * Карточка клиента: все поля разом, включая чистую позицию по счёту
     * и долгу (см. {@link #directory} — то же выражение).
     */
    @Transactional(readOnly = true)
    public CustomerDetail getDetail(Long id) {
        List<CustomerDetail> found = jdbc.query(
                "SELECT c.id, c.name, c.phone, c.email, c.customer_type, c.note, c.public_note,"
                        + " c.inn, c.company_name, " + NET_BALANCE_EXPR + " AS balance"
                        + " FROM customer c " + BALANCE_JOIN
                        + " WHERE c.id = ?",
                CustomerService::mapDetail, id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Клиент не найден: " + id);
        }
        return found.get(0);
    }

    /**
     * Правка карточки: имя, контакты, примечание и заметка, юрлицо.
     *
     * <p>Примечание видно клиенту и печатается в накладной ({@code public_note}),
     * заметка — только своим ({@code note}); перепутать их местами значит
     * напечатать клиенту то, что писали для себя.
     */
    @Transactional
    public CustomerDetail update(Long id, String name, String phone, String email,
                                 String publicNote, String note, String customerType,
                                 String inn, String companyName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя или название обязательно");
        }
        String type = customerType == null || customerType.isBlank() ? "PERSON" : customerType;
        if (!type.equals("PERSON") && !type.equals("COMPANY")) {
            throw new IllegalArgumentException("Неверный тип клиента: " + customerType);
        }
        // Имя — единственный признак, по которому система узнаёт контрагента
        // розничной продажи (колонки-признака в схеме нет). Переименованный,
        // он перестал бы находиться, и следующая продажа завела бы второго;
        // чужая карточка, названная так же, стала бы неотличима от него.
        // Остальные поля — телефон, почту, примечание — править можно.
        Long retailId = retailCustomerId();
        boolean renamingRetail = retailId != null && retailId.equals(id)
                && !RetailCustomer.NAME.equalsIgnoreCase(name.strip());
        boolean takingRetailName = (retailId == null || !retailId.equals(id))
                && RetailCustomer.NAME.equalsIgnoreCase(name.strip());
        if (renamingRetail || takingRetailName) {
            throw new IllegalStateException(
                    "«%s» — контрагент розничной продажи, он один и переименованию не подлежит"
                            .formatted(RetailCustomer.NAME));
        }

        int updated = jdbc.update("""
                UPDATE customer
                   SET name = ?, phone = ?, email = ?, public_note = ?, note = ?,
                       customer_type = ?, inn = ?, company_name = ?, updated_at = now()
                 WHERE id = ?""",
                name.strip(), blankToNull(phone), blankToNull(email),
                blankToNull(publicNote), blankToNull(note), type,
                blankToNull(inn), blankToNull(companyName), id);
        if (updated == 0) {
            throw new IllegalArgumentException("Клиент не найден: " + id);
        }
        return getDetail(id);
    }

    /** Чистая позиция: остаток счёта минус долг по выданным сделкам. */
    private static final String NET_BALANCE_EXPR =
            "COALESCE(bal.balance, 0) - COALESCE(debt.debt, 0)";

    /**
     * Два независимых агрегата, оба — те же выражения, что уже стоят
     * в {@code v_customer_settlement}: остаток журнала (там же, где
     * {@code v_account_discrepancy}: пополнение, возврат по сделке и правка
     * прибавляют, оплата и выдача вычитают) и долг по сделкам, которые
     * уже отдали клиенту, но не оплачены целиком. Пока товар не выдан —
     * это не долг, а обещание, и требовать по нему нечего (то же правило,
     * что у {@code Deal.debt()}, только вьюха и эта директория читают его
     * сами SQL-агрегатом: звать сервис на каждую строку страницы значило бы
     * N+1 на сотнях клиентов).
     */
    private static final String BALANCE_JOIN =
            "LEFT JOIN (SELECT customer_id, sum(CASE entry_type"
            + " WHEN 'TOP_UP' THEN amount"
            + " WHEN 'DEAL_REFUND' THEN amount"
            + " WHEN 'CORRECTION' THEN amount"
            + " ELSE -amount END) AS balance"
            + " FROM customer_account_entry GROUP BY customer_id) bal"
            + " ON bal.customer_id = c.id "
            + "LEFT JOIN (SELECT customer_id, sum(total_amount - paid_amount) AS debt"
            + " FROM deal WHERE status = 'ISSUED' AND total_amount > paid_amount"
            + " GROUP BY customer_id) debt"
            + " ON debt.customer_id = c.id ";

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Customer map(ResultSet rs, int row) throws SQLException {
        return new Customer(rs.getLong("id"), rs.getString("name"), rs.getString("phone"),
                rs.getString("email"), rs.getString("customer_type"));
    }

    private static CustomerDetail mapDetail(ResultSet rs, int row) throws SQLException {
        return new CustomerDetail(rs.getLong("id"), rs.getString("name"), rs.getString("phone"),
                rs.getString("email"), rs.getString("customer_type"), rs.getString("note"),
                rs.getString("public_note"), rs.getString("inn"), rs.getString("company_name"),
                rs.getBigDecimal("balance"));
    }

    public record Customer(Long id, String name, String phone, String email, String customerType) {
    }

    /**
     * Карточка клиента целиком — поля из схемы, которые до этой задачи были
     * недоступны человеку (корневой {@code CLAUDE.md}: «поля в схеме есть,
     * а заполнить их нечем»).
     *
     * @param note       заметка для себя — нигде не печатается
     * @param publicNote примечание клиенту — печатается в накладной
     * @param balance    чистая позиция: остаток лицевого счёта минус долг
     *                    по выданным и не оплаченным целиком сделкам —
     *                    не то же самое, что «На счету» в карточке (тот
     *                    только журнал и в минус не уходит)
     */
    public record CustomerDetail(Long id, String name, String phone, String email,
                                 String customerType, String note, String publicNote,
                                 String inn, String companyName, BigDecimal balance) {
    }

    /**
     * @param total сколько нашлось по отбору — список может быть обрезан пределом,
     *              как и у реестра возвратов
     */
    public record CustomersPage(List<CustomerDetail> items, long total) {
    }
}
