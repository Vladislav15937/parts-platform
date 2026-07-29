package ru.partsflow.platform.outbox;

/**
 * Доменное событие.
 *
 * <p>{@code partitionKey} определяет партицию Kafka и тем самым порядок обработки.
 * Ключ должен быть таким, чтобы события одной сущности не переставились местами:
 * иначе на площадку уедет устаревшая цена после актуальной. Обычно это
 * {@code tenant:aggregateId}.
 *
 * <p>{@code eventType} версионирован в имени ({@code part.price_changed.v1}).
 * Несовместимое изменение — новый тип {@code .v2}, а не правка старого.
 */
public record DomainEvent(
        String aggregateType,
        long aggregateId,
        String eventType,
        String partitionKey,
        byte[] payload
) {

    public static DomainEvent of(String aggregateType, long aggregateId, String eventType, byte[] payload) {
        String key = "%s:%s:%d".formatted(
                ru.partsflow.platform.tenant.TenantContext.require(), aggregateType, aggregateId);
        return new DomainEvent(aggregateType, aggregateId, eventType, key, payload);
    }
}
