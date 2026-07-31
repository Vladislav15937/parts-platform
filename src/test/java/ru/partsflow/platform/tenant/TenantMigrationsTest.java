package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Накат миграций на схемы уже заведённых арендаторов.
 *
 * <p>До этого схему клиента мигрировали ровно один раз — при заведении,
 * и новый changeset доезжал только до тех, кого заведут после деплоя.
 * Отказ отложенный: приложение поднимается, CI зелёный, новые клиенты
 * работают, а старый падает на первом запросе к новой колонке.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class TenantMigrationsTest extends PostgresTestBase {

    private static final String TENANT = "t_000074";

    @Autowired
    private TenantMigrations migrations;

    @Autowired
    private TenantSchemaMigrator migrator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private net.javacrumbs.shedlock.core.LockProvider lockProvider;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void registry() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id IN (74, 75)");
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status, schema_version)
                VALUES (74, ?, 'Разборка', 'migrco', 'ACTIVE', NULL)""", TENANT);
    }

    @Test
    @DisplayName("Отставшая схема догоняется, а версия попадает в реестр")
    void behindTenantIsCaughtUp() {
        // Так выглядит клиент, заведённый до нового changeset'а: объекта нет,
        // и его строки нет в журнале миграций схемы.
        forget("tenant-080-donor-profitability");
        jdbc.execute("DROP VIEW IF EXISTS %s.v_donor_profitability".formatted(TENANT));
        assertThat(viewExists("v_donor_profitability")).isFalse();

        var report = migrations.migrateAll();

        assertThat(viewExists("v_donor_profitability"))
                .as("схема осталась отставшей: клиент упадёт на первом обращении к ней")
                .isTrue();
        assertThat(report.migrated()).contains(TENANT);
        assertThat(versionOf(74))
                .as("версия не записана — оркестратор будет ходить в эту схему каждый раз")
                .isEqualTo(migrator.expectedVersion());
    }

    @Test
    @DisplayName("Повторный запуск ничего не делает: он должен быть дешёвым")
    void secondRunSkipsEverything() {
        forget("tenant-080-donor-profitability");
        jdbc.execute("DROP VIEW IF EXISTS %s.v_donor_profitability".formatted(TENANT));
        migrations.migrateAll();

        // Повторный запуск после сбоя — это и есть продолжение прохода,
        // и если он снова обходит все схемы, им не пользуются.
        var second = migrations.migrateAll();

        assertThat(second.migrated()).isEmpty();
        assertThat(second.skipped()).isPositive();
    }

    @Test
    @DisplayName("Сорвавшийся арендатор не останавливает остальных")
    void oneFailureDoesNotStopTheRest() {
        // Запись есть, схемы нет — так выглядит арендатор, чей провижининг
        // когда-то сорвался и был вручную переведён в ACTIVE.
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status)
                VALUES (75, 't_000075', 'Пропавшая', 'lostco', 'ACTIVE')""");
        forget("tenant-080-donor-profitability");
        jdbc.execute("DROP VIEW IF EXISTS %s.v_donor_profitability".formatted(TENANT));

        var report = migrations.migrateAll();

        assertThat(report.failures()).extracting(TenantMigrations.Failure::schema)
                .contains("t_000075");
        assertThat(report.migrated())
                .as("проход остановился на сломанном клиенте — остальные остались "
                        + "на старой схеме из-за чужой поломки")
                .contains(TENANT);
        assertThat(report.failures()).allSatisfy(
                failure -> assertThat(failure.reason()).isNotBlank());
    }

    @Test
    @DisplayName("Отставших видно одним запросом к реестру")
    void statusListsBehind() {
        jdbc.update("UPDATE public.tenant_registry SET schema_version = 'старьё' "
                + "WHERE tenant_id = 74");

        var status = migrations.status(false);

        assertThat(status.expectedVersion()).isNotBlank();
        assertThat(status.behind()).extracting(TenantMigrations.TenantView::schema)
                .contains(TENANT);

        migrations.migrateAll();

        assertThat(migrations.status(false).behind())
                .extracting(TenantMigrations.TenantView::schema)
                .doesNotContain(TENANT);
    }

    @Test
    @DisplayName("Незавершённый провижининг не трогают")
    void provisioningTenantIsLeftAlone() {
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status)
                VALUES (75, 't_000075', 'Заводится', 'newco', 'PROVISIONING')""");

        var report = migrations.migrateAll();

        // Схема такого арендатора наполовину создана, и владеет ею провижининг.
        assertThat(report.migrated()).doesNotContain("t_000075");
        assertThat(report.failures()).extracting(TenantMigrations.Failure::schema)
                .doesNotContain("t_000075");
        assertThat(migrations.status(false).behind())
                .extracting(TenantMigrations.TenantView::schema)
                .doesNotContain("t_000075");
    }

    @Test
    @DisplayName("Второй проход по той же ячейке отбивается блокировкой")
    void concurrentRunIsRejected() {
        var lock = lockProvider.lock(new net.javacrumbs.shedlock.core.LockConfiguration(
                java.time.Instant.now(), "tenant-migrations",
                java.time.Duration.ofMinutes(5), java.time.Duration.ZERO));
        assertThat(lock).isPresent();

        try {
            // Два инстанса приложения в ячейке накатят одну схему одновременно,
            // и Liquibase со своей блокировкой тут не спасает.
            assertThatThrownBy(() -> migrations.migrateAll())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("уже идут");
        } finally {
            lock.orElseThrow().unlock();
        }
    }

    @Test
    @DisplayName("Накат чинит схему, которой отметка врёт")
    void applyFixesTamperedSchema() {
        // Отметка говорит «всё на месте», а объекта в схеме нет. Пока накат
        // верил отметке, он пропускал ровно того клиента, ради которого его
        // и запускали: оператор получал «пропущено», а следом «отстал».
        jdbc.update("UPDATE public.tenant_registry SET schema_version = ? WHERE tenant_id = 74",
                migrator.expectedVersion());
        forget("tenant-080-donor-profitability");
        jdbc.execute("DROP VIEW IF EXISTS %s.v_donor_profitability".formatted(TENANT));

        var report = migrations.migrateAll();

        assertThat(report.migrated()).contains(TENANT);
        assertThat(viewExists("v_donor_profitability")).isTrue();
        assertThat(migrations.status(true).behind())
                .extracting(TenantMigrations.TenantView::schema)
                .doesNotContain(TENANT);
    }

    @Test
    @DisplayName("Глубокая проверка ловит схему, в которую лазили руками")
    void deepStatusCatchesTamperedSchema() {
        // Отметка в реестре говорит, что всё на месте, а объекта в схеме нет:
        // так выглядит клиент, которому миграцию катали через psql. Быстрая
        // проверка такому верит, и выкладка пройдёт на отставшей схеме.
        jdbc.update("UPDATE public.tenant_registry SET schema_version = ? WHERE tenant_id = 74",
                migrator.expectedVersion());
        forget("tenant-080-donor-profitability");
        jdbc.execute("DROP VIEW IF EXISTS %s.v_donor_profitability".formatted(TENANT));

        assertThat(migrations.status(false).behind())
                .as("быстрая проверка идёт по отметке — она и не должна это увидеть")
                .extracting(TenantMigrations.TenantView::schema)
                .doesNotContain(TENANT);

        assertThat(migrations.status(true).behind())
                .as("глубокая проверка поверила отметке вместо самой схемы")
                .extracting(TenantMigrations.TenantView::schema)
                .contains(TENANT);
        assertThat(migrations.status(true).behind())
                .filteredOn(t -> t.schema().equals(TENANT))
                .singleElement()
                .satisfies(t -> assertThat(t.pending()).isPositive());
    }

    @Test
    @DisplayName("Непроверяемая схема попадает в отставшие с причиной")
    void unreachableSchemaIsReported() {
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status)
                VALUES (75, 't_000075', 'Пропавшая', 'lostco', 'ACTIVE')""");

        // Молчание про клиента, которого не удалось проверить, хуже лишней
        // строки: выкладка прошла бы, посчитав его исправным.
        assertThat(migrations.status(true).behind())
                .filteredOn(t -> t.schema().equals("t_000075"))
                .singleElement()
                .satisfies(t -> assertThat(t.problem()).isNotBlank());
    }

    @Test
    @DisplayName("Провижининг отмечает версию сам: новый клиент не выглядит отставшим")
    void freshTenantIsNotBehind() {
        // Если провижининг версию не запишет, только что заведённый клиент
        // попадёт в behind, и шаг развёртывания откажется выкладывать код
        // из-за арендатора, у которого схема как раз самая свежая.
        jdbc.update("UPDATE public.tenant_registry SET schema_version = ? WHERE tenant_id = 74",
                migrator.expectedVersion());

        assertThat(migrations.status(false).behind())
                .extracting(TenantMigrations.TenantView::schema)
                .doesNotContain(TENANT);
    }

    /** Убирает changeset из журнала схемы — как будто его никогда не накатывали. */
    private void forget(String changeSetId) {
        jdbc.update("DELETE FROM %s.databasechangelog WHERE id = ?".formatted(TENANT),
                changeSetId);
    }

    private boolean viewExists(String name) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.views
                 WHERE table_schema = ? AND table_name = ?""",
                Integer.class, TENANT, name);
        return found != null && found > 0;
    }

    private String versionOf(long tenantId) {
        return jdbc.queryForObject(
                "SELECT schema_version FROM public.tenant_registry WHERE tenant_id = ?",
                String.class, tenantId);
    }
}
