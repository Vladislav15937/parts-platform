package ru.partsflow.sales;

import org.springframework.jdbc.core.JdbcTemplate;
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
        return jdbc.queryForObject("""
                INSERT INTO customer (name, phone, email, customer_type)
                VALUES (?, ?, ?, ?)
                RETURNING id, name, phone, email, customer_type""",
                CustomerService::map,
                name.strip(), blankToNull(phone), blankToNull(email),
                customerType == null || customerType.isBlank() ? "PERSON" : customerType);
    }

    /**
     * Раздел «Клиенты»: список с балансом, растущий предел вместо курсора.
     *
     * <p>Тот же приём, что и у реестра возвратов (задача 0021): список читают
     * с конца и не листают вглубь, поэтому «Показать ещё» дороже первой
     * загрузки только на добавленные строки, а не на всю пройденную глубину,
     * как было бы с {@code OFFSET}.
     *
     * <p>Баланс не хранится полем — колонку {@code customer.balance} убрали
     * вместе с триггером, который её вёл и который врал (changeset
     * {@code tenant-045}: пополнение и выдача складывались одним знаком).
     * Здесь остаток агрегируется в SQL одним запросом на всю страницу,
     * а не через {@link SalesService#accountBalance} по каждой строке:
     * список — это сотни клиентов, и запрос на строку был бы N+1.
     */
    @Transactional(readOnly = true)
    public CustomersPage directory(String query, int limit) {
        String term = query == null ? "" : query.strip();
        String digits = term.replaceAll("\\D", "");
        boolean filtered = !term.isEmpty();

        String from = " FROM customer c " + BALANCE_JOIN;
        String where = filtered
                ? " WHERE c.name ILIKE '%' || ? || '%'"
                        + " OR (? <> '' AND regexp_replace(COALESCE(c.phone, ''), '\\D', '', 'g')"
                        + " LIKE '%' || ? || '%')"
                : "";
        List<Object> args = new ArrayList<>();
        if (filtered) {
            args.add(term);
            args.add(digits);
            args.add(digits);
        }

        long total = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(limit);
        List<CustomerDetail> items = jdbc.query(
                "SELECT c.id, c.name, c.phone, c.email, c.customer_type, c.note, c.public_note,"
                        + " c.inn, c.company_name, COALESCE(bal.balance, 0) AS balance"
                        + from + where + " ORDER BY c.id DESC LIMIT ?",
                CustomerService::mapDetail, rowArgs.toArray());

        return new CustomersPage(items, total);
    }

    /** Карточка клиента: все поля разом, включая остаток лицевого счёта. */
    @Transactional(readOnly = true)
    public CustomerDetail getDetail(Long id) {
        List<CustomerDetail> found = jdbc.query(
                "SELECT c.id, c.name, c.phone, c.email, c.customer_type, c.note, c.public_note,"
                        + " c.inn, c.company_name, COALESCE(bal.balance, 0) AS balance"
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

    /**
     * Агрегат журнала лицевого счёта — то же выражение, что в
     * {@code v_account_discrepancy} и {@code v_customer_settlement}: пополнение,
     * возврат по сделке и правка прибавляют, оплата и выдача вычитают.
     */
    private static final String BALANCE_JOIN =
            "LEFT JOIN (SELECT customer_id, sum(CASE entry_type"
            + " WHEN 'TOP_UP' THEN amount"
            + " WHEN 'DEAL_REFUND' THEN amount"
            + " WHEN 'CORRECTION' THEN amount"
            + " ELSE -amount END) AS balance"
            + " FROM customer_account_entry GROUP BY customer_id) bal"
            + " ON bal.customer_id = c.id ";

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
     * @param balance    остаток лицевого счёта, считанный по журналу
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
