package ru.partsflow.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Письмо в лог. Для разработки: ссылку из письма нужно где-то взять.
 *
 * <p>Возвращает «принято», и письмо становится {@code SENT}, но
 * {@code DELIVERED} не станет никогда — отчёт о доставке приходит только
 * от оператора. Так статус не врёт и в разработке.
 */
public class LogMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(LogMailTransport.class);

    @Override
    public Result send(Outgoing m) {
        log.info("Письмо #{} для {} (в лог, оператор не подключён): {}\n{}",
                m.id(), m.to(), m.subject(), m.text());
        return Result.accepted("log-" + m.id());
    }
}
