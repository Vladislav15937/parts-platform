package ru.partsflow.platform.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание релея.
 *
 * <p>Отдельным бином, а не аннотацией на самом релее, по двум причинам.
 *
 * <p><b>Тесты вызывают релей напрямую.</b> С {@code @SchedulerLock} на вызываемом
 * методе такой вызов проходил через распределённую блокировку и мог быть молча
 * пропущен — тест при этом падал бы с «событие никуда не доехало», а причина
 * была бы не в коде.
 *
 * <p><b>Расписание надо уметь выключать.</b> В тестах поднимается с десяток
 * контекстов Spring поверх одной базы, и у каждого свой планировщик. Все они
 * идут по одному и тому же реестру арендаторов, поэтому фоновый релей чужого
 * контекста забирает событие раньше теста — и обработчик отрабатывает
 * с настоящим клиентом площадки вместо подставленной заглушки. Проверить после
 * этого нечего.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-delay-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "5m")
    public void relay() {
        relay.relay();
    }
}
