package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <p><b>Обход и сведение расхождений разведены по ролям.</b> Считать может
 * любой, кто работает руками, — от этого ничего не списывается. А проведение
 * превращает недостачу в убыток, и решение это принимает тот, кто отвечает
 * за деньги: то же правило, по которому списывает владелец или менеджер,
 * а не кладовщик.
 *
 * <p>Экран это делал с самого начала — блок «Свести расхождения» показан
 * владельцу и менеджеру, — а сервер не проверял ничего: правило жило
 * в одном интерфейсе из двух. Продавец, зашедший запросом, открывал пересчёт,
 * завершал его и проводил, то есть списывал недостачу по всему складу.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    /** Обход полок: считает тот, кто работает руками. */
    private static final String COUNTS = "hasAnyRole('OWNER','MANAGER','STOREKEEPER','SELLER')";

    /** Сведение расхождений: недостача превращается в убыток. */
    private static final String RECONCILES = "hasAnyRole('OWNER','MANAGER')";

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PreAuthorize(COUNTS)
    @PostMapping("/sessions")
    public ResponseEntity<SessionView> open(@Valid @RequestBody OpenRequest request) {
        InventorySession session = inventory.open(request.warehouseId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionView.of(session));
    }

    /** Фактическое количество по позиции. Ноль — это недостача, а не пропуск. */
    @PreAuthorize(COUNTS)
    @PostMapping("/sessions/{id}/counts")
    public SessionView count(@PathVariable Long id, @Valid @RequestBody CountRequest request) {
        return SessionView.of(inventory.count(
                id, request.partId(), request.qty(), CurrentUser.memberId(),
                request.countedAgoMs() == null ? null : Duration.ofMillis(request.countedAgoMs())));
    }

    @PreAuthorize(RECONCILES)
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
    @PreAuthorize(COUNTS)
    @GetMapping("/sessions/{id}/lines")
    public List<InventoryService.Line> lines(@PathVariable Long id) {
        return inventory.lines(id);
    }

    /** Открытая инвентаризация склада: телефон подхватывает начатый обход. */
    @PreAuthorize(COUNTS)
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
    @PreAuthorize(RECONCILES)
    @GetMapping("/sessions/{id}/discrepancies")
    public List<InventoryService.DiscrepancyLine> discrepancies(@PathVariable Long id) {
        return inventory.discrepancies(id);
    }

    @PreAuthorize(RECONCILES)
    @PostMapping("/sessions/{id}/apply")
    public AppliedView apply(@PathVariable Long id) {
        InventoryService.Applied applied = inventory.apply(id);
        return new AppliedView(id, applied.adjusted(), applied.blocked());
    }

    @PreAuthorize(RECONCILES)
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

    /**
     * @param adjusted сколько позиций скорректировано: сошедшиеся движений
     *                 не порождают
     * @param blocked  что не проведено и почему. Недостачу по детали,
     *                 обещанной покупателю, списать нельзя — остаток уйдёт
     *                 ниже резерва. Строка остаётся непроведённой, сессия
     *                 не закрывается, и повтор после снятия резерва допишет
     *                 только её
     */
    public record AppliedView(Long sessionId, int adjusted, java.util.List<String> blocked) {
    }
}
