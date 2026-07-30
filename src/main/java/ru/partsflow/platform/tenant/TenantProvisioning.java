package ru.partsflow.platform.tenant;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Создание нового арендатора.
 *
 * <p>До этого клиент заводился руками в четыре приёма: создать схему, накатить
 * миграции, вписать строку в реестр, вызвать {@code /api/members/bootstrap}
 * под секретом. Забыть один шаг ничего не стоило, а последствия расходились:
 * схема без записи в реестре не получает событий, запись без схемы валит вход.
 *
 * <p><b>Порядок жёсткий, и он не косметика.</b>
 *
 * <p>Сначала запись в реестре со статусом {@code PROVISIONING}: она резервирует
 * номер и код компании. Два одновременных создания иначе возьмут один номер —
 * уникальность стережёт база, а не проверка «а нет ли уже такого».
 *
 * <p>Потом схема и миграции. Всё это время арендатор невидим: релей outbox
 * идёт по {@code ACTIVE}, и полусозданная схема без таблицы {@code outbox}
 * валила бы ему каждый заход.
 *
 * <p>Потом владелец — до перевода в {@code ACTIVE}. Арендатор без единой
 * учётной записи это компания, в которую нельзя войти, и починить её можно
 * только тем самым секретом, от которого мы уходим.
 *
 * <p>И только в конце {@code ACTIVE}.
 *
 * <p><b>Сорвавшийся провижининг оставляет запись в {@code PROVISIONING}.</b>
 * Откатывать нечего: схема могла создаться, миграции — накатиться наполовину.
 * Видимая запись в незавершённом состоянии честнее, чем тихая уборка, после
 * которой в базе остаются осиротевшие схемы.
 */
@Service
public class TenantProvisioning {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioning.class);

    /** Код компании — часть будущего поддомена, отсюда и ограничения. */
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}");

    private static final String TENANT_CHANGELOG = "db/changelog/db.changelog-tenant.xml";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;

    public TenantProvisioning(JdbcTemplate jdbc, DataSource dataSource,
                              PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
    }

    public Result provision(Request request) {
        String code = normalizeCode(request.companyCode());
        validate(request, code);

        Reserved reserved = reserve(code, request.companyName());
        try {
            createSchema(reserved.schema());
            migrate(reserved.schema());
            createOwner(reserved.schema(), request);
            createFirstWarehouse(reserved.schema(), request.companyName());
            activate(reserved.tenantId());
        } catch (RuntimeException e) {
            log.error("Провижининг арендатора {} ({}) сорвался, запись осталась "
                    + "в состоянии PROVISIONING", reserved.schema(), code, e);
            throw e;
        }

        log.info("Арендатор {} создан: схема {}, владелец {}",
                code, reserved.schema(), request.ownerLogin());
        return new Result(reserved.tenantId(), reserved.schema(), code);
    }

    /**
     * Занимает номер и код компании.
     *
     * <p><b>От гонки защищает первичный ключ, а не транзакция.</b> Два
     * одновременных создания прочитают один и тот же «максимальный плюс один»
     * при любом уровне изоляции ниже сериализуемого; проигравший получит отказ
     * на вставке. Это и есть нужное поведение — тот же приём, что с ключом
     * идемпотентности приёмки: уникальность стережёт база, а не проверка
     * «нет ли уже такого».
     *
     * <p>Оборачивать в {@code @Transactional} тут бессмысленно вдвойне: метод
     * вызывается из того же бина, и аннотация прошла бы мимо прокси — молча.
     */
    private Reserved reserve(String code, String companyName) {
        Long tenantId = jdbc.queryForObject("""
                SELECT COALESCE(max(tenant_id), 0) + 1 FROM public.tenant_registry""", Long.class);
        String schema = "t_%06d".formatted(tenantId);

        try {
            jdbc.update("""
                    INSERT INTO public.tenant_registry
                        (tenant_id, schema_name, company_name, code, status)
                    VALUES (?, ?, ?, ?, 'PROVISIONING')""",
                    tenantId, schema, companyName, code);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Код занят или номер увели параллельно — второе означает гонку,
            // и повторить попытку должен вызывающий, а не мы молча.
            throw new IllegalStateException(
                    "Код компании «%s» занят либо арендатор создаётся параллельно".formatted(code), e);
        }
        return new Reserved(tenantId, schema);
    }

    /**
     * Создаёт схему.
     *
     * <p>Схему создаём мы, а не миграции: {@code DATABASECHANGELOG} лежит
     * внутри неё, и Liquibase заводит его до первого changeset'а. Имя собрано
     * из номера, а не пришло снаружи, — подстановка в DDL иначе была бы дырой.
     *
     * <p><b>Без {@code IF NOT EXISTS} намеренно.</b> Схема с таким именем уже
     * есть — значит номер разошёлся с реальностью: остался мусор от
     * сорвавшегося провижининга или кто-то создал её руками. Молча принять её
     * значит отдать новому клиенту чужие данные, а узнать об этом он может
     * первым же входом в чужой склад. Пусть падает.
     */
    private void createSchema(String schema) {
        try {
            jdbc.execute("CREATE SCHEMA " + schema);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new IllegalStateException(
                    "Схема %s уже существует. Реестр разошёлся с базой — разбираться руками: "
                            .formatted(schema) + "молча занять чужую схему нельзя", e);
        }
    }

    /**
     * Накатывает миграции арендатора.
     *
     * <p>Changelog берётся из артефакта: он попадает туда сборкой из
     * {@code db/changelog}. {@code DATABASECHANGELOG} — внутри схемы клиента,
     * это даёт независимое версионирование и параллельные миграции.
     */
    private void migrate(String schema) {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setLiquibaseSchemaName(schema);
            database.setDefaultSchemaName(schema);

            try (Liquibase liquibase = new Liquibase(TENANT_CHANGELOG,
                    new ClassLoaderResourceAccessor(), database)) {
                liquibase.setChangeLogParameter("tenant.schema", schema);
                liquibase.update(new Contexts());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Миграции арендатора " + schema + " не накатились", e);
        }
    }

    /**
     * Заводит владельца.
     *
     * <p>Схема в SQL квалифицируется руками: {@code TenantContext} тут
     * не поможет — {@code search_path} выставляет провайдер Hibernate внутри
     * транзакции JPA, а мы работаем с только что созданной схемой напрямую.
     * Имя схемы собрано из номера, подстановка безопасна.
     */
    private void createOwner(String schema, Request request) {
        jdbc.update("""
                INSERT INTO %s.tenant_member (display_name, role, login, password_hash)
                VALUES (?, 'OWNER', ?, ?)""".formatted(schema),
                request.ownerName() == null || request.ownerName().isBlank()
                        ? "Владелец" : request.ownerName().strip(),
                request.ownerLogin().strip(),
                passwordEncoder.encode(request.ownerPassword()));
    }

    /**
     * Заводит первый филиал и склад.
     *
     * <p>Ячейки не заводим: это физические полки, их коды знает только клиент,
     * и придуманные за него адреса разойдутся с тем, что написано на стеллаже.
     */
    private void createFirstWarehouse(String schema, String companyName) {
        Long branchId = jdbc.queryForObject(
                "INSERT INTO %s.branch (name) VALUES (?) RETURNING id".formatted(schema),
                Long.class, companyName.strip());

        jdbc.update("INSERT INTO %s.warehouse (branch_id, name) VALUES (?, 'Основной')"
                .formatted(schema), branchId);
    }

    private void activate(long tenantId) {
        jdbc.update("""
                UPDATE public.tenant_registry
                   SET status = 'ACTIVE', migrated_at = now()
                 WHERE tenant_id = ?""", tenantId);
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
    }

    private static void validate(Request request, String code) {
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Код компании — от 2 до 31 символа: латиница, цифры и дефис. Получено: «%s»"
                            .formatted(code));
        }
        if (request.companyName() == null || request.companyName().isBlank()) {
            throw new IllegalArgumentException("Название компании обязательно");
        }
        if (request.ownerLogin() == null || request.ownerLogin().isBlank()) {
            throw new IllegalArgumentException("Логин владельца обязателен");
        }
        // Тот же нижний предел, что у смены пароля: владелец — самая ценная
        // учётная запись арендатора, и заводить её со слабым паролем незачем.
        if (request.ownerPassword() == null || request.ownerPassword().length() < 8) {
            throw new IllegalArgumentException("Пароль владельца — минимум 8 символов");
        }
    }

    public record Request(String companyCode, String companyName,
                          String ownerLogin, String ownerPassword, String ownerName) {
    }

    public record Result(long tenantId, String schemaName, String companyCode) {
    }

    record Reserved(long tenantId, String schema) {
    }
}
