package ru.partsflow.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import ru.partsflow.support.PostgresTestBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Невошедшего на странице приложения уводит на вход, а программу —
 * нет (задача 0265, возврат PR 383).
 *
 * <p>До правки открытый без входа {@code /sales} отвечал 401 с пустым телом
 * раньше всякой страницы: человек видел белый экран, и приложение, которое
 * умеет увести на вход, до него просто не загружалось.
 *
 * <p><b>Настоящий HTTP, а не MockMvc.</b> Проверяется ответ, который получит
 * браузер: статус, {@code Location} и тело переадресации на оболочку.
 * Перенаправления клиент не выполняет ({@code Redirect.NEVER}) — иначе
 * «увели на вход» и «отдали страницу входа» были бы неразличимы.
 *
 * <p><b>Обе стороны обязательны.</b> {@code /api} без входа обязан остаться
 * 401: офлайн-очередь приёмки прочитала бы перенаправление как успех
 * и удалила запись. Фид Дрома и {@code /actuator} не задеты — их читают
 * программы.
 *
 * <p>Свойства — те же, что у {@code DromFeedStreamTest}: свой набор поднял
 * бы ещё один Spring. Схему тест не занимает.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class SignInRedirectHttpTest extends PostgresTestBase {

    /** Строка из заглушки оболочки {@code src/test/resources/static/index.html}. */
    private static final String SHELL_MARK = "оболочка-для-тестов";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/sales", "/%D1%87%D1%82%D0%BE-%D1%83%D0%B3%D0%BE%D0%B4%D0%BD%D0%BE/%D0%B3%D0%BB%D1%83%D0%B1%D0%B6%D0%B5",
            "/net-takogo-adresa?x=1"})
    @DisplayName("Страница без входа уводит на /login")
    void pageWithoutSignInRedirectsToLogin(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode())
                .as("невошедший на %s обязан уйти на вход, а не получить белый экран", path)
                .isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElse(""))
                .as("перенаправление ведёт на общую страницу входа")
                .endsWith("/login");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/login", "/register", "/s/razborka/tok-123", "/"})
    @DisplayName("Страницы без входа отдают оболочку и никуда не уводят")
    void pagesWithoutSignInServeTheShell(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).as("ответ на %s", path).isEqualTo(200);
        assertThat(response.body()).contains(SHELL_MARK);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/parts/catalog", "/api/net-takogo", "/api", "/actuator/env"})
    @DisplayName("Программе без входа — по-прежнему 401, а не перенаправление")
    void machinePathsStayUnauthorized(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode())
                .as("%s без входа: перенаправление очередь приёмки разобрала бы как успех", path)
                .isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    @DisplayName("Изменяющий запрос не на /api без входа не перенаправляется")
    void postIsNotRedirected() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/sales")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isNotEqualTo(302);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    @DisplayName("Фид Дрома не задет: неверная ссылка — тот же 404, а не вход")
    void feedIsNotRedirected() throws Exception {
        HttpResponse<String> response = get("/feeds/drom/net-takoy/nevernyj-token.xml");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    @DisplayName("Готовность не задета: отвечает сама, без входа и без перенаправления")
    void actuatorIsNotRedirected() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isIn(200, 503);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
