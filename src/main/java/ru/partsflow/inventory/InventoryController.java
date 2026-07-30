package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * REST инвентаризации.
 *
 * <p>Пересчёт вносится по одной позиции, а не пачкой — в отличие от приёмки.
 * Причина в способе работы: кладовщик идёт по полкам и сканирует штрихкоды,
 * между позициями проходят минуты, и потерять час работы из-за одного
 * неудачного запроса нельзя. Момент подсчёта каждой позиции при этом важен
 * сам по себе: по нему считается расхождение.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionView> open(@Valid @RequestBody OpenRequest request) {
        InventorySession session = inventory.open(request.warehouseId(), request.authorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionView.of(session));
    }

    /** Фактическое количество по позиции. Ноль — это недостача, а не пропуск. */
    @PostMapping("/sessions/{id}/counts")
    public SessionView count(@PathVariable Long id, @Valid @RequestBody CountRequest request) {
        return SessionView.of(inventory.count(
                id, request.partId(), request.qty(), request.authorId()));
    }

    @PostMapping("/sessions/{id}/finish")
    public SessionView finishCounting(@PathVariable Long id) {
        return SessionView.of(inventory.finishCounting(id));
    }

    /**
     * Расхождения. Считаются по журналу, поэтому доступны и до, и после
     * проведения: кладовщику можно показать итог, дать пересчитать спорные
     * полки и посмотреть снова.
     */
    @GetMapping("/sessions/{id}/discrepancies")
    public List<InventoryService.Discrepancy> discrepancies(@PathVariable Long id) {
        return inventory.discrepancies(id);
    }

    @PostMapping("/sessions/{id}/apply")
    public AppliedView apply(@PathVariable Long id) {
        return new AppliedView(id, inventory.apply(id));
    }

    @PostMapping("/sessions/{id}/cancel")
    public SessionView cancel(@PathVariable Long id) {
        return SessionView.of(inventory.cancel(id));
    }

    public record OpenRequest(@NotNull Long warehouseId, Long authorId) {
    }

    public record CountRequest(@NotNull Long partId,
                               @NotNull @PositiveOrZero BigDecimal qty,
                               Long authorId) {
    }

    public record SessionView(Long id, Long warehouseId, InventorySession.SessionStatus status,
                              Instant startedAt, Instant appliedAt,
                              int lines, long counted) {

        static SessionView of(InventorySession session) {
            return new SessionView(session.getId(), session.getWarehouseId(), session.getStatus(),
                    session.getStartedAt(), session.getAppliedAt(),
                    session.getLines().size(), session.countedLines().size());
        }
    }

    /** Сколько позиций скорректировано: сошедшиеся движений не порождают. */
    public record AppliedView(Long sessionId, int adjusted) {
    }
}
