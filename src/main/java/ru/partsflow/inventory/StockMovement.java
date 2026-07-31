package ru.partsflow.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Движение товара — неизменяемый факт.
 *
 * <p>Остаток есть сумма движений; {@code part.qty_on_hand} — лишь кэш.
 * С изменяемым полем «количество» любое расхождение (инвентаризация, возврат,
 * отмена сделки, ошибка сотрудника) неразбираемо задним числом: видно только
 * текущее значение и неизвестно, как оно таким стало. Для разборок, которые
 * покупают систему в том числе от воровства, это принципиально.
 *
 * <p>Правки и удаления запрещены триггером в БД. Исправление — только
 * компенсирующим движением.
 */
@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false)
    private MovementType movementType;

    /**
     * Со знаком: приход положительный, расход отрицательный.
     * Исключение — {@link MovementType#MOVE}: там это перемещаемое количество,
     * всегда положительное, см. {@link #move}.
     */
    @Column(name = "qty_delta", nullable = false)
    private BigDecimal qtyDelta;

    /**
     * Склады-источник и приёмник. Именно по ним триггер раскладывает остаток
     * в {@code part_stock}: приход указывает только {@code to}, расход только
     * {@code from}, перемещение — оба.
     */
    @Column(name = "from_warehouse_id")
    private Long fromWarehouseId;

    @Column(name = "to_warehouse_id")
    private Long toWarehouseId;

    @Column(name = "from_cell_id")
    private Long fromCellId;

    @Column(name = "to_cell_id")
    private Long toCellId;

    /** Документ, проведение которого породило движение. */
    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "ref_type")
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    public StockMovement(Long partId, MovementType movementType, BigDecimal qtyDelta) {
        this.partId = partId;
        this.movementType = movementType;
        this.qtyDelta = qtyDelta;
    }

    public static StockMovement intake(Long partId, BigDecimal quantity,
                                       Long toWarehouseId, Long toCellId) {
        StockMovement movement = new StockMovement(partId, MovementType.INTAKE, quantity);
        movement.toWarehouseId = toWarehouseId;
        movement.toCellId = toCellId;
        return movement;
    }

    public static StockMovement sale(Long partId, BigDecimal quantity,
                                     Long fromWarehouseId, Long dealId) {
        StockMovement movement = new StockMovement(partId, MovementType.SALE, quantity.negate());
        movement.fromWarehouseId = fromWarehouseId;
        movement.refType = "DEAL";
        movement.refId = dealId;
        return movement;
    }

    /**
     * Корректировка по инвентаризации.
     *
     * <p>Знак задаёт направление: минус — недостача, плюс — излишек. Сторона
     * склада выбирается по знаку, потому что триггер остатка смотрит не на знак,
     * а на пару складов: расход идёт по {@code from}, приход по {@code to}.
     *
     * @param delta расхождение, отличное от нуля — сошедшуюся позицию
     *              корректировать нечем, и БД такое движение отвергнет
     */
    public static StockMovement inventoryAdjust(Long partId, BigDecimal delta, Long warehouseId) {
        if (delta == null || delta.signum() == 0) {
            throw new IllegalArgumentException(
                    "Корректировка на ноль бессмысленна: позиция сошлась");
        }
        StockMovement movement =
                new StockMovement(partId, MovementType.INVENTORY_ADJUST, delta);
        if (delta.signum() < 0) {
            movement.fromWarehouseId = warehouseId;
        } else {
            movement.toWarehouseId = warehouseId;
        }
        return movement;
    }

    /**
     * Списание: бой, недостача, разукомплектация.
     *
     * <p>Отдельный тип, а не продажа с нулевой ценой: списанное не должно
     * попадать в выручку и в отчёты по менеджерам.
     */
    public static StockMovement writeOff(Long partId, BigDecimal quantity, Long fromWarehouseId) {
        StockMovement movement =
                new StockMovement(partId, MovementType.WRITE_OFF, quantity.abs().negate());
        movement.fromWarehouseId = fromWarehouseId;
        return movement;
    }

    /**
     * Возврат от клиента.
     *
     * <p>Ссылается на документ возврата, а не на сделку: склад возврата не
     * обязан совпадать со складом выдачи, и по журналу должно быть видно,
     * каким документом деталь вернулась.
     */
    public static StockMovement returned(Long partId, BigDecimal quantity,
                                        Long toWarehouseId, Long returnId) {
        StockMovement movement = new StockMovement(partId, MovementType.RETURN, quantity.abs());
        movement.toWarehouseId = toWarehouseId;
        movement.refType = "RETURN";
        movement.refId = returnId;
        return movement;
    }

    /**
     * Перемещение между складами.
     *
     * <p>Здесь {@code qtyDelta} — перемещаемое количество, положительное:
     * знак не несёт смысла, потому что для одного склада это расход, а для
     * другого приход. Триггер смотрит не на знак, а на пару складов, и общий
     * остаток по детали в итоге не меняется.
     */
    public static StockMovement move(Long partId, BigDecimal quantity,
                                     Long fromWarehouseId, Long toWarehouseId, Long toCellId) {
        if (fromWarehouseId.equals(toWarehouseId)) {
            throw new IllegalArgumentException("Перемещение на тот же склад бессмысленно");
        }
        StockMovement movement = new StockMovement(partId, MovementType.MOVE, quantity.abs());
        movement.fromWarehouseId = fromWarehouseId;
        movement.toWarehouseId = toWarehouseId;
        movement.toCellId = toCellId;
        return movement;
    }

    public Long getId() {
        return id;
    }

    public Long getPartId() {
        return partId;
    }

    public MovementType getMovementType() {
        return movementType;
    }

    public BigDecimal getQtyDelta() {
        return qtyDelta;
    }

    public Long getFromWarehouseId() {
        return fromWarehouseId;
    }

    public Long getToWarehouseId() {
        return toWarehouseId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getFromCellId() {
        return fromCellId;
    }

    public void setFromCellId(Long fromCellId) {
        this.fromCellId = fromCellId;
    }

    public Long getToCellId() {
        return toCellId;
    }

    public void setToCellId(Long toCellId) {
        this.toCellId = toCellId;
    }

    public String getRefType() {
        return refType;
    }

    public Long getRefId() {
        return refId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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
}
