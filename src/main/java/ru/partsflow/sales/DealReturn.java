package ru.partsflow.sales;

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
 * Возврат — документ со своей нумерацией, а не строка в сделке.
 *
 * <p>У клиента их много: на реальной разборке 3 464 возврата против 84 активных
 * сделок. Возвращают несколько позиций разом, документ печатают, а склад
 * возврата не обязан совпадать со складом выдачи — деталь принимают там, куда
 * клиенту удобно приехать.
 *
 * <p><b>Возврат — не отмена.</b> Отмена возможна, пока товар не ушёл; после
 * выдачи деталь физически у клиента, деньги в кассе, и оба факта нужно
 * отразить документом, а не переписыванием сделки.
 */
@Entity
@Table(name = "deal_return")
public class DealReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Номер для человека; генерируется последовательностью в БД и вычитывается после вставки. */
    @Generated(event = EventType.INSERT)
    @Column(insertable = false, updatable = false)
    private Long number;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    @Column(name = "customer_id")
    private Long customerId;

    /** Склад, на который принимают деталь. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReturnStatus status = ReturnStatus.DRAFT;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    private String reason;

    /**
     * Колонки {@code part_id}, {@code quantity} и {@code restocked} остались
     * в таблице от прежней модели, где возврат был одной строкой. Позиции
     * теперь живут в {@code deal_return_item}, а поле {@code restocked}
     * документа заполняем для совместимости со старыми записями.
     */
    @Column(nullable = false)
    private boolean restocked = true;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * Как и у сделки: момент ставит база, и вычитывать его обязан Hibernate.
     * Ответ на оформление возврата иначе уходит с {@code createdAt: null}.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** {@code nullable = false} — иначе Hibernate вставит позицию без ссылки на документ. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "return_id", nullable = false)
    private List<DealReturnItem> items = new ArrayList<>();

    protected DealReturn() {
    }

    public DealReturn(Long dealId, Long customerId, Long warehouseId) {
        this.dealId = dealId;
        this.customerId = customerId;
        this.warehouseId = warehouseId;
    }

    // ---------- поведение ----------

    public DealReturnItem addItem(Long partId, BigDecimal quantity, BigDecimal amount,
                                  boolean restocked) {
        requireDraft("добавить позицию");

        DealReturnItem item = new DealReturnItem(partId, quantity, amount, restocked);
        items.add(item);
        recalculate();
        return item;
    }

    /**
     * Завершает возврат: с этого момента деталь на складе, а деньги — у клиента.
     *
     * <p>Движение склада и деньги оформляет {@link SalesService}: здесь только
     * состояние документа.
     */
    public void complete(Instant when) {
        requireDraft("завершить");
        if (items.isEmpty()) {
            throw new IllegalStateException("Нечего возвращать: в возврате нет позиций");
        }
        this.status = ReturnStatus.DONE;
        this.completedAt = when;
    }

    public void cancel() {
        if (status == ReturnStatus.DONE) {
            throw new IllegalStateException(
                    "Завершённый возврат не отменяют: деталь уже принята на склад, "
                            + "а деньги отданы клиенту");
        }
        this.status = ReturnStatus.CANCELLED;
    }

    /** Сумма — агрегат позиций: хранить её отдельно значит однажды разойтись. */
    public void recalculate() {
        this.amount = items.stream()
                .map(DealReturnItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Признак документа теряет смысл при смешанном возврате, поэтому
        // держим его истинным только когда на склад вернулось всё.
        this.restocked = items.stream().allMatch(DealReturnItem::isRestocked);
    }

    /** Позиции, которые физически вернулись на склад. */
    public List<DealReturnItem> restockedItems() {
        return items.stream().filter(DealReturnItem::isRestocked).toList();
    }

    private void requireDraft(String action) {
        if (status != ReturnStatus.DRAFT) {
            throw new IllegalStateException(
                    "Нельзя %s: возврат в состоянии %s".formatted(action, status));
        }
    }

    // ---------- доступ ----------

    public Long getId() {
        return id;
    }

    public Long getNumber() {
        return number;
    }

    public Long getDealId() {
        return dealId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isRestocked() {
        return restocked;
    }

    public List<DealReturnItem> getItems() {
        return List.copyOf(items);
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
}
