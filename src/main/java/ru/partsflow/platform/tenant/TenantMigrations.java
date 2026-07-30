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
    private final LockProvider lockProvider;

    public TenantMigrations(JdbcTemplate jdbc, TenantSchemaMigrator migrator,
                            LockProvider lockProvider) {
        this.jdbc = jdbc;
        this.migrator = migrator;
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
            // Отметка в реестре — быстрый фильтр, а не истина: истина лежит
            // в DATABASECHANGELOG схемы, и Liquibase всё равно её перечитает.
            // Но обходить пятьсот схем ради тех, кто заведомо на месте, незачем.
            if (expected != null && expected.equals(tenant.version())) {
                skipped++;
                continue;
            }
            try {
                migrator.migrate(tenant.schema());
                markMigrated(tenant.tenantId(), expected);
                migrated.add(tenant.schema());
                log.info("Схема {} обновлена до {}", tenant.schema(), expected);
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
     * Кто отстал — одним запросом к реестру.
     *
     * <p>Ради этого в реестре и заведена {@code schema_version}: обход всех
     * схем ради вопроса «можно ли выкладывать код, который рассчитывает
     * на новую колонку» превращает проверку перед деплоем в минуты ожидания.
     *
     * <p>Пустая версия — это «мигрировали до появления оркестратора»,
     * а не «схемы нет»: такие тоже попадают в отставшие и будут накатаны.
     */
    public Status status() {
        String expected = migrator.expectedVersion();
        List<TenantView> behind = jdbc.query("""
                SELECT tenant_id, schema_name, schema_version
                  FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')
                   AND (schema_version IS DISTINCT FROM ?)
                 ORDER BY tenant_id""",
                (rs, i) -> new TenantView(rs.getLong("tenant_id"), rs.getString("schema_name"),
                        rs.getString("schema_version")),
                expected);

        Integer total = jdbc.queryForObject("""
                SELECT count(*) FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""", Integer.class);

        return new Status(expected, total == null ? 0 : total, behind);
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

    public record TenantView(long tenantId, String schema, String schemaVersion) {
    }
}
