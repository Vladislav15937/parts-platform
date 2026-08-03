package ru.partsflow.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Наименование запчасти так, как его пишет арендатор.
 *
 * <p>Приёмщик пишет «телевизор», имея в виду переднюю панель рамки радиатора,
 * и так делают все — но каждый по-своему: «запаска», «гидроусилитель»,
 * «комплект грм», «Консоль магнитофона (уценка)». Локальное написание
 * сопоставляется с эталоном из общего каталога ({@code catalog.part_kind}),
 * и категория берётся от эталона, а не от локального названия.
 *
 * <p>Без этого слоя не работают ни поиск, ни выгрузки: на площадку нельзя
 * отдать «телевизор» в категории «Оптика».
 *
 * <p>Несопоставленные висят отдельным списком и разгребаются руками — на живой
 * разборке таких 71 из 2 259. Инвариант БД: сопоставленное обязано иметь
 * эталон, несопоставленное обязано его не иметь.
 */
@Entity
@Table(name = "part_name")
public class PartName {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Эталон из {@code catalog.part_kind}. Пусто, пока не сопоставлено. */
    @Column(name = "part_kind_id")
    private Long partKindId;

    /** Категория приходит от эталона: локальное написание её не знает. */
    @Column(name = "category_id")
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false)
    private MatchStatus matchStatus = MatchStatus.UNMATCHED;

    /** Сколько позиций заведено под этим написанием: подсказывает, что чинить раньше. */
    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected PartName() {
    }

    public PartName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Наименование не может быть пустым");
        }
        this.name = name.strip();
    }

    /**
     * Привязывает эталон.
     *
     * @param auto {@code true} — сопоставил алгоритм, {@code false} — человек.
     *             Различие важно: автосопоставление можно пересчитать при
     *             изменении справочника, ручное трогать нельзя — его сделали
     *             осознанно, глядя на деталь
     */
    public void matchTo(Long partKindId, Long categoryId, boolean auto) {
        if (partKindId == null) {
            throw new IllegalArgumentException("Сопоставление без эталона бессмысленно");
        }
        this.partKindId = partKindId;
        this.categoryId = categoryId;
        this.matchStatus = auto ? MatchStatus.AUTO : MatchStatus.MANUAL;
    }

    /** Снимает сопоставление: эталон оказался не тем. */
    public void unmatch() {
        this.partKindId = null;
        this.categoryId = null;
        this.matchStatus = MatchStatus.UNMATCHED;
    }

    public boolean isMatched() {
        return matchStatus != MatchStatus.UNMATCHED;
    }

    /** Ручное сопоставление пересчёту не подлежит: его сделал человек. */
    public boolean isReMatchable() {
        return matchStatus != MatchStatus.MANUAL;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getPartKindId() {
        return partKindId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public MatchStatus getMatchStatus() {
        return matchStatus;
    }

    public int getUsageCount() {
        return usageCount;
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

    /** Как наименование оказалось сопоставлено с эталоном. */
    public enum MatchStatus {

        /** Эталон не найден. Такие разгребают руками на отдельном экране. */
        UNMATCHED,

        /** Сопоставил алгоритм: точное совпадение с именем эталона или синонимом. */
        AUTO,

        /** Сопоставил человек. Пересчёту не подлежит. */
        MANUAL
    }

    /**
     * Момент последней правки. До 4 августа 2026 его ставил триггер
     * {@code touch_updated_at}; теперь — приложение, как и всё остальное.
     *
     * <p>{@code @PreUpdate}, а не присваивание в каждом сеттере: полей
     * у сущности десятки, и забыть один из них — вопрос времени.
     */
    @Column(name = "updated_at", insertable = false)
    private java.time.Instant updatedAt;

    @jakarta.persistence.PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = java.time.Instant.now();
    }

    public java.time.Instant getUpdatedAt() {
        return updatedAt;
    }

}
