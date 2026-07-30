package ru.partsflow.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Проведение складских документов.
 *
 * <p>Единственное место, где документ превращается в движения. Документ и
 * движения меняются одной транзакцией: разъехавшись, они дадут документ
 * «выполнен» без изменения остатка — то есть кладовщик уверен, что принял,
 * а на складе ничего нет.
 */
@Service
public class StockDocumentService {

    private final StockDocumentRepository documents;
    private final StockMovementRepository movements;

    public StockDocumentService(StockDocumentRepository documents,
                               StockMovementRepository movements) {
        this.documents = documents;
        this.movements = movements;
    }

    @Transactional
    public StockDocument save(StockDocument document) {
        return documents.saveAndFlush(document);
    }

    /**
     * Проводит документ: пишет движения по каждой строке и закрывает документ.
     *
     * <p>Остаток пересчитает триггер по журналу — здесь в {@code part_stock}
     * и {@code qty_on_hand} никто не пишет.
     */
    @Transactional
    public StockDocument complete(Long documentId) {
        StockDocument document = require(documentId);
        Instant now = Instant.now();

        for (StockDocumentLine line : document.getLines()) {
            movements.save(movementFor(document, line));
        }
        document.complete(now);
        return documents.saveAndFlush(document);
    }

    /** Отменяет черновик. Проведённый документ отмене не подлежит. */
    @Transactional
    public StockDocument cancel(Long documentId) {
        StockDocument document = require(documentId);
        document.cancel();
        return documents.saveAndFlush(document);
    }

    private StockMovement movementFor(StockDocument document, StockDocumentLine line) {
        StockMovement movement = switch (document.getDocType()) {
            case INTAKE -> StockMovement.intake(
                    line.getPartId(), line.getQty(), document.getWarehouseId(), line.getCellId());
            case MOVE -> StockMovement.move(
                    line.getPartId(), line.getQty(),
                    document.getWarehouseId(), document.getToWarehouseId(), line.getCellId());
            case WRITE_OFF -> StockMovement.writeOff(
                    line.getPartId(), line.getQty(), document.getWarehouseId());
            case RETURN -> StockMovement.returned(
                    line.getPartId(), line.getQty(), document.getWarehouseId(), document.getId());
            case INVENTORY -> throw new UnsupportedOperationException(
                    "Инвентаризация проводится через InventoryService: там сверка факта "
                            + "с учётом, и движения появляются только на расхождениях");
        };
        movement.setDocumentId(document.getId());
        return movement;
    }

    private StockDocument require(Long documentId) {
        return documents.findById(documentId).orElseThrow(
                () -> new IllegalArgumentException("Складской документ не найден: " + documentId));
    }
}
