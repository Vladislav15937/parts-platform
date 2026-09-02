package ru.partsflow.inventory;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * Резерв остатка на складе.
 *
 * <p><b>Проверка и изменение — одна инструкция, и это здесь главное.</b>
 * Резерв — то место, где две одновременные продажи одной детали дают ошибку,
 * которую видно только на складе: приехали двое, деталь одна. Прочитать
 * свободный остаток в приложение, вычесть и записать обратно нельзя — между
 * чтением и записью встанет второй продавец. Поэтому условие «хватает
 * свободного» стоит в {@code WHERE}, а ответ читается по числу изменённых
 * строк: ноль — не хватило. Postgres сериализует конкурирующие изменения
 * одной строки, поэтому второй транзакции достанется уже увеличенный резерв,
 * а не устаревший снимок.
 *
 * <p>До 3 августа 2026 те же два запроса лежали в функциях БД
 * {@code reserve_stock} и {@code release_stock}. Логика переехала сюда
 * по правилу «логика в Java, база хранит данные и связи»; свойство
 * единственной инструкции при этом сохранено дословно — оно и было
 * единственной причиной держать её в базе.
 *
 * <p>Перенос заодно сделал отказ различимым: прежний код заворачивал
 * в «недостаточно свободного остатка» любое исключение вызова функции,
 * включая обрыв соединения. Теперь нехватка — это ноль изменённых строк,
 * а всё остальное летит наружу как есть.
 *
 * <p>Инвариант «резерв не больше остатка» стережёт схема
 * ({@code part_stock_reserved_ck}), а сверка {@code v_reservation_discrepancy}
 * обязана оставаться пустой.
 *
 * <p><b>Запросы идут через {@code EntityManager}, а не через
 * {@code JdbcTemplate}, и это не вкусовщина.</b> Hibernate перед нативным
 * запросом сбрасывает отложенные записи сессии; {@code JdbcTemplate} идёт мимо
 * него и не сбрасывает ничего. Резерв же считается по {@code qty}, которое
 * ведёт триггер журнала движений: неотправленное движение означало бы решение
 * по остатку, которого база ещё не видела. Прежний код звал функцию БД тем же
 * {@code EntityManager}, и это поведение сохранено намеренно.
 */
@Repository
public class StockReservationRepository {

    private final EntityManager entityManager;
    private final PartChangeLog partChanges;
    private final StockNaming naming;

    public StockReservationRepository(EntityManager entityManager, PartChangeLog partChanges,
                                      StockNaming naming) {
        this.entityManager = entityManager;
        this.partChanges = partChanges;
        this.naming = naming;
    }

    /**
     * Ставит резерв.
     *
     * @throws InsufficientStockException если свободного остатка не хватает —
     *         в том числе когда его перехватил другой продавец секунду назад
     */
    public void reserve(Long partId, Long warehouseId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество для резерва должно быть больше нуля");
        }

        // Имя схемы не подставляется: соединение уже указывает на схему
        // арендатора через search_path, выставленный TenantConnectionProvider.
        // Отсюда требование к вызывающему быть в транзакции.
        int updated = entityManager.createNativeQuery("""
                        UPDATE part_stock
                           SET qty_reserved = qty_reserved + :qty,
                               updated_at = now()
                         WHERE part_id = :part
                           AND warehouse_id = :warehouse
                           AND qty - qty_reserved >= :qty""")
                .setParameter("part", partId)
                .setParameter("warehouse", warehouseId)
                .setParameter("qty", quantity)
                .executeUpdate();

        if (updated == 0) {
            throw new InsufficientStockException(
                    // Продавец стоит перед покупателем: «деталь 6, склад 1»
                    // не говорит ему ни что кончилось, ни где смотреть.
                    "Недостаточно свободного остатка на складе %s: нужно %s — %s"
                            .formatted(naming.warehouse(warehouseId),
                                    quantity.stripTrailingZeros().toPlainString(),
                                    naming.part(partId)));
        }

        // На площадку уезжает свободный остаток, а не общий: отложенная деталь
        // обязана стать недоступной сразу, иначе за ней приедет второй
        // покупатель. Отметка здесь, а не у вызывающих: резерв меняется
        // только этими двумя методами.
        partChanges.changed(partId);
    }

    /** Снимает резерв: при отмене сделки и перед списанием на выдаче. */
    public void release(Long partId, Long warehouseId, BigDecimal quantity) {
        int updated = entityManager.createNativeQuery("""
                        UPDATE part_stock
                           SET qty_reserved = qty_reserved - :qty,
                               updated_at = now()
                         WHERE part_id = :part
                           AND warehouse_id = :warehouse
                           AND qty_reserved >= :qty""")
                .setParameter("part", partId)
                .setParameter("warehouse", warehouseId)
                .setParameter("qty", quantity)
                .executeUpdate();

        if (updated == 0) {
            // Снятие несуществующего резерва — признак рассогласования, а не
            // безобидная операция: молча пропустив его, мы оставим деталь
            // заблокированной навсегда.
            throw new InsufficientStockException(
                    "Нечего снимать с резерва на складе %s: требуется %s — %s"
                            .formatted(naming.warehouse(warehouseId),
                                    quantity.stripTrailingZeros().toPlainString(),
                                    naming.part(partId)));
        }

        partChanges.changed(partId);
    }

    /**
     * Свободный остаток детали на складе.
     *
     * <p>Именно свободный, а не общий: деталь, отложенная другому покупателю,
     * для нового заказа всё равно что продана. Пусто — строки раскладки нет,
     * то есть на этом складе детали не было вовсе.
     *
     * <p>Читается, а не считается в приложении: между чтением и решением
     * встанет другой продавец. Поэтому ответ здесь — только для того, чтобы
     * объяснить человеку, чего не хватает; сам резерв ставит {@link #reserve},
     * одной инструкцией с проверкой.
     */
    public BigDecimal availableQuantity(Long partId, Long warehouseId) {
        Object found = entityManager.createNativeQuery("""
                        SELECT COALESCE(sum(qty - qty_reserved), 0) FROM part_stock
                         WHERE part_id = :part AND warehouse_id = :warehouse""")
                .setParameter("part", partId)
                .setParameter("warehouse", warehouseId)
                .getSingleResult();
        return found == null ? BigDecimal.ZERO : (BigDecimal) found;
    }

    /** Свободного остатка не хватило. Отдельный тип: обработка у него своя. */
    public static class InsufficientStockException extends RuntimeException {

        public InsufficientStockException(String message) {
            super(message);
        }

        public InsufficientStockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
