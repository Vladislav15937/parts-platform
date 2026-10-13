package ru.partsflow.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Блоки карточки объявления Дрома: гарантия, самовывоз, доставка и оплата
 * (задача 0285).
 *
 * <p><b>Отдельной сущностью, а не полями {@link Part}.</b> Тексты длинные
 * (доставка — около тысячи знаков на каждой позиции), а {@code Part} читают
 * продажа, резерв и пересчёт, и слушатель журнала кладёт в {@code audit_log}
 * всю строку {@code part} до и после каждой правки. Здесь они лежат рядом,
 * в своей таблице {@code part_drom_card} (строка на позицию, ключ — сама
 * позиция), и читает их только карточка и импорт. Правка идёт через Hibernate,
 * поэтому {@code AuditLogListener} пишет автора — как у остальных полей.
 *
 * <p>Строки нет, пока блоков ни разу не было (очищенные потом остаются строкой
 * с {@code NULL}); пусто — {@code NULL}, а не пустая строка (это держит {@code CHECK} схемы, а приводит {@link #clean}).
 */
@Entity
@Table(name = "part_drom_card")
public class PartDromCard {

    @Id
    @Column(name = "part_id")
    private Long partId;

    /** «Гарантия и условия возврата», как у клиента на Дроме. */
    private String warranty;

    /** «Самовывоз», как у клиента на Дроме. */
    private String pickup;

    /** «Доставка и оплата», весь текст с переводами строк. */
    @Column(name = "delivery_payment")
    private String deliveryPayment;

    protected PartDromCard() {
    }

    public PartDromCard(Long partId) {
        this.partId = partId;
    }

    /**
     * Пустое — {@code null}: строка из одних пробелов и переводов строк не
     * значение, и {@code CHECK} схемы её не пустит. Непустое хранится как есть,
     * без чистки краёв: клиент не должен терять слова.
     */
    public static String clean(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Long getPartId() {
        return partId;
    }

    public String getWarranty() {
        return warranty;
    }

    public String getPickup() {
        return pickup;
    }

    public String getDeliveryPayment() {
        return deliveryPayment;
    }

    public void set(String warranty, String pickup, String deliveryPayment) {
        this.warranty = clean(warranty);
        this.pickup = clean(pickup);
        this.deliveryPayment = clean(deliveryPayment);
    }
}
