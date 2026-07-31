package ru.partsflow.inventory;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Резерв остатка на складе.
 *
 * <p><b>Почему через функции БД, а не через сущность.</b> Резерв — это место,
 * где две одновременные продажи одной детали дают ошибку, которую видно только
 * на складе: приехали двое, деталь одна. Прочитать остаток в приложение,
 * вычесть и записать обратно нельзя — между чтением и записью встанет второй
 * продавец.
 *
 * <p>Функции {@code reserve_stock} и {@code release_stock} проверяют и изменяют
 * одной инструкцией: условие «хватает свободного» стоит в {@code WHERE}.
 * Postgres сериализует конкурирующие изменения одной строки, поэтому второй
 * транзакции достанется уже увеличенный резерв, а не устаревший снимок.
 */
@Repository
public class StockReservationRepository {

    private final EntityManager entityManager;

    public StockReservationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Ставит резерв.
     *
     * @throws InsufficientStockException если свободного остатка не хватает —
     *         в том числе когда его перехватил другой продавец секунду назад
     */
    public void reserve(Long partId, Long warehouseId, BigDecimal quantity) {
        call("reserve_stock", partId, warehouseId, quantity,
                "Недостаточно свободного остатка: деталь %d, склад %d, требуется %s"
                        .formatted(partId, warehouseId, quantity));
    }

    /** Снимает резерв: при отмене сделки и перед списанием на выдаче. */
    public void release(Long partId, Long warehouseId, BigDecimal quantity) {
        call("release_stock", partId, warehouseId, quantity,
                "Нечего снимать с резерва: деталь %d, склад %d, требуется %s"
                        .formatted(partId, warehouseId, quantity));
    }

    private void call(String function, Long partId, Long warehouseId,
                      BigDecimal quantity, String message) {
        try {
            // Имя схемы не подставляется: соединение уже указывает на схему
            // арендатора через search_path, выставленный TenantConnectionProvider.
            entityManager.createNativeQuery(
                            "SELECT " + function + "(:part, :warehouse, :qty)")
                    .setParameter("part", partId)
                    .setParameter("warehouse", warehouseId)
                    .setParameter("qty", quantity)
                    .getSingleResult();
        } catch (RuntimeException e) {
            throw new InsufficientStockException(message, e);
        }
    }

    /** Свободного остатка не хватило. Отдельный тип: обработка у него своя. */
    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
