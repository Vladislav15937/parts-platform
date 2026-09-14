package ru.partsflow.platform.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import ru.partsflow.PartsPlatformApplication;
import ru.partsflow.support.PostgresTestBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запись заморожена на время выкладки — чтение работает, запись отвечает
 * словами и дожидается конца заморозки (задача 0112).
 *
 * <p><b>Что замораживают.</b> Выкладка ставит базе
 * {@code default_transaction_read_only = on} и обрывает её соединения, пока
 * снимает копию и поднимает на ней новую сборку. Тест делает то же самое,
 * но ролью, а не базой: база у тестовых контекстов общая, и заморозка всей
 * базы уронила бы соседние классы. Для приложения разницы нет — умолчание
 * сессии Postgres одинаково приходит и от базы, и от роли.
 *
 * <p><b>Настоящий экземпляр и настоящий HTTP, а не MockMvc.</b> Одна из двух
 * поломок живёт в фильтре сессий, который пишет отметку обращения уже после
 * ответа контроллера, — то есть там, куда MockMvc не ходит.
 *
 * <p><b>Схема своя</b> ({@code t_000203}), роль приложения своя
 * ({@code freeze_probe}): замороженным должен оказаться только этот экземпляр.
 */
class WriteFreezeHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000203";
    private static final String COMPANY = "zamorozka";
    private static final String ROLE = "freeze_probe";
    private static final String PASSWORD = "пароль-длинный";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static ConfigurableApplicationContext app;
    private static int port;

    @BeforeAll
    static void fixtures() throws Exception {
        provisionTenants(TENANT);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM public.tenant_registry WHERE tenant_id = 203");
            statement.execute("""
                    INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                    VALUES (203, 't_000203', 'Разборка на заморозке', 'zamorozka')""");
            statement.execute("DELETE FROM t_000203.tenant_member WHERE login = 'vladelec'");
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO t_000203.tenant_member (display_name, role, login, password_hash)
                    VALUES ('Владелец заморозки', 'OWNER', 'vladelec', ?)""")) {
                insert.setString(1, encoder.encode(PASSWORD));
                insert.executeUpdate();
            }

            // Роль приложения — член владельца схем: права те же, что у него,
            // а умолчание сессии можно поставить ей одной.
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM pg_roles WHERE rolname = '" + ROLE + "'")) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    statement.execute("CREATE ROLE %s LOGIN PASSWORD '%s' IN ROLE %s"
                            .formatted(ROLE, PASSWORD, POSTGRES.getUsername()));
                }
            }
            statement.execute("ALTER ROLE " + ROLE + " RESET default_transaction_read_only");
        }

        app = new SpringApplicationBuilder(PartsPlatformApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + ROLE,
                "--spring.datasource.password=" + PASSWORD,
                // Фоновые обходы идут по всему реестру ячейки — те же
                // свойства, что в PostgresTestBase и SessionStoreTest.
                "--app.migrate-catalog-on-start=false",
                "--app.outbox.relay-enabled=false",
                "--app.outbox.dead-letter-retry-enabled=false",
                "--app.outbox.metrics-enabled=false",
                "--app.feeds.delta-enabled=false",
                "--app.sessions.sweep-enabled=false");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterAll
    static void close() throws Exception {
        try {
            setFrozen(false);
        } finally {
            if (app != null) {
                app.close();
            }
        }
    }

    @Test
    @DisplayName("Замороженная база: чтение работает, запись — 503 со словами, после заморозки — проходит")
    void frozenDatabaseReadsAndRefusesWritesInWords() throws Exception {
        Browser owner = login();
        String name = "Покупатель во время заморозки " + System.nanoTime();
        String body = "{\"name\":\"%s\"}".formatted(name);

        setFrozen(true);

        // Чтение вошедшего. До правки отвечало пятисоткой: отметку обращения
        // Spring Session пишет в базу на каждом запросе, и отказ этой записи
        // ронял даже «кто я».
        HttpResponse<String> me = owner.get("/api/auth/me");
        assertThat(me.statusCode())
                .as("чтение вошедшего при замороженной записи: %s", me.body())
                .isEqualTo(200);
        HttpResponse<String> directory = owner.get("/api/customers/directory");
        assertThat(directory.statusCode())
                .as("чтение из схемы арендатора при замороженной записи: %s", directory.body())
                .isEqualTo(200);

        // Запись. До правки — 500 «Внутренняя ошибка»: продавец шёл бы искать
        // поломку, а не ждать минуту.
        HttpResponse<String> frozen = owner.post("/api/customers", body);
        assertThat(frozen.statusCode())
                .as("запись при замороженной базе: %s", frozen.body())
                .isEqualTo(503);
        assertThat(frozen.body()).contains("Идёт обновление").contains("не записано");
        assertThat(frozen.headers().firstValue("Retry-After"))
                .as("совет, через сколько повторить").isPresent();
        assertThat(customers(name)).as("отказанная запись не должна была лечь").isZero();

        // Вход пишет журнал входов — значит тоже 503, а не 401: «неверный
        // пароль» при верном пароле хуже любого отказа.
        Browser late = new Browser();
        late.get("/api/auth/csrf");
        HttpResponse<String> refusedLogin = late.post("/api/auth/login", credentials());
        assertThat(refusedLogin.statusCode())
                .as("вход при замороженной базе: %s", refusedLogin.body())
                .isEqualTo(503);

        setFrozen(false);

        // Повтор той же записи проходит, а вошедший остался вошедшим:
        // заморозка не выкидывает из кабинета.
        HttpResponse<String> retried = owner.post("/api/customers", body);
        assertThat(retried.statusCode())
                .as("повтор записи после заморозки: %s", retried.body())
                .isBetween(200, 201);
        assertThat(owner.get("/api/auth/me").statusCode())
                .as("вошедший после заморозки").isEqualTo(200);
        assertThat(customers(name)).isEqualTo(1);
    }

    // --- заморозка -------------------------------------------------------------

    /**
     * Та же пара действий, что у выкладки: умолчание сессии и обрыв соединений.
     * Без обрыва умолчание досталось бы только новым соединениям пула.
     */
    private static void setFrozen(boolean frozen) throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(frozen
                    ? "ALTER ROLE " + ROLE + " SET default_transaction_read_only = on"
                    : "ALTER ROLE " + ROLE + " RESET default_transaction_read_only");
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE usename = '" + ROLE + "'");
        }
        // Пул проверяет соединение, простоявшее дольше полсекунды; раньше
        // он отдал бы оборванное, и тест поймал бы обрыв, а не заморозку.
        Thread.sleep(1_000);
    }

    private static int customers(String name) throws Exception {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM t_000203.customer WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    // --- браузер ---------------------------------------------------------------

    private static String credentials() {
        return "{\"company\":\"%s\",\"login\":\"vladelec\",\"password\":\"%s\"}"
                .formatted(COMPANY, PASSWORD);
    }

    private static Browser login() throws Exception {
        Browser browser = new Browser();
        browser.get("/api/auth/csrf");
        HttpResponse<String> response = browser.post("/api/auth/login", credentials());
        assertThat(response.statusCode()).as("вход до заморозки: %s", response.body())
                .isEqualTo(200);
        return browser;
    }

    /** Клиент с cookie — как в SessionStoreTest: сессия и CSRF-токен из cookie. */
    private static final class Browser {

        private final Map<String, String> cookies = new LinkedHashMap<>();

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET());
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String csrf = cookies.get("XSRF-TOKEN");
            if (csrf != null) {
                request.header("X-XSRF-TOKEN", csrf);
            }
            return send(request);
        }

        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
            if (!cookies.isEmpty()) {
                request.header("Cookie", cookies.entrySet().stream()
                        .map(e -> e.getKey() + '=' + e.getValue())
                        .collect(Collectors.joining("; ")));
            }
            HttpResponse<String> response =
                    HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().allValues("set-cookie").forEach(this::keep);
            return response;
        }

        private void keep(String header) {
            String pair = header.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq < 0) {
                return;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }

        private static URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
