package ru.partsflow.inventory;

import org.springframework.security.access.AccessDeniedException;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.Permission;
import ru.partsflow.platform.security.TenantPrincipal;

/**
 * Право «удалять выполненные складские документы» (задача 0367).
 *
 * <p><b>Одно место, и это главное в классе.</b> Решение владельца продукта
 * 23.10.2026 (tasks/0367, «Кто удаляет»): удаляет тот, у кого есть право
 * на это действие, а владелец — всегда; права выдаются на каждого сотрудника
 * по каждому действию. С задачи 0044 это право
 * {@link Permission#STOCK_DOCUMENT_DELETE_DONE}: при переносе его не получила
 * ни одна роль (до 0044 удалял только владелец), раздаёт его владелец сам —
 * ролью или поверх роли. Владелец и администратор сервиса могут всегда
 * ({@link TenantPrincipal#can}).
 *
 * <p>Отказ сервиса ({@link #require()}), кнопка в «Движении товара» карточки
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
        return CurrentUser.get()
                .map(principal -> principal.can(Permission.STOCK_DOCUMENT_DELETE_DONE))
                .orElse(false);
    }

    /** Отказ 403 тому, у кого права нет. */
    public static void require() {
        if (!allowed()) {
            throw new AccessDeniedException(
                    "Нет права «%s»: его даёт владелец на экране «Сотрудники»"
                            .formatted(Permission.STOCK_DOCUMENT_DELETE_DONE.title()));
        }
    }
}
