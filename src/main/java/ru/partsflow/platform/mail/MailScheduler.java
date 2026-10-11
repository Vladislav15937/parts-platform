package ru.partsflow.platform.mail;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание разбора очереди писем. Отдельным бином и с выключателем — по тем
 * же двум причинам, что у релея outbox: тесты зовут {@link MailService#dispatchDue()}
 * напрямую, а планировщик чужого контекста забрал бы письмо раньше теста.
 */
@Component
@ConditionalOnProperty(name = "app.mail.dispatch-enabled", havingValue = "true",
        matchIfMissing = true)
public class MailScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailScheduler.class);

    private final MailService mail;

    public MailScheduler(MailService mail) {
        this.mail = mail;
    }

    @Scheduled(fixedDelayString = "${app.mail.dispatch-delay-ms:5000}")
    @SchedulerLock(name = "mail-dispatch", lockAtMostFor = "5m")
    public void dispatch() {
        try {
            mail.dispatchDue();
        } catch (RuntimeException e) {
            // Схема, не накатанная до catalog/022, или замороженная база:
            // расписание не должно падать вместе с ними.
            log.error("Разбор очереди писем не удался", e);
        }
    }
}
