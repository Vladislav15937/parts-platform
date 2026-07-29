package ru.partsflow.platform.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Единственный разрешённый способ публикации доменных событий.
 *
 * <p>Событие записывается в таблицу {@code outbox} <b>в той же транзакции</b>,
 * что и изменение данных. Прямая отправка в Kafka из бизнес-кода запрещена:
 * транзакция БД и публикация в брокер не атомарны, и при падении между ними
 * получится либо потерянное событие (объявление не обновилось на площадке),
 * либо событие о том, чего не произошло (транзакция откатилась).
 *
 * <p>{@code MANDATORY} — намеренно: вызов вне транзакции означает, что кто-то
 * пытается опубликовать событие без изменения данных, и это ошибка.
 */
@Component
public class DomainEventPublisher {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        entityManager.persist(new OutboxRecord(event));
    }
}
