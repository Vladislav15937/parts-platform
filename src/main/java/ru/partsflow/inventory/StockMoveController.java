package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Перемещение деталей между складами и ячейками.
 *
 * <p>Документ и движения существовали давно, а снаружи операция была
 * недоступна: клиент с филиалом не мог перевезти деталь, не залезая в базу.
 *
 * <p><b>Создаётся и проводится одним запросом.</b> Черновик перемещения
 * не нужен: в отличие от приёмки, здесь нечего обходить со списком — деталь
 * уже посчитана, её просто переносят. Промежуточное состояние означало бы
 * деталь, которой нет ни на одном складе.
 *
 * <p>Роль — владелец, менеджер или кладовщик: перевозит товар он.
 */
@RestController
@RequestMapping("/api/stock/moves")
public class StockMoveController {

    private final StockDocumentService documents;
    private final MoveJournalService journal;

    public StockMoveController(StockDocumentService documents, MoveJournalService journal) {
        this.documents = documents;
        this.journal = journal;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','STOREKEEPER')")
    public ResponseEntity<MoveView> move(@Valid @RequestBody MoveRequest request) {
        StockDocumentService.MoveOutcome outcome = documents.moveBatch(
                request.fromWarehouseId(), request.toWarehouseId(),
                request.items(), request.note(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(MoveView.of(outcome));
    }

    /** Журнал перевозок: документы, свежие сверху. Состав — по нажатию на строку. */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','STOREKEEPER')")
    public List<MoveJournalService.MoveDocument> journal() {
        return journal.list();
    }

    @GetMapping("/{id}/lines")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','STOREKEEPER')")
    public List<MoveJournalService.MoveLine> journalLines(@PathVariable Long id) {
        return journal.lines(id);
    }

    /**
     * @param toCellId ячейка на складе-приёмнике. Пусто — деталь лежит
     *                 на складе без адреса, и найти её можно только глазами
     */
    public record MoveItem(@NotNull Long partId,
                           @NotNull @Positive BigDecimal quantity,
                           Long toCellId) {
    }

    public record MoveRequest(@NotNull Long fromWarehouseId,
                              @NotNull Long toWarehouseId,
                              @NotEmpty List<MoveItem> items,
                              String note) {
    }

    /**
     * @param notMoved позиции, которые не поехали, потому что часть остатка
     *                 обещана покупателю; для успешной перевозки — пустой список
     */
    public record MoveView(Long id, Long number, Long fromWarehouseId, Long toWarehouseId,
                           String status, Instant completedAt, int items,
                           List<SkippedItemView> notMoved) {

        static MoveView of(StockDocumentService.MoveOutcome outcome) {
            StockDocument document = outcome.document();
            return new MoveView(document.getId(), document.getNumber(),
                    document.getWarehouseId(), document.getToWarehouseId(),
                    document.getStatus().name(), document.getCompletedAt(),
                    document.getLines().size(),
                    outcome.skipped().stream()
                            .map(item -> new SkippedItemView(item.partId(), item.publicCode()))
                            .toList());
        }
    }

    public record SkippedItemView(Long partId, String publicCode) {
    }
}
