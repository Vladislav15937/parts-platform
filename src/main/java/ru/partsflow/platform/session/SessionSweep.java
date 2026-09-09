package ru.partsflow.platform.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.TenantShare;

import java.time.Duration;

/**
 * Уборка зависших сессий: приложение перезапустили, запись осталась открытой.
 *
 * <p>«Без уборки журнал наполняется вечно-активными призраками»
 * (docs/sessions.md, §5). Выход закрывает запись сам, а вот сессия, которую
 * просто бросили, не закрывает ничего: сессии живут в памяти приложения,
 * и перезапуск уносит их молча.
 *
 * <p><b>Срок берётся у сервлет-контейнера, а не назначается здесь.</b>
 * Сколько живёт сессия — это решение владельца продукта, которого он ещё
 * не принимал (docs/sessions.md, §10: «сроки простоя и абсолютный»), и выдумать
 * его тут значило бы поменять поведение входа под видом журнала. Уборка
 * записывает то, что уже происходит: сессия считается недействительной
 * тогда же, когда её выбрасывает Tomcat.
 *
 * <p>Запас поверх срока обязателен: отметка активности прорежена, и в базе
 * она отстаёт от настоящей на интервал прореживания. Без запаса уборка
 * закрывала бы живые сессии людей, которые как раз работают.
 *
 * <p>Падение одного арендатора не останавливает остальных — тот же довод,
 * что у релея outbox и разбора недоставленного.
 */
@Component
public class SessionSweep {

    private static final Logger log = LoggerFactory.getLogger(SessionSweep.class);

    private final LoginSessions sessions;
    private final TenantShare tenants;
    private final Duration idle;

    public SessionSweep(LoginSessions sessions, TenantShare tenants,
                        @Value("${server.servlet.session.timeout:30m}") Duration sessionTimeout,
                        @Value("${app.sessions.activity-interval:2m}") Duration markEvery) {
        this.sessions = sessions;
        this.tenants = tenants;
        this.idle = sessionTimeout.plus(markEvery).plusMinutes(1);
    }

    /** Один проход по своей доле арендаторов. */
    public int closeStale() {
        int closed = 0;
        for (String schema : tenants.schemas()) {
            try {
                int inTenant = sessions.closeStale(schema, idle);
                if (inTenant > 0) {
                    log.info("Арендатор {}: закрыто зависших сессий {}", schema, inTenant);
                }
                closed += inTenant;
            } catch (Exception e) {
                log.error("Арендатор {}: уборка сессий не прошла", schema, e);
            }
        }
        return closed;
    }
}
