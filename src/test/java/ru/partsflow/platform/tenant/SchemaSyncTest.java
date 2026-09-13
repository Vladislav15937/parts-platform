package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Режим «привести схемы к версии артефакта и выйти».
 *
 * <p>Проверяется то, ради чего он написан: отставшая схема даёт ненулевой
 * код <b>до</b> наката и ноль после, повторный запуск — пустая операция,
 * схема выше версии называется по имени и не трогается, а сорвавшийся
 * арендатор не уносит с собой остальных.
 *
 * <p><b>Почему тест архивирует чужие строки реестра.</b> Приведение идёт
 * по всей ячейке — это его работа, — а реестр в прогоне общий: соседние
 * классы оставляют там своих арендаторов, и код возврата переставал бы
 * что-либо утверждать. Архивируются они на время одного метода и
 * возвращаются обратно; двигать чужие схемы вниз нельзя ни на секунду.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class SchemaSyncTest extends PostgresTestBase {

    private static final String TENANT = "t_000158";
    private static final String AHEAD = "t_000159";
    private static final String BROKEN = "t_000160";

    private static final String MINE = "(158, 159, 160)";

    @Autowired
    private SchemaSync sync;

    @Autowired
    private TenantSchemaMigrator migrator;

    @Autowired
    private JdbcTemplate jdbc;

    private List<Map<String, Object>> hidden;

    @BeforeAll
    static void provision() {
        provisionTenants(TENANT, AHEAD, BROKEN);
    }

    @BeforeEach
    void isolateCell() {
        hidden = jdbc.queryForList("""
                SELECT tenant_id, status FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED') AND tenant_id NOT IN """ + MINE);
        jdbc.update("""
                UPDATE public.tenant_registry SET status = 'ARCHIVED'
                 WHERE status IN ('ACTIVE', 'SUSPENDED') AND tenant_id NOT IN """ + MINE);

        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id IN " + MINE);
    }

    @AfterEach
    void releaseCell() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id IN " + MINE);
        for (Map<String, Object> row : hidden) {
            jdbc.update("UPDATE public.tenant_registry SET status = ? WHERE tenant_id = ?",
                    row.get("status"), row.get("tenant_id"));
        }
    }

    @Test
    @DisplayName("Отставшая схема: ненулевой код до наката и ноль после")
    void behindSchemaFailsCheckThenPasses() {
        register(158, TENANT);
        // Ровно то, что у клиента, до которого новый changeset не доехал:
        // объекта нет, и в журнале миграций схемы его строки тоже нет.
        migrator.rollbackLast(TENANT, 1);
        assertThat(viewExists(TENANT)).isFalse();

        SchemaSync.Result before = sync.run(new SchemaSync.Plan(SchemaSync.Mode.CHECK, null));

        assertThat(before.exitCode())
                .as("проверка промолчала на отставшей схеме — выкладка пойдёт в окно, "
                        + "где новый код работает на старой схеме")
                .isEqualTo(1);
        assertThat(before.problems()).extracting(SchemaSync.Problem::schema).contains(TENANT);
        assertThat(viewExists(TENANT))
                .as("проверка что-то изменила — она обязана только смотреть")
                .isFalse();

        SchemaSync.Result after = sync.run(SchemaSync.Plan.apply());

        assertThat(after.exitCode()).isZero();
        assertThat(after.brought()).contains(TENANT);
        assertThat(viewExists(TENANT)).isTrue();
        assertThat(versionOf(158)).isEqualTo(migrator.expectedVersion());
        assertThat(sync.run(new SchemaSync.Plan(SchemaSync.Mode.CHECK, null)).exitCode()).isZero();
    }

    @Test
    @DisplayName("Повторный запуск на накатанном — пустая операция и ноль")
    void secondRunChangesNothing() {
        register(158, TENANT);
        sync.run(SchemaSync.Plan.apply());

        SchemaSync.Result second = sync.run(SchemaSync.Plan.apply());

        assertThat(second.exitCode()).isZero();
        assertThat(second.brought())
                .as("повторный запуск снова двигает схему — им перестанут пользоваться")
                .isEmpty();
        assertThat(second.alreadyThere()).isPositive();
    }

    @Test
    @DisplayName("Вниз: схема опускается до названной версии и поднимается обратно")
    void schemaGoesDownToTargetVersion() {
        register(158, TENANT);
        int total = migrator.totalChangeSets();

        SchemaSync.Result down = sync.run(
                new SchemaSync.Plan(SchemaSync.Mode.APPLY, total - 1));

        assertThat(down.exitCode()).isZero();
        assertThat(viewExists(TENANT))
                .as("артефакт версии N-1 оставил схему на версии N: пара «код и база» "
                        + "разошлась, а выкладка об этом промолчала")
                .isFalse();
        assertThat(migrator.inspect(TENANT).applied()).isEqualTo(total - 1);
        assertThat(versionOf(158)).isEqualTo(migrator.versionAt(total - 1));
        assertThat(migrator.versionAt(total))
                .as("две отметки одной и той же версии читаются по-разному")
                .isEqualTo(migrator.expectedVersion());

        assertThat(sync.run(SchemaSync.Plan.apply()).exitCode()).isZero();
        assertThat(viewExists(TENANT)).isTrue();
    }

    @Test
    @DisplayName("Схема впереди образа названа по имени, и её не трогают")
    void aheadSchemaIsNamedAndLeftAlone() {
        register(159, AHEAD);
        // Так выглядит схема, побывавшая под более новой сборкой: changeset
        // накатан, а его файла в этом артефакте нет вовсе.
        jdbc.update("""
                INSERT INTO %s.databasechangelog
                    (id, author, filename, dateexecuted, orderexecuted, exectype, md5sum)
                VALUES ('tenant-999-iz-budushchego', 'platform',
                        'db/changelog/tenant/999-future.sql', now(),
                        (SELECT max(orderexecuted) + 1 FROM %s.databasechangelog),
                        'EXECUTED', '9:0')""".formatted(AHEAD, AHEAD));
        int applied = migrator.inspect(AHEAD).applied();

        SchemaSync.Result result = sync.run(SchemaSync.Plan.apply());

        assertThat(result.exitCode())
                .as("схема впереди образа принята за годную: получилась пара "
                        + "«код версии 100, база версии 103», и выкладка об этом смолчала")
                .isEqualTo(1);
        assertThat(result.problems())
                .as("схема впереди образа не названа по имени — искать её будет некому")
                .anySatisfy(problem -> {
                    assertThat(problem.schema()).isEqualTo(AHEAD);
                    assertThat(problem.reason()).contains("впереди", "999-future.sql");
                });
        assertThat(migrator.inspect(AHEAD).applied())
                .as("rollbackCount снял вместо чужих changeset'ов последние свои")
                .isEqualTo(applied);
        assertThat(viewExists(AHEAD)).isTrue();
    }

    @Test
    @DisplayName("Сорвавшийся арендатор назван, остальные домигрированы")
    void oneBrokenTenantDoesNotStopTheRest() {
        register(158, TENANT);
        register(160, BROKEN);

        migrator.rollbackLast(TENANT, 1);
        migrator.rollbackLast(BROKEN, 1);
        // Таблица снесена руками: журнал миграций об этом не знает, и накат
        // последнего changeset'а упирается в отсутствующее отношение.
        jdbc.execute("DROP TABLE %s.part_stock CASCADE".formatted(BROKEN));

        SchemaSync.Result result = sync.run(SchemaSync.Plan.apply());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.problems()).extracting(SchemaSync.Problem::schema)
                .containsExactly(BROKEN);
        assertThat(result.problems()).allSatisfy(
                problem -> assertThat(problem.reason()).isNotBlank());
        assertThat(result.brought())
                .as("проход встал на сломанном клиенте — остальные остались "
                        + "на старой схеме из-за чужой поломки")
                .contains(TENANT);
        assertThat(viewExists(TENANT)).isTrue();
    }

    @Test
    @DisplayName("Аргументы режима разбираются, а опечатка отбивается словами")
    void commandArgumentsAreParsed() {
        assertThat(SchemaSyncCommand.requested(new String[]{"--schema-sync"})).isTrue();
        assertThat(SchemaSyncCommand.requested(new String[]{"--check"})).isFalse();

        assertThat(SchemaSyncCommand.planOf(new String[]{"--schema-sync"}))
                .isEqualTo(new SchemaSync.Plan(SchemaSync.Mode.APPLY, null));
        assertThat(SchemaSyncCommand.planOf(new String[]{"--schema-sync", "--check"}))
                .isEqualTo(new SchemaSync.Plan(SchemaSync.Mode.CHECK, null));
        assertThat(SchemaSyncCommand.planOf(new String[]{"--schema-sync", "--to=132"}))
                .isEqualTo(new SchemaSync.Plan(SchemaSync.Mode.APPLY, 132));

        // Проглоченная опечатка означала бы накат там, где просили проверку.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> SchemaSyncCommand.planOf(
                        new String[]{"--schema-sync", "--chek"}))
                .hasMessageContaining("--chek");
    }

    @Test
    @DisplayName("Версия, которой в образе нет, отбивается, а не накатывается")
    void unknownTargetVersionIsRefused() {
        register(158, TENANT);

        SchemaSync.Result result = sync.run(new SchemaSync.Plan(
                SchemaSync.Mode.APPLY, migrator.totalChangeSets() + 1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.problems()).singleElement()
                .satisfies(problem -> assertThat(problem.reason()).contains("неизвестна"));
        assertThat(viewExists(TENANT)).isTrue();
    }

    private void register(long id, String schema) {
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status, schema_version)
                VALUES (?, ?, 'Разборка', ?, 'ACTIVE', NULL)""",
                id, schema, "sync" + id);
    }

    /** Последний changeset набора — вьюха сверки остатка; по ней и видно версию. */
    private boolean viewExists(String schema) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.views
                                WHERE table_schema = ? AND table_name = 'v_stock_discrepancy')""",
                Boolean.class, schema);
        return Boolean.TRUE.equals(exists);
    }

    private String versionOf(long tenantId) {
        return jdbc.queryForObject(
                "SELECT schema_version FROM public.tenant_registry WHERE tenant_id = ?",
                String.class, tenantId);
    }
}
