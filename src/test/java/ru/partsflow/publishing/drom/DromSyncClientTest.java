package ru.partsflow.publishing.drom;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.publishing.DeliveryStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Клиент дельт на настоящем соединении: что именно случилось, когда Дром
 * «не ответил» (задача 0268).
 *
 * <p>Сервер здесь местный ({@link HttpServer} и голые сокеты), но соединение,
 * сроки и исключения — настоящего {@link java.net.http.HttpClient}: вид
 * исключения на каждый сбой определяет он, а не заглушка, и проверять его
 * по заглушке значило бы проверять собственное предположение о нём.
 *
 * <p>Каждый случай проходит всю дорогу до карточки: результат запроса →
 * текст {@code last_error} ({@link DromSyncRecord}) → {@link DeliveryStatus}.
 */
class DromSyncClientTest {

    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : opened) {
            c.close();
        }
    }

    @Test
    @DisplayName("200 без тела — принято; 200 с кодом отказа — отказ с этим кодом")
    void answers() throws IOException {
        assertThat(clientAnswering(200, "").sync("1", "a", bytes(), "d.xml").success()).isTrue();

        DromSyncClient.Result refused = clientAnswering(200,
                "{\"error\":\"ERROR_REASON_PACKET_NOT_FOUND\"}").sync("1", "a", bytes(), "d.xml");
        assertThat(refused.success()).isFalse();
        assertThat(card(refused).code()).isEqualTo(DeliveryStatus.Code.PACKET_NOT_FOUND);
    }

    @Test
    @DisplayName("HTML-страница 502 — ошибка HTTP со статусом; страница при 200 — не «принято»")
    void htmlPages() throws IOException {
        String page = "<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head><body></body></html>";
        DromSyncClient.Result gateway = clientAnswering(502, page).sync("1", "a", bytes(), "d.xml");
        assertThat(gateway.success()).isFalse();
        DeliveryStatus status = card(gateway);
        assertThat(status.code()).isEqualTo(DeliveryStatus.Code.HTTP_ERROR);
        assertThat(status.httpStatus()).isEqualTo(502);
        assertThat(status.detail()).isEqualTo("502 Bad Gateway");

        DromSyncClient.Result login = clientAnswering(200, "<html><title>Вход</title></html>")
                .sync("1", "a", bytes(), "d.xml");
        assertThat(login.success()).as("страница сайта вместо ответа API принята за успех").isFalse();
        assertThat(card(login).code()).isEqualTo(DeliveryStatus.Code.UNRECOGNIZED);
    }

    @Test
    @DisplayName("Соединились и не дождались ответа — истёк срок, а не «нет связи»")
    void timeout() throws IOException {
        ServerSocket silent = new ServerSocket(0);
        opened.add(silent);
        // Принимает соединение и молчит: ровно «соединились, а ответа нет».
        Thread acceptor = new Thread(() -> {
            try {
                Socket s = silent.accept();
                opened.add(s);
            } catch (IOException ignored) {
                // сокет закрыт в конце теста
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        DromSyncClient client = new DromSyncClient(
                URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/sync"),
                Duration.ofSeconds(5), Duration.ofMillis(300));

        DromSyncClient.Result result = client.sync("1", "a", bytes(), "d.xml");

        assertThat(result.failure()).isEqualTo(DromSyncClient.Transport.TIMEOUT);
        assertThat(card(result).code()).isEqualTo(DeliveryStatus.Code.TIMEOUT);
    }

    @Test
    @DisplayName("Порт не слушает — соединение не установилось")
    void refusedConnection() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        DromSyncClient client = new DromSyncClient(URI.create("http://127.0.0.1:" + port + "/sync"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));

        DromSyncClient.Result result = client.sync("1", "a", bytes(), "d.xml");

        assertThat(result.failure()).isEqualTo(DromSyncClient.Transport.CONNECT);
        assertThat(card(result).code()).isEqualTo(DeliveryStatus.Code.NO_CONNECTION);
    }

    @Test
    @DisplayName("Имя не разрешилось — своё, а не «нет соединения»")
    void unresolvedHost() {
        // .invalid не разрешается никогда (RFC 2606) — и без сети тоже.
        DromSyncClient client = new DromSyncClient(URI.create("http://baza.drom.invalid/sync"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));

        DromSyncClient.Result result = client.sync("1", "a", bytes(), "d.xml");

        assertThat(result.failure()).isEqualTo(DromSyncClient.Transport.DNS);
        assertThat(card(result).code()).isEqualTo(DeliveryStatus.Code.HOST_UNKNOWN);
    }

    @Test
    @DisplayName("Срок соединения — «не соединились», а не «не дождались ответа»")
    void connectTimeoutIsNoConnection() {
        assertThat(DromSyncClient.transportOf(new HttpConnectTimeoutException("HTTP connect timed out")))
                .isEqualTo(DromSyncClient.Transport.CONNECT);
        assertThat(DromSyncClient.transportOf(new ConnectException("Connection refused")))
                .isEqualTo(DromSyncClient.Transport.CONNECT);
        assertThat(DromSyncClient.transportOf(new IOException("Connection reset")))
                .isEqualTo(DromSyncClient.Transport.BROKEN);
    }

    @Test
    @DisplayName("Сбой без сообщения — отказ с текстом, а не пустота, которую читают «принято»")
    void failureWithoutMessageIsNeverEmpty() {
        String text = DromSyncRecord.of(new DromSyncClient.Result(false, 0, null));
        assertThat(text).isNotBlank();
        DeliveryStatus status = DeliveryStatus.of("DROM", "ACTIVE", true, "1", Instant.now(), text);
        assertThat(status.state()).isEqualTo(DeliveryStatus.State.REFUSED);
        assertThat(status.code()).isEqualTo(DeliveryStatus.Code.CONNECTION_BROKEN);
    }

    private DromSyncClient clientAnswering(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sync", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        opened.add(() -> server.stop(0));
        return new DromSyncClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sync"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private static DeliveryStatus card(DromSyncClient.Result result) {
        return DeliveryStatus.of("DROM", "ACTIVE", true, "1", Instant.now(), DromSyncRecord.of(result));
    }

    private static byte[] bytes() {
        return "<offers/>".getBytes(StandardCharsets.UTF_8);
    }
}
