package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Журнал, который нельзя переписать прямым SQL.
 *
 * <p>Пока приложение ходило в базу одной ролью, оно владело всеми таблицами,
 * а владелец возвращает себе любое право одной командой: `REVOKE` не значил
 * ничего, и «кто уронил цену» по журналу можно было спросить только у того,
 * кто не хотел соврать. Это записано в правилах проекта как единственная
 * дыра в доказуемости — и закрывается она разделением ролей, а не обещанием.
 *
 * <p>Тест поднимает настоящую рантайм-роль и проверяет ею три журнала:
 * читать и добавлять можно, менять и удалять — нет.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "app.runtime-role=partsflow_runtime",
})
class RuntimeRoleTest extends PostgresTestBase {

    private static final String TENANT = "t_000098";
    private static final String ROLE = "partsflow_runtime";
    private static final String PASSWORD = "runtime-проба";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SchemaGrants grants;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Рантайм-роль пишет в журнал, но не правит и не стирает")
    void journalsAreImmutableForRuntimeRole() {
        createRole();
        grants.apply(TENANT);

        JdbcTemplate runtime = new JdbcTemplate(runtimeDataSource());
        Long partId = jdbc.queryForObject("""
                INSERT INTO %s.part (category_id, title, price) VALUES (1, 'Фара', 5000)
                RETURNING id""".formatted(TENANT), Long.class);

        // Писать в журнал роль обязана: без этого не примешь ни одной детали.
        runtime.update("""
                INSERT INTO %s.stock_movement (part_id, movement_type, qty_delta, reason)
                VALUES (?, 'INTAKE', 1, 'проба прав')""".formatted(TENANT), partId);
        assertThat(runtime.queryForObject(
                "SELECT count(*) FROM %s.stock_movement WHERE part_id = ?".formatted(TENANT),
                Integer.class, partId)).isEqualTo(1);

        // А переписать — нет, и это единственное, чего не обходит прямой SQL
        // из приложения. Исправление ошибки идёт встречной записью.
        // Колонка своя у каждого: identity-колонку Postgres отвергает раньше,
        // чем проверяет права, и такой запрос ничего бы не доказал.
        var journals = java.util.Map.of(
                "stock_movement", "reason",
                "audit_log", "table_name",
                "customer_account_entry", "comment");
        for (var entry : journals.entrySet()) {
            String journal = entry.getKey();
            String column = entry.getValue();
            assertThatThrownBy(() -> runtime.update(
                    "UPDATE %s.%s SET %s = %s".formatted(TENANT, journal, column, column)))
                    .as("журнал %s правится прямым SQL", journal)
                    // Отказ ищем в причине: Spring заворачивает его
                    // в BadSqlGrammarException, и в тексте верхнего уровня
                    // стоит только сам запрос.
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> runtime.update(
                    "DELETE FROM %s.%s".formatted(TENANT, journal)))
                    .as("журнал %s стирается прямым SQL", journal)
                    .hasStackTraceContaining("permission denied");
        }

        // Обычные таблицы при этом правятся: запрет точечный, а не «всё только
        // на чтение» — иначе не сохранить ни цену, ни заметку.
        runtime.update("UPDATE %s.part SET price = 6000 WHERE id = ?".formatted(TENANT), partId);
        assertThat(runtime.queryForObject(
                "SELECT price FROM %s.part WHERE id = ?".formatted(TENANT),
                java.math.BigDecimal.class, partId)).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("История допусков администратора: добавлять и закрывать можно, стирать и переписывать нет")
    void adminAccessHistoryIsAppendOnlyForRuntimeRole() {
        createRole();
        grants.apply(TENANT);
        // Ячейка, на которой роль уже получила прежние широкие права:
        // узкий GRANT их не отнимает, и без REVOKE в выдаче тест это покажет.
        for (String table : new String[] {"admin_access_grant", "admin_access_request"}) {
            jdbc.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
            jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.%s TO %s"
                    .formatted(table, ROLE));
        }
        grants.applyCellTables();
        // Накат повторяют после каждой выкладки: второй раз ничего не ломает.
        grants.applyCellTables();

        long tenantId = 98;
        jdbc.update("DELETE FROM public.admin_access_request WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.admin_access_grant WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", tenantId);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Проба прав истории', 'runtime98')""", tenantId, TENANT);
        jdbc.update("""
                INSERT INTO public.service_admin (login, password_hash, display_name)
                VALUES ('runtime-role-test', 'x', 'Проба')
                ON CONFLICT DO NOTHING""");
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM public.service_admin WHERE login = 'runtime-role-test'", Long.class);

        try {
            JdbcTemplate runtime = new JdbcTemplate(runtimeDataSource());

            // Что код делает на самом деле: добавляет и закрывает.
            runtime.update("""
                    INSERT INTO public.admin_access_grant (tenant_id, granted_by) VALUES (?, 1)""",
                    tenantId);
            runtime.update("""
                    INSERT INTO public.admin_access_request (tenant_id, admin_id) VALUES (?, ?)""",
                    tenantId, adminId);
            assertThat(runtime.update("""
                    UPDATE public.admin_access_grant SET closed_at = now(), closed_by = 1
                     WHERE tenant_id = ? AND closed_at IS NULL""", tenantId)).isEqualTo(1);
            assertThat(runtime.update("""
                    UPDATE public.admin_access_request SET closed_at = now(), outcome = 'DECLINED'
                     WHERE tenant_id = ? AND closed_at IS NULL""", tenantId)).isEqualTo(1);

            // А стереть и переписать след нельзя: ни строку, ни «кто и когда пустил».
            for (String table : new String[] {"admin_access_grant", "admin_access_request"}) {
                assertThatThrownBy(() -> runtime.update(
                        "DELETE FROM public.%s WHERE tenant_id = ?".formatted(table), tenantId))
                        .as("история %s стирается прямым SQL", table)
                        .hasStackTraceContaining("permission denied");
            }
            assertThatThrownBy(() -> runtime.update(
                    "UPDATE public.admin_access_grant SET granted_by = 2 WHERE tenant_id = ?", tenantId))
                    .as("автор разрешения правится задним числом")
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> runtime.update(
                    "UPDATE public.admin_access_grant SET granted_at = now() WHERE tenant_id = ?", tenantId))
                    .as("время выдачи правится задним числом")
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> runtime.update(
                    "UPDATE public.admin_access_request SET requested_at = now() WHERE tenant_id = ?", tenantId))
                    .as("время запроса правится задним числом")
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> runtime.update(
                    "UPDATE public.admin_access_request SET admin_id = admin_id WHERE tenant_id = ?", tenantId))
                    .as("автор запроса правится задним числом")
                    .hasStackTraceContaining("permission denied");

            // Учётную запись администратора рабочая роль только читает.
            assertThat(runtime.queryForObject(
                    "SELECT count(*) FROM public.service_admin WHERE login = 'runtime-role-test'",
                    Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> runtime.update(
                    "UPDATE public.service_admin SET is_active = false WHERE id = ?", adminId))
                    .hasStackTraceContaining("permission denied");
        } finally {
            jdbc.update("DELETE FROM public.admin_access_request WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM public.admin_access_grant WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM public.service_admin WHERE login = 'runtime-role-test'");
            jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", tenantId);
        }
    }

    private void createRole() {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, ROLE);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.execute("CREATE ROLE %s LOGIN PASSWORD '%s'".formatted(ROLE, PASSWORD));
        jdbc.execute("GRANT CONNECT ON DATABASE %s TO %s"
                .formatted(jdbc.queryForObject("SELECT current_database()", String.class), ROLE));
        jdbc.execute("GRANT USAGE ON SCHEMA catalog TO " + ROLE);
        jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO " + ROLE);
    }

    private DataSource runtimeDataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(ROLE);
        source.setPassword(PASSWORD);
        return source;
    }
}
