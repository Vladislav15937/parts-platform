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

import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Duration;
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
        InventorySession session = inventory.open(request.warehouseId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionView.of(session));
    }

    /** Фактическое количество по позиции. Ноль — это недостача, а не пропуск. */
    @PostMapping("/sessions/{id}/counts")
    public SessionView count(@PathVariable Long id, @Valid @RequestBody CountRequest request) {
        return SessionView.of(inventory.count(
                id, request.partId(), request.qty(), CurrentUser.memberId(),
                request.countedAgoMs() == null ? null : Duration.ofMillis(request.countedAgoMs())));
    }

    @PostMapping("/sessions/{id}/finish")
    public SessionView finishCounting(@PathVariable Long id) {
        return SessionView.of(inventory.finishCounting(id));
    }

    /**
     * Лист обхода: строки сессии с наименованиями и ячейками.
     *
     * <p>Телефон забирает его целиком сразу после открытия сессии — пересчёт
     * идёт в ангаре без связи, и подгружать по мере обхода нечем.
     */
    @GetMapping("/sessions/{id}/lines")
    public List<InventoryService.Line> lines(@PathVariable Long id) {
        return inventory.lines(id);
    }

    /** Открытая инвентаризация склада: телефон подхватывает начатый обход. */
    @GetMapping("/sessions/open")
    public ResponseEntity<SessionView> openSession(@RequestParam Long warehouseId) {
        return inventory.openSessionOf(warehouseId)
                .map(session -> ResponseEntity.ok(SessionView.of(session)))
                .orElseGet(() -> ResponseEntity.noContent().build());
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

    public record OpenRequest(@NotNull Long warehouseId) {
    }

    /**
     * @param countedAgoMs сколько миллисекунд назад посчитали полку. Телефон
     *                     обязан его слать: между подсчётом и приходом запроса
     *                     лежит офлайн-очередь, и по времени получения
     *                     расхождение считать нельзя. Именно давность, а не
     *                     момент: часы устройства врут смещением, и оно
     *                     в давности сокращается. Пусто — подсчёт только что
     */
    public record CountRequest(@NotNull Long partId,
                               @NotNull @PositiveOrZero BigDecimal qty,
                               @PositiveOrZero Long countedAgoMs) {
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
