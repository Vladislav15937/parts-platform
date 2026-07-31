package ru.partsflow.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventorySessionRepository extends JpaRepository<InventorySession, Long> {

    List<InventorySession> findByWarehouseIdOrderByIdDesc(Long warehouseId);

    /** Открытая инвентаризация склада: вторую на тот же склад начинать нельзя. */
    List<InventorySession> findByWarehouseIdAndStatus(Long warehouseId,
                                                      InventorySession.SessionStatus status);
}
