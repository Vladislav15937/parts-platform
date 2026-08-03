package ru.partsflow.inventory;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Складской документ над журналом движений.
 *
 * <p><b>Зачем документ, если есть журнал.</b> Кладовщик работает документами:
 * «Поступление 7163 от 01.07, склад Ткацкая, выполнен, 12 позиций». Он его
 * собирает, проверяет и печатает. Плоская строка журнала на этот вопрос
 * не отвечает — по ней не видно ни кто собирал, ни что вошло в одну операцию.
 *
 * <p><b>Движения появляются только при проведении.</b> Черновик остаток
 * не двигает: собранный, но не проведённый документ — это лист, с которым
 * ходят по складу. Проведение необратимо, потому что необратим журнал:
 * ошибку исправляют встречным документом, а не отменой проведённого.
 */
@Entity
@Table(name = "stock_document")
public class StockDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Номер для человека; генерируется последовательностью в БД. */
    @Generated(event = EventType.INSERT)
    @Column(insertable = false, updatable = false)
    private Long number;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    private DocumentType docType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.DRAFT;

    /** Склад операции. Для перемещения — откуда. */
    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    /** Только для перемещения: куда. БД это проверяет. */
    @Column(name = "to_warehouse_id")
    private Long toWarehouseId;

    @Column(name = "supply_id")
    private Long supplyId;

    /**
     * Ключ запроса от клиента: по нему повтор офлайн-очереди узнаётся как повтор,
     * а не создаёт вторую партию. Уникален в БД.
     */
    @Column(name = "client_request_id")
    private String clientRequestId;

    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** {@code nullable = false} — иначе Hibernate вставит строку без ссылки на документ. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "document_id", nullable = false)
    private List<StockDocumentLine> lines = new ArrayList<>();

    protected StockDocument() {
    }

    public static StockDocument intake(Long warehouseId, Long supplyId) {
        StockDocument document = new StockDocument();
        document.docType = DocumentType.INTAKE;
        document.warehouseId = requireWarehouse(warehouseId);
        document.supplyId = supplyId;
        return document;
    }

    public static StockDocument writeOff(Long warehouseId) {
        StockDocument document = new StockDocument();
        document.docType = DocumentType.WRITE_OFF;
        document.warehouseId = requireWarehouse(warehouseId);
        return document;
    }

    public static StockDocument move(Long fromWarehouseId, Long toWarehouseId) {
        if (toWarehouseId == null || toWarehouseId.equals(fromWarehouseId)) {
            throw new IllegalArgumentException("Перемещение требует другого склада-приёмника");
        }
        StockDocument document = new StockDocument();
        document.docType = DocumentType.MOVE;
        document.warehouseId = requireWarehouse(fromWarehouseId);
        document.toWarehouseId = toWarehouseId;
        return document;
    }

    // ---------- поведение ----------

    public StockDocumentLine addLine(Long partId, BigDecimal qty, Long cellId) {
        requireDraft("добавить позицию");

        StockDocumentLine line = new StockDocumentLine(partId, qty, cellId);
        lines.add(line);
        return line;
    }

    /**
     * Проводит документ: с этого момента остаток изменён.
     *
     * <p>Сами движения пишет {@code StockDocumentService} — сущность не знает
     * про журнал. Здесь только состояние документа, и оно меняется в той же
     * транзакции, что и движения.
     */
    public void complete(Instant when) {
        requireDraft("провести");
        if (lines.isEmpty()) {
            throw new IllegalStateException("Пустой документ проводить нечего");
        }
        this.status = DocumentStatus.DONE;
        this.completedAt = when;
    }

    /**
     * Отменяет черновик.
     *
     * <p>Проведённый документ не отменяют: журнал движений неизменяем, и
     * «отмена» означала бы тихое исправление истории. Ошибку исправляют
     * встречным документом — так на складе видно, что произошло.
     */
    public void cancel() {
        if (status == DocumentStatus.DONE) {
            throw new IllegalStateException(
                    "Проведённый документ не отменяют: журнал движений неизменяем. "
                            + "Оформи встречный документ");
        }
        this.status = DocumentStatus.CANCELLED;
    }

    public boolean isDraft() {
        return status == DocumentStatus.DRAFT;
    }

    private void requireDraft(String action) {
        if (status != DocumentStatus.DRAFT) {
            throw new IllegalStateException(
                    "Нельзя %s: документ в состоянии %s".formatted(action, status));
        }
    }

    private static Long requireWarehouse(Long warehouseId) {
        if (warehouseId == null) {
            throw new IllegalArgumentException("Складской документ без склада бессмыслен");
        }
        return warehouseId;
    }

    // ---------- доступ ----------

    public Long getId() {
        return id;
    }

    public Long getNumber() {
        return number;
    }

    public DocumentType getDocType() {
        return docType;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public Long getToWarehouseId() {
        return toWarehouseId;
    }

    public Long getSupplyId() {
        return supplyId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<StockDocumentLine> getLines() {
        return List.copyOf(lines);
    }

    public enum DocumentType {
        INTAKE,
        MOVE,
        WRITE_OFF,
        RETURN,
        INVENTORY
    }

    public enum DocumentStatus {
        DRAFT,
        DONE,
        CANCELLED
    }

    /**
     * Момент последней правки. До 4 августа 2026 его ставил триггер
     * {@code touch_updated_at}; теперь — приложение, как и всё остальное.
     *
     * <p>{@code @PreUpdate}, а не присваивание в каждом сеттере: полей
     * у сущности десятки, и забыть один из них — вопрос времени.
     */
    @Column(name = "updated_at", insertable = false)
    private java.time.Instant updatedAt;

    @jakarta.persistence.PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = java.time.Instant.now();
    }

    public java.time.Instant getUpdatedAt() {
        return updatedAt;
    }

}
