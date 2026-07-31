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
 * Сделка.
 *
 * <p>Живёт внутри клиента: у постоянного покупателя их десятки, и смотрят на них
 * всегда в его контексте.
 *
 * <p><b>Резерв не снимается сам.</b> По истечении срока сделка остаётся
 * {@code RESERVED} и попадает в отдельный список «истёк срок», где менеджер
 * решает: продлить, отменить или дожать. Автоснятие выглядит аккуратнее, но
 * превращает очередь на обзвон в невидимую потерю — на реальной разборке
 * в этом списке висело 62 сделки, и это работа, а не мусор.
 */
@Entity
@Table(name = "deal")
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Номер для человека; генерируется последовательностью в БД.
     *
     * <p>{@code @Generated} заставляет Hibernate вычитать значение после
     * вставки. Без него номер остаётся null в уже сохранённой сделке и
     * попадает в историю документа как «перенесено в сделку null».
     */
    @Generated(event = EventType.INSERT)
    @Column(insertable = false, updatable = false)
    private Long number;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "branch_id")
    private Long branchId;

    /**
     * Менеджер заполняется всегда: учёт продаж по людям для расчёта зарплат
     * невозможно достоверно восстановить задним числом.
     */
    @Column(name = "manager_id")
    private Long managerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DealStatus status = DealStatus.DRAFT;

    @Column(name = "deal_source_id")
    private Long dealSourceId;

    /** Склад, с которого выдают. Позиции могут лежать на разных. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "reserved_until")
    private Instant reservedUntil;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    private String note;

    @Column(name = "delivery_note")
    private String deliveryNote;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * {@code nullable = false} обязателен: без него Hibernate вставляет позицию
     * с пустым {@code deal_id} и лишь потом проставляет ссылку отдельным UPDATE,
     * а колонка объявлена NOT NULL — вставка падает.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "deal_id", nullable = false)
    private List<DealItem> items = new ArrayList<>();

    protected Deal() {
    }

    public Deal(Long customerId, Long managerId) {
        this.customerId = customerId;
        this.managerId = managerId;
    }

    // ---------- поведение ----------

    public DealItem addItem(Long partId, BigDecimal quantity, BigDecimal price, Long warehouseId) {
        requireOpen("добавить позицию");

        DealItem item = new DealItem(partId, quantity, price, warehouseId);
        items.add(item);
        recalculate();
        return item;
    }

    /**
     * Переносит позиции в другую сделку.
     *
     * <p>Операция из живой практики: клиент забирает половину сегодня, половину
     * на неделе, и вторая половина уезжает в отдельную сделку, чтобы первую
     * можно было закрыть. Перекладывать руками через удаление и добавление
     * нельзя — потеряется, что позиция уже была зарезервирована.
     */
    public List<DealItem> transferTo(Deal target, List<Long> itemIds) {
        requireOpen("перенести позиции");
        target.requireOpen("принять позиции");
        if (target == this) {
            throw new IllegalArgumentException("Перенос в ту же сделку бессмыслен");
        }

        // Сравнение через getId() != null обязательно: у ещё не сохранённых
        // позиций идентификатора нет, и List.of(...).contains(null) падает
        // с NullPointerException вместо внятной ошибки.
        List<DealItem> moved = items.stream()
                .filter(i -> i.getId() != null && itemIds.contains(i.getId()))
                .toList();

        if (moved.size() != itemIds.size()) {
            throw new IllegalArgumentException(
                    "Не все позиции найдены в сделке %s: запрошено %d, найдено %d. "
                            .formatted(number, itemIds.size(), moved.size())
                            + "Если сделка только что создана — сохраните её перед переносом");
        }

        items.removeAll(moved);
        target.items.addAll(moved);
        recalculate();
        target.recalculate();
        return moved;
    }

    /** Ставит резерв до указанного момента. Продление — этот же метод. */
    public void reserve(Instant until) {
        requireOpen("зарезервировать");
        if (items.isEmpty()) {
            throw new IllegalStateException("Нечего резервировать: в сделке нет позиций");
        }
        if (until == null || !until.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Срок резерва должен быть в будущем");
        }
        this.status = DealStatus.RESERVED;
        this.reservedUntil = until;
    }

    /**
     * Принимает резерв исходной сделки при переносе.
     *
     * <p>Не {@link #reserve}: тот требует срок в будущем, а переносят
     * и просроченное — тогда просроченной честно остаётся и новая сделка,
     * и она попадёт в список для продавца. Черновиком новый документ быть
     * не может: товар при переносе не освобождается, он обещан клиенту,
     * а черновик не обещает ничего — и выдать его продавцу будет нечем.
     */
    public void inheritReservation(Instant until) {
        requireOpen("принять резерв");
        this.status = DealStatus.RESERVED;
        this.reservedUntil = until;
    }

    public void markReady() {
        requireOpen("пометить готовой к выдаче");
        this.status = DealStatus.READY;
    }

    /**
     * Выдача клиенту. Именно здесь товар уходит со склада.
     *
     * <p>Долг выдачу не блокирует: на разборке отдают и в долг, и по частичной
     * оплате, и запрет здесь означал бы, что систему обходят мимо кассы.
     */
    public void issue(Instant when) {
        requireOpen("выдать");
        if (items.isEmpty()) {
            throw new IllegalStateException("Нечего выдавать: в сделке нет позиций");
        }
        items.forEach(DealItem::issue);
        this.status = DealStatus.ISSUED;
        this.issuedAt = when;
        this.closedAt = when;
    }

    /**
     * Отмечает позиции возвращёнными.
     *
     * <p>Сделка становится {@code RETURNED} только когда у клиента не осталось
     * ни одной выданной позиции. Частичный возврат — обычное дело: взяли четыре
     * детали, одна не подошла, — и такая сделка остаётся выданной.
     */
    public List<DealItem> registerReturn(List<Long> itemIds, Instant when) {
        if (status != DealStatus.ISSUED) {
            throw new IllegalStateException(
                    "Возврат оформляют по выданной сделке, а эта в состоянии " + status);
        }

        List<DealItem> returned = items.stream()
                .filter(i -> i.getId() != null && itemIds.contains(i.getId()))
                .toList();

        if (returned.size() != itemIds.size()) {
            throw new IllegalArgumentException(
                    "Не все позиции найдены в сделке %s: запрошено %d, найдено %d"
                            .formatted(number, itemIds.size(), returned.size()));
        }

        returned.forEach(DealItem::markReturned);

        if (items.stream().noneMatch(i -> i.getStatus() == DealItemStatus.ISSUED)) {
            this.status = DealStatus.RETURNED;
            this.closedAt = when;
        }
        return returned;
    }

    public void cancel(Instant when) {
        if (status == DealStatus.ISSUED) {
            throw new IllegalStateException(
                    "Выданную сделку не отменяют, а оформляют возвратом: иначе товар "
                            + "вернётся на склад без документа и без возврата денег");
        }
        items.forEach(DealItem::cancel);
        this.status = DealStatus.CANCELLED;
        this.closedAt = when;
    }

    /** Срок резерва вышел, но резерв остаётся — это очередь на обзвон. */
    public boolean isReservationExpired(Instant now) {
        return status == DealStatus.RESERVED
                && reservedUntil != null
                && reservedUntil.isBefore(now);
    }

    public BigDecimal debt() {
        return totalAmount.subtract(paidAmount).max(BigDecimal.ZERO);
    }

    public boolean isPartiallyPaid() {
        return paidAmount.signum() > 0 && paidAmount.compareTo(totalAmount) < 0;
    }

    public boolean isFullyPaid() {
        return paidAmount.compareTo(totalAmount) >= 0;
    }

    /** Сумма пересчитывается от позиций: хранить её независимо — гарантированное расхождение. */
    public void recalculate() {
        this.totalAmount = items.stream()
                .filter(i -> i.getStatus() != DealItemStatus.CANCELLED)
                .map(DealItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void registerPayment(BigDecimal amount) {
        this.paidAmount = this.paidAmount.add(amount);
    }

    private void requireOpen(String action) {
        if (!status.holdsStock()) {
            throw new IllegalStateException(
                    "Нельзя %s: сделка в состоянии %s".formatted(action, status));
        }
    }

    // ---------- доступ ----------

    public Long getId() {
        return id;
    }

    public Long getNumber() {
        return number;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public DealStatus getStatus() {
        return status;
    }

    public Instant getReservedUntil() {
        return reservedUntil;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public List<DealItem> getItems() {
        return List.copyOf(items);
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public Long getDealSourceId() {
        return dealSourceId;
    }

    public void setDealSourceId(Long dealSourceId) {
        this.dealSourceId = dealSourceId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getDeliveryNote() {
        return deliveryNote;
    }

    public void setDeliveryNote(String deliveryNote) {
        this.deliveryNote = deliveryNote;
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

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
