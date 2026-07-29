package ru.partsflow.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockDocumentRepository extends JpaRepository<StockDocument, Long> {

    /** Реестр документов склада: свежие сверху, как в списке у кладовщика. */
    List<StockDocument> findByWarehouseIdOrderByIdDesc(Long warehouseId);

    List<StockDocument> findByStatusOrderByIdDesc(StockDocument.DocumentStatus status);

    List<StockDocument> findBySupplyIdOrderByIdAsc(Long supplyId);
}
