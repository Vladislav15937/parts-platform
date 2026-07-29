package ru.partsflow.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

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
}
