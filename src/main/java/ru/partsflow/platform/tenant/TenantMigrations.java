package ru.partsflow.platform.tenant;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Накат миграций на схемы уже заведённых арендаторов.
 *
 * <p><b>Зачем отдельно от провижининга.</b> Схему клиента мигрировали ровно
 * один раз — при заведении. Новый changeset доезжал только до тех, кого
 * заведут после деплоя; у существующих схема молча оставалась прежней.
 * Отказ при этом отложенный и адресный: приложение поднимается, CI зелёный,
 * новые клиенты работают, а старый падает на первом запросе, который тронет
 * новую колонку.
 *
 * <p><b>Шагом развёртывания, а не при старте приложения.</b> Пятьсот схем
 * на подъёме — это минуты недоступности всей ячейки при каждом релизе,
 * и падение одной миграции не дало бы подняться остальным клиентам.
 *
 * <p><b>Продолжение после сбоя — это повторный запуск.</b> Своего журнала
 * прохода нет и не нужно: состояние каждого арендатора лежит в его же
 * {@code DATABASECHANGELOG}, и накатанное Liquibase пропустит сам. Отдельный
 * журнал был бы вторым источником правды, расходящимся с первым.
 *
 * <p><b>Один сорвавшийся не останавливает остальных.</b> Схема у каждого своя,
 * и отказ на седьмом клиенте — не причина оставить пятьсот остальных
 * на старой версии. Сорвавшиеся возвращаются списком: их разбирают руками,
 * а не ищут в логах.
 *
 * <p>Под блокировкой: два инстанса приложения в ячейке накатят одну схему
 * одновременно, и Liquibase со своей блокировкой не спасёт — второй будет
 * ждать первого столько, сколько идут все миграции, а потом отвалится
 * по таймауту посреди прохода.
 */
@Service
public class TenantMigrations {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrations.class);

    private static final String LOCK = "tenant-migrations";

    /**
     * Пятьсот схем по секунде — это восемь минут; час с запасом на случай,
     * когда changeset перестраивает таблицу.
     */
    private static final Duration LOCK_AT_MOST = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final TenantSchemaMigrator migrator;
    private final SchemaGrants grants;
    private final LockProvider lockProvider;

    public TenantMigrations(JdbcTemplate jdbc, TenantSchemaMigrator migrator,
                            SchemaGrants grants,
                            LockProvider lockProvider) {
        this.jdbc = jdbc;
        this.migrator = migrator;
        this.grants = grants;
        this.lockProvider = lockProvider;
    }

    /**
     * Мигрирует всех, кто в работе.
     *
     * <p>{@code PROVISIONING} пропускается: такой арендатор принадлежит
     * своему провижинингу, и лезть в наполовину созданную схему нельзя.
     * {@code SUSPENDED} — мигрируется: приостановленный клиент вернётся,
     * и вернётся на отставшей схеме, если его сейчас пропустить.
     */
    public Report migrateAll() {
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(), LOCK, LOCK_AT_MOST, Duration.ZERO));

        if (lock.isEmpty()) {
            throw new IllegalStateException(
                    "Миграции арендаторов уже идут на другом узле. Повторите позже: "
                            + "два прохода по одной схеме одновременно её и ломают");
        }

        try {
            return run();
        } finally {
            lock.get().unlock();
        }
    }

    private Report run() {
        String expected = migrator.expectedVersion();
        List<Tenant> tenants = jdbc.query("""
                SELECT tenant_id, schema_name, schema_version
                  FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')
                 ORDER BY tenant_id""",
                (rs, i) -> new Tenant(rs.getLong("tenant_id"), rs.getString("schema_name"),
                        rs.getString("schema_version")));

        List<String> migrated = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        int skipped = 0;

        for (Tenant tenant : tenants) {
            try {
                // Отметку в реестре здесь не спрашиваем. Она кэш, и она врёт,
                // если в схему лазили руками: накат пропускал бы ровно того
                // клиента, ради которого его и запустили, а оператор получал
                // «пропущено» и следом «отстал» — тупик без штатного выхода.
                // Пропускает Liquibase, по своему журналу: на актуальной схеме
                // это один SELECT, и пятьсот таких — секунды, а не минуты.
                int pending = migrator.pendingCount(tenant.schema());
                if (pending == 0) {
                    skipped++;
                    // Отметку всё же поправим: у клиента, мигрированного
                    // до появления оркестратора, она пуста, и без этого
                    // он вечно висел бы в отставших у быстрой проверки.
                    markMigrated(tenant.tenantId(), expected);
                    continue;
                }

                migrator.migrate(tenant.schema());
                // Новая таблица прав не наследует: без этого арендатор
                // после миграции падает на первом запросе к ней.
                grants.apply(tenant.schema());
                markMigrated(tenant.tenantId(), expected);
                migrated.add(tenant.schema());
                log.info("Схема {} обновлена до {}: не хватало {}",
                        tenant.schema(), expected, pending);
            } catch (RuntimeException e) {
                // Дальше идём осознанно: схемы независимы, и один сломанный
                // клиент не повод оставить остальных на старой версии.
                log.error("Миграции арендатора {} сорвались, остальные продолжаем",
                        tenant.schema(), e);
                failures.add(new Failure(tenant.schema(), rootMessage(e)));
            }
        }

        log.info("Миграции арендаторов: обновлено {}, пропущено {}, сорвалось {}",
                migrated.size(), skipped, failures.size());
        return new Report(expected, migrated, skipped, failures);
    }

    /**
     * Кто отстал.
     *
     * <p><b>Два режима, и это не удобство.</b> Быстрый идёт по отметкам
     * в реестре — ради этого {@code schema_version} там и заведена: обходить
     * пятьсот схем ради вопроса «можно ли выкладывать» значит превратить
     * проверку в минуты ожидания. Но отметка — кэш, и она врёт, если в схему
     * лазили руками: объекта нет, а в реестре стоит нужная версия. Поймано
     * живым прогоном на стенде, где вьюху до появления оркестратора катали
     * через psql.
     *
     * <p>Глубокий спрашивает у самого Liquibase, чего схеме не хватает.
     * Он дорогой, поэтому не по умолчанию — но именно им проверяют перед
     * выкладкой кода, который на новую схему рассчитывает.
     *
     * <p>Пустая версия — это «мигрировали до появления оркестратора»,
     * а не «схемы нет»: такие тоже попадают в отставшие и будут накатаны.
     */
    public Status status(boolean deep) {
        String expected = migrator.expectedVersion();
        List<TenantView> behind = jdbc.query("""
                SELECT tenant_id, schema_name, schema_version
                  FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')
                   AND (schema_version IS DISTINCT FROM ?)
                 ORDER BY tenant_id""",
                (rs, i) -> new TenantView(rs.getLong("tenant_id"), rs.getString("schema_name"),
                        rs.getString("schema_version"), null, null),
                expected);

        Integer total = jdbc.queryForObject("""
                SELECT count(*) FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""", Integer.class);

        return new Status(expected, total == null ? 0 : total,
                deep ? verified(expected) : behind);
    }

    /**
     * Отставшие по мнению самого Liquibase.
     *
     * <p>Отметка в реестре тут ни при чём: спрашивается каждая схема. Схема,
     * которую не удалось проверить, попадает в отставшие с причиной — молчание
     * про непроверенного клиента хуже, чем лишняя строка в отчёте.
     */
    private List<TenantView> verified(String expected) {
        List<Tenant> tenants = jdbc.query("""
                SELECT tenant_id, schema_name, schema_version
                  FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')
                 ORDER BY tenant_id""",
                (rs, i) -> new Tenant(rs.getLong("tenant_id"), rs.getString("schema_name"),
                        rs.getString("schema_version")));

        List<TenantView> behind = new ArrayList<>();
        for (Tenant tenant : tenants) {
            try {
                int pending = migrator.pendingCount(tenant.schema());
                if (pending > 0) {
                    behind.add(new TenantView(tenant.tenantId(), tenant.schema(),
                            tenant.version(), pending, null));
                }
            } catch (RuntimeException e) {
                behind.add(new TenantView(tenant.tenantId(), tenant.schema(),
                        tenant.version(), null, rootMessage(e)));
            }
        }
        return behind;
    }

    private void markMigrated(long tenantId, String version) {
        jdbc.update("""
                UPDATE public.tenant_registry
                   SET schema_version = ?, migrated_at = now()
                 WHERE tenant_id = ?""", version, tenantId);
    }

    /** Причина отказа лежит в самом глубоком исключении, а не в обёртке. */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private record Tenant(long tenantId, String schema, String version) {
    }

    /**
     * @param skipped сколько уже были на нужной версии: повторный запуск после
     *                сбоя обязан быть дешёвым, иначе им не пользуются
     */
    public record Report(String version, List<String> migrated, int skipped,
                         List<Failure> failures) {
    }

    public record Failure(String schema, String reason) {
    }

    /**
     * @param behind отставшие. Пустой список — можно выкладывать код,
     *               который рассчитывает на новую схему
     */
    public record Status(String expectedVersion, int tenants, List<TenantView> behind) {
    }

    /**
     * @param pending скольких changeset'ов не хватает. Пусто в быстрой
     *                проверке: она смотрит на отметку, а не на схему
     * @param problem почему проверить не вышло. Обычно — схемы нет вовсе
     */
    public record TenantView(long tenantId, String schema, String schemaVersion,
                             Integer pending, String problem) {
    }
}
