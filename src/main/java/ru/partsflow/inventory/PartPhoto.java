package ru.partsflow.inventory;

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
 * Фотография запчасти.
 *
 * <p><b>В БД только ключ, файл — в S3.</b> Хранить снимки в Postgres значит
 * получить базу на терабайты, которую нельзя быстро восстановить, — а весь смысл
 * схемы на арендатора в том, чтобы одного клиента можно было поднять из бэкапа
 * за минуты.
 *
 * <p>Статус нужен потому, что запись появляется до файла: приложение выдаёт
 * ссылку на загрузку, телефон грузит, и только потом подтверждает.
 * {@code UPLOADED} без подтверждения означает оборванную загрузку — такие
 * записи чистятся, иначе карточка покажет битую картинку.
 */
@Entity
@Table(name = "part_photo")
public class PartPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    /** Полный ключ объекта в бакете, включая префикс арендатора. */
    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * Главная фотография — та, что уходит на площадку первой и видна в списке.
     * В БД частичный уникальный индекс: главная у детали одна.
     */
    @Column(name = "is_main", nullable = false)
    private boolean main;

    private Integer width;

    private Integer height;

    private Long bytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PhotoStatus status = PhotoStatus.UPLOADED;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected PartPhoto() {
    }

    public PartPhoto(Long partId, String s3Key) {
        if (partId == null) {
            throw new IllegalArgumentException("Фотография без детали никому не нужна");
        }
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("Фотография без ключа в хранилище не найдётся");
        }
        this.partId = partId;
        this.s3Key = s3Key;
    }

    /** Подтверждает, что файл в хранилище есть, и запоминает его размер. */
    public void confirm(Long bytes, Integer width, Integer height) {
        this.bytes = bytes;
        this.width = width;
        this.height = height;
        this.status = PhotoStatus.PROCESSED;
    }

    public void markFailed() {
        this.status = PhotoStatus.FAILED;
    }

    public void makeMain() {
        this.main = true;
    }

    public void unmakeMain() {
        this.main = false;
    }

    public void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isConfirmed() {
        return status == PhotoStatus.PROCESSED;
    }

    public Long getId() {
        return id;
    }

    public Long getPartId() {
        return partId;
    }

    public String getS3Key() {
        return s3Key;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isMain() {
        return main;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Long getBytes() {
        return bytes;
    }

    public PhotoStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Состояние загрузки. Файл появляется в хранилище позже записи в БД. */
    public enum PhotoStatus {

        /** Ссылка выдана, подтверждения ещё нет. */
        UPLOADED,

        /** Файл в хранилище проверен приложением. */
        PROCESSED,

        /** Загрузка не состоялась. */
        FAILED
    }
}
