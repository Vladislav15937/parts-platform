package ru.partsflow.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Поставка: контейнер, закупка или разовое поступление.
 *
 * <p><b>Это узел, а не поле.</b> Товар на разборке приходит не только с битой
 * машины: у клиента, с которого снята карта функционала, запчасти идут
 * контейнерами из Японии, и контрактные детали приезжают напрямую — без донора
 * вообще. К поставке привязаны и доноры, и такие детали, и складские документы
 * приёмки.
 *
 * <p>Отсюда правило: {@code part.donor_id} может быть пустым, а вот происхождение
 * товара без поставки не восстановить.
 */
@Entity
@Table(name = "supply")
public class Supply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplyKind kind = SupplyKind.CONTAINER;

    /** Номер контейнера или закупки. Уникален в паре с видом. */
    @Column(nullable = false)
    private String number;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "arrived_on")
    private LocalDate arrivedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplyStatus status = SupplyStatus.EXPECTED;

    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Supply() {
    }

    public Supply(SupplyKind kind, String number) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("Поставка без номера неотличима от другой");
        }
        this.kind = kind == null ? SupplyKind.CONTAINER : kind;
        this.number = number.strip();
    }

    /**
     * Отмечает поставку прибывшей.
     *
     * <p>Дата прихода — не то же, что дата создания записи: контейнер заводят
     * заранее, когда он ещё в море, и по нему уже принимают предзаказы.
     */
    public void markArrived(LocalDate when) {
        if (status == SupplyStatus.CLOSED) {
            throw new IllegalStateException("Закрытая поставка не может прибыть повторно");
        }
        this.status = SupplyStatus.ARRIVED;
        this.arrivedOn = when == null ? LocalDate.now() : when;
    }

    public void markInTransit() {
        if (status != SupplyStatus.EXPECTED) {
            throw new IllegalStateException(
                    "В путь отправляется только ожидаемая поставка, а эта в состоянии " + status);
        }
        this.status = SupplyStatus.IN_TRANSIT;
    }

    /**
     * Закрывает поставку: всё разобрано и разнесено.
     *
     * <p>Закрытие не удаляет и не прячет содержимое — по нему потом считают,
     * во что обошёлся контейнер и что с него ещё лежит на складе.
     */
    public void close() {
        if (status != SupplyStatus.ARRIVED) {
            throw new IllegalStateException(
                    "Закрыть можно только прибывшую поставку, а эта в состоянии " + status);
        }
        this.status = SupplyStatus.CLOSED;
    }

    public boolean acceptsGoods() {
        return status == SupplyStatus.ARRIVED;
    }

    public Long getId() {
        return id;
    }

    public SupplyKind getKind() {
        return kind;
    }

    public String getNumber() {
        return number;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public LocalDate getArrivedOn() {
        return arrivedOn;
    }

    public SupplyStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Вид поставки. Контейнер — основной поток у клиентов с японским товаром. */
    public enum SupplyKind {
        CONTAINER,
        PURCHASE,
        OTHER
    }

    /** Где поставка находится. Заводят её задолго до прихода. */
    public enum SupplyStatus {
        EXPECTED,
        IN_TRANSIT,
        ARRIVED,
        CLOSED
    }
}
