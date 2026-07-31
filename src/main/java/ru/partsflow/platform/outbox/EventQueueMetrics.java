package ru.partsflow.platform.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Метрики событийного контура: что застряло и насколько.
 *
 * <p>До этого о застрявшей очереди узнавали от клиента: «объявление висит,
 * а деталь продана». Три числа отвечают на это раньше него — глубина outbox,
 * возраст самого старого неопубликованного события и число записей, ждущих
 * человека в разборе.
 *
 * <p><b>Считается по всей ячейке, а не по арендатору.</b> Метрика на клиента
 * при пятистах арендаторах — это пятьсот временных рядов на каждое число,
 * то есть хранилище метрик размером с базу. Кому именно плохо, показывает
 * экран разбора; метрика отвечает на другой вопрос — «плохо ли вообще».
 *
 * <p>Значения снимаются по расписанию, а не в момент опроса Prometheus:
 * обход схем всех арендаторов на каждый запрос метрик превратил бы
 * наблюдение в нагрузку.
 */
@Component
public class EventQueueMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(EventQueueMetrics.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();
    private final AtomicLong unresolved = new AtomicLong();
    private final AtomicLong needAttention = new AtomicLong();

    public EventQueueMetrics(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge("partsflow.outbox.pending", pending, AtomicLong::doubleValue);
        registry.gauge("partsflow.outbox.oldest.seconds", oldestSeconds, AtomicLong::doubleValue);
        registry.gauge("partsflow.deadletter.unresolved", unresolved, AtomicLong::doubleValue);
        // Отдельно от общего числа: пока робот повторяет, делать нечего,
        // а вот запись, которую он бросил, ждёт человека.
        registry.gauge("partsflow.deadletter.attention", needAttention, AtomicLong::doubleValue);
    }

    /**
     * Пересчитывает по всем арендаторам ячейки.
     *
     * <p>Расписание вынесено в {@link EventQueueMetricsScheduler} — как у релея
     * и по той же причине: этот метод зовут тесты напрямую.
     */
    public void refresh() {
        long pendingTotal = 0;
        long oldest = 0;
        long unresolvedTotal = 0;
        long attentionTotal = 0;

        for (String schema : activeTenantSchemas()) {
            try {
                TenantContext.set(schema);
                Counts counts = countsOf();
                if (counts != null) {
                    pendingTotal += counts.pending();
                    oldest = Math.max(oldest, counts.oldestSeconds());
                    unresolvedTotal += counts.unresolved();
                    attentionTotal += counts.attention();
                }
            } catch (Exception e) {
                // Метрики не повод ронять что-либо: непосчитанный арендатор
                // хуже посчитанного, но лучше остановленного сбора.
                log.warn("Арендатор {}: метрики очереди не собрались", schema, e);
            } finally {
                TenantContext.clear();
            }
        }

        pending.set(pendingTotal);
        oldestSeconds.set(oldest);
        unresolved.set(unresolvedTotal);
        needAttention.set(attentionTotal);
    }

    /**
     * Все четыре числа одним запросом.
     *
     * <p><b>Именно одним, а не четырьмя.</b> В ячейке до двухсот арендаторов,
     * и обход по четыре запроса на каждого раз в полминуты — это уже заметная
     * доля нагрузки базы, созданная наблюдением за ней. Обход при этом
     * последовательный: одновременные транзакции по всем арендаторам заняли бы
     * пул соединений целиком и остановили бы работу ради метрик.
     *
     * <p>В транзакции: {@code search_path} выставляет провайдер соединений
     * Hibernate, и снаружи запрос уходит в {@code public} — «relation outbox
     * does not exist». Одного {@link TenantContext} для этого мало.
     *
     * <p>Возраст самого старого события считается здесь же: число «в очереди
     * сто событий» не говорит ничего — сто событий, появившихся секунду назад,
     * это обычная работа, а те же сто возрастом в час — остановившийся релей.
     */
    private Counts countsOf() {
        return transactions.execute(status -> jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM outbox WHERE published_at IS NULL),
                       (SELECT min(created_at) FROM outbox WHERE published_at IS NULL),
                       (SELECT count(*) FROM event_dead_letter WHERE resolved_at IS NULL),
                       (SELECT count(*) FROM event_dead_letter
                         WHERE resolved_at IS NULL AND attempts >= ?)""",
                (rs, i) -> {
                    OffsetDateTime oldest = rs.getObject(2, OffsetDateTime.class);
                    return new Counts(
                            rs.getLong(1),
                            oldest == null ? 0
                                    : Duration.between(oldest.toInstant(), Instant.now())
                                            .toSeconds(),
                            rs.getLong(3),
                            rs.getLong(4));
                },
                DeadLetterService.AUTO_ATTEMPTS));
    }

    private record Counts(long pending, long oldestSeconds, long unresolved, long attention) {
    }

    private List<String> activeTenantSchemas() {
        return jdbc.queryForList(
                "SELECT schema_name FROM public.tenant_registry WHERE status = 'ACTIVE' "
                        + "ORDER BY tenant_id", String.class);
    }
}
