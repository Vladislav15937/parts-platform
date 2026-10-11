package ru.partsflow.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Отправка через Unisender Go: {@code POST {baseUrl}/email/send.json},
 * ключ в заголовке {@code X-API-KEY}.
 *
 * <p><b>Что здесь проверено, а что нет.</b> Форма запроса и ответа взята
 * из описания API; живого ключа на день написания нет, поэтому настоящий
 * оператор этим кодом не вызывался ни разу. Сверено только с локальным
 * сервером в {@code UnisenderGoTransportTest}.
 *
 * <p><b>Классы отказа.</b> Сеть, таймаут, 5xx, 429, 408 — временные. 401 и 403
 * тоже временные: это ключ или адрес API, чинит их человек настройкой, а письмо
 * до тех пор должно ждать, а не гибнуть вместе с регистрацией. Остальные 4xx —
 * отказ по существу (адрес, формат): повтор их не изменит.
 */
public class UnisenderGoTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(UnisenderGoTransport.class);

    private final MailProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    public UnisenderGoTransport(MailProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(props.httpTimeout()).build();
    }

    @Override
    public Result send(Outgoing m) {
        if (MailProperties.blank(props.apiKey()) || MailProperties.blank(props.fromEmail())
                || MailProperties.blank(props.baseUrl())) {
            log.error("Письмо #{} не отправлено: не заданы APP_MAIL_API_KEY, APP_MAIL_FROM "
                    + "и/или APP_MAIL_BASE_URL. Письмо ждёт в очереди", m.id());
            return Result.temporary("не настроен оператор: нет ключа, адреса API или отправителя");
        }

        ObjectNode message = json.createObjectNode();
        message.putArray("recipients").addObject().put("email", m.to());
        message.putObject("body").put("html", m.html()).put("plaintext", m.text());
        message.put("subject", m.subject());
        message.put("from_email", props.fromEmail());
        if (!MailProperties.blank(props.fromName())) {
            message.put("from_name", props.fromName());
        }
        // Служебное письмо: ссылка отписки в нём бессмысленна, а ссылки,
        // переписанные оператором для учёта кликов, — лишний хоп.
        message.put("skip_unsubscribe", 1);
        message.put("track_links", 0);
        message.put("track_read", 0);
        ObjectNode body = json.createObjectNode();
        body.set("message", message);

        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(props.baseUrl().replaceAll("/+$", "") + "/email/send.json"))
                    .timeout(props.httpTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-API-KEY", props.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return interpret(m, response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.temporary("прервано");
        } catch (IOException | RuntimeException e) {
            return Result.temporary("оператор недоступен: " + e.getClass().getSimpleName());
        }
    }

    Result interpret(Outgoing m, int status, String text) {
        if (status == 200) {
            try {
                JsonNode root = json.readTree(text);
                JsonNode failed = root.path("failed_emails");
                if (failed.size() > 0) {
                    return Result.rejected("оператор не принял адрес: " + failed);
                }
                String job = root.path("job_id").asText("");
                if (!job.isEmpty()) {
                    return Result.accepted(job);
                }
            } catch (IOException ignored) {
                // разобрать нечем — ниже
            }
            // 200 без номера задания: следить за доставкой нечем, и считать
            // это «принято» значило бы выдать отправку за результат.
            return Result.temporary("ответ оператора без job_id");
        }
        String detail = "оператор ответил " + status + ": " + abbreviate(text);
        if (status >= 500 || status == 429 || status == 408 || status == 401 || status == 403) {
            if (status == 401 || status == 403) {
                log.error("Оператор писем не принял ключ ({}). Письмо #{} ждёт в очереди", status, m.id());
            }
            return Result.temporary(detail);
        }
        return Result.rejected(detail);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
