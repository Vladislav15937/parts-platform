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

    /**
     * Список и карточка пересчёта — только смотреть. «Просмотру» это доступно
     * наравне с владельцем и менеджером: журнал склада ссылается на пересчёт
     * («Пересчёт №4»), и посмотреть, что тогда считали, — не то же самое,
     * что провести или отменить.
     */
    private static final String READS = "hasAnyRole('OWNER','MANAGER','VIEWER')";

    /**
     * Комментарий к пересчёту. «Просмотра» тут нет — роль называется
     * владельцу «только смотреть».
     *
     * <p><b>Кладовщика тут нет пока, и это не то, чего хотела задача.</b>
     * Задача 0020 называет роли дословно — «те же, что у „Завершить
     * подсчёт“, плюс кладовщик: пишет его тот, кто ходил», — и по существу
     * это верно: «83619 не найден» знает человек у полки, а не тот, кто
     * потом смотрит расхождения. Но поверхности у кладовщика нет ни одной:
     * журнал пересчётов ему не показывают ({@code HomeScreen}), а обход
     * полок с телефона поля комментария не имеет — где ему там стоять,
     * задача не описывает, и это решение владельца продукта, а не наше.
     *
     * <p>Право без поверхности выбрано худшим из двух: {@code
     * tools/endpoint-coverage.py} сверяет пути, а не роли, и висящее право
     * читается следующим как рабочий путь, покрытый экраном, — при том что
     * кладовщик не может даже прочитать написанное. Возвращать его сюда
     * надо вместе с экраном, одной веткой, чтобы право и его поверхность
     * доказывались вместе.
     */
    private static final String COMMENTS = "hasAnyRole('OWNER','MANAGER')";

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @PreAuthorize(COUNTS)
    @PostMapping("/sessions")
    public ResponseEntity<SessionView> open(@Valid @RequestBody OpenRequest request) {
        InventorySession session = inventory.open(
                request.warehouseId(), request.cellId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionView.of(session));
    }

    /**
     * «Найдено товаров: N» на форме открытия — до какой-либо сессии.
     *
     * <p>Считает тем же условием, что и {@link #open}: иначе счётчик и лист
     * обхода после открытия разойдутся, и кладовщик решит, что часть позиций
     * потерялась.
     */
    @PreAuthorize(COUNTS)
    @GetMapping("/count")
    public PositionCount count(@RequestParam Long warehouseId,
                               @RequestParam(required = false) Long cellId) {
        return new PositionCount(inventory.countPositions(warehouseId, cellId));
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

    /**
     * Список пересчётов для воронки владельца: «В работе», «Выполненные»,
     * «Отменённые» и «Все пересчёты» — до этого закрытый пересчёт нельзя
     * было найти вовсе, ни списком, ни по номеру.
     *
     * <p><b>Статусов принимается несколько.</b> Воронка «Выполненные» — это
     * и {@code COUNTED}, и {@code APPLIED}: нормально закрытый пересчёт всегда
     * проведён, и фильтр по одному статусу оставлял бы проведённый документ
     * вне всех воронок, кроме «Все пересчёты». Группировку задаёт экран
     * (`SESSION_FUNNEL` в `inventory.ts`), а сервер отбирает по набору —
     * иначе имена воронок пришлось бы знать обеим сторонам, и они разошлись
     * бы на первой правке.
     *
     * @param status статусы {@link InventorySession.SessionStatus} через
     *               повтор параметра либо пусто — все статусы
     */
    @PreAuthorize(READS)
    @GetMapping("/sessions")
    public InventoryService.SessionPage sessions(
            @RequestParam(required = false) List<String> status) {
        return inventory.listSessions(parseStatuses(status));
    }

    /** Одна сессия любого статуса — открывается нажатием на строку списка. */
    @PreAuthorize(READS)
    @GetMapping("/sessions/{id}")
    public InventoryService.SessionSummary session(@PathVariable Long id) {
        return inventory.sessionSummary(id);
    }

    /**
     * Комментарий к пересчёту — свободный текст, необязательный.
     *
     * <p>Правится, пока пересчёт не проведён и не отменён; закрытый отвечает
     * 409 со словами, а не пятисоткой. Пустое тело стирает комментарий
     * в {@code NULL} — «не заполнено» и «человек написал пусто» обязаны
     * отличаться.
     */
    @PreAuthorize(COMMENTS)
    @PostMapping("/sessions/{id}/note")
    public SessionView note(@PathVariable Long id, @RequestBody NoteRequest request) {
        return SessionView.of(inventory.changeNote(id, request.note()));
    }

    private List<InventorySession.SessionStatus> parseStatuses(List<String> statuses) {
        if (statuses == null) {
            return List.of();
        }
        return statuses.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(InventoryController::parseStatus)
                .distinct()
                .toList();
    }

    private static InventorySession.SessionStatus parseStatus(String status) {
        try {
            return InventorySession.SessionStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            // 400 со словами, а не 500: это ошибка вызывающего, и повторять
            // её вечно офлайн-очереди незачем.
            throw new IllegalArgumentException("Неизвестный статус пересчёта: " + status);
        }
    }

    /**
     * Открытая инвентаризация склада с той же выборкой: телефон подхватывает
     * начатый обход.
     *
     * <p>«С той же выборкой» проверяется по фактическому адресу строк сессии
     * ({@link InventoryService#openSessionOf}), а не по отдельному полю: своя
     * колонка под выбор потребовала бы миграции ради значения, которое и так
     * лежит в строках. На складе может быть открыта только одна сессия сразу
     * (см. {@link InventoryService#open}), так что несовпадение выборки просто
     * не отдаёт её — кладовщик увидит «инвентаризация не открыта» и откроет
     * новую своей ячейкой, где и разберётся с чужой.
     */
    @PreAuthorize(COUNTS)
    @GetMapping("/sessions/open")
    public ResponseEntity<SessionView> openSession(@RequestParam Long warehouseId,
                                                    @RequestParam(required = false) Long cellId) {
        return inventory.openSessionOf(warehouseId, cellId)
                .map(session -> ResponseEntity.ok(SessionView.of(session)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Код детали → позиция, по всему складу сессии, а не только по её выборке.
     *
     * <p>Нужен, чтобы отличить скан «деталь лежит в другой ячейке этого же
     * склада» (группа «С проблемами») от «код не найден на этом складе»:
     * лист обхода при выборке по ячейке содержит только её позиции, и без
     * этого списка сканер не отличил бы одно от другого. Отдаётся целиком —
     * так же, как лист обхода, — потому что сканирование обязано работать
     * без связи.
     */
    @PreAuthorize(COUNTS)
    @GetMapping("/sessions/{id}/codes")
    public List<InventoryService.WarehouseCode> codes(@PathVariable Long id) {
        return inventory.warehouseCodes(id);
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

    /**
     * @param cellId выборка при открытии. {@code null} — весь склад, как
     *               раньше. {@link InventoryService#NO_CELL} — «без адреса»
     *               (позиции без ячейки). Иначе — конкретная ячейка
     */
    public record OpenRequest(@NotNull Long warehouseId, Long cellId) {
    }

    /** Счётчик формы открытия: сколько позиций попадёт в лист при этой выборке. */
    public record PositionCount(long count) {
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

    /**
     * @param note комментарий человека или {@code null}. Едет и сюда, а не
     *             только в {@code SessionSummary}: сведения открывают двумя
     *             путями — строкой журнала и поиском открытой сессии
     *             по складу, — и поле, приезжающее лишь одним, показывало бы
     *             пустой комментарий там, где он написан
     */
    public record NoteRequest(String note) {
    }

    public record SessionView(Long id, Long warehouseId, InventorySession.SessionStatus status,
                              Instant startedAt, Instant appliedAt,
                              int lines, long counted, String note) {

        static SessionView of(InventorySession session) {
            return new SessionView(session.getId(), session.getWarehouseId(), session.getStatus(),
                    session.getStartedAt(), session.getAppliedAt(),
                    session.getLines().size(), session.countedLines().size(), session.getNote());
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
