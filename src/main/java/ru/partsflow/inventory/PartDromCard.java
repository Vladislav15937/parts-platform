package ru.partsflow.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Блоки карточки объявления Дрома: гарантия, самовывоз, доставка и оплата
 * (задача 0285), вид детали и раздел как у клиента (задача 0286), «Подходит»
 * (задача 0287), «Характеристики» (задача 0292), «Может подойти» (задача 0293).
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

    /**
     * «Вид детали» как у клиента на Дроме («Карданный вал Lexus GX400», задача 0286).
     * Не {@code part_kind_id}: тот — справочный вид без марки.
     */
    @Column(name = "drom_kind")
    private String dromKind;

    /** «Раздел» как у клиента («Автозапчасти → Трансмиссия»). Не {@code part.section} («Секция» склада). */
    @Column(name = "drom_section")
    private String dromSection;

    /**
     * «Применимость» («Подходит») как у клиента на Дроме (задача 0287): блоки текстом,
     * строка на пункт, части через « | ». Не {@code part_applicability} — тот справочный.
     */
    @Column(name = "applicability")
    private String applicability;

    /**
     * «Характеристики» объявления Дрома («Прочие поля» файла переноса, задача 0292):
     * строки «Подпись [ключ]: значение» — модель магнитолы, размер DIN, каналы,
     * мощность — как пришли. Набор зависит от рубрики Дрома, полей под него нет.
     */
    @Column(name = "characteristics")
    private String characteristics;

    /**
     * «Может подойти» объявления Дрома (задача 0293): второй блок применимости,
     * догадка самого Дрома — строка на машину «Марка Модель — КОД, КОД…», как
     * пришла. Только текст: в справочную {@code part_applicability} не
     * раскладывается (решение владельца 14.10.2026, {@code tasks/0293}).
     */
    @Column(name = "may_fit")
    private String mayFit;

    /**
     * «Дата» объявления Дрома (задача 0298): ГГГГ-ММ-ДД текстом, как в ячейке файла
     * переноса. Что она значит у Дрома, снаружи не определить, поэтому не «Создан» позиции.
     */
    @Column(name = "ad_date")
    private String adDate;

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

    public String getDromKind() {
        return dromKind;
    }

    public String getDromSection() {
        return dromSection;
    }

    public String getApplicability() {
        return applicability;
    }

    public String getCharacteristics() {
        return characteristics;
    }

    public String getMayFit() {
        return mayFit;
    }

    public String getAdDate() {
        return adDate;
    }

    public void set(String warranty, String pickup, String deliveryPayment,
                    String dromKind, String dromSection, String applicability,
                    String characteristics, String mayFit, String adDate) {
        this.warranty = clean(warranty);
        this.pickup = clean(pickup);
        this.deliveryPayment = clean(deliveryPayment);
        this.dromKind = clean(dromKind);
        this.dromSection = clean(dromSection);
        this.applicability = clean(applicability);
        this.characteristics = clean(characteristics);
        this.mayFit = clean(mayFit);
        this.adDate = clean(adDate);
    }
}
