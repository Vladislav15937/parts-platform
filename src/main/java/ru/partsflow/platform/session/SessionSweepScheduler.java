package ru.partsflow.platform.session;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Расписание уборки зависших сессий.
 *
 * <p>Отдельным бином — по той же причине, что у релея outbox и разбора
 * недоставленного: тесты зовут проход напрямую, а {@code @SchedulerLock}
 * на вызываемом методе молча пропускал бы такой вызов.
 *
 * <p>Раз в пять минут: сессия, брошенная полчаса назад, подождёт ещё пять
 * минут без всякого вреда — на экране она в это время честно называется
 * активной, потому что недействительной ещё не стала.
 */
@Component
@ConditionalOnProperty(name = "app.sessions.sweep-enabled", havingValue = "true",
        matchIfMissing = true)
public class SessionSweepScheduler {

    private final SessionSweep sweep;

    public SessionSweepScheduler(SessionSweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${app.sessions.sweep-delay-ms:300000}")
    @SchedulerLock(name = "session-sweep", lockAtMostFor = "10m")
    public void closeStale() {
        sweep.closeStale();
    }
}
