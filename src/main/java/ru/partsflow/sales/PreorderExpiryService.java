package ru.partsflow.sales;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.platform.tenant.TenantShare;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Проход по арендаторам: снять предзаказы, у которых вышел срок (задача 0260).
 *
 * <p>Само снятие — {@link SalesService#expirePreorder}, со своей транзакцией и
 * блокировкой строки сделки. Здесь только обход: список кандидатов читается в
 * {@link TransactionTemplate} (без транзакции {@code JdbcTemplate} ушёл бы в
 * {@code public}), а каждая сделка снимается отдельной транзакцией — отказ одной
 * (например, её как раз правит продавец и версия разошлась) не откатывает
 * остальные и не останавливает соседних арендаторов.
 *
 * <p>Расписание вынесено в {@link PreorderExpiryScheduler} по той же причине,
 * что у релеев: тесты зовут проход напрямую, а {@code @SchedulerLock} на нём
 * молча пропускал бы вызов.
 */
@Service
public class PreorderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(PreorderExpiryService.class);

    /** Сделок на арендатора за проход: остальные дождутся следующего. */
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TenantShare tenants;
    private final SalesService sales;

    public PreorderExpiryService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                 TenantShare tenants, SalesService sales) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tenants = tenants;
        this.sales = sales;
    }

    /** Один проход по своей доле арендаторов ячейки. */
    public void sweep() {
        for (String schema : tenants.schemas()) {
            try {
                TenantContext.set(schema);
                int removed = sweepCurrentTenant(Instant.now());
                if (removed > 0) {
                    log.info("Арендатор {}: снято просроченных предзаказов — {}", schema, removed);
                }
            } catch (Exception e) {
                // Падение одного арендатора не должно останавливать остальных.
                log.error("Арендатор {}: ошибка снятия просроченных предзаказов", schema, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * Проход по арендатору, уже выставленному в {@link TenantContext}. Открыт
     * ради тестов: в общем реестре тестовых схем десятки, и проход по всем
     * ломал бы утверждения про одну.
     *
     * @return сколько сделок затронуто
     */
    public int sweepCurrentTenant(Instant now) {
        List<Long> candidates = transactions.execute(status -> jdbc.queryForList("""
                SELECT d.id FROM deal d
                 WHERE d.status = 'RESERVED' AND d.reserved_until < ?
                   AND EXISTS (SELECT 1 FROM deal_item i
                                WHERE i.deal_id = d.id
                                  AND (i.status = 'PREORDER'
                                       OR (i.status = 'RESERVED' AND i.from_preorder)))
                 ORDER BY d.reserved_until
                 LIMIT ?""", Long.class, Timestamp.from(now), BATCH));
        int removed = 0;
        for (Long dealId : candidates == null ? List.<Long>of() : candidates) {
            try {
                if (sales.expirePreorder(dealId, now)) {
                    removed++;
                }
            } catch (Exception e) {
                log.warn("Не удалось снять просроченный предзаказ сделки {}: {}",
                        dealId, e.toString());
            }
        }
        return removed;
    }
}
