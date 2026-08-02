package ru.partsflow.inventory;

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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Запчасть.
 *
 * <p>Это <b>партия</b>, а не всегда физический экземпляр: разборщик продаёт и
 * двери поштучно, и болты пачками. Уникальная деталь — партия с количеством 1.
 * Это заметно проще двух отдельных сущностей и не мешает штрих-кодированию.
 */
@Entity
@Table(name = "part")
public class Part {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Неугадываемый код для URL магазина, этикеток и объявлений.
     * Последовательный id светить нельзя: по номеру объявления конкурент
     * посчитает клиенту весь склад и темп поступлений.
     * Генерируется дефолтом в БД.
     */
    // @Generated обязателен, а не для порядка: без него Hibernate не вычитывает
    // сгенерированный базой код после INSERT, и приёмка отвечает карточкой
    // с пустым кодом при заполненном в базе. Та же ловушка, что с deal.number.
    @Generated(event = EventType.INSERT)
    @Column(name = "public_code", insertable = false, updatable = false)
    private String publicCode;

    @Column(name = "donor_id")
    private Long donorId;

    /**
     * Поставка, которой деталь приехала. Заполнена и когда донора нет вовсе:
     * контрактная запчасть приходит контейнером напрямую.
     */
    @Column(name = "supply_id")
    private Long supplyId;

    @Column(name = "part_kind_id")
    private Long partKindId;

    /** Локальное наименование арендатора; через него — связь с эталоном каталога. */
    @Column(name = "part_name_id")
    private Long partNameId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private String title;

    /** Комментарий к товару; выводится в объявлении. */
    private String description;

    /** Заметка для своих: нигде не показывается покупателю. */
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_lr")
    private LateralSide sideLr;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_fr")
    private LongitudinalSide sideFr;

    @Enumerated(EnumType.STRING)
    @Column(name = "side_ud")
    private VerticalSide sideUd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartCondition condition = PartCondition.USED;

    /** Оценка состояния. Уходит на площадки, поэтому значения фиксированы. */
    @Enumerated(EnumType.STRING)
    @Column(name = "quality_grade")
    private QualityGrade qualityGrade;

    private String marking;

    private String manufacturer;

    private String color;

    /**
     * Адрес хранения в номенклатуре клиента, пять уровней: {@code 01-02-02-03-01}.
     * Дублирует {@code storage_cell} намеренно: клиенты переезжают со своей
     * нумерацией полок и расставаться с ней не готовы.
     */
    private String section;

    /** Цена установки детали силами разборки. */
    @Column(name = "installation_price")
    private BigDecimal installationPrice;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    /**
     * Кэш остатка. Ведётся триггером БД по журналу {@code stock_movement}.
     * Никогда не пиши сюда из кода — только через вставку движения,
     * иначе кэш разъедется с журналом и сверка это поймает.
     */
    @Column(name = "qty_on_hand", insertable = false, updatable = false)
    private BigDecimal qtyOnHand = BigDecimal.ZERO;

    private BigDecimal price;

    @Column(name = "min_price")
    private BigDecimal minPrice;

    @Column(name = "cost_price")
    private BigDecimal costPrice;

    /**
     * Кто и когда последний раз менял цену. Вопрос «почему деталь ушла за
     * бесценок» задаётся задним числом, и ответить на него без этих двух полей
     * невозможно: аудит по всем полям слишком тяжёл, чтобы держать его вечно.
     */
    @Column(name = "price_changed_at")
    private Instant priceChangedAt;

    @Column(name = "price_changed_by")
    private Long priceChangedBy;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    /** Габариты и вес в упаковке — по ним считается доставка, а не по детали. */
    @Column(name = "package_length_mm")
    private Integer packageLengthMm;

    @Column(name = "package_width_mm")
    private Integer packageWidthMm;

    @Column(name = "package_height_mm")
    private Integer packageHeightMm;

    @Column(name = "package_weight_kg")
    private BigDecimal packageWeightKg;

    /** Ссылка на ролик о детали: на видео показывают работу и целость корпуса. */
    @Column(name = "video_url")
    private String videoUrl;

    /**
     * Свободный текст объявления. От заметки и комментария отличается адресатом:
     * те пишут для себя, этот уезжает покупателю.
     */
    @Column(name = "text_block")
    private String textBlock;

    /** Код позиции в прежней системе клиента: нужен на время переезда. */
    @Column(name = "legacy_code")
    private String legacyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartStatus status = PartStatus.DRAFT;

    @Column(name = "storage_cell_id")
    private Long storageCellId;

    private String barcode;

    /**
     * Выгружать ли позицию на площадки.
     *
     * <p>По умолчанию да: на разборке деталь снимают, чтобы продать, а «не
     * выгружать» — это отметка руками для битой и отложенной под заказ.
     *
     * <p>Значение задано здесь, а не только умолчанием колонки: Hibernate
     * пишет колонку в каждом INSERT явно, и умолчание базы до неё не доходит.
     * Именно на этом прайс нового клиента и оставался пустым.
     */
    @Column(name = "is_published", nullable = false)
    private boolean published = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * Кто правил карточку последним. Время правки ведёт триггер, а имя вести
     * некому: «изменено вчера» не отвечает на вопрос, к кому идти с вопросом
     * про цену, уехавшую втрое.
     */
    @Column(name = "updated_by")
    private Long updatedBy;

    protected Part() {
    }

    public Part(Long categoryId, String title, BigDecimal price) {
        this.categoryId = categoryId;
        this.title = title;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public String getPublicCode() {
        return publicCode;
    }

    public Long getDonorId() {
        return donorId;
    }

    public void setDonorId(Long donorId) {
        this.donorId = donorId;
    }

    public Long getPartKindId() {
        return partKindId;
    }

    public void setPartKindId(Long partKindId) {
        this.partKindId = partKindId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LateralSide getSideLr() {
        return sideLr;
    }

    public LongitudinalSide getSideFr() {
        return sideFr;
    }

    public VerticalSide getSideUd() {
        return sideUd;
    }

    /** Стороны задаются вместе: по отдельности легко забыть одну и получить «фара левая» без «передняя». */
    public void setSides(LateralSide lr, LongitudinalSide fr, VerticalSide ud) {
        this.sideLr = lr;
        this.sideFr = fr;
        this.sideUd = ud;
    }

    public QualityGrade getQualityGrade() {
        return qualityGrade;
    }

    public void setQualityGrade(QualityGrade qualityGrade) {
        this.qualityGrade = qualityGrade;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public BigDecimal getInstallationPrice() {
        return installationPrice;
    }

    public void setInstallationPrice(BigDecimal installationPrice) {
        this.installationPrice = installationPrice;
    }

    public Long getSupplyId() {
        return supplyId;
    }

    public void setSupplyId(Long supplyId) {
        this.supplyId = supplyId;
    }

    public Long getPartNameId() {
        return partNameId;
    }

    public void setPartNameId(Long partNameId) {
        this.partNameId = partNameId;
    }

    public PartCondition getCondition() {
        return condition;
    }

    public void setCondition(PartCondition condition) {
        this.condition = condition;
    }

    public String getMarking() {
        return marking;
    }

    public void setMarking(String marking) {
        this.marking = marking;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getQtyOnHand() {
        return qtyOnHand;
    }

    public BigDecimal getPrice() {
        return price;
    }

    /**
     * Меняет цену и одновременно фиксирует, кто это сделал.
     *
     * <p>Отдельного {@code setPrice} нет намеренно: цена без автора и времени
     * изменения — это ровно та ситуация, в которой потом нельзя разобрать,
     * почему деталь ушла за бесценок.
     */
    public void changePrice(BigDecimal newPrice, Long changedBy) {
        this.price = newPrice;
        this.priceChangedAt = Instant.now();
        this.priceChangedBy = changedBy;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public String getTextBlock() {
        return textBlock;
    }

    public void setTextBlock(String textBlock) {
        this.textBlock = textBlock;
    }

    /** Автор последней правки карточки. Ставится на каждое изменение полей. */
    public void touchedBy(Long memberId) {
        this.updatedBy = memberId;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getPriceChangedAt() {
        return priceChangedAt;
    }

    public Long getPriceChangedBy() {
        return priceChangedBy;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public Integer getLengthMm() {
        return lengthMm;
    }

    public Integer getWidthMm() {
        return widthMm;
    }

    public Integer getHeightMm() {
        return heightMm;
    }

    public void setDimensionsMm(Integer length, Integer width, Integer height) {
        this.lengthMm = length;
        this.widthMm = width;
        this.heightMm = height;
    }

    public Integer getPackageLengthMm() {
        return packageLengthMm;
    }

    public Integer getPackageWidthMm() {
        return packageWidthMm;
    }

    public Integer getPackageHeightMm() {
        return packageHeightMm;
    }

    public void setPackageDimensionsMm(Integer length, Integer width, Integer height) {
        this.packageLengthMm = length;
        this.packageWidthMm = width;
        this.packageHeightMm = height;
    }

    public BigDecimal getPackageWeightKg() {
        return packageWeightKg;
    }

    public void setPackageWeightKg(BigDecimal packageWeightKg) {
        this.packageWeightKg = packageWeightKg;
    }

    public String getLegacyCode() {
        return legacyCode;
    }

    public void setLegacyCode(String legacyCode) {
        this.legacyCode = legacyCode;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

    /**
     * Статус только читается: его ведёт триггер {@code stock_movement_apply}
     * по журналу движений. Сеттера нет намеренно — записанный из кода статус
     * разойдётся с остатком, и проданная деталь останется в поиске продавца.
     */
    public PartStatus getStatus() {
        return status;
    }

    public Long getStorageCellId() {
        return storageCellId;
    }

    public void setStorageCellId(Long storageCellId) {
        this.storageCellId = storageCellId;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
