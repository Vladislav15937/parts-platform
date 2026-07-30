package ru.partsflow.platform.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Реальный транспорт. Включается профилем {@code kafka}.
 *
 * <p>Настройки надёжности задаются в {@code application.yml} и не декоративны:
 * пара {@code acks=all} + {@code min.insync.replicas=2} — то, что реально
 * защищает от потери при падении брокера; по отдельности каждая бесполезна.
 * {@code enable.idempotence=true} убирает дубликаты от внутренних повторов
 * продюсера.
 *
 * <p>Отправка синхронная (ждём подтверждения всей пачки): релей пометит записи
 * опубликованными только после успеха, поэтому терять их нельзя.
 */
@Component
@Profile("kafka")
public class KafkaEventTransport implements EventTransport {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String cell;

    public KafkaEventTransport(KafkaTemplate<String, byte[]> kafkaTemplate,
                               @Value("${app.cell:cell01}") String cell) {
        this.kafkaTemplate = kafkaTemplate;
        this.cell = cell;
    }

    @Override
    public void send(List<OutboxRecord> batch) {
        List<CompletableFuture<?>> futures = batch.stream()
                .map(this::toRecord)
                .<CompletableFuture<?>>map(kafkaTemplate::send)
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private ProducerRecord<String, byte[]> toRecord(OutboxRecord record) {
        ProducerRecord<String, byte[]> producerRecord =
                new ProducerRecord<>(topicFor(record), record.getPartitionKey(), record.getPayload());

        // event_id нужен потребителям для дедупликации: доставка at-least-once.
        producerRecord.headers()
                .add(new RecordHeader("event_id",
                        String.valueOf(record.getId()).getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("event_type",
                        record.getEventType().getBytes(StandardCharsets.UTF_8)))
                // Схема арендатора: потребитель обязан работать с его данными,
                // а из тела события её не достать. Из ключа партиции достать
                // можно, но ключ — про порядок, и завязывать на его формат
                // разбор значит запретить его когда-либо менять.
                .add(new RecordHeader("tenant",
                        TenantContext.require().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("aggregate_type",
                        record.getAggregateType().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("aggregate_id",
                        String.valueOf(record.getAggregateId()).getBytes(StandardCharsets.UTF_8)));
        return producerRecord;
    }

    /** {@code cell01.parts.part-changed.v1} — версия в имени топика. */
    private String topicFor(OutboxRecord record) {
        return "%s.%ss.%s".formatted(cell, record.getAggregateType(),
                record.getEventType().replace('.', '-'));
    }
}
