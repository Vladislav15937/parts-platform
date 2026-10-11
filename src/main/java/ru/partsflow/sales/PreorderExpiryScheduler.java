package ru.partsflow.sales;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание снятия просроченных предзаказов (задача 0260).
 *
 * <p>Отдельным бином, а не аннотацией на сервисе, по тем же причинам, что у
 * релея дельт: тесты зовут проход напрямую, и {@code @SchedulerLock} на вызванном
 * методе пропускал бы такой вызов; а расписание надо уметь выключать — контекстов
 * Spring в тестах много, база одна, и фоновый проход чужого контекста снял бы
 * просроченную сделку раньше, чем тест её проверил.
 *
 * <p>Раз в минуту: срок называется днём, а не минутой, и задержка снятия на
 * минуту ничего не стоит. Замок ShedLock не даёт двум нодам идти одновременно;
 * от двойного снятия при этом защищает не он, а блокировка строки сделки.
 */
@Component
@ConditionalOnProperty(name = "app.preorder-expiry.enabled", havingValue = "true",
        matchIfMissing = true)
public class PreorderExpiryScheduler {

    private final PreorderExpiryService expiry;

    public PreorderExpiryScheduler(PreorderExpiryService expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${app.preorder-expiry.delay-ms:60000}")
    @SchedulerLock(name = "preorder-expiry", lockAtMostFor = "10m")
    public void run() {
        expiry.sweep();
    }
}
