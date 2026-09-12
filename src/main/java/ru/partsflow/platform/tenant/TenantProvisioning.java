package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
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
 * номер и код компании. Номер выдаётся под блокировкой, а код стережёт
 * уникальный индекс — но не проверка «а нет ли уже такого»
 * (см. {@link #reserve}).
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
     * <p>Пять — и теперь это про тех, кто пишет в реестр <b>мимо</b>
     * провижининга (накат руками, фикстура теста): свои заявки между собой
     * больше не сталкиваются вовсе, они выстраиваются в очередь
     * на {@link #NUMBER_LOCK}.
     *
     * <p><b>До 12 сентября 2026 это число было пределом одновременных
     * заведений, и предел был пять.</b> Прежний расчёт считал столкновения
     * независимыми — «на каждой десятой заявке, а подряд пять раз проиграть
     * надо постараться», — но независимыми они не были: проигравший
     * перечитывал <i>тот же</i> максимум и сталкивался со всеми остальными
     * проигравшими, то есть за круг побеждал ровно один. Последнему из N
     * заявок нужно было N попыток, и любая пачка больше пяти получала
     * «Не удалось занять номер» при бесконечном запасе свободных номеров.
     * Ловилось это красным CI раз в сотню прогонов (заявок в тесте шесть),
     * а у клиента вылезло бы на десяти разборках, заводимых подряд.
     */
    private static final int RESERVE_ATTEMPTS = 5;

    /**
     * Рекомендательная блокировка на выдачу номера.
     *
     * <p>Ключ выведен из имени таблицы, а не взят числом с потолка: важно
     * только, чтобы он совпал у всех, кто выдаёт номера в этой базе, —
     * а {@code hashtext} от одной строки в одном кластере даёт одно число.
     *
     * <p>Блокировка <b>транзакционная</b> (снимается коммитом), а не
     * сессионная. Сессионная протекла бы через PgBouncer в transaction mode
     * ровно так же, как протекал бы {@code SET search_path}, — и держалась бы
     * на чужом соединении до перезапуска.
     */
    private static final String NUMBER_LOCK =
            "SELECT pg_advisory_xact_lock(hashtext('partsflow.tenant_registry.tenant_id'))";

    private final JdbcTemplate jdbc;
    private final SchemaGrants grants;
    private final TransactionTemplate numbering;
    private final TenantSchemaMigrator migrator;
    private final PasswordEncoder passwordEncoder;
    private final long cellNumber;

    public TenantProvisioning(
                              // Реестр и схемы ведёт владелец: рабочая роль
                              // реестр только читает при входе.
                              @SchemaOwnerDataSource.SchemaOwner DataSource dataSource,
                              SchemaGrants grants,
                              TenantSchemaMigrator migrator, PasswordEncoder passwordEncoder,
                              @Value("${app.cell-number:1}") long cellNumber) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.grants = grants;
        // Своя транзакция на выдачу номера, и своя же — по источнику:
        // владельцем схем ходят четверо, менеджер транзакций Spring сидит
        // на рабочем источнике и к этому отношения не имеет.
        //
        // REQUIRES_NEW, а не REQUIRED: снаружи транзакции нет и не должно
        // быть (миграции длиннее любой разумной), но присоединись мы к чужой —
        // нарушение уникальности пометило бы её на откат, и повтор попытки
        // писал бы в мёртвую транзакцию.
        this.numbering = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.numbering.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            // Права рантайм-роли — сразу после миграций и до первого запроса:
            // схема без прав означает клиента, который заведён и не работает.
            grants.apply(reserved.schema());
            recordVersion(reserved.tenantId());
            createOwner(reserved.schema(), request);
            createFirstWarehouse(reserved.schema(), request.companyName());
            createRetailCustomer(reserved.schema());
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
     * <p><b>Номер выдаётся под блокировкой, а не наперегонки.</b> «Максимальный
     * плюс один» два одновременных создания читают одинаково при любом уровне
     * изоляции ниже сериализуемого, и повтор тут не помогает: проигравший
     * перечитывает тот же максимум и снова сталкивается со всеми остальными
     * проигравшими. За круг побеждает ровно один, значит пачке из N заявок
     * нужно N кругов — а кругов отмерено {@link #RESERVE_ATTEMPTS}.
     * Отсюда отказ «Не удалось занять номер» при полном запасе свободных
     * номеров: он приходил тем вернее, чем плотнее заводят клиентов.
     *
     * <p>Поэтому чтение максимума и вставка идут <b>одной транзакцией под
     * {@link #NUMBER_LOCK}</b> — то же правило, что у остатка склада:
     * проверка и изменение не разъезжаются. Ждущий блокировку стоит
     * в очереди (миллисекунды: внутри два запроса и ни одного похода
     * наружу), а не сжигает попытку.
     *
     * <p>Повтор при этом оставлен: блокировка рекомендательная, и тот, кто
     * пишет в реестр мимо провижининга, о ней не знает. Но теперь пять
     * попыток — это пять шансов против <i>чужой</i> записи, а не предел
     * на число заводимых разом клиентов.
     *
     * <p>Нарушение уникальности ловится <b>снаружи</b> транзакции: прежняя
     * помечена на откат, и перечитывать «занят ли код» в ней нельзя — то же
     * правило, что у одновременного повтора приёмки.
     */
    Reserved reserve(String code, String companyName) {
        // Номер ячейки в старшем разряде: у второй ячейки арендаторы начинаются
        // с 2 000 001, а не с единицы. Иначе схемы t_000001 есть в обеих,
        // и дамп из одной нельзя развернуть в другую без переименования —
        // а переносить клиента между ячейками придётся при первой же
        // перебалансировке. Заодно по номеру видно, где искать клиента.
        long base = cellNumber * CELL_RANGE;

        for (int attempt = 1; attempt <= RESERVE_ATTEMPTS; attempt++) {
            try {
                return numbering.execute(status -> {
                    // Блокировка первой инструкцией транзакции: взятая после
                    // чтения максимума, она защищала бы уже устаревшее число.
                    jdbc.execute(NUMBER_LOCK);

                    Long tenantId = jdbc.queryForObject("""
                            SELECT GREATEST(COALESCE(max(tenant_id), 0), ?) + 1
                              FROM public.tenant_registry""", Long.class, base);
                    String schema = "t_%06d".formatted(tenantId);
                    jdbc.update("""
                            INSERT INTO public.tenant_registry
                                (tenant_id, schema_name, company_name, code, status)
                            VALUES (?, ?, ?, ?, 'PROVISIONING')""",
                            tenantId, schema, companyName, code);
                    return new Reserved(tenantId, schema);
                });
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Код занят — повторять бессмысленно, номер тут ни при чём.
                // Это разные причины, и раньше они шли одним сообщением:
                // оператор массового заведения не мог понять, повторять ему
                // или искать другой код.
                if (codeTaken(code)) {
                    throw new IllegalStateException(
                            "Код компании «%s» занят".formatted(code), e);
                }
                // Под блокировкой этого не бывает: значит в реестр написали
                // мимо провижининга. Не debug: оператор массового заведения
                // должен увидеть, что номера уводит кто-то ещё.
                log.warn("Номер увели мимо провижининга, попытка {} из {}",
                        attempt, RESERVE_ATTEMPTS);
            }
        }
        throw new IllegalStateException(
                ("Не удалось занять номер за %d попыток: в public.tenant_registry пишет "
                        + "кто-то мимо провижининга").formatted(RESERVE_ATTEMPTS));
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

    /**
     * Заводит контрагента розничной продажи.
     *
     * <p>В отличие от ячеек, этот справочник придумывать за клиента можно:
     * «Частное лицо» — не его данные, а название того, кто покупает без
     * заведения карточки, и подставлено оно в форму продажи с первого дня.
     * Заводится один раз: дальше на него ссылаются сделки, и второго
     * не появится (см. {@code CustomerService.retail}).
     *
     * <p>Имя берётся константой оттуда же: два написания «Частного лица»
     * разошлись бы молча — провижининг завёл бы одно, а экран продажи искал
     * бы другое и завёл бы второго при первой продаже.
     */
    private void createRetailCustomer(String schema) {
        jdbc.update("INSERT INTO %s.customer (name, customer_type) VALUES (?, 'PERSON')"
                .formatted(schema), ru.partsflow.shared.RetailCustomer.NAME);
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
