package ru.partsflow.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Источник сделки: откуда пришла продажа.
 *
 * <p>Справочник, а не свободный текст: по нему считают, какой канал приносит
 * деньги, и «Авито», «авито» и «Авито ' стали бы тремя каналами.
 *
 * <p>Не путать с {@code deal.marketplace}: тот машинный код и часть ключа
 * идемпотентности заказа, а этот владелец переименовывает как хочет.
 */
@Entity
@Table(name = "deal_source")
public class DealSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    protected DealSource() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isArchived() {
        return archived;
    }
}
