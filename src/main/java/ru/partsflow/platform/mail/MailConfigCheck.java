package ru.partsflow.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Говорит при старте, уходят ли письма.
 *
 * <p>Запуск не валится: ячейка без договора с оператором законна и обязана
 * подниматься. Но тихо она не поднимается — иначе первый узнавший о том, что
 * письма не уходят, был бы человек, ждущий письмо. Письма при этом не теряются:
 * очередь копит их до исправления настройки.
 */
@Component
public class MailConfigCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MailConfigCheck.class);

    private final MailProperties props;

    public MailConfigCheck(MailProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.unisender()) {
            log.warn("Письма НЕ уходят: app.mail.provider={} (оператор не подключён), "
                    + "тексты пишутся только в лог. Подключение — APP_MAIL_PROVIDER=unisender, "
                    + "APP_MAIL_API_KEY, APP_MAIL_FROM", props.provider());
            return;
        }
        if (MailProperties.blank(props.apiKey()) || MailProperties.blank(props.fromEmail())
                || MailProperties.blank(props.baseUrl())) {
            log.error("Письма НЕ уходят: выбран оператор unisender, но не заданы "
                    + "APP_MAIL_API_KEY, APP_MAIL_FROM и/или APP_MAIL_BASE_URL. Письма копятся в очереди "
                    + "и уйдут после исправления настройки");
        }
        if (MailProperties.blank(props.webhookSecret())) {
            log.warn("Отчёты о доставке не принимаются: не задан APP_MAIL_WEBHOOK_SECRET. "
                    + "Письма будут «отправлены», но не «доставлены»");
        }
    }
}
