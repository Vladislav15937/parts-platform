package ru.partsflow.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Строка инвентаризации: что учёт думал, что кладовщик нашёл.
 *
 * <p>{@code qtyExpected} — снимок учётного остатка на момент открытия сессии,
 * а не текущий: между открытием и проведением склад работает.
 *
 * <p>{@code qtyCounted} пустое значит «не посчитано», а не «не найдено». Разница
 * принципиальная: не дошли до полки — не повод списывать, а вот посчитанный
 * ноль — это недостача, и её надо провести.
 */
@Entity
@Table(name = "inventory_line")
public class InventoryLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Column(name = "cell_id")
    private Long cellId;

    @Column(name = "qty_expected", nullable = false)
    private BigDecimal qtyExpected;

    @Column(name = "qty_counted")
    private BigDecimal qtyCounted;

    @Column(name = "counted_by")
    private Long countedBy;

    /**
     * Момент подсчёта. Не для истории: против учётного остатка на этот момент
     * и считается расхождение, иначе продажа во время пересчёта станет
     * недостачей.
     */
    @Column(name = "counted_at")
    private Instant countedAt;

    /**
     * Когда строка проведена. Пусто — корректировка ещё не записана.
     *
     * <p>Отметка на строке, а не только на сессии: недостачу по детали,
     * обещанной покупателю, списать нельзя, и из-за одной такой строки
     * не должна ждать вся инвентаризация. Проведённая строка второй
     * корректировки не породит, поэтому повтор после снятия резерва
     * безопасен.
     */
    @Column(name = "applied_at")
    private Instant appliedAt;

    protected InventoryLine() {
    }

    InventoryLine(Long partId, BigDecimal qtyExpected, Long cellId) {
        if (partId == null) {
            throw new IllegalArgumentException("Строка инвентаризации без детали бессмысленна");
        }
        this.partId = partId;
        this.qtyExpected = qtyExpected == null ? BigDecimal.ZERO : qtyExpected;
        this.cellId = cellId;
    }

    void count(BigDecimal qty, Long countedBy, Instant when) {
        if (qty == null || qty.signum() < 0) {
            throw new IllegalArgumentException(
                    "Фактическое количество не может быть отрицательным");
        }
        this.qtyCounted = qty;
        this.countedBy = countedBy;
        this.countedAt = when;
    }

    public boolean isCounted() {
        return qtyCounted != null;
    }

    /** Проведённая строка второй корректировки не породит. */
    public boolean isApplied() {
        return appliedAt != null;
    }

    void markApplied(Instant when) {
        this.appliedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Long getPartId() {
        return partId;
    }

    public Long getCellId() {
        return cellId;
    }

    public BigDecimal getQtyExpected() {
        return qtyExpected;
    }

    public BigDecimal getQtyCounted() {
        return qtyCounted;
    }

    public Long getCountedBy() {
        return countedBy;
    }

    public Instant getCountedAt() {
        return countedAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
