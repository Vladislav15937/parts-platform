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
 * Платёж — самостоятельный документ, а не придаток сделки.
 *
 * <p>Бывает приход без сделки (клиент положил денег на счёт авансом) и расход
 * (вернули деньги, инкассация). Поэтому {@code dealId} необязателен,
 * а направление задаётся явно.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deal_id")
    private Long dealId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "payment_source_id")
    private Long paymentSourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentDirection direction = PaymentDirection.IN;

    /** Всегда положительная: знак несёт direction, а не сумма. */
    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "fiscal_receipt_id")
    private String fiscalReceiptId;

    private String comment;

    @Column(name = "paid_at", insertable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "created_by")
    private Long createdBy;

    protected Payment() {
    }

    public Payment(PaymentDirection direction, BigDecimal amount, Long customerId) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Сумма платежа должна быть положительной: направление задаётся полем direction");
        }
        this.direction = direction;
        this.amount = amount;
        this.customerId = customerId;
    }

    /** Со знаком — для кассовых итогов. */
    public BigDecimal signedAmount() {
        return direction == PaymentDirection.IN ? amount : amount.negate();
    }

    public Long getId() { return id; }
    public Long getDealId() { return dealId; }
    public void setDealId(Long dealId) { this.dealId = dealId; }
    public Long getCustomerId() { return customerId; }
    public Long getPaymentSourceId() { return paymentSourceId; }
    public void setPaymentSourceId(Long paymentSourceId) { this.paymentSourceId = paymentSourceId; }
    public PaymentDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getFiscalReceiptId() { return fiscalReceiptId; }
    public void setFiscalReceiptId(String id) { this.fiscalReceiptId = id; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Instant getPaidAt() { return paidAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
