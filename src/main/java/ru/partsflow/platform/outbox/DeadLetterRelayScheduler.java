package ru.partsflow.platform.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание повторной доставки.
 *
 * <p>Отдельным бином — по тем же двум причинам, что и у релея outbox: тесты
 * зовут проход напрямую (с {@code @SchedulerLock} на вызываемом методе такой
 * вызов молча пропускался бы), а в тестах поднимается с десяток контекстов
 * поверх одной базы, и чужой планировщик забрал бы запись раньше теста —
 * с настоящим клиентом площадки вместо подставленной заглушки.
 *
 * <p>Раз в минуту, а не раз в секунду: самая короткая выдержка и так минута,
 * и чаще ходить незачем.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.dead-letter-retry-enabled", havingValue = "true",
        matchIfMissing = true)
public class DeadLetterRelayScheduler {

    private final DeadLetterRelay relay;

    public DeadLetterRelayScheduler(DeadLetterRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.dead-letter-retry-delay-ms:60000}")
    @SchedulerLock(name = "dead-letter-retry", lockAtMostFor = "10m")
    public void retryDue() {
        relay.retryDue();
    }
}
