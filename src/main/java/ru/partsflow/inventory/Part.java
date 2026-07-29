package ru.partsflow.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
    @Column(name = "public_code", insertable = false, updatable = false)
    private String publicCode;

    @Column(name = "donor_id")
    private Long donorId;

    @Column(name = "part_kind_id")
    private Long partKindId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private String title;

    private String description;

    private String side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartCondition condition = PartCondition.USED;

    private String marking;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartStatus status = PartStatus.DRAFT;

    @Column(name = "storage_cell_id")
    private Long storageCellId;

    private String barcode;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

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

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
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

    public void setPrice(BigDecimal price) {
        this.price = price;
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

    public PartStatus getStatus() {
        return status;
    }

    public void setStatus(PartStatus status) {
        this.status = status;
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
