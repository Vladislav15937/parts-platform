package ru.partsflow.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.partsflow.platform.tenant.TenantContext;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Транспорт по умолчанию: приложение поднимается и работает без брокера.
 * В тестах даёт возможность проверять контракт публикации без Testcontainers
 * с Kafka, что экономит десятки секунд на каждом прогоне.
 *
 * <p><b>Здесь же и доставка потребителям.</b> Без брокера отправлять некуда,
 * поэтому события уходят прямо в {@link EventDispatcher}. Профиль {@code kafka}
 * делает то же самое, но из слушателя топика — потребители у обоих профилей
 * одни и те же и про транспорт не знают.
 *
 * <p><b>Раздача идёт после коммита релея, а не внутри него.</b> Обработчик
 * ходит в сеть — дельта на Дром это HTTP-запрос к площадке, — и делать это
 * внутри открытой транзакции значит держать соединение с базой всё время
 * ответа площадки. При двух сотнях арендаторов в ячейке пул кончится раньше,
 * чем придёт первый ответ.
 *
 * <p>Арендатор запоминается на момент отправки, а не читается в колбэке:
 * к моменту раздачи релей уже перейдёт к следующему.
 */
@Component
@Profile("!kafka")
public class InMemoryEventTransport implements EventTransport {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventTransport.class);

    private final EventDispatcher dispatcher;
    private final Queue<OutboxRecord> delivered = new ConcurrentLinkedQueue<>();

    public InMemoryEventTransport(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void send(List<OutboxRecord> batch) {
        delivered.addAll(batch);
        log.debug("In-memory транспорт: доставлено {} событий", batch.size());

        List<ConsumedEvent> events = batch.stream().map(ConsumedEvent::of).toList();
        String tenant = TenantContext.require();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(tenant, events);
                }
            });
        } else {
            // Вне транзакции — только в тестах, дёргающих транспорт напрямую.
            deliver(tenant, events);
        }
    }

    private void deliver(String tenant, List<ConsumedEvent> events) {
        TenantContext.set(tenant);
        try {
            events.forEach(dispatcher::dispatch);
        } finally {
            TenantContext.clear();
        }
    }

    /** Для тестов. */
    public List<OutboxRecord> delivered() {
        return List.copyOf(delivered);
    }

    public void clear() {
        delivered.clear();
    }
}
