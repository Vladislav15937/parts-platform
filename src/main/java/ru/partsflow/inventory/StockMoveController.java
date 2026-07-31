package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public StockMoveController(StockDocumentService documents) {
        this.documents = documents;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','STOREKEEPER')")
    public ResponseEntity<MoveView> move(@Valid @RequestBody MoveRequest request) {
        StockDocument document = StockDocument.move(
                request.fromWarehouseId(), request.toWarehouseId());
        document.setCreatedBy(CurrentUser.memberId());
        document.setNote(request.note());

        for (MoveItem item : request.items()) {
            document.addLine(item.partId(), item.quantity(), item.toCellId());
        }

        StockDocument saved = documents.save(document);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MoveView.of(documents.complete(saved.getId())));
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

    public record MoveView(Long id, Long number, Long fromWarehouseId, Long toWarehouseId,
                           String status, Instant completedAt, int items) {

        static MoveView of(StockDocument document) {
            return new MoveView(document.getId(), document.getNumber(),
                    document.getWarehouseId(), document.getToWarehouseId(),
                    document.getStatus().name(), document.getCompletedAt(),
                    document.getLines().size());
        }
    }
}
