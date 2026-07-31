package ru.partsflow.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DealRepository extends JpaRepository<Deal, Long> {

    List<Deal> findByCustomerIdOrderByIdDesc(Long customerId);

    List<Deal> findByStatus(DealStatus status);

    /**
     * Сделки с истёкшим резервом.
     *
     * <p>Это очередь на обзвон, а не мусор для автоочистки: по каждой менеджер
     * решает — продлить, отменить или дожать. На живой разборке таких сделок
     * было 62, и это самая заметная колонка на доске продаж.
     */
    @Query("""
            SELECT d FROM Deal d
            WHERE d.status = ru.partsflow.sales.DealStatus.RESERVED
              AND d.reservedUntil < :now
            ORDER BY d.reservedUntil
            """)
    List<Deal> findExpiredReservations(@Param("now") Instant now);

    /**
     * Сделка по номеру заказа площадки.
     *
     * <p>Нужна двум: продавцу, которому покупатель называет номер заказа,
     * а не наш, — и приёму заказа, где повтор обязан вернуть прежнюю сделку,
     * а не завести вторую.
     */
    Optional<Deal> findByMarketplaceAndExternalOrderNo(String marketplace, String externalOrderNo);

    /**
     * Заказы площадок, по которым продавец ещё не ответил.
     *
     * <p>По сроку ответа, а не по дате заказа: пропущенный срок у Дрома —
     * это возврат денег покупателю, и заказ, до которого осталось два часа,
     * важнее вчерашнего, у которого их сутки. Отменённые и выданные сюда
     * не попадают: отвечать по ним уже не нужно.
     */
    @Query("""
            SELECT d FROM Deal d
            WHERE d.externalOrderNo IS NOT NULL
              AND d.orderAcceptedAt IS NULL
              AND d.status IN (ru.partsflow.sales.DealStatus.DRAFT,
                               ru.partsflow.sales.DealStatus.RESERVED,
                               ru.partsflow.sales.DealStatus.READY)
            ORDER BY d.replyDeadline NULLS LAST, d.id
            """)
    List<Deal> findAwaitingReply();
}
