package ru.partsflow.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Позиция сделки.
 *
 * <p>Себестоимость фиксируется снимком в момент выдачи: переоценка донора
 * задним числом не должна переписывать прибыль прошлых месяцев.
 */
@Entity
@Table(name = "deal_item")
public class DealItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private BigDecimal discount = BigDecimal.ZERO;

    /** Склад, с которого уйдёт именно эта позиция. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DealItemStatus status = DealItemStatus.RESERVED;

    @Column(name = "cost_price_snapshot")
    private BigDecimal costPriceSnapshot;

    protected DealItem() {
    }

    DealItem(Long partId, BigDecimal quantity, BigDecimal price, Long warehouseId) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество в позиции должно быть больше нуля");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Цена не может быть отрицательной");
        }
        this.partId = partId;
        this.quantity = quantity;
        this.price = price;
        this.warehouseId = warehouseId;
    }

    public BigDecimal lineTotal() {
        return price.multiply(quantity).subtract(discount);
    }

    void issue() {
        if (status == DealItemStatus.RESERVED) {
            status = DealItemStatus.ISSUED;
        }
    }

    void cancel() {
        if (status == DealItemStatus.RESERVED) {
            status = DealItemStatus.CANCELLED;
        }
    }

    void markReturned() {
        if (status != DealItemStatus.ISSUED) {
            throw new IllegalStateException(
                    "Вернуть можно только выданное, а позиция в состоянии " + status);
        }
        status = DealItemStatus.RETURNED;
    }

    /**
     * Себестоимость запоминается один раз — в момент выдачи. Повторный вызов
     * молча игнорируется: перезапись означала бы ровно ту переоценку задним
     * числом, от которой снимок и защищает.
     */
    void captureCost(BigDecimal costPrice) {
        if (costPriceSnapshot == null) {
            costPriceSnapshot = costPrice;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPartId() {
        return partId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount == null ? BigDecimal.ZERO : discount;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public DealItemStatus getStatus() {
        return status;
    }

    public BigDecimal getCostPriceSnapshot() {
        return costPriceSnapshot;
    }
}
