package ru.partsflow.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockDocumentRepository extends JpaRepository<StockDocument, Long> {

    /** Повтор офлайн-очереди узнаётся здесь: тот же ключ — тот же документ. */
    java.util.Optional<StockDocument> findByClientRequestId(String clientRequestId);

    /** Реестр документов склада: свежие сверху, как в списке у кладовщика. */
    List<StockDocument> findByWarehouseIdOrderByIdDesc(Long warehouseId);

    List<StockDocument> findByStatusOrderByIdDesc(StockDocument.DocumentStatus status);

    List<StockDocument> findBySupplyIdOrderByIdAsc(Long supplyId);
}
