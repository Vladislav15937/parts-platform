package ru.partsflow.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;

import java.time.Duration;
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
 * <p><b>Отправка идёт вне транзакции БД, и это главное свойство этого класса.</b>
 * Пока {@code transport.send} стоял между открытием транзакции и коммитом,
 * ответа брокера ждала транзакция, а вместе с ней — соединение из пула.
 * Замерено на живом прогоне: при остановленной Kafka прежний код держал
 * соединение в состоянии {@code idle in transaction} и на пятидесятой секунде
 * всё ещё держал, нынешний — ни одного за всё время наблюдения. При двух
 * сотнях арендаторов в ячейке это разница между «пул занят ожиданием
 * брокера» и «пул свободен для продавцов и приёмщиков».
 *
 * <p><b>Чего это не чинит:</b> обход остаётся однопоточным, и отправка
 * при лежащем брокере всё так же блокирует поток — до {@code max.block.ms}
 * на арендатора (замерено около минуты). То есть событийный контур во время
 * аварии брокера по-прежнему стоит; не стоит только база. Лечится это
 * параллельным обходом или коротким таймаутом отправки, и делать это надо
 * отдельно и осознанно.
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
    private final TransactionTemplate transactions;
    private final Duration claimTtl;

    public OutboxRelay(JdbcTemplate jdbcTemplate,
                       OutboxRepository outboxRepository,
                       EventTransport transport,
                       TransactionTemplate transactions,
                       @Value("${app.outbox.claim-ttl:5m}") Duration claimTtl) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRepository = outboxRepository;
        this.transport = transport;
        this.transactions = transactions;
        this.claimTtl = claimTtl;
    }

    /**
     * Один проход по всем арендаторам ячейки.
     *
     * <p>Расписание вынесено в {@link OutboxRelayScheduler} намеренно. Здесь
     * его быть не должно: метод вызывают напрямую тесты, и с {@code @Scheduled}
     * плюс {@code @SchedulerLock} на нём вызов проходил через блокировку
     * ShedLock — то есть мог быть молча пропущен.
     */
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

    /**
     * Один заход по арендатору: заявка, отправка, пометка.
     *
     * <p><b>Транзакции держатся явно, не {@code @Transactional}.</b> Метод
     * вызывается из {@link #relay()} того же бина, а через self-invocation
     * прокси Spring не проходит: аннотация молча не сработает. Транзакции
     * при этом не будет вовсе — а без неё {@code FOR UPDATE SKIP LOCKED}
     * ничего не блокирует и, главное, пометка об отправке не сбрасывается
     * в базу. Событие останется неопубликованным и уедет в транспорт снова
     * через секунду, и так вечно. {@code OutboxRelayTest.publishedEventIsMarked}
     * это стережёт.
     *
     * <p>Транзакций именно три, и порядок в них единственно возможный.
     * Пометить до отправки — значит потерять событие при отказе брокера;
     * отправить внутри той же транзакции, в которой пачку заявили, — вернуться
     * к соединению, занятому на всё время ответа брокера. Отправка между ними
     * означает возможный дубль при падении процесса, и это осознанный выбор
     * из трёх: доставка и так at-least-once, а от повтора потребитель защищён
     * вставкой в {@code processed_event}.
     */
    private int relayTenant() {
        List<OutboxRecord> batch = transactions.execute(status ->
                outboxRepository.claimUnpublished(BATCH_SIZE, claimTtl));

        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        List<Long> ids = batch.stream().map(OutboxRecord::getId).toList();

        try {
            // Вне транзакции: ответа брокера ждёт только этот поток,
            // соединение с базой в это время отпущено.
            transport.send(batch);
        } catch (RuntimeException e) {
            // Снимаем заявку сразу, а не ждём её истечения: отказ бывает
            // мгновенным, и держать события минуты из-за ошибки, которая
            // повторится через секунду, незачем.
            transactions.executeWithoutResult(status -> outboxRepository.releaseClaim(ids));
            throw e;
        }

        transactions.executeWithoutResult(status -> outboxRepository.markPublished(ids));
        return batch.size();
    }

    private List<String> activeTenantSchemas() {
        return jdbcTemplate.queryForList(
                "SELECT schema_name FROM public.tenant_registry WHERE status = 'ACTIVE' ORDER BY tenant_id",
                String.class);
    }
}
