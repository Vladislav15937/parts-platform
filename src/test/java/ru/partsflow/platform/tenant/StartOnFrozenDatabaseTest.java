package ru.partsflow.platform.tenant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import ru.partsflow.PartsPlatformApplication;
import ru.partsflow.support.PostgresTestBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Сборка поднимается на замороженной базе — на чтение (задача 0122).
 *
 * <p><b>Что ломалось.</b> Выкладка замораживает запись в базе работающей
 * сборки на полминуты ({@code default_transaction_read_only}, задача 0112),
 * и всё это время людей обслуживает как раз она. А перезапустись она в эти
 * секунды — упала по памяти, кто-то сделал {@code docker compose restart}, —
 * и подняться уже не могла: накат общей схемы при старте выдаёт права
 * рабочей роли ({@link SchemaGrants#applyCellTables()}), на замороженной
 * базе это {@code GRANT} с кодом 25006, а {@link CatalogMigrations} валил
 * на нём запуск. Контейнер уходил в круг перезапусков, и полминуты «нельзя
 * записать» превращались в <b>полный отказ ячейки, включая чтение</b>, —
 * до отката выкладки. Решение владельца от 18 сентября 2026: чинить до
 * первого обновления у настоящего клиента.
 *
 * <p><b>Почему своя база, а не своя роль.</b> Выкладка морозит базу целиком,
 * и стартовые шаги идут <b>владельцем схем</b>, а не рабочей ролью: заморозь
 * мы роль, как это делает {@code WriteFreezeHttpTest}, владелец писал бы
 * по-прежнему и ломаться было бы нечему. Морозить же общую базу прогона
 * нельзя — на ней живут все остальные контексты. Поэтому здесь свой
 * {@code parts_frozen_0122} и настоящее {@code ALTER DATABASE … SET
 * default_transaction_read_only = on}.
 *
 * <p><b>Роли тоже настоящие, две.</b> Владелец схем делает DDL и выдаёт
 * права, рабочая роль обслуживает запросы: без разделения
 * {@code applyCellTables} не делает вовсе ничего, и проверка отвечала бы
 * не про бой.
 */
class StartOnFrozenDatabaseTest extends PostgresTestBase {

    /** Своя база: её и морозим, как это делает выкладка. */
    private static final String DB = "parts_frozen_0122";

    /** Рабочая роль ячейки: та же затея, что у {@code partsflow_app} в бою. */
    private static final String ROLE = "frozen_probe_0122";
    private static final String PASSWORD = "frozen-probe-0122";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final ObjectMapper json = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void prepareCell() throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB);
            createRole(statement);
        }
        // Тем же путём из classpath, каким changelog читает приложение:
        // Liquibase считает changeset по пути файла, и накатанное «из каталога»
        // он за своё не признает — общая схема оказалась бы непринятой,
        // то есть проверка мерила бы не заморозку, а несовпадение путей.
        migrateCatalogFromClasspath();
        grantCellRights();
    }

    @AfterAll
    static void dropCell() throws Exception {
        setFrozen(false);
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("DROP ROLE IF EXISTS " + ROLE);
        }
    }

    @BeforeEach
    void listenToStartup() {
        // Spring Boot при каждом запуске поднимает logback заново — и вместе
        // с ним отцепляет приёмник, повешенный до старта: строка в логе есть,
        // а проверка её не видит. Поймано этой же проверкой, а не выведено.
        // «none» означает «логирование не трогать»: остаётся то, что настроено
        // в прогоне, и приёмник переживает подъём.
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void stopListening() throws Exception {
        logger().detachAppender(appender);
        System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
        setFrozen(false);
    }

    @Test
    @DisplayName("Заморозка на время выкладки: сборка поднимается и отвечает на чтение")
    void startsOnFrozenDatabase() throws Exception {
        setFrozen(true);

        ConfigurableApplicationContext app = start(ROLE);
        try {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();

            assertThat(get(port, "/actuator/health").statusCode())
                    .as("сборка поднялась, а на чтение не отвечает")
                    .isEqualTo(200);

            HttpResponse<String> readiness = get(port, "/actuator/readiness");
            JsonNode body = json.readTree(readiness.body());
            assertThat(ok(body, "database"))
                    .as("база не отвечает при работающей базе: %s", readiness.body())
                    .isTrue();
            assertThat(ok(body, "catalog"))
                    .as("общая схема принята целиком, а готовность считает иначе: %s",
                            readiness.body())
                    .isTrue();

            assertThat(warnings())
                    .as("про пропущенные шаги старта не сказано ни слова: человек, "
                            + "разбирающийся с ячейкой, не узнает, что права не выданы "
                            + "и схему не накатывали")
                    .anySatisfy(message -> assertThat(message)
                            .contains("заморожена")
                            .contains("только на чтение"));
        } finally {
            app.close();
        }
    }

    @Test
    @DisplayName("Незамороженная база: права рабочей роли выдаются, как и раньше")
    void grantsStillApplyWhenNotFrozen() throws Exception {
        // Без этого проверка выше зеленела бы и на «глотаем всё подряд»:
        // старт, переставший выдавать права вовсе, оставил бы ячейку без
        // хранилища сессий — то есть вход отвечал бы пятисоткой всем.
        revokeSessionRights();
        assertThat(canWriteSessions())
                .as("права не сняты — дальше проверять нечего").isFalse();

        ConfigurableApplicationContext app = start(ROLE);
        app.close();

        assertThat(canWriteSessions())
                .as("старт не выдал прав на хранилище сессий: вход отвечал бы "
                        + "пятисоткой при верном пароле")
                .isTrue();
    }

    @Test
    @DisplayName("Отказ не про заморозку запуск по-прежнему валит")
    void otherFailuresStillStopTheStart() {
        // Роли с таким именем в кластере нет: GRANT отвечает 42704, а не 25006.
        // Проглоти мы и это, ячейка поднималась бы с ненастроенными правами
        // и молчала бы об этом.
        assertThatThrownBy(() -> start("net_takoy_roli_0122").close())
                .as("запуск пережил отказ, к заморозке отношения не имеющий")
                .hasMessageContaining("права");
    }

    // --- подъём сборки ---------------------------------------------------------

    private static ConfigurableApplicationContext start(String runtimeRole) {
        return new SpringApplicationBuilder(PartsPlatformApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + jdbcUrl(),
                "--spring.datasource.username=" + ROLE,
                "--spring.datasource.password=" + PASSWORD,
                // Разделение ролей, как в ячейке: DDL и права — владельцем схем.
                "--app.runtime-role=" + runtimeRole,
                "--app.ddl.url=" + jdbcUrl(),
                "--app.ddl.username=" + POSTGRES.getUsername(),
                "--app.ddl.password=" + POSTGRES.getPassword(),
                // Накат общей схемы при старте — то, что здесь и проверяется.
                "--app.migrate-catalog-on-start=true",
                // Фоновые обходы идут по всему реестру ячейки — те же
                // свойства, что в PostgresTestBase и WriteFreezeHttpTest.
                "--app.outbox.relay-enabled=false",
                "--app.outbox.dead-letter-retry-enabled=false",
                "--app.outbox.metrics-enabled=false",
                "--app.feeds.delta-enabled=false",
                "--app.sessions.sweep-enabled=false");
    }

    // --- заморозка -------------------------------------------------------------

    /**
     * То же, что делает шаг выкладки: умолчание транзакции у самой базы
     * и обрыв её соединений — без обрыва умолчание досталось бы только
     * новым соединениям пула.
     */
    private static void setFrozen(boolean frozen) throws Exception {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE " + DB
                    + (frozen ? " SET default_transaction_read_only = on"
                              : " RESET default_transaction_read_only"));
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = '" + DB + "' AND pid <> pg_backend_pid()");
        }
    }

    // --- ячейка ----------------------------------------------------------------

    private static void createRole(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT count(*) FROM pg_roles WHERE rolname = '" + ROLE + "'")) {
            rows.next();
            if (rows.getInt(1) == 0) {
                statement.execute(
                        "CREATE ROLE %s LOGIN PASSWORD '%s'".formatted(ROLE, PASSWORD));
            }
        }
    }

    /** То же, что выдаёт {@code ops/create-roles.sh} при заведении ячейки. */
    private static void grantCellRights() throws Exception {
        try (Connection connection = cellConnection(POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("GRANT CONNECT ON DATABASE " + DB + " TO " + ROLE);
            statement.execute("GRANT USAGE ON SCHEMA catalog TO " + ROLE);
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO " + ROLE);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
            statement.execute("GRANT SELECT ON public.tenant_registry TO " + ROLE);
            statement.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON public.shedlock TO " + ROLE);
        }
    }

    private static void revokeSessionRights() throws Exception {
        try (Connection connection = cellConnection(POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("REVOKE ALL ON public.spring_session FROM " + ROLE);
            statement.execute("REVOKE ALL ON public.spring_session_attributes FROM " + ROLE);
        }
    }

    private static boolean canWriteSessions() throws Exception {
        try (Connection connection = cellConnection(POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT has_table_privilege('%s', 'public.spring_session', 'INSERT')"
                             .formatted(ROLE))) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static void migrateCatalogFromClasspath() throws Exception {
        try (Connection connection = cellConnection(POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            var database = liquibase.database.DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(
                            new liquibase.database.jvm.JdbcConnection(connection));
            database.setLiquibaseSchemaName("public");
            try (liquibase.Liquibase liquibase = new liquibase.Liquibase(
                    "db/changelog/db.changelog-catalog.xml",
                    new liquibase.resource.ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new liquibase.Contexts());
            }
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection cellConnection(String user, String password) throws Exception {
        return DriverManager.getConnection(jdbcUrl(), user, password);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), DB);
    }

    // --- ответы и лог ----------------------------------------------------------

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private boolean ok(JsonNode body, String check) {
        for (JsonNode node : body.get("checks")) {
            if (check.equals(node.get("check").asText())) {
                return node.get("ok").asBoolean();
            }
        }
        throw new IllegalStateException("В ответе готовности нет проверки " + check);
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CatalogMigrations.class);
    }
}
