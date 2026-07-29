package ru.partsflow.platform.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.tenant.TenantContext;

import java.util.List;

/**
 * Переливает события из {@code outbox} в транспорт.
 *
 * <p>Идёт по всем арендаторам ячейки. Выборка через {@code FOR UPDATE SKIP LOCKED}
 * даёт конкурентную обработку несколькими экземплярами без гонок и без явных
 * блокировок.
 *
 * <p>Порядок внутри арендатора сохраняется: выбираем по возрастанию {@code id}
 * и отправляем пачку целиком. Ключ партиции в Kafka обеспечивает порядок дальше,
 * на стороне потребителей.
 *
 * <p>При падении транспорта записи остаются неопубликованными и уйдут в следующий
 * заход — это и есть гарантия at-least-once. Дубликаты возможны, поэтому
 * потребители обязаны быть идемпотентными.
 *
 * <p>TODO: когда задержка в сотни миллисекунд станет мешать, заменить на Debezium
 * (CDC по логической репликации) — это уберёт постоянный опрос БД.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final OutboxRepository outboxRepository;
    private final EventTransport transport;

    public OutboxRelay(JdbcTemplate jdbcTemplate,
                       OutboxRepository outboxRepository,
                       EventTransport transport) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRepository = outboxRepository;
        this.transport = transport;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-delay-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "5m")
    public void relay() {
        for (String schema : activeTenantSchemas()) {
            try {
                TenantContext.set(schema);
                int sent = relayTenant();
                if (sent > 0) {
                    log.debug("Арендатор {}: отправлено {} событий", schema, sent);
                }
            } catch (Exception e) {
                // Падение одного арендатора не должно останавливать остальных.
                log.error("Арендатор {}: ошибка релея outbox", schema, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    @Transactional
    protected int relayTenant() {
        List<OutboxRecord> batch = outboxRepository.lockUnpublished(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        transport.send(batch);
        batch.forEach(OutboxRecord::markPublished);
        return batch.size();
    }

    private List<String> activeTenantSchemas() {
        return jdbcTemplate.queryForList(
                "SELECT schema_name FROM public.tenant_registry WHERE status = 'ACTIVE' ORDER BY tenant_id",
                String.class);
    }
}
