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
    private final StockLedger ledger;
    private final StockReservationRepository stock;
    private final PartChangeLog partChanges;

    public StockDocumentService(StockDocumentRepository documents,
                               StockLedger ledger,
                               StockReservationRepository stock,
                               PartChangeLog partChanges) {
        this.documents = documents;
        this.ledger = ledger;
        this.stock = stock;
        this.partChanges = partChanges;
    }

    @Transactional
    public StockDocument save(StockDocument document) {
        return documents.saveAndFlush(document);
    }

    /** Документ по ключу запроса клиента: так узнаётся повтор офлайн-очереди. */
    @Transactional(readOnly = true)
    public java.util.Optional<StockDocument> findByClientRequestId(String clientRequestId) {
        return documents.findByClientRequestId(clientRequestId);
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
            requireAvailable(document, line);
            ledger.record(movementFor(document, line));
        }
        // Остаток и статус пересчитает триггер журнала, но узнать об этом
        // площадке неоткуда: отметку ставим здесь, где движение и рождается.
        partChanges.changed(document.getLines().stream()
                .map(StockDocumentLine::getPartId)
                .toList());

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

    /**
     * Проверяет, что уносимое со склада там есть и не обещано другому.
     *
     * <p>Сторожем остаётся база: между этой проверкой и записью движения
     * встанет продавец с резервом, и правым окажется ограничение схемы.
     * Проверка нужна не вместо него, а ради текста: без неё попытка перевезти
     * пять из одной отвечала «Операция нарушает целостность данных», по которой
     * кладовщик идёт искать поломку сервера вместо того, чтобы посмотреть
     * на полку.
     *
     * <p>Свободный, а не общий остаток: деталь, отложенная покупателю, уже
     * обещана, и увезти её на другой склад значит сорвать сделку молча.
     */
    private void requireAvailable(StockDocument document, StockDocumentLine line) {
        if (document.getDocType() != StockDocument.DocumentType.MOVE
                && document.getDocType() != StockDocument.DocumentType.WRITE_OFF) {
            return;
        }
        java.math.BigDecimal available =
                stock.availableQuantity(line.getPartId(), document.getWarehouseId());
        if (available.compareTo(line.getQty()) < 0) {
            throw new StockReservationRepository.InsufficientStockException(
                    "На складе свободно %s, а требуется %s: деталь %d"
                            .formatted(available.stripTrailingZeros().toPlainString(),
                                    line.getQty().stripTrailingZeros().toPlainString(),
                                    line.getPartId()),
                    null);
        }
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
