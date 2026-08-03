package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Value;

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

    /**
     * Ширина диапазона одной ячейки. Миллион при потолке в двести арендаторов —
     * запас, который не кончится, а номер читается глазами: 2 000 003 —
     * третий клиент второй ячейки.
     */
    private static final long CELL_RANGE = 1_000_000L;

    /**
     * Сколько раз пробовать занять номер.
     *
     * <p>Пять: при заведении ячейки пачкой в несколько потоков столкновение
     * случается на каждой десятой заявке, но подряд пять раз проиграть надо
     * очень постараться. Больше — значит прятать настоящую проблему: если
     * не хватает и пяти, арендаторов создают десятками в секунду, и об этом
     * лучше узнать отказом.
     */
    private static final int RESERVE_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TenantSchemaMigrator migrator;
    private final PasswordEncoder passwordEncoder;
    private final long cellNumber;

    public TenantProvisioning(JdbcTemplate jdbc, DataSource dataSource,
                              TenantSchemaMigrator migrator, PasswordEncoder passwordEncoder,
                              @Value("${app.cell-number:1}") long cellNumber) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.migrator = migrator;
        this.passwordEncoder = passwordEncoder;
        if (cellNumber < 1) {
            throw new IllegalArgumentException(
                    "Номер ячейки начинается с единицы: " + cellNumber);
        }
        this.cellNumber = cellNumber;
    }

    public Result provision(Request request) {
        String code = normalizeCode(request.companyCode());
        validate(request, code);

        Reserved reserved = reserve(code, request.companyName());
        try {
            createSchema(reserved.schema());
            migrator.migrate(reserved.schema());
            recordVersion(reserved.tenantId());
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
        // Номер ячейки в старшем разряде: у второй ячейки арендаторы начинаются
        // с 2 000 001, а не с единицы. Иначе схемы t_000001 есть в обеих,
        // и дамп из одной нельзя развернуть в другую без переименования —
        // а переносить клиента между ячейками придётся при первой же
        // перебалансировке. Заодно по номеру видно, где искать клиента.
        long base = cellNumber * CELL_RANGE;

        // Номер берётся «максимальный плюс один», и два одновременных создания
        // прочитают одно и то же при любом уровне изоляции ниже сериализуемого.
        // Уникальность стережёт первичный ключ — но проигравшему надо дать
        // второй номер, а не отказ: при заведении ячейки заявки идут пачкой,
        // и на двухстах арендаторах в четыре потока так терялась каждая
        // десятая. Замерено нагрузочной пробой: 21 отказ на 195 заявок.
        for (int attempt = 1; attempt <= RESERVE_ATTEMPTS; attempt++) {
            Long tenantId = jdbc.queryForObject("""
                    SELECT GREATEST(COALESCE(max(tenant_id), 0), ?) + 1
                      FROM public.tenant_registry""", Long.class, base);
            String schema = "t_%06d".formatted(tenantId);
            try {
                jdbc.update("""
                        INSERT INTO public.tenant_registry
                            (tenant_id, schema_name, company_name, code, status)
                        VALUES (?, ?, ?, ?, 'PROVISIONING')""",
                        tenantId, schema, companyName, code);
                return new Reserved(tenantId, schema);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Код занят — повторять бессмысленно, номер тут ни при чём.
                // Это разные причины, и раньше они шли одним сообщением:
                // оператор массового заведения не мог понять, повторять ему
                // или искать другой код.
                if (codeTaken(code)) {
                    throw new IllegalStateException(
                            "Код компании «%s» занят".formatted(code), e);
                }
                log.debug("Номер {} увели параллельно, попытка {}", tenantId, attempt);
            }
        }
        throw new IllegalStateException(
                "Не удалось занять номер за %d попыток: арендаторов создают слишком много сразу"
                        .formatted(RESERVE_ATTEMPTS));
    }

    /** Занят ли код: отличает «повторять бессмысленно» от «номер увели». */
    private boolean codeTaken(String code) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM public.tenant_registry WHERE code = ?", Integer.class, code);
        return found != null && found > 0;
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
     * Отмечает версию схемы в реестре.
     *
     * <p>Иначе только что заведённый клиент выглядит для оркестратора
     * отставшим — а отличить «не мигрировали ни разу» от «мигрировали,
     * но не записали» по пустой колонке нельзя.
     */
    private void recordVersion(long tenantId) {
        jdbc.update("""
                UPDATE public.tenant_registry
                   SET schema_version = ?, migrated_at = now()
                 WHERE tenant_id = ?""", migrator.expectedVersion(), tenantId);
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
