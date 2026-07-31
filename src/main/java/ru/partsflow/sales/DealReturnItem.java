package ru.partsflow.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Позиция возврата.
 *
 * <p>Флаг {@code restocked} — не мелочь: бракованную деталь принимают у клиента
 * и возвращают деньги, но на склад она не встаёт. Возврат её в остаток означал
 * бы, что она снова продаётся.
 */
@Entity
@Table(name = "deal_return_item")
public class DealReturnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean restocked = true;

    protected DealReturnItem() {
    }

    DealReturnItem(Long partId, BigDecimal quantity, BigDecimal amount, boolean restocked) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество в возврате должно быть больше нуля");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Сумма возврата не может быть отрицательной");
        }
        this.partId = partId;
        this.quantity = quantity;
        this.amount = amount;
        this.restocked = restocked;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isRestocked() {
        return restocked;
    }
}
