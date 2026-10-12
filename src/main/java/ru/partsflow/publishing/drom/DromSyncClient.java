package ru.partsflow.publishing.drom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

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

            HttpRequest request = HttpRequest.newBuilder(SYNC_ENDPOINT)
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            boolean ok = response.statusCode() == 200 && !response.body().contains("ERROR_REASON");
            if (!ok) {
                log.warn("Дром: синхронизация не удалась, HTTP {}, тело: {}",
                        response.statusCode(), response.body());
            }
            return new Result(ok, response.statusCode(), response.body());

        } catch (IOException e) {
            return new Result(false, 0, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, "Прервано");
        }
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

    public record Result(boolean success, int httpStatus, String body) {
    }
}
