package ru.partsflow.publishing.drom;

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
 * Доводит изменения склада до площадки: очередь {@code part_change} → дельта.
 *
 * <p><b>Зачем очередь, а не отправка прямо из места правки.</b> Разговор
 * с чужим сервером внутри транзакции продавца означал бы, что нажатие
 * «выдать» ждёт ответа площадки, а соединение из пула ждёт вместе с ним.
 * Очередь разводит эти два времени: правка коммитится сразу, отправка идёт
 * своим ходом и умеет повторяться, не трогая склад.
 *
 * <p>Наполняет очередь {@link ru.partsflow.inventory.PartChangeLog} —
 * из приёмки, склада, продаж и правки карточки. До 3 августа 2026 это делал
 * триггер базы; он был полнее, но противоречил правилу «логика в Java»,
 * и цена его невидимости выше цены забытой отметки: забытая откладывает
 * обновление до полного прайса, а невидимая съедает часы на вопрос «почему
 * оно поменялось само».
 *
 * <p><b>Пачкой, а не по одному изменению.</b> Правка секции у сотни позиций
 * не должна засыпать площадку сотней запросов; отметка лежит на позиции,
 * а не на изменении, поэтому сто правок одной детали за интервал дают одну
 * дельту. Задержка обмена на это и тратится: она измеряется секундами против
 * суток у полного прайса.
 *
 * <p><b>Отправка идёт вне транзакции БД</b> — по той же причине, что
 * и в {@code OutboxRelay}: ответа площадки ждёт только этот поток, соединение
 * из пула в это время отпущено. Пачка при этом помечается заявкой
 * ({@code claimed_at}), а не держится блокировкой строк: блокировка снимается
 * коммитом, то есть до отправки.
 *
 * <p><b>Отметки снимаются только после успеха.</b> Не ушло — позиция остаётся
 * в очереди и уедет следующим заходом. Повтор безопасен: дельта несёт текущее
 * состояние, а не приращение.
 */
@Component
public class FeedDeltaRelay {

    private static final Logger log = LoggerFactory.getLogger(FeedDeltaRelay.class);

    /**
     * Сколько позиций уходит одной дельтой.
     *
     * <p>Ограничение площадки — 5 МБ на запрос, а позиция это несколько сотен
     * байт: пятьсот штук укладываются с запасом на порядок. Больше упирается
     * уже не в размер, а в время ответа: пачка, которую площадка обрабатывает
     * полминуты, задерживает всех остальных арендаторов ячейки — обход
     * однопоточный.
     */
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final DromDeltaSender sender;
    private final TransactionTemplate transactions;
    private final Duration claimTtl;

    public FeedDeltaRelay(JdbcTemplate jdbc,
                          DromDeltaSender sender,
                          TransactionTemplate transactions,
                          @Value("${app.feeds.claim-ttl:5m}") Duration claimTtl) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.transactions = transactions;
        this.claimTtl = claimTtl;
    }

    /**
     * Один проход по всем арендаторам ячейки.
     *
     * <p>Расписание вынесено в {@link FeedDeltaScheduler} намеренно — по той же
     * причине, что и у релея outbox: этот метод зовут тесты напрямую,
     * а {@code @SchedulerLock} на нём молча пропускал бы такой вызов.
     */
    public void relay() {
        for (String schema : activeTenantSchemas()) {
            try {
                TenantContext.set(schema);
                int sent = relayTenant();
                if (sent > 0) {
                    log.debug("Арендатор {}: дельта по {} позициям", schema, sent);
                }
            } catch (Exception e) {
                // Падение одного арендатора не должно останавливать остальных.
                log.error("Арендатор {}: ошибка отправки дельты", schema, e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * Один заход по названному арендатору.
     *
     * <p>Открыт ради тестов: {@link #relay()} идёт по всему реестру, а в тестах
     * схем два десятка и у соседей свой склад — утверждения про размер пачки
     * ломались бы от чужих изменений.
     */
    public int relayFor(String schema) {
        try {
            TenantContext.set(schema);
            return relayTenant();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Заход по арендатору: заявка, отправка, уборка.
     *
     * <p>Транзакции держатся явно, а не аннотацией: метод вызывается из
     * {@link #relay()} того же бина, а self-invocation мимо прокси Spring —
     * {@code @Transactional} там молча не сработает, и заявка не сбросится
     * в базу. Ровно эта ловушка уже стоила релею outbox вечного переотправления.
     */
    private int relayTenant() {
        List<Long> batch = transactions.execute(status -> claim());
        if (batch == null || batch.isEmpty()) {
            return 0;
        }

        boolean sent;
        try {
            // Вне транзакции: ответа площадки ждёт только этот поток.
            sent = sender.sendParts(batch, "feed-%d".formatted(batch.get(0)));
        } catch (RuntimeException e) {
            transactions.executeWithoutResult(status -> release(batch));
            throw e;
        }

        if (!sent) {
            // Снимаем заявку сразу, а не ждём её истечения: отказ площадки
            // бывает мгновенным, и держать позиции минуты незачем.
            transactions.executeWithoutResult(status -> release(batch));
            return 0;
        }

        transactions.executeWithoutResult(status -> clear(batch));
        return batch.size();
    }

    /**
     * Забирает пачку изменившихся позиций.
     *
     * <p>Заявка со сроком, а не блокировка: она обязана пережить коммит,
     * потому что отправка идёт после него. Просроченную забирают заново —
     * это тот же at-least-once, и от повтора площадка защищена тем, что
     * дельта несёт состояние.
     *
     * <p>{@code SKIP LOCKED} — от второго экземпляра приложения, который
     * в этот же момент выбирает свою пачку.
     */
    private List<Long> claim() {
        return jdbc.queryForList("""
                UPDATE part_change
                   SET claimed_at = now()
                 WHERE part_id IN (
                     SELECT part_id FROM part_change
                      WHERE claimed_at IS NULL OR claimed_at < now() - ?::interval
                      ORDER BY marked_at
                      LIMIT ?
                      FOR UPDATE SKIP LOCKED)
                RETURNING part_id""",
                Long.class, claimTtl.toSeconds() + " seconds", BATCH_SIZE);
    }

    /**
     * Убирает отметки отправленных позиций.
     *
     * <p>Только те, что не менялись после заявки: правка, случившаяся, пока
     * дельта была в пути, уехала в площадку прежним состоянием, и стереть её
     * отметку значит оставить сайт неправым до полного забора. Такую правку
     * видно по снятой заявке: {@link ru.partsflow.inventory.PartChangeLog}
     * сбрасывает {@code claimed_at} на каждой отметке.
     */
    private void clear(List<Long> partIds) {
        jdbc.update("DELETE FROM part_change WHERE claimed_at IS NOT NULL AND part_id IN ("
                        + placeholders(partIds) + ")",
                partIds.toArray());
    }

    private void release(List<Long> partIds) {
        jdbc.update("UPDATE part_change SET claimed_at = NULL WHERE part_id IN ("
                        + placeholders(partIds) + ")",
                partIds.toArray());
    }

    /**
     * Список знаков вопроса под {@code IN}.
     *
     * <p>Не {@code = ANY (?)}: массив туда надо создавать через соединение,
     * а размер пачки здесь ограничен пятью сотнями — плана запроса это
     * не портит.
     */
    private static String placeholders(List<Long> ids) {
        return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private List<String> activeTenantSchemas() {
        return jdbc.queryForList(
                "SELECT schema_name FROM public.tenant_registry WHERE status = 'ACTIVE'"
                        + " ORDER BY tenant_id",
                String.class);
    }
}
