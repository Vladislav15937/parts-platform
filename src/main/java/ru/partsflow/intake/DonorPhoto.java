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

/**
 * Фотография машины-донора.
 *
 * <p><b>Зачем они отдельно от снимков детали.</b> У двигателя и коробки
 * состояние машины — половина объявления: покупатель смотрит, откуда снято,
 * какой пробег, цел ли кузов. Снимок самой детали этого не показывает,
 * а снимать машину заново на каждую снятую с неё запчасть никто не станет —
 * их с одного донора выходит до полутора сотен. Поэтому машина снимается
 * один раз, а в объявление её фотографии попадают тем позициям, чьё
 * наименование владелец отметил у выгрузки.
 *
 * <p><b>Устройство повторяет {@code PartPhoto} намеренно.</b> Тот же путь
 * в два шага (ссылка, загрузка мимо приложения, подтверждение), тот же
 * признак главной с частичным уникальным индексом, тот же ключ запроса
 * от клиента. Второй способ хранить снимки разошёлся бы с первым на первой
 * же правке — так в этом проекте уже расходились белые списки колонок
 * и словари состояний сделки.
 *
 * <p>Статус нужен потому, что запись появляется до файла: приложение выдаёт
 * ссылку на загрузку, браузер грузит, и только потом подтверждает.
 * {@code UPLOADED} без подтверждения означает оборванную загрузку — такой
 * снимок не покажется ни в карточке машины, ни в объявлении.
 */
@Entity
@Table(name = "donor_photo")
public class DonorPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "donor_id", nullable = false)
    private Long donorId;

    /** Полный ключ объекта в бакете, включая префикс арендатора. */
    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * Главная фотография машины — та, что уходит в объявление первой из
     * донорских. В БД частичный уникальный индекс: главная у машины одна.
     */
    @Column(name = "is_main", nullable = false)
    private boolean main;

    private Integer width;

    private Integer height;

    private Long bytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PhotoStatus status = PhotoStatus.UPLOADED;

    /** Ключ запроса от клиента: повтор не создаёт вторую запись и мусор в S3. */
    @Column(name = "client_request_id")
    private String clientRequestId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected DonorPhoto() {
    }

    public DonorPhoto(Long donorId, String s3Key) {
        if (donorId == null) {
            throw new IllegalArgumentException("Фотография без машины никому не нужна");
        }
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("Фотография без ключа в хранилище не найдётся");
        }
        this.donorId = donorId;
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

    public Long getDonorId() {
        return donorId;
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

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
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
