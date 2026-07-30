package ru.partsflow.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.TenantContext;

import java.util.List;

/**
 * Робот повторной доставки: идёт по арендаторам ячейки и повторяет то,
 * чей срок подошёл.
 *
 * <p>Большинство отказов временные — площадка полежала полчаса, — и человеку
 * такое разбирать незачем. До появления этого прохода любая, даже минутная,
 * недоступность Дрома означала объявление, навсегда оставшееся доступным
 * по проданной детали.
 *
 * <p>Падение одного арендатора не останавливает остальных: схемы независимы,
 * тот же довод, что у релея outbox и у оркестратора миграций.
 */
@Component
public class DeadLetterRelay {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRelay.class);

    private final JdbcTemplate jdbc;
    private final DeadLetterService deadLetters;

    public DeadLetterRelay(JdbcTemplate jdbc, DeadLetterService deadLetters) {
        this.jdbc = jdbc;
        this.deadLetters = deadLetters;
    }

    /**
     * Один проход по всем арендаторам.
     *
     * <p>Расписание вынесено в {@link DeadLetterRelayScheduler} по той же
     * причине, что у релея outbox: тесты зовут этот метод напрямую, и
     * {@code @SchedulerLock} на нём молча пропускал бы вызов.
     */
    public void retryDue() {
        for (String schema : activeTenantSchemas()) {
            try {
                TenantContext.set(schema);
                int delivered = deadLetters.retryDue();
                if (delivered > 0) {
                    log.info("Арендатор {}: доставлено повтором {} событий", schema, delivered);
                }
            } catch (Exception e) {
                log.error("Арендатор {}: ошибка повторной доставки", schema, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private List<String> activeTenantSchemas() {
        return jdbc.queryForList(
                "SELECT schema_name FROM public.tenant_registry WHERE status = 'ACTIVE' "
                        + "ORDER BY tenant_id", String.class);
    }
}
