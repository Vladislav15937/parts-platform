package ru.partsflow.platform.session;

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

import java.io.IOException;
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
 * Состояние сессии живёт в общей схеме ячейки, а не в памяти процесса
 * (задача 0080).
 *
 * <p><b>Два настоящих экземпляра приложения, а не один контекст.</b> Оба
 * утверждения задачи про это и есть: «перезапуск не выкидывает вошедших»
 * доказывается только погашенным и поднятым процессом, а «отозвали на первом
 * — второй отказывает немедленно» без второго экземпляра не доказывается
 * вовсе. Поэтому здесь не {@code @SpringBootTest}, а
 * {@link SpringApplicationBuilder}: контексты поднимаются и гасятся руками,
 * на своих портах, с одной общей базой — ровно как два контейнера ячейки
 * за терминатором.
 *
 * <p><b>И ходим мы к ним по HTTP, настоящим клиентом с cookie.</b> MockMvc
 * тут не годится ни в каком виде: он живёт внутри одного контекста, а вся
 * проверка — про то, что происходит <b>между</b> ними.
 *
 * <p><b>Схемы свои, ничьи больше</b> ({@code t_000156} и {@code t_000157}):
 * вторая нужна не для полноты, а для проверки, что отзыв не перетекает
 * между клиентами — логин {@code prodavec} есть в обеих, и готовый резолвер
 * Spring Session индексирует сессии как раз по логину.
 */
class SessionStoreTest extends PostgresTestBase {

    private static final String TENANT = "t_000156";
    private static final String COMPANY = "sesstore";
    private static final String OTHER_TENANT = "t_000157";
    private static final String OTHER_COMPANY = "sessdruguyu";
    private static final String PASSWORD = "пароль-длинный";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** Сосед: работает всё время класса и ничего про чужие сессии не знает. */
    private static Instance neighbour;

    private static long sellerId;

    @BeforeAll
    static void fixtures() {
        provisionTenants(TENANT, OTHER_TENANT);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM public.tenant_registry WHERE tenant_id IN (156, 157)");
            statement.execute("""
                    INSERT INTO public.tenant_registry
                        (tenant_id, schema_name, company_name, code)
                    VALUES (156, 't_000156', 'Разборка на Ткацкой', 'sesstore'),
                           (157, 't_000157', 'Разборка на Дальней', 'sessdruguyu')""");

            member(connection, TENANT, "vladelec", "Владелец", "OWNER", encoder);
            sellerId = member(connection, TENANT, "prodavec", "Сидоров", "SELLER", encoder);
            // Тот же логин в другой компании — на нём и ловится отзыв,
            // перетекающий между клиентами.
            member(connection, OTHER_TENANT, "prodavec", "Однофамилец", "SELLER", encoder);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось завести фикстуру", e);
        }

        neighbour = start();
    }

    @AfterAll
    static void stopNeighbour() {
        if (neighbour != null) {
            neighbour.close();
        }
    }

    /**
     * Первое требование задачи: погасили и подняли экземпляр — вошедший
     * остаётся вошедшим.
     *
     * <p><b>И тут же второй край: перезапуск не продлевает мёртвое.</b>
     * Сессия, у которой простой вышел, обязана остаться недействительной —
     * иначе «пережила перезапуск» превратилось бы в «начала жизнь заново»,
     * и сессия, брошенная на чужом компьютере, оживала бы на каждой выкладке.
     * Обе половины в одном методе намеренно: перезапуск для них один и тот же,
     * а порознь первая зеленела бы и на коде, который просто не проверяет срок.
     */
    @Test
    @DisplayName("Сессия переживает перезапуск экземпляра, а истёкшая не воскресает")
    void sessionSurvivesRestartButExpiredStaysDead() throws Exception {
        Browser live;
        Browser abandoned;
        Instance first = start();
        try {
            live = login(first, COMPANY, "prodavec");
            abandoned = login(first, COMPANY, "prodavec");
            assertThat(live.get(first, "/api/auth/me").statusCode()).isEqualTo(200);
        } finally {
            first.close();
        }

        Instance restarted = start();
        try {
            HttpResponse<String> mine = live.get(restarted, "/api/auth/me");
            assertThat(mine.statusCode())
                    .as("перезапуск не должен требовать повторного входа")
                    .isEqualTo(200);
            assertThat(mine.body()).contains("prodavec");

            // Брошенную состарили: последняя активность — двое суток назад,
            // то есть простой давно вышел. Живую не трогаем — её этот запрос
            // и не касается.
            ageSession(abandoned.sessionId(), Duration.ofDays(2));

            assertThat(abandoned.get(restarted, "/api/auth/me").statusCode())
                    .as("истёкшая сессия не оживает от перезапуска")
                    .isEqualTo(401);
        } finally {
            restarted.close();
        }
    }

    /**
     * Второе требование, и без второго экземпляра оно не доказывается вовсе:
     * отозвали на первом — второй отказывает <b>сразу</b>, а не по истечении.
     */
    @Test
    @DisplayName("Отзыв на одном экземпляре виден на другом немедленно")
    void revocationOnOneInstanceIsImmediateOnTheOther() throws Exception {
        Instance first = start();
        try {
            Browser seller = login(first, COMPANY, "prodavec");
            Browser owner = login(first, COMPANY, "vladelec");

            assertThat(seller.get(neighbour, "/api/auth/me").statusCode())
                    .as("до отзыва сосед пускает")
                    .isEqualTo(200);

            HttpResponse<String> disabled =
                    owner.post(first, "/api/members/" + sellerId + "/disable", "");
            assertThat(disabled.statusCode()).isEqualTo(204);

            assertThat(seller.get(neighbour, "/api/auth/me").statusCode())
                    .as("сосед обязан отказать на первом же запросе после отзыва")
                    .isEqualTo(401);
            assertThat(owner.get(neighbour, "/api/auth/me").statusCode())
                    .as("а чужие сессии отзыв не трогает")
                    .isEqualTo(200);
        } finally {
            enable(TENANT, sellerId);
            first.close();
        }
    }

    /**
     * Отзыв не перетекает между клиентами.
     *
     * <p>Логин уникален только внутри арендатора, а сессии в хранилище общие
     * на ячейку: индексируй их готовым резолвером Spring Session — по
     * {@code Authentication.getName()}, то есть по логину, — и выключенный
     * {@code prodavec} одной разборки выкинул бы {@code prodavec} соседней.
     * Это не неудобство, а действие безопасности, перетекшее к чужому
     * клиенту.
     */
    @Test
    @DisplayName("Отзыв в одной компании не трогает тёзку в другой")
    void revocationDoesNotCrossTenants() throws Exception {
        Instance first = start();
        try {
            Browser theirs = login(first, OTHER_COMPANY, "prodavec");
            Browser ours = login(first, COMPANY, "prodavec");
            Browser owner = login(first, COMPANY, "vladelec");

            owner.post(first, "/api/members/" + sellerId + "/disable", "");

            assertThat(ours.get(first, "/api/auth/me").statusCode())
                    .as("своего выключили")
                    .isEqualTo(401);
            assertThat(theirs.get(first, "/api/auth/me").statusCode())
                    .as("тёзка в другой компании работает как работал")
                    .isEqualTo(200);
        } finally {
            enable(TENANT, sellerId);
            first.close();
        }
    }

    /**
     * Срок жизни сессии в базе — тот же, что был у сервлет-контейнера.
     *
     * <p>Задача требует проверить, что <b>пределы жизни не изменились</b>:
     * переезд состояния в базу не должен был поменять поведение входа под
     * своим видом. Раньше срок держал Tomcat и увидеть его было негде;
     * теперь он лежит колонкой, и это первое место, где ошибка стала бы
     * видимой — причём тихо: срок, записанный нулём или отрицательным,
     * сделал бы сессию просроченной с рождения, а срок в сутки продлил бы
     * брошенную вкладку на чужом компьютере.
     *
     * <p>Сверяется с {@code server.servlet.session.timeout} (умолчание
     * сервлет-контейнера — 30 минут), а не с записанным здесь числом:
     * назначать срок — дело владельца продукта, и он его ещё не назначал
     * (docs/sessions.md, §10).
     */
    @Test
    @DisplayName("Срок простоя в базе равен сроку сервлет-контейнера")
    void idleLimitIsTheServletContainerOne() throws Exception {
        Instance first = start();
        try {
            Browser seller = login(first, COMPANY, "prodavec");
            Duration expected = first.context()
                    .getEnvironment()
                    .getProperty("server.servlet.session.timeout", Duration.class,
                                 Duration.ofMinutes(30));

            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT max_inactive_interval,
                                expiry_time - last_access_time
                           FROM public.spring_session
                          WHERE session_id = ?""")) {
                statement.setString(1, seller.sessionId());
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("строка сессии обязана быть в базе").isTrue();
                    assertThat(rows.getInt(1))
                            .as("срок простоя в базе — секунды сервлет-контейнера")
                            .isEqualTo((int) expected.toSeconds());
                    assertThat(rows.getLong(2))
                            .as("момент истечения — последняя активность плюс срок простоя")
                            .isEqualTo(expected.toMillis());
                }
            }
        } finally {
            first.close();
        }
    }

    // --- экземпляр приложения -------------------------------------------------

    private record Instance(ConfigurableApplicationContext context, int port) {

        void close() {
            context.close();
        }
    }

    /**
     * Поднять экземпляр приложения на своём порту поверх общей базы.
     *
     * <p>Фоновые обходы выключены теми же свойствами, что и в остальных
     * тестах: они идут по всему реестру арендаторов ячейки и мешали бы
     * соседним классам, а к сессиям отношения не имеют.
     */
    private static Instance start() {
        // Свойства передаются аргументами командной строки, а не
        // .properties(...): последний кладёт их в «умолчания», а те стоят
        // НИЖЕ application.yml — экземпляр молча уходил на localhost:5432,
        // то есть в базу разработчика, и вход отвечал 401 при верном пароле.
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(PartsPlatformApplication.class).run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        // Фоновые обходы идут по всему реестру арендаторов
                        // ячейки: к сессиям отношения не имеют, а соседним
                        // классам мешают. Те же свойства выключают их
                        // и в PostgresTestBase.
                        "--app.migrate-catalog-on-start=false",
                        "--app.outbox.relay-enabled=false",
                        "--app.outbox.dead-letter-retry-enabled=false",
                        "--app.outbox.metrics-enabled=false",
                        "--app.feeds.delta-enabled=false",
                        "--app.sessions.sweep-enabled=false");
        return new Instance(context,
                ((WebServerApplicationContext) context).getWebServer().getPort());
    }

    // --- браузер --------------------------------------------------------------

    /**
     * Клиент с cookie: один экземпляр — одна вкладка браузера одного человека.
     *
     * <p>Живёт дольше экземпляра приложения, и в этом весь смысл: у него
     * на руках только cookie, а состояние сессии — в базе.
     */
    private static final class Browser {

        private final Map<String, String> cookies = new LinkedHashMap<>();

        HttpResponse<String> get(Instance instance, String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(instance, path)).GET());
        }

        HttpResponse<String> post(Instance instance, String path, String body) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(instance, path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String csrf = cookies.get("XSRF-TOKEN");
            if (csrf != null) {
                request.header("X-XSRF-TOKEN", csrf);
            }
            return send(request);
        }

        /** Идентификатор сессии: cookie отдаёт его в base64, как и в бою. */
        String sessionId() {
            String value = cookies.get("SESSION");
            return value == null ? null
                    : new String(java.util.Base64.getDecoder().decode(value),
                                 StandardCharsets.UTF_8);
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

        private static URI uri(Instance instance, String path) {
            return URI.create("http://localhost:" + instance.port() + path);
        }
    }

    private static Browser login(Instance instance, String company, String login) throws Exception {
        Browser browser = new Browser();
        // Токен CSRF берётся отдельным запросом — ровно как это делает PWA.
        browser.get(instance, "/api/auth/csrf");
        HttpResponse<String> response = browser.post(instance, "/api/auth/login", """
                {"company":"%s","login":"%s","password":"%s"}"""
                .formatted(company, login, PASSWORD));
        assertThat(response.statusCode()).as("вход %s/%s", company, login).isEqualTo(200);
        assertThat(browser.sessionId()).as("вход обязан выдать cookie сессии").isNotNull();
        return browser;
    }

    // --- база -----------------------------------------------------------------

    private static void ageSession(String sessionId, Duration idle) throws Exception {
        long when = System.currentTimeMillis() - idle.toMillis();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE public.spring_session
                        SET last_access_time = ?, expiry_time = ?
                      WHERE session_id = ?""")) {
            statement.setLong(1, when);
            statement.setLong(2, when);
            statement.setString(3, sessionId);
            assertThat(statement.executeUpdate())
                    .as("строка сессии обязана лежать в общей схеме — её там и старим")
                    .isEqualTo(1);
        }
    }

    private static void enable(String schema, long memberId) {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE %s.tenant_member SET is_active = true WHERE id = %d"
                    .formatted(schema, memberId));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось вернуть сотрудника в строй", e);
        }
    }

    private static long member(Connection connection, String schema, String login,
                               String displayName, String role, BCryptPasswordEncoder encoder)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""".formatted(schema))) {
            statement.setString(1, displayName);
            statement.setString(2, role);
            statement.setString(3, login);
            statement.setString(4, encoder.encode(PASSWORD));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static Connection connect() throws IOException {
        try {
            return DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        } catch (Exception e) {
            throw new IOException("Нет соединения с базой", e);
        }
    }
}
