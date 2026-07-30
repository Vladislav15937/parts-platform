package ru.partsflow.publishing.drom;

import org.springframework.stereotype.Component;
import ru.partsflow.platform.outbox.ConsumedEvent;
import ru.partsflow.platform.outbox.EventHandler;

import java.util.Set;

/**
 * Замыкает продажу на выгрузку: выдали деталь — обновили её на Дроме.
 *
 * <p>Живёт в {@code publishing}, а не в {@code sales}, намеренно. Продажи
 * не должны знать, что где-то есть площадки: подключат Авито — появится второй
 * такой обработчик, отключат Дром — исчезнет этот, и код сделки не шелохнётся.
 *
 * <p>Ловятся и выдача, и возврат: возвращённая деталь снова доступна, и её
 * состояние на площадке тоже надо поправить. Отмена сделки движений не даёт —
 * товар не уезжал, объявление не менялось, — но резерв она снимает, а на Дром
 * уходит доступное количество, поэтому событие тоже наше.
 */
@Component
public class DealIssuedHandler implements EventHandler {

    private final DromDeltaSender sender;

    public DealIssuedHandler(DromDeltaSender sender) {
        this.sender = sender;
    }

    @Override
    public String name() {
        return "drom-deal-delta";
    }

    @Override
    public Set<String> handles() {
        return Set.of("deal.issued.v1", "deal.returned.v1", "deal.cancelled.v1");
    }

    @Override
    public void handle(ConsumedEvent event) {
        boolean sent = sender.onDealIssued(event.aggregateId());
        if (!sent) {
            // Отправитель исключений не бросает намеренно: сорванная выгрузка
            // не повод откатывать выдачу товара. Но для очереди событий это
            // именно отказ — иначе непрошедшая дельта потеряется молча,
            // и площадка неделю будет показывать проданную деталь.
            throw new IllegalStateException(
                    "Дром не принял дельту по сделке " + event.aggregateId()
                            + ", подробности в publication_log");
        }
    }
}
