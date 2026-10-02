package ru.partsflow.platform.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.platform.tenant.TenantMigrations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Что печатается в документах сделки: реквизиты продавца, текст про гарантию,
 * пометка об НДС, поля подписей и реквизиты юр. лица (задача 0051).
 *
 * <p><b>Реквизитов два уровня, и один не заменяет другого.</b> Блок на склад
 * («ИП Санин Д.В., Барнаул, Ткацкая 626») — это тот, кто продал, и он идёт
 * в товарный чек и накладную; реквизиты организации (ИНН, КПП, банк, р/с, к/с,
 * руководитель, главный бухгалтер) — в счёт на юр. лицо. У клиента ориентира
 * на двух складах стоят <b>разные ИП</b> (§7 {@code docs/bazon-parity.md},
 * обход 29–30.07.2026), то есть один общий блок напечатал бы на чеке не того
 * продавца — и заметил бы это покупатель, пришедший по гарантии через две
 * недели.
 *
 * <p><b>Почему колонки, а не jsonb.</b> Рядом лежит {@link MemberSettingsService}
 * со свободным jsonb — там состояние экрана одного человека. Здесь данные
 * предприятия: их ставит владелец, а печатает их каждый продавец на каждой
 * продаже. Правило уже записано в {@code db/CLAUDE.md} вместе с ценой: каждая
 * следующая настройка компании — колонка и changeset.
 *
 * <p><b>Читается на каждой печати и не кэшируется</b> — тот же довод, что
 * у {@link CompanySettingsService}: один запрос по первичному ключу
 * однострочной таблицы, а кэш пришлось бы сбрасывать по арендаторам во всех
 * узлах ячейки, и цена ошибки — владелец поправил реквизиты, а чеки сутки
 * печатаются со старыми.
 */
@Service
public class PrintSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PrintSettingsService.class);

    /**
     * Колонка-признак: по ней видно, докатана ли схема до {@code tenant/067}.
     *
     * <p>Спрашивается именно колонка, а не таблица. {@code company_setting}
     * появилась в {@code tenant/066}, и у арендатора, накатанного до неё,
     * таблица есть, а печатных настроек в ней нет — то есть проверка «таблица
     * на месте» ответила бы «всё хорошо» и отправила бы запрос за колонкой,
     * которой нет. Отставание на один changeset — обычное состояние между
     * накатом и выкладкой ({@code SchemaVersionCheck} про это и пишет при
     * старте).
     */
    private static final String MARKER = "print_extra_text";

    /** Схемы, про которые уже сказано, что они отстали: по строке, а не по печати. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private final JdbcTemplate jdbc;

    public PrintSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Всё, что печатается, одним ответом.
     *
     * <p><b>Отставшая схема не отнимает печать — она отнимает только
     * настройки.</b> Тот же довод, по которому
     * {@code CompanySettingsService.read()} отдаёт прежние три дня, а не
     * пятисотку: чтение стоит на пути документа, который продавец печатает
     * покупателю, и {@code SELECT} по несуществующей колонке уходил бы
     * {@code BadSqlGrammarException}'ом, то есть 500 «Внутренняя ошибка»
     * на выдаче товара. Пустые настройки дают чек без текста про гарантию
     * и без пометки об НДС — документ, который всё-таки можно отдать в руки.
     *
     * <p>Склады при этом читаются всегда: их имена нужны и на отставшей схеме,
     * иначе экран настроек встретил бы владельца пустым списком, из которого
     * не видно, что настраивать.
     */
    @Transactional(readOnly = true)
    public PrintSettings read() {
        if (!ready()) {
            warnOnce();
            return new PrintSettings(null, null, true, false, Legal.empty(), warehousesWithout());
        }
        List<WarehouseDetails> blocks = warehouses();
        List<PrintSettings> found = jdbc.query("""
                SELECT print_extra_text, print_vat_note,
                       print_client_signature, print_issuer_signature,
                       legal_name, legal_address, legal_inn, legal_kpp,
                       legal_bank_name, legal_bank_bic, legal_bank_account,
                       legal_bank_corr_account, legal_director, legal_chief_accountant
                  FROM company_setting WHERE id = 1""",
                (rs, row) -> settings(rs, blocks));

        // Строку заводит тот же changeset, что и таблицу, — но арендатора могли
        // восстановить из дампа, снятого посреди миграции. Умолчания те же,
        // что в схеме: подпись клиента печатается, подпись выдавшего — нет.
        return found.isEmpty()
                ? new PrintSettings(null, null, true, false, Legal.empty(), blocks)
                : found.get(0);
    }

    /**
     * Пишет настройки целиком — и настройки компании, и блоки складов.
     *
     * <p><b>Одним методом, потому что это одна форма.</b> Экран «Печатные
     * формы» показывает их вместе и отправляет целиком: два пути записи
     * означали бы наполовину сохранённую настройку при отказе второго —
     * владелец ушёл бы со страницы, думая, что реквизиты заданы.
     *
     * <p><b>Отставшая схема отвечает отказом, а не молчаливым «сохранено»</b> —
     * обратное решение по сравнению с чтением, и ровно то же, что
     * у {@code CompanySettingsService.update}: прочитать можно с умолчанием,
     * а сохранить некуда, и «сохранено» было бы враньём о том, что теперь
     * печатается в чеке.
     *
     * <p>Пустая строка приводится к {@code NULL}, и это не косметика: пусто
     * означает «не заполнено», а не значение. Для пометки об НДС это прямо
     * пункт критерия приёмки — незаданная пометка не печатает строку вовсе,
     * а пустая строка в колонке дала бы в документе пустую строку.
     */
    @Transactional
    public PrintSettings update(String extraText, String vatNote,
                                boolean clientSignature, boolean issuerSignature,
                                Legal legal, List<WarehouseDetails> blocks) {
        if (!ready()) {
            warnOnce();
            throw new IllegalStateException(
                    "Настройки печати ещё не накатаны на схему вашей компании — "
                            + "сохранить их некуда. Это чинит администратор сервиса: "
                            + "нужен накат миграций. Пока документы печатаются "
                            + "без реквизитов и без текста про гарантию.");
        }
        Legal values = legal == null ? Legal.empty() : legal;

        jdbc.update("""
                INSERT INTO company_setting (id, print_extra_text, print_vat_note,
                        print_client_signature, print_issuer_signature,
                        legal_name, legal_address, legal_inn, legal_kpp,
                        legal_bank_name, legal_bank_bic, legal_bank_account,
                        legal_bank_corr_account, legal_director, legal_chief_accountant)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                        print_extra_text = EXCLUDED.print_extra_text,
                        print_vat_note = EXCLUDED.print_vat_note,
                        print_client_signature = EXCLUDED.print_client_signature,
                        print_issuer_signature = EXCLUDED.print_issuer_signature,
                        legal_name = EXCLUDED.legal_name,
                        legal_address = EXCLUDED.legal_address,
                        legal_inn = EXCLUDED.legal_inn,
                        legal_kpp = EXCLUDED.legal_kpp,
                        legal_bank_name = EXCLUDED.legal_bank_name,
                        legal_bank_bic = EXCLUDED.legal_bank_bic,
                        legal_bank_account = EXCLUDED.legal_bank_account,
                        legal_bank_corr_account = EXCLUDED.legal_bank_corr_account,
                        legal_director = EXCLUDED.legal_director,
                        legal_chief_accountant = EXCLUDED.legal_chief_accountant,
                        updated_at = now()""",
                blankToNull(extraText), blankToNull(vatNote),
                clientSignature, issuerSignature,
                blankToNull(values.name()), blankToNull(values.address()),
                blankToNull(values.inn()), blankToNull(values.kpp()),
                blankToNull(values.bankName()), blankToNull(values.bankBic()),
                blankToNull(values.bankAccount()), blankToNull(values.bankCorrAccount()),
                blankToNull(values.director()), blankToNull(values.chiefAccountant()));

        for (WarehouseDetails block : blocks == null ? List.<WarehouseDetails>of() : blocks) {
            if (block == null || block.id() == null) {
                continue;
            }
            int updated = jdbc.update("UPDATE warehouse SET print_details = ? WHERE id = ?",
                    blankToNull(block.details()), block.id());
            if (updated == 0) {
                // Без номера строки в базе: владелец правил поле у строки
                // списка, а не набирал идентификатор. Единственный способ сюда
                // попасть — склад убрали, пока форма была открыта; тем же
                // отвечают PaymentSourceService и CustomerService.
                throw new IllegalArgumentException(
                        "Склад не найден — обновите страницу, список устарел");
            }
        }
        return read();
    }

    /**
     * Блок реквизитов одного склада и его имя — для того, кто собирает документ.
     *
     * <p>Отдельный метод, а не {@code read()} у вызывающего: печать спрашивает
     * один склад, и тащить ради него настройки компании со всеми складами
     * незачем. Склада нет — {@code null}, а не исключение: что сказать
     * человеку, решает тот, кто собирает документ.
     */
    @Transactional(readOnly = true)
    public WarehouseDetails warehouseOf(Long warehouseId) {
        if (warehouseId == null) {
            return null;
        }
        List<WarehouseDetails> found = ready()
                ? jdbc.query("SELECT id, name, print_details FROM warehouse WHERE id = ?",
                        PrintSettingsService::block, warehouseId)
                : jdbc.query("SELECT id, name, NULL AS print_details FROM warehouse WHERE id = ?",
                        PrintSettingsService::block, warehouseId);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<WarehouseDetails> warehouses() {
        return jdbc.query("""
                SELECT id, name, print_details FROM warehouse
                 WHERE is_active ORDER BY name, id""", PrintSettingsService::block);
    }

    /** Те же склады на схеме, где колонки реквизитов ещё нет. */
    private List<WarehouseDetails> warehousesWithout() {
        return jdbc.query("""
                SELECT id, name, NULL AS print_details FROM warehouse
                 WHERE is_active ORDER BY name, id""", PrintSettingsService::block);
    }

    private static WarehouseDetails block(ResultSet rs, int row) throws SQLException {
        return new WarehouseDetails(rs.getLong("id"), rs.getString("name"),
                rs.getString("print_details"));
    }

    private static PrintSettings settings(ResultSet rs, List<WarehouseDetails> blocks)
            throws SQLException {
        Legal legal = new Legal(
                rs.getString("legal_name"), rs.getString("legal_address"),
                rs.getString("legal_inn"), rs.getString("legal_kpp"),
                rs.getString("legal_bank_name"), rs.getString("legal_bank_bic"),
                rs.getString("legal_bank_account"), rs.getString("legal_bank_corr_account"),
                rs.getString("legal_director"), rs.getString("legal_chief_accountant"));

        return new PrintSettings(
                rs.getString("print_extra_text"), rs.getString("print_vat_note"),
                rs.getBoolean("print_client_signature"), rs.getBoolean("print_issuer_signature"),
                legal, blocks);
    }

    /**
     * Докатана ли схема до колонок печати.
     *
     * <p>Спрашивается каталог, а не ловится отказ, — тот же довод, что
     * в {@code CompanySettingsService}: внутри чужой транзакции отказавшая
     * инструкция помечает её на откат целиком, и всё, что вызвавший сделает
     * дальше, получит «current transaction is aborted». А здесь вызывающий
     * как раз внутри транзакции: документ собирается вместе со сделкой.
     *
     * <p>{@code current_schema()} — схема арендатора: её ставит провайдер
     * соединений Hibernate внутри транзакции. Без квалификации
     * {@code to_regclass} нашла бы одноимённую таблицу в {@code public}.
     */
    private boolean ready() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pg_attribute
                                WHERE attrelid = to_regclass(current_schema() || '.company_setting')
                                  AND attname = ? AND NOT attisdropped)""",
                Boolean.class, MARKER));
    }

    /** Про отставшую схему говорим один раз, а не на каждый напечатанный чек. */
    private void warnOnce() {
        String schema = TenantContext.getOrNull();
        if (warned.add(String.valueOf(schema))) {
            log.warn("Схема {} не накатана до tenant/067: настроек печати нет, "
                            + "документы сделки печатаются без реквизитов, текста гарантии "
                            + "и пометки об НДС. Привести к версии образа: "
                            + TenantMigrations.MIGRATE_COMMAND, schema);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Реквизиты организации: идут в счёт на юр. лицо.
     *
     * <p>Не блок склада: тот про то, кто физически продал деталь, а счёт
     * юридическое лицо выставляет от себя, со своим ИНН и своим расчётным
     * счётом. У клиента ориентира все эти поля пустые — и это не повод
     * их не заводить: счёт без ИНН и без р/с покупатель не оплатит.
     */
    public record Legal(String name, String address, String inn, String kpp,
                        String bankName, String bankBic, String bankAccount,
                        String bankCorrAccount, String director, String chiefAccountant) {

        public static Legal empty() {
            return new Legal(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * @param details «Название компании, адрес и контакты» одной многострочной
     *                записью — так это заведено у ориентира, и так же это
     *                печатается. Разбирать блок на поля мы не вправе: владелец
     *                вписывает туда то, что считает нужным, включая телефон
     *                и ОГРНИП, а разбор потерял бы всё, что не легло в наши
     *                поля. Пусто — реквизиты не заданы
     */
    public record WarehouseDetails(Long id, String name, String details) {
    }

    /**
     * @param extraText       условия возврата и гарантии: печатается в конце
     *                        документа. Пусто — блока нет вовсе
     * @param vatNote         пометка об НДС. Пусто — строки в документе нет:
     *                        это пункт 6 критерия приёмки задачи 0051, и пустая
     *                        строка тут не то же самое, что «НДС 0»
     * @param clientSignature печатать поле подписи клиента
     * @param issuerSignature печатать поле подписи выдавшего товар
     */
    public record PrintSettings(String extraText, String vatNote,
                                boolean clientSignature, boolean issuerSignature,
                                Legal legal, List<WarehouseDetails> warehouses) {
    }
}
