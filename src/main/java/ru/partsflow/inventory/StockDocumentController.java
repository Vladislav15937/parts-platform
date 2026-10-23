package ru.partsflow.inventory;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Удаление выполненного складского документа (задача 0367).
 *
 * <p>Кнопка стоит в «Движении товара» карточки позиции (поступление, списание,
 * перемещение) и в журнале «Перевозки». Своего списка поступлений и списаний
 * в системе нет, и под эту задачу он не заводится: документ находят по позиции,
 * которую он двигал.
 *
 * <p>Ролей здесь нет намеренно: право одно и лежит в одном месте —
 * {@link PostedDocumentDeletion}, — его спрашивает сервис. {@code @PreAuthorize}
 * рядом был бы второй копией, которую задача 0044 не нашла бы.
 */
@RestController
@RequestMapping("/api/stock/documents")
public class StockDocumentController {

    private final StockDocumentService documents;

    public StockDocumentController(StockDocumentService documents) {
        this.documents = documents;
    }

    /**
     * 200 с итогом и при первом удалении, и при повторе ({@code alreadyDeleted}):
     * повтор — не ошибка, документ удалён. 409 — документ не из удаляемых
     * (черновик, отменённый, возврат, пересчёт), 403 — нет права.
     */
    @DeleteMapping("/{id}")
    public StockDocumentService.DeletionOutcome delete(@PathVariable Long id) {
        return documents.delete(id);
    }
}
