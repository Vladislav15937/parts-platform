package ru.partsflow.inventory;

import ru.partsflow.platform.security.MemberSignature;
import ru.partsflow.shared.NotFound;
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
    private final StockDocumentHistory history;

    public StockDocumentService(StockDocumentRepository documents,
                               StockLedger ledger,
                               StockReservationRepository stock,
                               PartChangeLog partChanges,
                               org.springframework.jdbc.core.JdbcTemplate jdbc,
                                StockNaming naming,
                                StockDocumentHistory history) {
        this.history = history;
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
        requireExists("warehouse", document.getWarehouseId(), NotFound.WAREHOUSE);
        requireExists("warehouse", document.getToWarehouseId(), NotFound.DESTINATION_WAREHOUSE);
        for (StockDocumentLine line : document.getLines()) {
            requireExists("part", line.getPartId(), NotFound.PART);
            requireExists("storage_cell", line.getCellId(), NotFound.CELL);
        }
    }

    /**
     * Имя таблицы подставляется текстом, и это безопасно: оно приходит
     * из этого же класса, а не из запроса. Параметром таблицу не задать.
     */
    private void requireExists(String table, Long id, NotFound subject) {
        if (id == null) {
            return;
        }
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (found == null || found == 0) {
            // Номер строки базы человеку не говорит ничего: он выбирал
            // из списка, а не набирал идентификатор (задача 0067).
            throw subject.error(id);
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
        // Под блокировкой строки и с проверкой состояния ДО движений (задача
        // 0360): «Провести документ» на экране нажимают дважды, и второе
        // нажатие без блокировки написало бы приход второй раз — проверка
        // черновика стояла после записи движений.
        StockDocument document = documents.findByIdForUpdate(documentId).orElseThrow(
                () -> NotFound.STOCK_DOCUMENT.error(documentId));
        Instant now = Instant.now();
        document.complete(now);

        for (StockDocumentLine line : document.getLines()) {
            requireAvailable(document, line);
            ledger.record(movementFor(document, line));
        }
        // Остаток и статус пересчитает триггер журнала, но узнать об этом
        // площадке неоткуда: отметку ставим здесь, где движение и рождается.
        partChanges.changed(document.getLines().stream()
                .map(StockDocumentLine::getPartId)
                .toList());

        if (document.getDocType() == StockDocument.DocumentType.INTAKE) {
            history.record(document.getId(), StockDocumentHistory.Kind.POSTED);
        }
        return documents.saveAndFlush(document);
    }

    /**
     * Отменяет черновик. Выполненный сначала возвращают в черновик
     * «Отменить проводку» ({@link #unpost}). Повтор — не ошибка и не вторая
     * запись в истории.
     */
    @Transactional
    public StockDocument cancel(Long documentId) {
        StockDocument document = documents.findByIdForUpdate(documentId).orElseThrow(
                () -> NotFound.STOCK_DOCUMENT.error(documentId));
        if (document.getStatus() == StockDocument.DocumentStatus.CANCELLED) {
            return document;
        }
        document.cancel();
        if (document.getDocType() == StockDocument.DocumentType.INTAKE) {
            history.record(document.getId(), StockDocumentHistory.Kind.CANCELLED);
        }
        return documents.saveAndFlush(document);
    }

    /**
     * «Провести документ» с экрана поступления (задача 0360): повтор нажатия
     * отдаёт уже выполненный документ, а не отказ — человек нажал второй раз
     * потому, что первое не показало результата.
     */
    @Transactional
    public StockDocument post(Long documentId) {
        StockDocument document = documents.findByIdForUpdate(documentId).orElseThrow(
                () -> NotFound.STOCK_DOCUMENT.error(documentId));
        if (document.getStatus() == StockDocument.DocumentStatus.DONE && !document.isDeleted()) {
            return document;
        }
        return complete(documentId);
    }

    /**
     * Добавляет позицию в черновик поступления и пишет «Добавлена позиция N»
     * (задача 0360). Документ — под блокировкой: проведение, идущее в ту же
     * минуту, либо увидит строку целиком, либо откажет ей словами.
     */
    @Transactional
    public StockDocument addLine(Long documentId, Long partId, BigDecimal qty, Long cellId) {
        StockDocument document = documents.findByIdForUpdate(documentId).orElseThrow(
                () -> NotFound.STOCK_DOCUMENT.error(documentId));
        document.addLine(partId, qty, cellId);
        StockDocument saved = save(document);
        history.lineAdded(documentId, partId);
        return saved;
    }

    /**
     * Поступление, в которое можно добавить позицию: существует, это поступление
     * и оно черновик. Проверяется до заведения карточки — иначе отказ пришёл
     * бы после неё, и слова назвали бы не ту причину.
     */
    @Transactional(readOnly = true)
    public StockDocument findDraftIntake(Long documentId) {
        StockDocument document = requireIntake(documentId);
        if (!document.isDraft()) {
            throw new IllegalStateException(document.getStatus() == StockDocument.DocumentStatus.DONE
                    ? "Поступление уже выполнено: позиции добавляют в черновик — сначала «Отменить проводку»"
                    : "Поступление отменено: позиции в него не добавляют");
        }
        return document;
    }

    /** Поступление по номеру строки; другой вид документа — «не найдено», а не чужой документ. */
    @Transactional(readOnly = true)
    public StockDocument requireIntake(Long documentId) {
        StockDocument document = documents.findById(documentId).orElseThrow(
                () -> NotFound.INCOME.error(documentId));
        if (document.getDocType() != StockDocument.DocumentType.INTAKE) {
            throw NotFound.INCOME.error(documentId);
        }
        return document;
    }

    /** Стоит ли позиция строкой в документе — повтор «Добавить» узнаётся по ней. */
    @Transactional(readOnly = true)
    public boolean hasLine(Long documentId, Long partId) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM stock_document_line WHERE document_id = ? AND part_id = ?""",
                Integer.class, documentId, partId);
        return found != null && found > 0;
    }

    /**
     * «Отменить проводку» (задача 0360): выполненное поступление снова
     * черновик, приход снят встречным движением {@code INTAKE} со ссылкой
     * {@link StockMovement#DOCUMENT_UNPOSTED}. Исходные движения остаются
     * в журнале — он неизменяем.
     *
     * <p><b>Отмена целиком или никак.</b> Снимается только свободное, и это
     * одна инструкция ({@link StockLedger#reverse}); снялось меньше строки
     * документа — значит деталь продана, отложена, перевезена или списана,
     * и вся операция откатывается отказом словами. Умолчание задачи
     * (раздел «Решение сборщика»): проданную позицию не снимаем и в минус
     * не уводим — «Позиция уже продана — отмена проводки невозможна».
     * Ориентир в этом месте не видели.
     *
     * <p>Повтор нажатия: документ уже черновик и последнее в его истории —
     * «Проводка отменена» — отдаётся как есть, а не отказом.
     */
    @Transactional
    public StockDocument unpost(Long documentId) {
        StockDocument document = documents.findByIdForUpdate(documentId).orElseThrow(
                () -> NotFound.STOCK_DOCUMENT.error(documentId));
        if (document.getDocType() != StockDocument.DocumentType.INTAKE) {
            throw new IllegalStateException(
                    "Отменить проводку можно у поступления; перемещение и списание удаляют");
        }
        if (document.isDraft() && history.lastIs(documentId, StockDocumentHistory.Kind.UNPOSTED)) {
            return document;
        }
        document.unpost();
        for (StockDocumentLine line : document.getLines()) {
            BigDecimal taken = ledger.reverse(line.getPartId(), MovementType.INTAKE, line.getQty(),
                    document.getWarehouseId(), null, document.getId(),
                    StockMovement.DOCUMENT_UNPOSTED, "Отмена проводки");
            if (taken.compareTo(line.getQty()) < 0) {
                throw new IllegalStateException(unpostRefusal(document, line));
            }
        }
        history.record(documentId, StockDocumentHistory.Kind.UNPOSTED);
        return documents.saveAndFlush(document);
    }

    /**
     * Почему проводку не отменить — словами и с названием позиции: в документе
     * их десятки, и человек должен понять, на какой споткнулся.
     */
    private String unpostRefusal(StockDocument document, StockDocumentLine line) {
        String part = naming.part(line.getPartId());
        // «Продана» — только если продана после этой проводки и не вернулась:
        // продажа, случившаяся до неё (продали, вернули, приняли заново), к нехватке
        // отношения не имеет, и такой отказ назвал бы не ту причину. Граница —
        // номер движения прихода этой проводки, а не время: время документа
        // ставят часы приложения, время движений — часы базы.
        BigDecimal soldSince = jdbc.queryForObject("""
                SELECT coalesce(sum(m.qty_delta), 0) FROM stock_movement m
                 WHERE m.part_id = ? AND m.movement_type IN ('SALE', 'RETURN')
                   AND m.id > (SELECT coalesce(max(i.id), 0) FROM stock_movement i
                                WHERE i.part_id = m.part_id AND i.document_id = ?
                                  AND i.movement_type = 'INTAKE' AND i.qty_delta > 0)""",
                BigDecimal.class, line.getPartId(), document.getId());
        if (soldSince != null && soldSince.signum() < 0) {
            return part + ": позиция уже продана — отмена проводки невозможна";
        }
        BigDecimal reserved = jdbc.query("""
                        SELECT qty_reserved FROM part_stock WHERE part_id = ? AND warehouse_id = ?""",
                (rs, i) -> rs.getBigDecimal(1), line.getPartId(), document.getWarehouseId())
                .stream().findFirst().orElse(BigDecimal.ZERO);
        if (reserved.signum() > 0) {
            return part + ": позиция отложена покупателю — отмена проводки невозможна, "
                    + "сначала снимите резерв";
        }
        String warehouse = naming.warehouse(document.getWarehouseId());
        return part + ": позиции нет на складе " + warehouse
                + " в том количестве, что пришло, — её перевезли или списали; "
                + "отмена проводки невозможна";
    }

    /**
     * Удаляет выполненное поступление, списание или перемещение (задача 0367).
     *
     * <p>Решение владельца 23.10.2026 (tasks/0367, «Ответ владельца — 23 октября
     * 2026», пп. 1–5): удаление пишет встречное движение — остаток возвращается
     * как до документа, — исходное движение остаётся в журнале, документ
     * помечается «Удалён» (статус остаётся «Выполнен»). Снимается только
     * свободное: проданное и отложенное остаётся на складе, и сколько не снято,
     * называет ответ ({@link DeletionOutcome#notTaken}). «Отменить проводку»
     * (задача 0360) — другая операция, и здесь её нет.
     *
     * <p><b>Повтор — не второе удаление и не отказ.</b> Строка документа берётся
     * под блокировкой ({@code FOR UPDATE}): два одновременных нажатия встают
     * в очередь, второе видит отметку первого и получает тот же ответ, что
     * и последовательный повтор, — итог первого удаления с {@code alreadyDeleted}.
     * Встречное движение при этом одно. Ответ ошибкой на сделанное означал бы
     * красное у человека, чей документ как раз удалён.
     *
     * <p>Право проверяется в одном месте — {@link PostedDocumentDeletion}.
     */
    @Transactional
    public DeletionOutcome delete(Long documentId) {
        PostedDocumentDeletion.require();
        StockDocument document = documents.findByIdForUpdate(documentId).orElseThrow(
                () -> NotFound.STOCK_DOCUMENT.error(documentId));
        if (document.isDeleted()) {
            return outcomeOf(document, true);
        }
        document.requireDeletable();

        for (StockDocumentLine line : document.getLines()) {
            switch (document.getDocType()) {
                // Пришедшее снимается с того склада, куда пришло.
                case INTAKE -> ledger.reverse(line.getPartId(), MovementType.INTAKE, line.getQty(),
                        document.getWarehouseId(), null, document.getId());
                // Списанное возвращается целиком: оно ушло со склада, и
                // вернуть его на полку ничто не мешает.
                case WRITE_OFF -> ledger.reverse(line.getPartId(), MovementType.WRITE_OFF,
                        line.getQty(), null, document.getWarehouseId(), document.getId());
                // Увезённое едет обратно, сколько свободно на складе-получателе.
                case MOVE -> ledger.reverse(line.getPartId(), MovementType.MOVE, line.getQty(),
                        document.getToWarehouseId(), document.getWarehouseId(), document.getId());
                default -> throw new IllegalStateException("Этот документ не удаляется");
            }
        }

        // До микросекунд, как хранит timestamptz: иначе первый ответ несёт
        // наносекунды часов JVM (на Linux они есть), а повтор — момент из базы,
        // и «тот же итог» разошёлся бы в последних знаках. Поймано CI, на macOS
        // часы микросекундные и разницы не видно.
        document.markDeleted(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                ru.partsflow.platform.security.CurrentUser.memberId());
        documents.saveAndFlush(document);
        return outcomeOf(document, false);
    }

    /**
     * Итог удаления, собранный по журналу, а не по памяти этого вызова: так
     * повтор (и одновременный, дождавшийся блокировки) отвечает тем же, что
     * первое удаление, — снятое считается суммой встречных движений документа.
     */
    private DeletionOutcome outcomeOf(StockDocument document, boolean alreadyDeleted) {
        List<NotTaken> notTaken = jdbc.query("""
                        SELECT p.id, p.number, p.public_code, p.title,
                               l.wanted,
                               coalesce((SELECT sum(abs(m.qty_delta)) FROM stock_movement m
                                          WHERE m.document_id = ? AND m.ref_type = ?
                                            AND m.part_id = p.id), 0) AS taken
                          FROM (SELECT part_id, sum(qty) AS wanted, min(id) AS first_line
                                  FROM stock_document_line WHERE document_id = ?
                                 GROUP BY part_id) l
                          JOIN part p ON p.id = l.part_id
                         ORDER BY l.first_line""",
                        (rs, i) -> new NotTaken(rs.getLong("id"), rs.getLong("number"),
                                rs.getString("public_code"), rs.getString("title"),
                                rs.getBigDecimal("wanted"), rs.getBigDecimal("taken")),
                        document.getId(), StockMovement.DOCUMENT_DELETED, document.getId())
                .stream()
                .filter(row -> row.taken().compareTo(row.wanted()) < 0)
                .toList();
        // Кто удалил — подписью (задача 0373): «уже удалено (Сергей)» на разборке
        // с двумя Сергеями не говорит, к кому идти.
        String deletedBy = jdbc.query(
                "SELECT " + MemberSignature.sql("m") + " FROM tenant_member m WHERE m.id = ?",
                (rs, i) -> rs.getString(1), document.getDeletedBy()).stream().findFirst().orElse(null);
        return new DeletionOutcome(document.getId(), document.getNumber(),
                document.getDocType().name(), document.getDeletedAt(), deletedBy,
                alreadyDeleted, notTaken);
    }

    /**
     * @param alreadyDeleted документ был удалён раньше (повтор нажатия
     *                       или второй сотрудник): встречных движений этот
     *                       вызов не писал, итог — первого удаления
     * @param notTaken       что осталось на складе, потому что продано
     *                       или отложено; пусто — снято всё
     */
    public record DeletionOutcome(Long documentId, Long number, String docType,
                                  Instant deletedAt, String deletedBy,
                                  boolean alreadyDeleted, List<NotTaken> notTaken) {
    }

    /**
     * Позиция, снятая не полностью.
     *
     * @param number порядковый номер позиции — им её называют вслух
     * @param wanted сколько было в документе
     * @param taken  сколько снято встречным движением
     */
    public record NotTaken(Long partId, Long number, String publicCode, String title,
                           BigDecimal wanted, BigDecimal taken) {
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

}
