package ru.partsflow.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InventorySessionRepository extends JpaRepository<InventorySession, Long> {

    List<InventorySession> findByWarehouseIdOrderByIdDesc(Long warehouseId);

    /**
     * Сессия под блокировкой строки: только для проведения.
     *
     * <p>Отметка {@code applied_at} на строке защищает от повтора, но читается
     * она в начале транзакции — два одновременных проведения видят её пустой
     * оба и списывают недостачу дважды. Проверено на живом складе: остаток
     * 20 при недостаче 2 стал 16 вместо 18, и оба ответа сказали
     * «скорректировано 1» — то есть пересчёт испортил склад тем самым
     * действием, которым его чинят, и молча.
     *
     * <p>Блокировка пессимистичная, в отличие от сделки. У сделки версия,
     * потому что там окно гонки — это два нажатия, и проигравшему надо
     * сказать «документ изменил другой сотрудник». Здесь ждать нечего
     * и некому: второе проведение — это двойное нажатие или второй менеджер,
     * и правильный ответ ему «проведено 0», а не отказ. Блокировка живёт
     * внутри одной транзакции, человеческого времени в ней нет.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InventorySession s WHERE s.id = :id")
    Optional<InventorySession> findByIdForUpdate(Long id);

    /** Открытая инвентаризация склада: вторую на тот же склад начинать нельзя. */
    List<InventorySession> findByWarehouseIdAndStatus(Long warehouseId,
                                                      InventorySession.SessionStatus status);
}
