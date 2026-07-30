package ru.partsflow.platform.outbox;

/**
 * Событие, доставленное потребителю.
 *
 * <p>Отдельный тип, а не {@link OutboxRecord}: запись outbox — это строка
 * в таблице издателя, и потребителю она недоступна вовсе, когда между ними
 * стоит Kafka. Общее у них только то, что реально едет по проводу.
 *
 * @param eventId идентификатор для дедупликации. При Kafka приезжает
 *                заголовком {@code event_id}
 */
public record ConsumedEvent(long eventId,
                            String aggregateType,
                            long aggregateId,
                            String eventType,
                            byte[] payload) {

    static ConsumedEvent of(OutboxRecord record) {
        return new ConsumedEvent(record.getId(), record.getAggregateType(),
                record.getAggregateId(), record.getEventType(), record.getPayload());
    }
}
