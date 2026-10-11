package ru.partsflow.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Форма запроса к Unisender Go и разбор его ответов — против локального
 * сервера. Настоящего оператора не вызывает: ключа нет.
 */
class UnisenderGoTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MailTransport.Outgoing LETTER = new MailTransport.Outgoing(
            7, "owner@example.test", "Тема", "текст", "<p>html</p>");

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"status\":\"success\",\"job_id\":\"JOB-1\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-API-KEY"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private UnisenderGoTransport transport(String apiKey, String baseUrl) {
        return new UnisenderGoTransport(new MailProperties("unisender", apiKey, baseUrl,
                "noreply@example.test", "Отправитель", null, null, null,
                Duration.ofSeconds(2), null, null), JSON);
    }

    private UnisenderGoTransport live() {
        return transport("secret-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/ru/api/v1/");
    }

    @Test
    @DisplayName("Запрос: адрес, ключ в заголовке, получатель, тема, оба тела и отправитель")
    void requestShape() throws Exception {
        MailTransport.Result r = live().send(LETTER);

        assertThat(r.outcome()).isEqualTo(MailTransport.Outcome.ACCEPTED);
        assertThat(r.providerId()).isEqualTo("JOB-1");
        assertThat(path.get()).isEqualTo("/ru/api/v1/email/send.json");
        assertThat(key.get()).isEqualTo("secret-key");
        JsonNode m = JSON.readTree(body.get()).path("message");
        assertThat(m.path("recipients").get(0).path("email").asText()).isEqualTo("owner@example.test");
        assertThat(m.path("subject").asText()).isEqualTo("Тема");
        assertThat(m.path("body").path("html").asText()).isEqualTo("<p>html</p>");
        assertThat(m.path("body").path("plaintext").asText()).isEqualTo("текст");
        assertThat(m.path("from_email").asText()).isEqualTo("noreply@example.test");
        assertThat(m.path("from_name").asText()).isEqualTo("Отправитель");
    }

    @Test
    @DisplayName("Ключ оператора в тело запроса не попадает")
    void keyIsNotInTheBody() {
        live().send(LETTER);
        assertThat(body.get()).doesNotContain("secret-key");
    }

    @Test
    @DisplayName("5xx, 429, 401 и 403 — временные: письмо ждёт, а не гибнет")
    void temporaryStatuses() {
        for (int code : new int[]{500, 503, 429, 401, 403}) {
            status = code;
            reply = "{\"status\":\"error\",\"message\":\"x\"}";
            assertThat(live().send(LETTER).outcome())
                    .as("код %d", code).isEqualTo(MailTransport.Outcome.TEMPORARY);
        }
    }

    @Test
    @DisplayName("Прочие 4xx и адрес в failed_emails — отказ по существу")
    void rejections() {
        status = 400;
        reply = "{\"status\":\"error\",\"message\":\"bad\"}";
        assertThat(live().send(LETTER).outcome()).isEqualTo(MailTransport.Outcome.REJECTED);

        status = 200;
        reply = "{\"status\":\"success\",\"job_id\":\"J\",\"failed_emails\":{\"owner@example.test\":\"invalid\"}}";
        assertThat(live().send(LETTER).outcome()).isEqualTo(MailTransport.Outcome.REJECTED);
    }

    @Test
    @DisplayName("200 без job_id — не «принято»: следить за доставкой нечем")
    void successWithoutJobIsNotAccepted() {
        reply = "{\"status\":\"success\"}";
        assertThat(live().send(LETTER).outcome()).isEqualTo(MailTransport.Outcome.TEMPORARY);
    }

    @Test
    @DisplayName("Оператор недоступен — временный отказ")
    void unreachable() {
        int port = server.getAddress().getPort();
        server.stop(0);
        assertThat(transport("k", "http://127.0.0.1:" + port + "/").send(LETTER).outcome())
                .isEqualTo(MailTransport.Outcome.TEMPORARY);
    }

    @Test
    @DisplayName("Нет ключа или адреса API — запроса не будет, письмо ждёт настройки")
    void notConfiguredSendsNothing() {
        assertThat(transport("", "http://127.0.0.1:" + server.getAddress().getPort()).send(LETTER).outcome())
                .isEqualTo(MailTransport.Outcome.TEMPORARY);
        assertThat(transport("k", null).send(LETTER).outcome())
                .isEqualTo(MailTransport.Outcome.TEMPORARY);
        assertThat(path.get()).isNull();
    }
}
