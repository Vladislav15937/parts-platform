package ru.partsflow.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Справочник услуг: доставка, упаковка.
 *
 * <p>Назван не {@code Service}, чтобы не спорить со спринговым понятием
 * сервиса в этом же пакете — {@code SalesService} рядом.
 *
 * <p>Цена здесь — подсказка продавцу, а не тариф: в строку сделки она
 * подставляется по умолчанию и правится в разговоре.
 */
@Entity
@Table(name = "service")
public class ServiceKind {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private BigDecimal price;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    protected ServiceKind() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isArchived() {
        return archived;
    }
}
