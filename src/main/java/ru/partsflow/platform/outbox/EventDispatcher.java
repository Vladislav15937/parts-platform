package ru.partsflow.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Раздаёт события обработчикам.
 *
 * <p>Стоит между транспортом и прикладным кодом, чтобы каждый потребитель
 * не переписывал одно и то же: проверку повтора, изоляцию отказа, запись
 * непринятого.
 *
 * <p><b>Дедупликация вставкой, а не чтением.</b> Между «посмотреть, не
 * обрабатывали ли» и самой обработкой встанет второй экземпляр потребителя,
 * и оба решат, что события не было. Поэтому первым делом идёт
 * {@code INSERT ... ON CONFLICT DO NOTHING} в {@code processed_event}: кто
 * вставил строку, тот и обрабатывает, остальные уходят. Первичный ключ так
 * не обмануть.
 *
 * <p><b>Отметка ставится до обработки, а не после.</b> Это выбор в пользу
 * «не сделать дважды» против «не потерять»: при падении между отметкой
 * и обработкой событие потеряется. Потерянное видно в
 * {@code event_dead_letter} — точнее, там видно всё, что упало с исключением,
 * а падение самого процесса не видно нигде. Обратный порядок дал бы обратную
 * беду: повторную обработку при падении между обработкой и отметкой, то есть
 * второе списание остатка. Отменить дубль дороже, чем повторить потерянное.
 *
 * <p><b>Отказ одного обработчика не трогает остальных</b> и не откатывает
 * публикацию: релей своё дело сделал, и заставлять его переотправлять всё
 * из-за одного упавшего потребителя значит наказать всех остальных.
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final Map<String, List<EventHandler>> byType;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public EventDispatcher(List<EventHandler> handlers,
                           JdbcTemplate jdbc,
                           TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.byType = handlers.stream()
                .flatMap(handler -> handler.handles().stream()
                        .map(type -> Map.entry(type, handler)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        if (!handlers.isEmpty()) {
            log.info("Потребители событий: {}", handlers.stream()
                    .map(h -> h.name() + h.handles()).toList());
        }
    }

    /**
     * Раздаёт событие всем подписанным на его тип.
     *
     * <p>Вызывается уже с установленным арендатором: у события нет способа
     * сообщить схему иначе, а обработчик работает с данными арендатора.
     */
    public void dispatch(ConsumedEvent event) {
        for (EventHandler handler : byType.getOrDefault(event.eventType(), List.of())) {
            deliver(handler, event);
        }
    }

    private void deliver(EventHandler handler, ConsumedEvent event) {
        if (!claim(handler, event)) {
            log.debug("Событие {} уже обработано обработчиком {}", event.eventId(), handler.name());
            return;
        }
        try {
            handler.handle(event);
        } catch (Exception e) {
            log.error("Обработчик {} не принял событие {} ({})",
                    handler.name(), event.eventId(), event.eventType(), e);
            release(handler, event, e);
        }
    }

    /**
     * Занимает событие за обработчиком.
     *
     * @return {@code false}, если его уже обрабатывали
     */
    private boolean claim(EventHandler handler, ConsumedEvent event) {
        Integer inserted = transactions.execute(status -> jdbc.update("""
                INSERT INTO processed_event (handler, event_id, event_type)
                VALUES (?, ?, ?)
                ON CONFLICT (handler, event_id) DO NOTHING""",
                handler.name(), event.eventId(), event.eventType()));
        return inserted != null && inserted > 0;
    }

    /**
     * Возвращает событие после отказа: снимает отметку и кладёт в разбор.
     *
     * <p>Отметку снимаем, иначе повторная доставка тем же транспортом уйдёт
     * в «уже обработано» и событие не получит второго шанса вовсе.
     */
    private void release(EventHandler handler, ConsumedEvent event, Exception cause) {
        String message = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();

        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM processed_event WHERE handler = ? AND event_id = ?",
                    handler.name(), event.eventId());
            jdbc.update("""
                    INSERT INTO event_dead_letter
                        (handler, event_id, event_type, aggregate_id, payload, error)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (handler, event_id) DO UPDATE
                       SET attempts = event_dead_letter.attempts + 1,
                           error = excluded.error,
                           resolved_at = NULL""",
                    handler.name(), event.eventId(), event.eventType(),
                    event.aggregateId(), event.payload(), message);
        });
    }
}
