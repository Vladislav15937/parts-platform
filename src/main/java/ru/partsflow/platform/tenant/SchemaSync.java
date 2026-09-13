package ru.partsflow.platform.tenant;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Привести схемы ячейки к версии этого артефакта — и выйти.
 *
 * <p><b>Зачем отдельно от {@link TenantMigrations}.</b> Тот накатывает
 * по запросу к <b>работающему</b> приложению, и из этого следует порядок,
 * который выполнить нельзя: changelog лежит внутри jar, значит новые
 * changeset'ы умеет накатить только новый артефакт — то есть тот, что уже
 * подменил старый. Expand/contract из {@code docs/deployment.md} требует
 * обратного (сначала схема, потом код), и между шагами оставалось окно,
 * где новый код работает на старых схемах. На 12 сентября 2026 боевая
 * ячейка отставала на девять changeset'ов, и выкладка означала такое окно
 * на минуту-две: журнал входов, память витрины, срок резерва и номер
 * позиции опираются на таблицы из них.
 *
 * <p>Этот класс поднимается одноразовым контейнером <b>того же образа</b>
 * ({@link SchemaSyncCommand}), приводит схемы к версии образа и завершает
 * процесс. Работающее приложение он не подменяет и порт не занимает —
 * веб-слоя в этом режиме нет вовсе.
 *
 * <p><b>Приводит, а не накатывает.</b> Решение владельца от 12 сентября
 * 2026: «не должно быть такого, чтобы версия приложения была 100, а база
 * соответствовала версии 103». Поэтому схема выше цели опускается
 * ({@code --to}), а не пропускается молча, — с одной оговоркой, названной
 * вслух и в {@code docs/deployment.md}: changeset'ы, которых в этом
 * артефакте нет, он снять не может физически (тело {@code --rollback}
 * лежит в чужом jar). Такая схема попадает в проблемные с именами лишних
 * changeset'ов, а опускает её <b>та сборка, которая их завела</b>.
 *
 * <p><b>Истина — сам Liquibase, а не отметка.</b> {@code schema_version}
 * в реестре врёт, если в схему лазили руками; и накат, и проверка
 * спрашивают {@link TenantSchemaMigrator#inspect(String)}. Отметку здесь
 * только обновляют.
 *
 * <p><b>Сорвавшийся арендатор не останавливает остальных.</b> Схемы
 * независимы, и отказ на седьмом клиенте не повод оставить пятьсот
 * на старой версии. Сорвавшиеся приезжают списком, а код возврата
 * ненулевой — релиз, оставивший клиента не на той схеме, обязан быть
 * заметен сразу.
 */
@Component
public class SchemaSync {

    private static final Logger log = LoggerFactory.getLogger(SchemaSync.class);

    /**
     * Тот же замок, что у {@link TenantMigrations}: одноразовый контейнер
     * и эндпоинт работающего приложения делают одно и то же, и два прохода
     * по одной схеме её и ломают.
     */
    private static final String LOCK = "tenant-migrations";

    private static final Duration LOCK_AT_MOST = Duration.ofHours(1);

    /** Сколько проблемных схем называть по имени: дальше — числом. */
    private static final int NAMED = 3;

    /** Под этим именем в списке проблем ходит то, что не принадлежит арендатору. */
    private static final String CELL = "(ячейка)";

    private static final String CELL_SCHEMA = "(общая схема ячейки)";

    private final JdbcTemplate jdbc;
    private final TenantSchemaMigrator migrator;
    private final CatalogSchemaMigrator catalog;
    private final SchemaGrants grants;
    private final LockProvider lockProvider;

    public SchemaSync(
            // Владельцем схем, а не рабочей ролью: DDL и реестр арендаторов —
            // управляющие данные. Накат рабочей ролью объявлял бы отставшими
            // всех подряд: прав на UPDATE public.tenant_registry у неё нет.
            @SchemaOwnerDataSource.SchemaOwner DataSource ownerDataSource,
            TenantSchemaMigrator migrator,
            CatalogSchemaMigrator catalog,
            SchemaGrants grants,
            LockProvider lockProvider) {
        this.jdbc = new JdbcTemplate(ownerDataSource);
        this.migrator = migrator;
        this.catalog = catalog;
        this.grants = grants;
        this.lockProvider = lockProvider;
    }

    /** Что делать: посмотреть или привести. */
    public enum Mode {
        /** Ничего не менять, только ответить «все ли на версии». */
        CHECK,
        /** Привести к целевой версии. */
        APPLY
    }

    /**
     * @param target к какой версии приводить — число changeset'ов набора.
     *               {@code null} означает «к версии этого артефакта», и это
     *               обычный случай: пара «код и схема» получается свойством
     *               построения, а не внимательностью выкладывающего
     */
    public record Plan(Mode mode, Integer target) {

        public static Plan apply() {
            return new Plan(Mode.APPLY, null);
        }
    }

    public Result run(Plan plan) {
        int total = migrator.totalChangeSets();
        int target = plan.target() == null ? total : plan.target();

        if (target < 0 || target > total) {
            return new Result(null, List.of(), 0, List.of(new Problem(CELL,
                    "Версия " + target + " этому образу неизвестна: в нём "
                            + total + " changeset'ов схемы арендатора")));
        }

        // Общая схема идёт ПЕРВОЙ и без замка ячейки, а не потому, что так
        // короче: сам замок живёт в public.shedlock, которую заводит она же.
        // На пустой ячейке порядок наоборот отвечал «relation public.shedlock
        // does not exist» — то есть инструмент не работал ровно там, где его
        // зовут первым. Liquibase на этом шаге держит свою блокировку
        // (DATABASECHANGELOGLOCK), как и при старте приложения.
        List<Problem> problems = new ArrayList<>();
        int broughtCatalog = syncCatalog(plan, problems);

        if (plan.mode() == Mode.CHECK) {
            return sync(plan, target, problems, broughtCatalog);
        }

        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(), LOCK, LOCK_AT_MOST, Duration.ZERO));

        if (lock.isEmpty()) {
            // Не исключение: у шага выкладки должен быть код возврата
            // и внятная строка, а не стек в логе одноразового контейнера.
            String reason = "Миграции уже идут — другим контейнером либо "
                    + "эндпоинтом работающего приложения. Повторите позже";
            log.error(reason);
            problems.add(new Problem(CELL, reason));
            return new Result(null, List.of(), 0, List.copyOf(problems));
        }

        try {
            return sync(plan, target, problems, broughtCatalog);
        } finally {
            lock.get().unlock();
        }
    }

    private Result sync(Plan plan, int target, List<Problem> problems, int broughtCatalog) {
        int total = migrator.totalChangeSets();
        String targetVersion = migrator.versionAt(target);
        log.info("Целевая версия схемы арендатора: {}{}", targetVersion,
                plan.mode() == Mode.CHECK ? " (только проверка, ничего не меняем)" : "");

        if (target < total) {
            // Цена отката не выводима из схемы: структурно верный откат
            // теряет данные молча. Поэтому здесь только указатель на того,
            // кто цену печатает, — читать её обязан человек, до запуска.
            log.warn("Откат вниз: {} → {}. Цену печатает ./db/rollback-cost.py --from {} --to {}",
                    total, target, total, target);
        }

        List<Tenant> tenants;
        try {
            tenants = jdbc.query("""
                    SELECT tenant_id, schema_name
                      FROM public.tenant_registry
                     WHERE status IN ('ACTIVE', 'SUSPENDED')
                     ORDER BY tenant_id""",
                    (rs, i) -> new Tenant(rs.getLong("tenant_id"), rs.getString("schema_name")));
        } catch (RuntimeException e) {
            // Реестра нет — это непринятая общая схема ячейки, и проверка
            // на пустой базе упиралась сюда стеком вместо строки. Шаг выкладки
            // читает код возврата, человек — причину; стек не годится ни тому,
            // ни другому.
            problems.add(new Problem(CELL, "реестр арендаторов не читается: "
                    + TenantMigrations.rootMessage(e)));
            Result broken = new Result(targetVersion, List.of(), 0, List.copyOf(problems));
            report(plan, broken, broughtCatalog);
            return broken;
        }

        List<String> brought = new ArrayList<>();
        int alreadyThere = 0;

        for (Tenant tenant : tenants) {
            // Считаем «уже на версии» по тому, не прибавилось ли проблем:
            // схема, которую не двигали, и схема, которую не смогли, — разные
            // вещи, а возвращает syncTenant в обоих случаях false.
            int problemsBefore = problems.size();
            try {
                if (syncTenant(tenant, plan, target, targetVersion, problems)) {
                    brought.add(tenant.schema());
                } else if (problems.size() == problemsBefore) {
                    alreadyThere++;
                }
            } catch (RuntimeException e) {
                // Дальше идём осознанно: схемы независимы, и один сломанный
                // клиент не повод оставить остальных не на той версии.
                log.error("Схема {}: не вышло, остальные продолжаем", tenant.schema(), e);
                problems.add(new Problem(tenant.schema(), TenantMigrations.rootMessage(e)));
            }
        }

        Result result = new Result(targetVersion, List.copyOf(brought),
                alreadyThere, List.copyOf(problems));
        report(plan, result, broughtCatalog);
        return result;
    }

    private int syncCatalog(Plan plan, List<Problem> problems) {
        if (plan.mode() == Mode.CHECK) {
            try {
                CatalogSchemaMigrator.Pending pending = catalog.pending();
                if (!pending.ok()) {
                    problems.add(new Problem(CELL_SCHEMA,
                            "не принято changeset'ов: " + pending.missing().size()
                                    + " — " + pending.names()));
                }
            } catch (RuntimeException e) {
                problems.add(new Problem(CELL_SCHEMA, TenantMigrations.rootMessage(e)));
            }
            return 0;
        }

        try {
            int missing = missingInCatalog();
            if (missing != 0) {
                // Накат зовётся, когда накатывать есть что, — и это не экономия
                // одного SELECT'а. Liquibase считает changeset по ПУТИ файла,
                // а changelog каталога открывают тремя путями (classpath
                // у приложения, каталог проекта у тестов, третий у verify.sh):
                // безусловный update на ячейке, накатанной другим путём, начал
                // бы создавать catalog.brand заново. Вопрос «чего не хватает»
                // такого изъяна не имеет — он сверяет идентификаторы.
                // Ответ -1 значит «истории нет вовсе» — это пустая ячейка,
                // и накатывать надо тем более.
                catalog.migrate();
                log.info("Общая схема ячейки приведена{}", missing < 0
                        ? "" : ": принято changeset'ов " + missing);
            }
            // Права на служебные таблицы ячейки — здесь же и по тому же доводу,
            // что в CatalogMigrations: без них сессию некуда записать, то есть
            // войти не может никто. Выдаются и тогда, когда накатывать нечего:
            // разделение ролей включают на работающей ячейке.
            grants.applyCellTables();
            return missing == 0 ? 0 : 1;
        } catch (RuntimeException e) {
            problems.add(new Problem(CELL_SCHEMA, TenantMigrations.rootMessage(e)));
            return 0;
        }
    }

    /** @return сколько changeset'ов не хватает общей схеме; {@code -1} — «спросить не у кого» */
    private int missingInCatalog() {
        try {
            return catalog.pending().missing().size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** @return {@code true}, если схему пришлось двигать */
    private boolean syncTenant(Tenant tenant, Plan plan, int target,
                               String targetVersion, List<Problem> problems) {

        TenantSchemaMigrator.SchemaState state = migrator.inspect(tenant.schema());

        if (state.ahead()) {
            // Эта сборка их не снимет: тело отката лежит в файле changeset'а,
            // то есть в чужом jar. Соврать «всё в порядке» тут хуже отказа:
            // получилась бы ровно та пара «код одной версии, база другой»,
            // ради которой задачу и заводили.
            problems.add(new Problem(tenant.schema(),
                    "схема впереди этого образа на " + state.extra().size()
                            + ": " + names(state.extra())
                            + ". Опустить её может только сборка, которая их завела"));
            return false;
        }

        boolean atTarget = state.applied() == target
                && (target < migrator.totalChangeSets() || state.pending() == 0);

        if (plan.mode() == Mode.CHECK) {
            if (!atTarget) {
                problems.add(new Problem(tenant.schema(), state.applied() > target
                        ? "накатано " + state.applied() + " из " + target
                                + ", лишних changeset'ов: " + (state.applied() - target)
                        : "накатано " + state.applied() + " из " + target
                                + ", не хватает " + state.pending()));
            }
            return false;
        }

        if (atTarget) {
            // Права выдаём и тем, кого двигать не пришлось: разделение ролей
            // включают на работающей ячейке, где все схемы уже на версии, —
            // без этого рабочая роль осталась бы без доступа к складу.
            grants.apply(tenant.schema());
            mark(tenant.tenantId(), targetVersion);
            return false;
        }

        if (state.applied() > target) {
            int back = state.applied() - target;
            migrator.rollbackLast(tenant.schema(), back);
            log.info("Схема {} опущена до {}: снято changeset'ов {}",
                    tenant.schema(), targetVersion, back);
        } else if (target == migrator.totalChangeSets()) {
            // Цель — вершина набора: накатываем всё непройденное разом.
            // Так же отрабатывают вьюхи с runOnChange, которых счётом
            // накатанного не поймать: changeset и накатан, и подлежит накату.
            migrator.migrate(tenant.schema());
            log.info("Схема {} доведена до {}: не хватало {}",
                    tenant.schema(), targetVersion, state.pending());
        } else {
            int forward = target - state.applied();
            migrator.migrateNext(tenant.schema(), forward);
            log.info("Схема {} поднята до {}: накатано changeset'ов {}",
                    tenant.schema(), targetVersion, forward);
        }

        grants.apply(tenant.schema());
        mark(tenant.tenantId(), targetVersion);
        return true;
    }

    private void mark(long tenantId, String version) {
        jdbc.update("""
                UPDATE public.tenant_registry
                   SET schema_version = ?, migrated_at = now()
                 WHERE tenant_id = ?""", version, tenantId);
    }

    private void report(Plan plan, Result result, int broughtCatalog) {
        if (plan.mode() == Mode.CHECK) {
            if (result.ok()) {
                log.info("Все схемы на версии {} — выкладывать можно",
                        result.targetVersion());
            } else {
                log.error("Не на версии {}: схем {}", result.targetVersion(),
                        result.problems().size());
            }
        } else {
            log.info("Приведено схем: {} (плюс общая схема ячейки: {}), "
                            + "уже на версии: {}, не вышло: {}",
                    result.brought().size(), broughtCatalog == 1 ? "да" : "нет",
                    result.alreadyThere(), result.problems().size());
        }

        for (Problem problem : result.problems()) {
            log.error("  {} — {}", problem.schema(), problem.reason());
        }
    }

    private static String names(List<String> all) {
        String first = String.join(", ", all.subList(0, Math.min(NAMED, all.size())));
        int rest = all.size() - Math.min(NAMED, all.size());
        return rest == 0 ? first : first + " и ещё " + rest;
    }

    private record Tenant(long tenantId, String schema) {
    }

    /**
     * @param brought      кого пришлось двигать
     * @param alreadyThere кто уже был на целевой версии. Повторный запуск
     *                     обязан быть пустой операцией, иначе им не пользуются
     * @param problems     кто остался не на версии и почему
     */
    public record Result(String targetVersion, List<String> brought, int alreadyThere,
                         List<Problem> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }

        /**
         * Ноль — все схемы на целевой версии, ненулевой — нет.
         *
         * <p>Один ненулевой код на все причины намеренно: шаг выкладки
         * отличает «можно дальше» от «нельзя», а <b>что именно</b> случилось,
         * читают в списке проблем — там схема названа по имени.
         */
        public int exitCode() {
            return ok() ? 0 : 1;
        }
    }

    public record Problem(String schema, String reason) {
    }
}
