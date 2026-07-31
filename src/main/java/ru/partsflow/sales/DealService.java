package ru.partsflow.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Услуга строкой сделки: доставка, упаковка.
 *
 * <p><b>Отдельно от {@link DealItem}, а не позицией с пустой деталью.</b>
 * Выдача пишет движение склада на каждую позицию, и услуга среди них означала
 * бы движение детали, которой нет. Разные таблицы делают это невозможным,
 * а не оставляют на дисциплину в коде.
 *
 * <p>Цена берётся из строки, а не из справочника: доставка до Надыма и до
 * соседней улицы стоит по-разному, и тариф здесь был бы враньём.
 *
 * <p><b>Услуга не возвращается вместе с товаром.</b> Деталь приехала обратно,
 * а доставка уже состоялась — вернуть её нельзя, и деньги за неё клиенту
 * не причитаются. Решил владелец вернуть по-доброму — это отдельная выплата,
 * а не строка возврата.
 */
@Entity
@Table(name = "deal_service")
public class DealService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(nullable = false)
    private BigDecimal price;

    protected DealService() {
    }

    DealService(Long serviceId, BigDecimal quantity, BigDecimal price) {
        if (serviceId == null) {
            throw new IllegalArgumentException("Не указана услуга");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Цена услуги не может быть отрицательной");
        }
        this.serviceId = serviceId;
        this.quantity = quantity == null ? BigDecimal.ONE : quantity;
        this.price = price;
        if (this.quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество услуги должно быть больше нуля");
        }
    }

    public BigDecimal lineTotal() {
        return price.multiply(quantity);
    }

    public Long getId() {
        return id;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
