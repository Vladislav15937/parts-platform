package ru.partsflow.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Донор — машина, с которой снимают запчасти.
 *
 * <p><b>Статус и локация — разные вещи.</b> {@link DonorStatus} отвечает
 * на «что с ней делают»: купили, разбирают, разобрали. {@code location} —
 * на «где она физически»: «В Барнаул», «На складе». У клиентов с японским
 * товаром машина едет неделями, и всё это время статус не меняется, а локация
 * меняется несколько раз.
 *
 * <p><b>Донор — не единственный источник товара.</b> Контрактные запчасти
 * приходят контейнером напрямую, поэтому у детали донор необязателен,
 * а поставка есть всегда.
 *
 * <p>Деньги по донору живут отдельно: {@code donor_cost} — журнал затрат,
 * {@code cost_allocation_method} — как эти затраты разносятся на снятые
 * запчасти. Одного поля «стоимость» тут мало: в контейнер входит и цена машины,
 * и доставка, и таможня, и они приходят в разное время.
 */
@Entity
@Table(name = "donor")
public class Donor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Код для человека; генерируется в БД и вычитывается после вставки. */
    @Generated(event = EventType.INSERT)
    @Column(name = "public_code", insertable = false, updatable = false)
    private String publicCode;

    private String vin;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "generation_id")
    private Long generationId;

    @Column(name = "modification_id")
    private Long modificationId;

    private Short year;

    private String color;

    @Column(name = "color_code")
    private String colorCode;

    @Column(name = "mileage_km")
    private Integer mileageKm;

    @Column(name = "plate_number")
    private String plateNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DonorStatus status = DonorStatus.PURCHASED;

    /** Где машина физически. Свободный текст: у каждого клиента свои названия. */
    private String location;

    @Column(name = "supply_id")
    private Long supplyId;

    @Enumerated(EnumType.STRING)
    private Steering steering;

    @Enumerated(EnumType.STRING)
    @Column(name = "drive_type")
    private DriveType driveType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transmission_type")
    private TransmissionType transmissionType;

    /** Модель агрегата: «K112F-01». Нужна при подборе КПП на замену. */
    @Column(name = "transmission_model")
    private String transmissionModel;

    @Column(name = "equipment_code")
    private String equipmentCode;

    /**
     * Кузов и двигатель как написано в документах машины: «ACV40», «2AZ-FE».
     *
     * <p>Свободный текст, а не ссылка в каталог, и это не небрежность.
     * Кузов у нас есть и в справочнике — `catalog.generation.code`, — но
     * поколение приёмщик выбирает не всегда: у модели, которой в каталоге
     * нет, ссылаться не на что, а кузов в ПТС написан. По той же причине
     * двигатель отдельно от `modification`.
     *
     * <p>Колонки завёл перенос, а сущность их не знала: заполнить их мог
     * только импорт, то есть у клиента, который заводит машины руками, они
     * были пусты всегда — при том что по кузову и двигателю деталь и
     * подбирают, и в прайс Дрома они уходят отдельными тегами.
     */
    @Column(name = "body_code")
    private String bodyCode;

    @Column(name = "engine_code")
    private String engineCode;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "branch_id")
    private Long branchId;

    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Donor() {
    }

    public Donor(Long brandId) {
        if (brandId == null) {
            throw new IllegalArgumentException(
                    "Донор без марки не найдётся ни в поиске, ни в подборе по применимости");
        }
        this.brandId = brandId;
    }

    // ---------- поведение ----------

    /** Привязывает к поставке: машина приехала контейнером. */
    public void arrivedWith(Long supplyId) {
        this.supplyId = supplyId;
    }

    /** Меняет локацию в логистической цепочке. Статус при этом не двигается. */
    public void moveTo(String location) {
        this.location = location;
    }

    public void startDismantling() {
        if (status != DonorStatus.PURCHASED) {
            throw new IllegalStateException(
                    "Разбор начинают на купленной машине, а эта в состоянии " + status);
        }
        this.status = DonorStatus.DISMANTLING;
    }

    /**
     * Разбор закончен.
     *
     * <p>Не мешает продавать уже снятые с неё детали: остаток живёт у деталей,
     * а не у машины.
     */
    public void finishDismantling() {
        if (status != DonorStatus.DISMANTLING) {
            throw new IllegalStateException(
                    "Закончить можно начатый разбор, а машина в состоянии " + status);
        }
        this.status = DonorStatus.DISMANTLED;
    }

    public void writeOff() {
        if (status == DonorStatus.WRITTEN_OFF) {
            return;
        }
        this.status = DonorStatus.WRITTEN_OFF;
    }

    /** С машины можно снимать запчасти. */
    public boolean yieldsParts() {
        return status == DonorStatus.DISMANTLING || status == DonorStatus.DISMANTLED;
    }

    // ---------- доступ ----------

    public Long getId() {
        return id;
    }

    public String getPublicCode() {
        return publicCode;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public Long getBrandId() {
        return brandId;
    }

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public Long getGenerationId() {
        return generationId;
    }

    public void setGenerationId(Long generationId) {
        this.generationId = generationId;
    }

    public Long getModificationId() {
        return modificationId;
    }

    public void setModificationId(Long modificationId) {
        this.modificationId = modificationId;
    }

    public Short getYear() {
        return year;
    }

    public void setYear(Short year) {
        this.year = year;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getColorCode() {
        return colorCode;
    }

    public void setColorCode(String colorCode) {
        this.colorCode = colorCode;
    }

    public Integer getMileageKm() {
        return mileageKm;
    }

    public void setMileageKm(Integer mileageKm) {
        this.mileageKm = mileageKm;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        this.plateNumber = plateNumber;
    }

    public DonorStatus getStatus() {
        return status;
    }

    public String getLocation() {
        return location;
    }

    public Long getSupplyId() {
        return supplyId;
    }

    public Steering getSteering() {
        return steering;
    }

    public void setSteering(Steering steering) {
        this.steering = steering;
    }

    public DriveType getDriveType() {
        return driveType;
    }

    public void setDriveType(DriveType driveType) {
        this.driveType = driveType;
    }

    public TransmissionType getTransmissionType() {
        return transmissionType;
    }

    public void setTransmissionType(TransmissionType transmissionType) {
        this.transmissionType = transmissionType;
    }

    public String getTransmissionModel() {
        return transmissionModel;
    }

    public void setTransmissionModel(String transmissionModel) {
        this.transmissionModel = transmissionModel;
    }

    public String getEquipmentCode() {
        return equipmentCode;
    }

    public void setEquipmentCode(String equipmentCode) {
        this.equipmentCode = equipmentCode;
    }

    public String getBodyCode() {
        return bodyCode;
    }

    public void setBodyCode(String bodyCode) {
        this.bodyCode = bodyCode;
    }

    public String getEngineCode() {
        return engineCode;
    }

    public void setEngineCode(String engineCode) {
        this.engineCode = engineCode;
    }

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
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

    /** Стадия разбора. Не путать с локацией: та про физическое положение. */
    public enum DonorStatus {
        PURCHASED,
        DISMANTLING,
        DISMANTLED,
        WRITTEN_OFF
    }

    public enum Steering {
        LEFT,
        RIGHT
    }

    public enum DriveType {
        FWD,
        RWD,
        AWD
    }

    public enum TransmissionType {
        MT,
        AT,
        CVT,
        AMT,
        DCT
    }
}
