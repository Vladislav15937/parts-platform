package ru.partsflow.platform.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "outbox")
public class OutboxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private long aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(nullable = false)
    private byte[] payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxRecord() {
    }

    public OutboxRecord(DomainEvent event) {
        this.aggregateType = event.aggregateType();
        this.aggregateId = event.aggregateId();
        this.eventType = event.eventType();
        this.partitionKey = event.partitionKey();
        this.payload = event.payload();
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public byte[] getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
