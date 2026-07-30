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
import java.sql.Statement;

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
                    .withPassword("app")
                    // Каждый @SpringBootTest с иным набором свойств поднимает свой
                    // кэшированный контекст, а в каждом — пул на 20 соединений
                    // из application.yml. Десяток контекстов упирается
                    // в max_connections=100 по умолчанию, и очередной тест падает
                    // с «too many clients already» — причём не тот, который
                    // виноват, а тот, что запустился последним.
                    //
                    // Поднимаем предел здесь, а не урезаем пул в базовом классе:
                    // @DynamicPropertySource старше свойств @SpringBootTest,
                    // и урезание молча отменило бы пул из одного соединения,
                    // на котором держится TenantIsolationTest.
                    .withCommand("postgres", "-c", "max_connections=400");

    static {
        // Docker Engine 29 отвечает 400 на всё ниже API 1.44 (MinAPIVersion),
        // а docker-java внутри Testcontainers ходит на 1.32 и версию не
        // согласовывает — контейнер не поднимается вовсе. Обновление
        // Testcontainers до 1.21.3 это не лечит, проверено. Свойство читается
        // при создании клиента, поэтому его ставим до старта контейнера — так
        // работает и из Maven, и из IDE.
        //
        // Значение переопределяемо: на машине со старым Docker жёсткие 1.44
        // сломают всё ровно наоборот — движок не знает такой версии. Порядок
        // источников — системное свойство, переменная окружения, умолчание.
        if (System.getProperty("api.version") == null) {
            String fromEnv = System.getenv("DOCKER_API_VERSION");
            System.setProperty("api.version",
                    fromEnv == null || fromEnv.isBlank() ? "1.44" : fromEnv);
        }

        POSTGRES.start();
        migrateCatalog();
        reserveNumberingRange();
    }

    /**
     * Отодвигает нумерацию провижининга от схем с фиксированными именами.
     *
     * <p>Провижининг выводит имя схемы из «максимальный номер плюс один»,
     * а тесты создают {@code t_000042}…{@code t_000069} сами и в реестр
     * не пишутся. Без этой записи провижининг рано или поздно возьмёт занятое
     * имя — и справедливо откажется его занимать, потому что молча принять
     * чужую схему нельзя.
     *
     * <p>В базовом классе, а не в одном тесте: база у всех контекстов общая,
     * и порядок классов не гарантирован.
     */
    private static void reserveNumberingRange() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    INSERT INTO public.tenant_registry
                        (tenant_id, schema_name, company_name, code, status)
                    VALUES (900000, 't_900000', 'Резерв нумерации', 'numbering-guard', 'SUSPENDED')
                    ON CONFLICT (tenant_id) DO NOTHING""");
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зарезервировать диапазон номеров", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Фоновый релей в тестах выключен. Контекстов Spring поднимается
        // с десяток, база у них одна, и планировщик чужого контекста заберёт
        // событие раньше теста — вместе с подставленными в нём заглушками.
        // Тем, кому релей нужен, вызывают его метод напрямую.
        registry.add("app.outbox.relay-enabled", () -> "false");
        // Накат каталога при старте контекста выключен: базовый класс уже
        // накатил его сам, до всякого Spring.
        //
        // Дело не только в дублировании работы. Liquibase считает changeset
        // по ПУТИ файла, а пути у нас разные: тест открывает changelog как
        // `db.changelog-catalog.xml` из каталога db/changelog, приложение —
        // как `db/changelog/db.changelog-catalog.xml` из classpath. Для
        // Liquibase это разные changeset'ы, он не видит своей же истории
        // и пытается создать catalog.brand второй раз.
        registry.add("app.migrate-catalog-on-start", () -> "false");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static void migrateCatalog() {
        runLiquibase("db.changelog-catalog.xml", "public", null);
    }

    protected static void provisionTenants(String... schemas) {
        for (String schema : schemas) {
            // Схему создаёт провижининг, а не миграции: DATABASECHANGELOG лежит
            // внутри неё, и Liquibase создаёт его до того, как выполнит первый
            // changeset. Тот же порядок, что в db/verify.sh.
            createSchema(schema);
            runLiquibase("db.changelog-tenant.xml", schema, schema);
        }
    }

    private static void createSchema(String schema) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать схему арендатора: " + schema, e);
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
