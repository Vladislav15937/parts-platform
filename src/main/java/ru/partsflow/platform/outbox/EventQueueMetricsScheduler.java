package ru.partsflow.platform.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание сбора метрик очереди.
 *
 * <p>Отдельным бином, как у релея: тесты зовут сбор напрямую, а в тестах
 * поднимается с десяток контекстов поверх одной базы — фоновый сбор чужого
 * контекста только жёг бы соединения.
 *
 * <p>Без {@code @SchedulerLock} намеренно: считать глубину очереди должен
 * каждый экземпляр. Метрика привязана к экземпляру, который её отдаёт,
 * и блокировка сделала бы её пустой у всех, кроме одного.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.metrics-enabled", havingValue = "true",
        matchIfMissing = true)
public class EventQueueMetricsScheduler {

    private final EventQueueMetrics metrics;

    public EventQueueMetricsScheduler(EventQueueMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.outbox.metrics-delay-ms:30000}")
    public void refresh() {
        metrics.refresh();
    }
}
