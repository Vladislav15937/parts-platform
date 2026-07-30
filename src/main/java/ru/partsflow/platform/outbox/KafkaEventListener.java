package ru.partsflow.platform.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.TenantContext;

import java.nio.charset.StandardCharsets;

/**
 * Приём событий из Kafka. Профиль {@code kafka}.
 *
 * <p>Подписка по шаблону на все топики ячейки: топик заводится на каждый тип
 * события, и перечислять их именами значит править конфигурацию при каждом
 * новом типе — а обработчик уже написан и подписан.
 *
 * <p>Разбор события целиком в заголовках, тело не трогаем: диспетчер отдаёт
 * его обработчику как есть. Так слушатель не зависит от формата полезной
 * нагрузки и переживёт переход на Protobuf.
 *
 * <p>Смещение подтверждается штатно после возврата из метода. Отказ обработчика
 * до сюда не доходит — его ловит диспетчер и кладёт событие в
 * {@code event_dead_letter}. Иначе один непринятый обработчиком документ
 * остановил бы всю партицию: потребитель встал бы на нём и перечитывал
 * бесконечно, а за ним стоят события всех остальных арендаторов ячейки.
 */
@Component
@Profile("kafka")
public class KafkaEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventListener.class);

    private final EventDispatcher dispatcher;

    public KafkaEventListener(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topicPattern = "${app.cell:cell01}\\..*", groupId = "${app.consumer-group:partsflow}")
    public void onEvent(ConsumerRecord<String, byte[]> record) {
        String tenant = header(record, "tenant");
        String eventType = header(record, "event_type");
        String eventId = header(record, "event_id");

        if (tenant == null || eventType == null || eventId == null) {
            // Событие без заголовков разобрать нечем: непонятно ни чьё оно,
            // ни что это. Молча пропустить хуже, чем громко пожаловаться.
            log.error("Событие из {} без обязательных заголовков, пропущено", record.topic());
            return;
        }

        ConsumedEvent event = new ConsumedEvent(
                Long.parseLong(eventId),
                header(record, "aggregate_type"),
                Long.parseLong(header(record, "aggregate_id")),
                eventType,
                record.value());

        TenantContext.set(tenant);
        try {
            dispatcher.dispatch(event);
        } finally {
            TenantContext.clear();
        }
    }

    private String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
