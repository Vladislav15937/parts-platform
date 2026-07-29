package ru.partsflow.support;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * База для интеграционных тестов: поднимает Postgres в контейнере и накатывает
 * миграции тем же Liquibase, что и в проде.
 *
 * <p>Именно тем же — не отдельным тестовым DDL. Схема, отличающаяся от боевой,
 * даёт зелёные тесты при сломанном проде, и это худший вид ложной уверенности.
 */
@Testcontainers
public abstract class PostgresTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("parts")
                    .withUsername("app")
                    .withPassword("app");

    static {
        POSTGRES.start();
        migrateCatalog();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static void migrateCatalog() {
        runLiquibase("db.changelog-catalog.xml", "public", null);
    }

    protected static void provisionTenants(String... schemas) {
        for (String schema : schemas) {
            runLiquibase("db.changelog-tenant.xml", schema, schema);
        }
    }

    private static void runLiquibase(String changelog, String liquibaseSchema, String tenantSchema) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setLiquibaseSchemaName(liquibaseSchema);
            if (tenantSchema != null) {
                database.setDefaultSchemaName(tenantSchema);
            }

            var accessor = new DirectoryResourceAccessor(new File(changelogDir()));
            try (Liquibase liquibase = new Liquibase(changelog, accessor, database)) {
                if (tenantSchema != null) {
                    liquibase.setChangeLogParameter("tenant.schema", tenantSchema);
                }
                liquibase.update(new Contexts());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось накатить миграции: " + changelog, e);
        }
    }

    private static String changelogDir() {
        // Тесты запускаются из корня модуля; миграции лежат рядом с кодом.
        return new File("db/changelog").getAbsolutePath();
    }
}
