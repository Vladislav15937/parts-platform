package ru.partsflow.inventory;

import org.springframework.security.access.AccessDeniedException;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.TenantPrincipal;

/**
 * Право «удалять выполненные складские документы» (задача 0367).
 *
 * <p><b>Одно место, и это главное в классе.</b> Решение владельца продукта
 * 23.10.2026 (tasks/0367, «Кто удаляет»): удаляет тот, у кого есть право
 * на это действие, а владелец — всегда; права выдаются на каждого сотрудника
 * по каждому действию. Выдавать права по действиям система пока не умеет —
 * это задача 0044, — поэтому сегодня набор прав у всех пуст, и удаляет
 * только владелец (и администратор сервиса, у которого права владельца,
 * {@link TenantPrincipal#actsAsOwner}). Это не другое правило, а то же самое
 * при пустом наборе прав.
 *
 * <p>Исполнитель 0044 меняет тело {@link #allowed()} — и больше ничего:
 * отказ сервиса ({@link #require()}), кнопка в «Движении товара» карточки
 * и в «Перевозках» (сервер отдаёт её признаком строки, своего списка ролей
 * у экрана нет) читают этот метод. Вторая копия проверки — {@code @PreAuthorize}
 * на контроллере или перечень ролей на экране — переехала бы при замене ролей
 * правами порознь, ровно как это грозило выгрузке таблицей до {@link TableExport}.
 */
public final class PostedDocumentDeletion {

    private PostedDocumentDeletion() {
    }

    /** Может ли вошедший удалить выполненный документ. */
    public static boolean allowed() {
        return CurrentUser.get().map(TenantPrincipal::actsAsOwner).orElse(false);
    }

    /** Отказ 403 тому, у кого права нет. */
    public static void require() {
        if (!allowed()) {
            throw new AccessDeniedException(
                    "Удалять выполненные складские документы может только владелец");
        }
    }
}
