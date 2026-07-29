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

    /** Со знаком: приход положительный, расход отрицательный. */
    @Column(name = "qty_delta", nullable = false)
    private BigDecimal qtyDelta;

    @Column(name = "from_cell_id")
    private Long fromCellId;

    @Column(name = "to_cell_id")
    private Long toCellId;

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

    public static StockMovement intake(Long partId, BigDecimal quantity, Long toCellId) {
        StockMovement movement = new StockMovement(partId, MovementType.INTAKE, quantity);
        movement.toCellId = toCellId;
        return movement;
    }

    public static StockMovement sale(Long partId, BigDecimal quantity, Long dealId) {
        StockMovement movement = new StockMovement(partId, MovementType.SALE, quantity.negate());
        movement.refType = "DEAL";
        movement.refId = dealId;
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
