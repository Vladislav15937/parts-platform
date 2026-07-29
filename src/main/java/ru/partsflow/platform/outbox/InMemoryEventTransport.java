package ru.partsflow.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Транспорт по умолчанию: приложение поднимается и работает без брокера.
 * В тестах даёт возможность проверять контракт публикации без Testcontainers
 * с Kafka, что экономит десятки секунд на каждом прогоне.
 */
@Component
@Profile("!kafka")
public class InMemoryEventTransport implements EventTransport {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventTransport.class);

    private final Queue<OutboxRecord> delivered = new ConcurrentLinkedQueue<>();

    @Override
    public void send(List<OutboxRecord> batch) {
        delivered.addAll(batch);
        log.debug("In-memory транспорт: доставлено {} событий", batch.size());
    }

    /** Для тестов. */
    public List<OutboxRecord> delivered() {
        return List.copyOf(delivered);
    }

    public void clear() {
        delivered.clear();
    }
}
