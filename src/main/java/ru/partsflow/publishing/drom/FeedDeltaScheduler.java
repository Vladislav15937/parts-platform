package ru.partsflow.publishing.drom;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание отправки дельт.
 *
 * <p>Отдельным бином, а не аннотацией на релее, по тем же двум причинам, что
 * и у outbox: тесты зовут релей напрямую, и {@code @SchedulerLock} на вызванном
 * методе молча пропускал бы такой вызов; а само расписание надо уметь
 * выключать — в тестах поднимается с десяток контекстов Spring поверх одной
 * базы, и фоновый релей чужого контекста заберёт пачку раньше теста.
 *
 * <p>Интервал по умолчанию — пятнадцать секунд, и это не «почаще, чтобы
 * поживее». Он же задаёт склейку: правка сотни позиций, сделанная списком,
 * укладывается в один заход и уезжает одной дельтой вместо сотни запросов
 * к площадке. Секундный интервал, как у outbox, здесь означал бы разговор
 * с чужим сервером по каждому нажатию кнопки.
 */
@Component
@ConditionalOnProperty(name = "app.feeds.delta-enabled", havingValue = "true",
        matchIfMissing = true)
public class FeedDeltaScheduler {

    private final FeedDeltaRelay relay;

    public FeedDeltaScheduler(FeedDeltaRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.feeds.delta-delay-ms:15000}")
    @SchedulerLock(name = "feed-delta-relay", lockAtMostFor = "5m")
    public void relay() {
        relay.relay();
    }
}
