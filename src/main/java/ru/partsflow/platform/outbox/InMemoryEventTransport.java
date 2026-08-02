package ru.partsflow.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
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
 * <p><b>Раздача идёт вне транзакции.</b> Обработчик ходит в сеть — дельта
 * на Дром это HTTP-запрос к площадке, — и делать это внутри открытой
 * транзакции значит держать соединение с базой всё время ответа площадки.
 * При двух сотнях арендаторов в ячейке пул кончится раньше, чем придёт
 * первый ответ. Раньше это обеспечивалось подпиской на {@code afterCommit}:
 * релей звал транспорт из своей транзакции. Теперь он зовёт его снаружи,
 * и свойство держится самим порядком вызовов, а не колбэком.
 *
 * <p><b>Арендатор восстанавливается, а не стирается.</b> Релей после отправки
 * помечает пачку опубликованной — уже своей транзакцией, но в том же потоке
 * и с тем же арендатором. Очищенный здесь {@code TenantContext} отправил бы
 * эту пометку в {@code public}, то есть в «relation outbox does not exist»,
 * и событие уходило бы в транспорт снова и снова.
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
        deliver(TenantContext.require(), events);
    }

    private void deliver(String tenant, List<ConsumedEvent> events) {
        String previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            events.forEach(dispatcher::dispatch);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
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
