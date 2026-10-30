package ru.partsflow.intake;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.inventory.StockDocument;
import ru.partsflow.inventory.StockDocumentService;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.Permission;

import java.math.BigDecimal;

/**
 * Раздел «Поступления» (задача 0360): документ поступления с черновиком.
 *
 * <p>Решение владельца 19.10.2026 «Всё как у bazon»: на компьютере поступление
 * создают черновиком, в течение смены набирают в него позиции и в конце
 * нажимают «Провести документ» — только тогда остаток растёт. Проведённый можно
 * «Отменить проводку», черновик — «Отменить поступление». Партия с телефона
 * по-прежнему приходуется одним действием ({@code POST /api/intake/receipts}):
 * сервер оформляет её таким же документом и сразу проводит.
 *
 * <p>Каждое изменяющее действие отвечает документом целиком: экран после него
 * показывает и позиции, и историю, и второй запрос был бы ожиданием ровно
 * тогда, когда человек набирает следующую позицию.
 *
 * <p>Право — «Принимать товар» ({@code intake.receive}), как у приёмки:
 * это та же операция, разложенная на шаги.
 */
@RestController
@RequestMapping("/api/intake/incomes")
public class IncomeController {

    private static final String RECEIVES = "hasAuthority('intake.receive')";

    private final IntakeService intake;
    private final IncomeService incomes;
    private final StockDocumentService documents;

    public IncomeController(IntakeService intake, IncomeService incomes,
                            StockDocumentService documents) {
        this.intake = intake;
        this.incomes = incomes;
        this.documents = documents;
    }

    /** Список по вкладке: drafts, done, cancelled, all — страницей от номера {@code before}. */
    @GetMapping
    public IncomeService.Page list(@RequestParam(defaultValue = "all") String tab,
                                   @RequestParam(required = false) Long before) {
        IncomeService.Page page = incomes.list(tab, before);
        return seesMoney() ? page : page.withoutMoney();
    }

    @GetMapping("/{id}")
    public IncomeService.Income income(@PathVariable Long id) {
        return shown(id);
    }

    /**
     * Закупочная сумма и цены строк — право «Смотреть закупочную цену и себестоимость»
     * (решение владельца 30.10.2026: «право отдельно, через список того, что разрешено»).
     * У владельца — всегда. Проверяется на каждом ответе документом, а не только
     * на чтении: «Провести» и «+ Добавить» тоже отвечают документом целиком.
     */
    private static boolean seesMoney() {
        return CurrentUser.require().can(Permission.PART_MONEY_VIEW);
    }

    private IncomeService.Income shown(long id) {
        IncomeService.Income income = incomes.of(id);
        return seesMoney() ? income : income.withoutMoney();
    }

    /** «Создать поступление»: 201 с черновиком; повтор по ключу формы — тот же черновик. */
    @PostMapping
    @PreAuthorize(RECEIVES)
    public ResponseEntity<IncomeService.Income> create(@RequestBody CreateRequest request) {
        StockDocument draft;
        try {
            draft = intake.createDraft(request.warehouseId(), request.supplyId(), request.note(),
                    CurrentUser.memberId(), request.requestId());
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // Одновременный повтор: первый запрос успел завести черновик, второй
            // упёрся в ключ. Черновик есть, и человеку нужен он, а не отказ.
            draft = intake.draftAfterConflict(request.requestId());
            if (draft == null) {
                throw conflict;
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(shown(draft.getId()));
    }

    /** «+ Добавить» → позиция в черновик: карточка без остатка и строка документа. */
    @PostMapping("/{id}/items")
    @PreAuthorize(RECEIVES)
    public ResponseEntity<IncomeService.Income> addItem(@PathVariable Long id,
                                                        @Valid @RequestBody ItemRequest request) {
        try {
            intake.addToDraft(id, request.toService(), request.donorId(), CurrentUser.memberId(),
                    request.requestId());
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            if (intake.draftLineAfterConflict(id, request.requestId()) == null) {
                throw conflict;
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(shown(id));
    }

    /** «Провести документ»: остаток растёт; повтор отдаёт выполненный. */
    @PostMapping("/{id}/post")
    @PreAuthorize(RECEIVES)
    public IncomeService.Income post(@PathVariable Long id) {
        documents.requireIntake(id);
        documents.post(id);
        return shown(id);
    }

    /** «Отменить проводку»: документ снова черновик, приход снят; проданное — отказ словами. */
    @PostMapping("/{id}/unpost")
    @PreAuthorize(RECEIVES)
    public IncomeService.Income unpost(@PathVariable Long id) {
        documents.requireIntake(id);
        documents.unpost(id);
        return shown(id);
    }

    /** «Отменить поступление»: черновик уходит во вкладку «Отменённые». */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(RECEIVES)
    public IncomeService.Income cancel(@PathVariable Long id) {
        documents.requireIntake(id);
        documents.cancel(id);
        return shown(id);
    }

    /**
     * @param warehouseId склад оприходования — обязателен, отказ словами
     * @param supplyId    поставка; пусто — «- Не выбрана -»
     * @param requestId   ключ формы: заводится при её открытии и не меняется при повторе
     */
    public record CreateRequest(Long warehouseId, Long supplyId, String note, String requestId) {
    }

    /**
     * Позиция черновика: наименование, количество, цены, заметка, состояние.
     * Поля формы «Б/У товары» целиком — задача 0362; здесь то, без чего позиции
     * в документе нет.
     */
    public record ItemRequest(String rawName,
                              BigDecimal quantity,
                              BigDecimal price,
                              BigDecimal costPrice,
                              String condition,
                              String oemNumber,
                              String note,
                              Long donorId,
                              String requestId) {

        IntakeService.ItemRequest toService() {
            return new IntakeService.ItemRequest(rawName,
                    quantity == null ? BigDecimal.ONE : quantity,
                    price, costPrice, null, null, null, null,
                    IntakeService.conditionOf(condition), null, null, oemNumber, null, note);
        }
    }
}
