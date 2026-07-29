package ru.partsflow.intake;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.catalog.PartName;
import ru.partsflow.catalog.PartNameService;
import ru.partsflow.inventory.Part;
import ru.partsflow.inventory.PartRepository;
import ru.partsflow.inventory.StockDocument;
import ru.partsflow.inventory.StockDocumentService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Приёмка: от контейнера до детали на полке.
 *
 * <p>Сценарий целиком: пришла поставка → в ней машины и контрактные детали →
 * с машины снимают запчасть → заводят карточку → кладут в ячейку. Всё это
 * одна цепочка, и рвётся она в предсказуемом месте — на наименовании: приёмщик
 * пишет «телевизор», а в справочнике такого нет.
 *
 * <p><b>Несопоставленное наименование приёмку не останавливает.</b> На складе
 * стоит человек с деталью в руках; отказать ему значит получить деталь
 * без карточки, то есть невидимую для продажи. Наименование заводится как есть
 * и попадает в список нераспознанных, а разгребают его вечером.
 */
@Service
public class IntakeService {

    private final SupplyRepository supplies;
    private final DonorRepository donors;
    private final PartRepository parts;
    private final PartNameService partNames;
    private final StockDocumentService documents;

    public IntakeService(SupplyRepository supplies,
                         DonorRepository donors,
                         PartRepository parts,
                         PartNameService partNames,
                         StockDocumentService documents) {
        this.supplies = supplies;
        this.donors = donors;
        this.parts = parts;
        this.partNames = partNames;
        this.documents = documents;
    }

    // ---------- поставки ----------

    /**
     * Регистрирует поставку или возвращает уже существующую.
     *
     * <p>Повторный вызов не создаёт вторую: номер контейнера уникален в паре
     * с видом, и приёмщик, заводящий его второй раз, обычно просто не нашёл
     * первый.
     */
    @Transactional
    public Supply registerSupply(Supply.SupplyKind kind, String number,
                                 String supplierName, Long authorId) {
        Supply.SupplyKind actualKind = kind == null ? Supply.SupplyKind.CONTAINER : kind;
        return supplies.findByKindAndNumber(actualKind, number.strip())
                .orElseGet(() -> {
                    Supply supply = new Supply(actualKind, number);
                    supply.setSupplierName(supplierName);
                    supply.setCreatedBy(authorId);
                    return supplies.saveAndFlush(supply);
                });
    }

    @Transactional
    public Supply markSupplyArrived(Long supplyId, LocalDate when) {
        Supply supply = requireSupply(supplyId);
        supply.markArrived(when);
        return supplies.saveAndFlush(supply);
    }

    // ---------- доноры ----------

    /** Заводит машину и привязывает её к поставке, если та указана. */
    @Transactional
    public Donor registerDonor(Donor donor, Long supplyId, Long authorId) {
        if (supplyId != null) {
            requireSupply(supplyId);
            donor.arrivedWith(supplyId);
        }
        donor.setCreatedBy(authorId);
        return donors.saveAndFlush(donor);
    }

    /** Меняет локацию машины в логистической цепочке. Стадия разбора не двигается. */
    @Transactional
    public Donor moveDonor(Long donorId, String location) {
        Donor donor = requireDonor(donorId);
        donor.moveTo(location);
        return donors.saveAndFlush(donor);
    }

    @Transactional
    public Donor startDismantling(Long donorId) {
        Donor donor = requireDonor(donorId);
        donor.startDismantling();
        return donors.saveAndFlush(donor);
    }

    // ---------- детали ----------

    /**
     * Принимает партию деталей одним складским документом.
     *
     * <p>Документ, а не набор отдельных движений: кладовщик собирает
     * поступление списком и проводит целиком. Черновик остаток не двигает —
     * это лист, с которым ходят по складу.
     *
     * @param donorId  машина, с которой снято; {@code null} для контрактных
     *                 деталей, приехавших контейнером напрямую
     * @param supplyId поставка — есть всегда, в отличие от донора
     */
    @Transactional
    public Receipt receive(Long warehouseId, Long supplyId, Long donorId,
                           List<ItemRequest> items, Long authorId) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Приёмка без позиций не имеет смысла");
        }
        if (supplyId != null) {
            requireSupply(supplyId);
        }
        Donor donor = donorId == null ? null : requireDonor(donorId);

        StockDocument document = documents.save(StockDocument.intake(warehouseId, supplyId));
        document.setCreatedBy(authorId);

        List<Part> created = new java.util.ArrayList<>(items.size());
        for (ItemRequest item : items) {
            PartName partName = partNames.resolve(item.rawName(), authorId);

            Part part = new Part(partName.getCategoryId(), item.title(), item.price());
            part.setPartNameId(partName.getId());
            part.setCostPrice(item.costPrice());
            if (donor != null) {
                part.setDonorId(donor.getId());
            }
            part.setSupplyId(supplyId);
            part.setCreatedBy(authorId);

            Part savedPart = parts.saveAndFlush(part);
            document.addLine(savedPart.getId(), item.quantity(), item.cellId());
            created.add(savedPart);
        }

        StockDocument saved = documents.save(document);
        StockDocument completed = documents.complete(saved.getId());
        return new Receipt(completed, created);
    }

    private Supply requireSupply(Long supplyId) {
        return supplies.findById(supplyId).orElseThrow(
                () -> new IllegalArgumentException("Поставка не найдена: " + supplyId));
    }

    private Donor requireDonor(Long donorId) {
        return donors.findById(donorId).orElseThrow(
                () -> new IllegalArgumentException("Донор не найден: " + donorId));
    }

    /**
     * Позиция приёмки.
     *
     * @param rawName написание приёмщика — оно и попадёт в справочник
     *                наименований как есть
     * @param title   наименование карточки; собирается
     *                {@code PartTitleGenerator}, руками его не пишут
     */
    public record ItemRequest(String rawName, String title, BigDecimal quantity,
                              BigDecimal price, BigDecimal costPrice, Long cellId) {
    }

    /** Результат приёмки: проведённый документ и заведённые карточки. */
    public record Receipt(StockDocument document, List<Part> parts) {
    }
}
