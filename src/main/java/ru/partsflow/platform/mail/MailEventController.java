package ru.partsflow.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Приём отчётов оператора о доставке: {@code POST /api/mail/events/{секрет}}.
 *
 * <p>Это единственное, что переводит письмо в {@code DELIVERED}. Права даёт
 * секрет в адресе ({@code app.mail.webhook-secret}), как у прайса площадки:
 * у оператора нет и не будет сессии. Пустой секрет выключает приём — 404,
 * как и неверный: по ответу не узнать, включён ли механизм.
 *
 * <p><b>Не проверено на живом операторе.</b> Разбор опирается на описание
 * события {@code transactional_email_status} ({@code events_by_user[].events[]
 * .event_data}: {@code job_id}, {@code status}); на день написания ключа и
 * кабинета нет. Разбор терпимый: чужое и непонятное пропускается, а не роняет
 * приём — оператор повторил бы весь пакет. Подпись самого оператора (поле
 * {@code auth}) не сверяется: защита здесь — секрет в адресе.
 */
@RestController
public class MailEventController {

    private static final Logger log = LoggerFactory.getLogger(MailEventController.class);

    public record Applied(int events, int applied) {}

    private final MailService mail;
    private final MailProperties props;

    public MailEventController(MailService mail, MailProperties props) {
        this.mail = mail;
        this.props = props;
    }

    @PostMapping("/api/mail/events/{secret}")
    public Applied receive(@PathVariable String secret, @RequestBody JsonNode body) {
        String expected = props.webhookSecret();
        if (MailProperties.blank(expected) || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        int seen = 0;
        int applied = 0;
        for (JsonNode user : body.path("events_by_user")) {
            for (JsonNode event : user.path("events")) {
                if (!"transactional_email_status".equals(event.path("event_name").asText())) {
                    continue;
                }
                JsonNode data = event.path("event_data");
                seen++;
                String detail = data.path("delivery_info").path("delivery_status").asText(null);
                if (mail.applyEvent(data.path("job_id").asText(null),
                        data.path("status").asText(null), detail)) {
                    applied++;
                }
            }
        }
        log.info("Отчёты оператора писем: событий {}, применено {}", seen, applied);
        return new Applied(seen, applied);
    }
}
