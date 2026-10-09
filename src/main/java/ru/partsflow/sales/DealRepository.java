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
     *
     * <p>Сделка, где ВСЕ открытые позиции — предзаказ (деталь по ожидаемой
     * поставке ещё не пришла), сюда не попадает: резерва склада у неё нет, и
     * «подержите ещё» не про что. Смешанная (предзаказ плюс обычная позиция
     * со склада) попадает по сроку обычной позиции: иначе просроченный резерв
     * настоящей детали не видит никто. То же условие стоит в ветке «Истек срок» доски
     * ({@code SalesService.PREORDER_ONLY}) — разойдясь, они дали бы два
     * ответа на один вопрос (задача 0170).
     */
    @Query("""
            SELECT d FROM Deal d
            WHERE d.status = ru.partsflow.sales.DealStatus.RESERVED
              AND d.reservedUntil < :now
              AND (NOT EXISTS (SELECT 1 FROM Deal d2 JOIN d2.items i
                               WHERE d2.id = d.id
                                 AND i.status = ru.partsflow.sales.DealItemStatus.PREORDER)
                   OR EXISTS (SELECT 1 FROM Deal d3 JOIN d3.items o
                              WHERE d3.id = d.id
                                AND o.status = ru.partsflow.sales.DealItemStatus.RESERVED))
            ORDER BY d.reservedUntil
            """)
    List<Deal> findExpiredReservations(@Param("now") Instant now);

    /**
     * Сделки, в которых деталь отложена предзаказом (задача 0170).
     *
     * <p>По сделке и позиции, а не по дате: кто отложил раньше, тот первым
     * получает деталь, когда поставка придёт.
     */
    @Query("""
            SELECT DISTINCT d FROM Deal d JOIN d.items i
            WHERE i.partId = :partId
              AND i.status = ru.partsflow.sales.DealItemStatus.PREORDER
            ORDER BY d.id
            """)
    List<Deal> findWithPreorderOf(@Param("partId") Long partId);

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

    /**
     * Сделки с действующей ссылкой.
     *
     * <p>Просроченные не отдаются вовсе: ссылка, которую однажды переслали
     * в переписке, после срока перестаёт показывать склад. Список короткий —
     * ссылку выдают под конкретный разговор с клиентом.
     */
    @Query("""
            SELECT d FROM Deal d
            WHERE d.shareToken IS NOT NULL AND d.shareExpires > :now
            """)
    List<Deal> findShared(@Param("now") Instant now);
}
