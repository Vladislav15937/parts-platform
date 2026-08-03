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
import java.time.Instant;

/**
 * Движение по лицевому счёту клиента.
 *
 * <p>Баланс — агрегат этого журнала, как остаток агрегат движений склада:
 * «сколько на счету» без истории операций невозможно разобрать в тот день,
 * когда клиент с суммой не согласен. Журнал неизменяем на уровне БД.
 */
@org.hibernate.annotations.Immutable
@Entity
@Table(name = "customer_account_entry")
public class CustomerAccountEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private AccountEntryType entryType;

    /** Со знаком: пополнение положительное, списание отрицательное. */
    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "deal_id")
    private Long dealId;

    @Column(name = "payment_id")
    private Long paymentId;

    private String comment;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected CustomerAccountEntry() {
    }

    public CustomerAccountEntry(Long customerId, AccountEntryType type, BigDecimal signedAmount) {
        if (signedAmount == null || signedAmount.signum() == 0) {
            throw new IllegalArgumentException("Нулевое движение по счёту не имеет смысла");
        }
        if (type == AccountEntryType.CORRECTION && signedAmount.signum() == 0) {
            throw new IllegalArgumentException("Корректировка обязана менять баланс");
        }
        this.customerId = customerId;
        this.entryType = type;
        this.amount = signedAmount;
    }

    /**
     * Сумма со знаком: пополнение и возврат прибавляют, оплата и выдача
     * вычитают.
     *
     * <p>Знак живёт здесь, а не в базе: там суммы положительные, иначе
     * «минус тысяча» и «тысяча со знаком минус» перестают различаться
     * при чтении журнала глазами.
     */
    public java.math.BigDecimal signedAmount() {
        return switch (entryType) {
            case TOP_UP, DEAL_REFUND -> amount;
            case DEAL_PAYMENT, WITHDRAW -> amount.negate();
            // Ручная правка кладётся со знаком в самой сумме: она и заводится
            // затем, чтобы поправить остаток в любую сторону.
            case CORRECTION -> amount;
        };
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public AccountEntryType getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public Long getDealId() { return dealId; }
    public void setDealId(Long dealId) { this.dealId = dealId; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
