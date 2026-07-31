package ru.partsflow.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Строка складского документа: что и сколько, и в какую ячейку. */
@Entity
@Table(name = "stock_document_line")
public class StockDocumentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Column(nullable = false)
    private BigDecimal qty;

    /** Цена операции: для поступления — по чём пришло. */
    private BigDecimal price;

    @Column(name = "cell_id")
    private Long cellId;

    protected StockDocumentLine() {
    }

    StockDocumentLine(Long partId, BigDecimal qty, Long cellId) {
        if (partId == null) {
            throw new IllegalArgumentException("Строка документа без детали бессмысленна");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("Количество в строке документа должно быть больше нуля");
        }
        this.partId = partId;
        this.qty = qty;
        this.cellId = cellId;
    }

    public Long getId() {
        return id;
    }

    public Long getPartId() {
        return partId;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Long getCellId() {
        return cellId;
    }
}
