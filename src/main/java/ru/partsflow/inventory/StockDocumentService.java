package ru.partsflow.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final StockNaming naming;

    public StockDocumentService(StockDocumentRepository documents,
                               StockLedger ledger,
                               StockReservationRepository stock,
                               PartChangeLog partChanges,
                               org.springframework.jdbc.core.JdbcTemplate jdbc,
                                StockNaming naming) {
        this.documents = documents;
        this.ledger = ledger;
        this.stock = stock;
        this.partChanges = partChanges;
        this.jdbc = jdbc;
        this.naming = naming;
    }

    @Transactional
    public StockDocument save(StockDocument document) {
        requireReferences(document);
        return documents.saveAndFlush(document);
    }

    /**
     * Перевозит пачку одним документом, пропуская позиции, которых на складе
     * не хватает: отложенные покупателю не едут, а остальные всё равно
     * переезжают одним движением.
     *
     * <p><b>Зачем пре-проверка, если {@code complete()} и так её делает.</b>
     * Без неё пачка в двести позиций, где одна отложена, откатывалась бы
     * целиком — кладовщик получал 409 на весь документ и набирал сто
     * девяносто девять позиций заново руками. Здесь читаем свободный остаток
     * заранее и строим документ только из того, что поедет.
     *
     * <p>Сторожем при этом остаётся сам {@code complete()}: между проверкой
     * и записью движения встанет второй продавец, и в этом (редком) случае
     * отказ будет как раньше — на весь документ. Это не read-modify-write
     * над остатком: мы ничего не пишем по результатам чтения, только решаем,
     * какие строки предложить документу, а хватает ли их на самом деле,
     * решает та же инструкция в {@code WHERE}, что и всегда.
     *
     * <p>Если не может уехать ни одна позиция (в частности — единственная,
     * как раньше делала карточка), не подменяем документ пустым: строим его
     * из исходных строк как есть, и {@code complete()} откажет тем же
     * способом, что и до этой правки, — той же ошибкой на тот же случай.
     */
    @Transactional
    public MoveOutcome moveBatch(Long fromWarehouseId, Long toWarehouseId,
                                 List<StockMoveController.MoveItem> items,
                                 String note, Long createdBy) {
        List<StockMoveController.MoveItem> insufficient = new ArrayList<>();
        for (StockMoveController.MoveItem item : items) {
            BigDecimal available = stock.availableQuantity(item.partId(), fromWarehouseId);
            if (available.compareTo(item.quantity()) < 0) {
                insufficient.add(item);
            }
        }

        List<StockMoveController.MoveItem> toMove = items;
        if (!insufficient.isEmpty() && insufficient.size() < items.size()) {
            toMove = items.stream().filter(item -> !insufficient.contains(item)).toList();
        }

        StockDocument document = StockDocument.move(fromWarehouseId, toWarehouseId);
        document.setCreatedBy(createdBy);
        document.setNote(note);
        for (StockMoveController.MoveItem item : toMove) {
            document.addLine(item.partId(), item.quantity(), item.toCellId());
        }

        StockDocument saved = save(document);
        StockDocument completed = complete(saved.getId());

        List<SkippedItem> skipped = toMove == items
                ? List.of()
                : insufficient.stream()
                        .map(item -> new SkippedItem(item.partId(), publicCodeOf(item.partId())))
                        .toList();
        return new MoveOutcome(completed, skipped);
    }

    private String publicCodeOf(Long partId) {
        return jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId);
    }

    /** Итог перевозки пачкой: сам документ и то, что в него не попало. */
    public record MoveOutcome(StockDocument document, List<SkippedItem> skipped) {
    }

    /** Позиция, которая не поехала, потому что часть остатка обещана покупателю. */
    public record SkippedItem(Long partId, String publicCode) {
    }

    /**
     * Склад, ячейка и деталь обязаны существовать — и сказать об этом надо
     * словами.
     *
     * <p>Пока проверки не было, чужой номер доезжал до внешнего ключа
     * и возвращался как «Операция нарушает целостность данных». Через этот
     * метод проходят приёмка, списание, перевозка и возврат, то есть один
     * невнятный ответ на четыре операции сразу.
     *
     * <p>Для человека это «сервер сломался», для офлайн-очереди — 409, отказ
     * по существу: партия уходит в «требует внимания» с сообщением, из
     * которого не понять, что делать. Случай не выдуманный: склад или ячейку
     * могли выключить, пока телефон был без связи, а в теле записи очереди
     * лежат прежние номера.
     *
     * <p>Проверка ради текста, а не вместо схемы: сторожем остаются внешние
     * ключи — между проверкой и вставкой строку может убрать кто-то другой.
     */
    private void requireReferences(StockDocument document) {
        requireExists("warehouse", document.getWarehouseId(), "Склад не найден: ");
        requireExists("warehouse", document.getToWarehouseId(), "Склад назначения не найден: ");
        for (StockDocumentLine line : document.getLines()) {
            requireExists("part", line.getPartId(), "Деталь не найдена: ");
            requireExists("storage_cell", line.getCellId(), "Ячейка не найдена: ");
        }
    }

    /**
     * Имя таблицы подставляется текстом, и это безопасно: оно приходит
     * из этого же класса, а не из запроса. Параметром таблицу не задать.
     */
    private void requireExists(String table, Long id, String complaint) {
        if (id == null) {
            return;
        }
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (found == null || found == 0) {
            throw new IllegalArgumentException(complaint + id);
        }
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
                    "На складе %s свободно %s, а требуется %s: %s"
                            .formatted(naming.warehouse(document.getWarehouseId()),
                                    available.stripTrailingZeros().toPlainString(),
                                    line.getQty().stripTrailingZeros().toPlainString(),
                                    naming.part(line.getPartId())),
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
