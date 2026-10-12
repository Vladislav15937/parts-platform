package ru.partsflow.publishing.drom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Клиент дельта-обновлений Дрома.
 *
 * <p><b>Этот класс — только половина интеграции.</b> Полный прайс к Дрому не
 * отправляется: он лежит по постоянному URL в S3, и Дром забирает его сам —
 * ровно как Авито. Сюда уходят только точечные изменения: продали деталь,
 * поменяли цену, появилась новая позиция. Разбор в {@code docs/drom-integration.md}.
 *
 * <p>Endpoint принимает {@code multipart/form-data} с тремя полями:
 * {@code packetId} (идентификатор прайс-листа в кабинете клиента), {@code auth}
 * (см. {@link #authHash}) и {@code data} (дельта в формате исходного прайса).
 *
 * <p>Лимит 5 МБ относится именно к дельте и в норме недостижим: одна проданная
 * деталь — это несколько сотен байт. Если упёрлись в него, отправлять надо не
 * пачку дельт, а полный прайс по ссылке.
 *
 * <p><b>Чего этот API не умеет.</b> Ответ на успешный запрос — {@code 200 OK}
 * и ничего больше: ни статусов позиций, ни замечаний по товарам. Всё это живёт
 * в кабинете клиента. Не рассчитывай наполнить отсюда {@code listing.status}
 * и {@code listing.error_text} — по Дрому они останутся пустыми.
 */
@Component
public class DromSyncClient {

    private static final Logger log = LoggerFactory.getLogger(DromSyncClient.class);

    private static final URI SYNC_ENDPOINT = URI.create("https://baza.drom.ru/good/packet/api/sync");
    private static final int MAX_PACKET_BYTES = 5 * 1024 * 1024;

    private final URI endpoint;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public DromSyncClient() {
        this(SYNC_ENDPOINT, Duration.ofSeconds(15), Duration.ofMinutes(2));
    }

    /** Для проверки на настоящем соединении: свой адрес и свои сроки. */
    DromSyncClient(URI endpoint, Duration connectTimeout, Duration requestTimeout) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    public int maxPacketBytes() {
        return MAX_PACKET_BYTES;
    }

    /**
     * @param packetId идентификатор прайс-листа в кабинете клиента
     * @param auth     см. {@link #authHash}
     * @param deltaXml изменённые позиции в формате исходного прайса;
     *                 удаление — количество {@code 0}. Не более {@link #maxPacketBytes()}
     */
    public Result sync(String packetId, String auth, byte[] deltaXml, String fileName) {
        if (deltaXml.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException(
                    "Дельта %d байт превышает лимит %d — такой объём отправляй полным прайсом по ссылке"
                            .formatted(deltaXml.length, MAX_PACKET_BYTES));
        }

        String boundary = "----partsflow" + System.nanoTime();
        try {
            byte[] body = multipartBody(boundary, packetId, auth, deltaXml, fileName);

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // Успех и отказ — одним разбором по виду, тем же, которым карточка
            // потом называет причину: разойдись они, карточка сказала бы
            // «отказ» про то, что отправитель счёл принятым, или наоборот.
            boolean ok = DromAnswer.accepted(response.statusCode(), response.body());
            if (!ok) {
                log.warn("Дром: синхронизация не удалась, HTTP {}, тело: {}",
                        response.statusCode(), response.body());
            }
            return new Result(ok, response.statusCode(), response.body());

        } catch (IOException e) {
            Transport kind = transportOf(e);
            log.warn("Дром: синхронизация не удалась, {}: {}", kind, e.toString());
            return new Result(false, 0, describe(e), kind);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, "Прервано", Transport.BROKEN);
        }
    }

    /**
     * Что случилось с соединением, когда ответа нет вовсе.
     *
     * <p>«Не ответил» человеку не помогает: истёкший срок ожидания, отказ
     * в соединении и неразрешённое имя лечатся разным — первое ждут, второе
     * чаще всего сбой площадки, третье почти всегда наша сеть. Различаются
     * они видом исключения {@link HttpClient}: срок — {@link HttpTimeoutException}
     * (срок соединения — его подвид, и это «не соединились»), имя —
     * {@link UnresolvedAddressException} или {@link UnknownHostException}
     * в цепочке причин, отказ — {@link ConnectException}.
     */
    static Transport transportOf(Throwable e) {
        if (e instanceof HttpConnectTimeoutException) {
            return Transport.CONNECT;
        }
        if (e instanceof HttpTimeoutException) {
            return Transport.TIMEOUT;
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof UnresolvedAddressException || c instanceof UnknownHostException) {
                return Transport.DNS;
            }
        }
        if (e instanceof ConnectException) {
            return Transport.CONNECT;
        }
        return Transport.BROKEN;
    }

    /** Текст сбоя: сообщение исключения бывает пустым, а пустота тут не годится. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        for (Throwable c = e.getCause(); (message == null || message.isBlank()) && c != null;
             c = c.getCause()) {
            message = c.getMessage();
        }
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * Хеш авторизации: sha512 от ключа кабинета.
     *
     * <p>Ключ уникален на кабинет и выдаётся Дромом по заявке. Логин и пароль
     * клиента здесь ни при чём — не проси их и не храни: это лишний барьер
     * доверия при подключении и лишняя ответственность потом.
     */
    public static String authHash(String cabinetKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(cabinetKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 недоступен", e);
        }
    }

    private byte[] multipartBody(String boundary, String packetId, String auth,
                                 byte[] file, String fileName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, boundary, "packetId", packetId);
        writeField(out, boundary, "auth", auth);

        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"data\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/xml\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void writeField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /** Что случилось с соединением, когда HTTP-ответа нет (задача 0268). */
    public enum Transport {
        /** Соединились, а ответа не дождались. */
        TIMEOUT,
        /** Соединение не установилось: отказ или срок соединения. */
        CONNECT,
        /** Имя площадки не разрешилось. */
        DNS,
        /** Связь оборвалась иначе (сброс, TLS, прерывание). */
        BROKEN
    }

    /**
     * @param httpStatus 0, если HTTP-ответа не было
     * @param body       тело ответа; без ответа — текст сбоя
     * @param failure    при {@code httpStatus == 0} — что случилось с соединением;
     *                   иначе {@code null}
     */
    public record Result(boolean success, int httpStatus, String body, Transport failure) {

        public Result(boolean success, int httpStatus, String body) {
            this(success, httpStatus, body, null);
        }
    }
}
