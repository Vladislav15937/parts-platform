package ru.partsflow.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockDocumentRepository extends JpaRepository<StockDocument, Long> {

    /** Повтор офлайн-очереди узнаётся здесь: тот же ключ — тот же документ. */
    java.util.Optional<StockDocument> findByClientRequestId(String clientRequestId);

    /*
     * Три искателя ниже не зовёт никто (проверено перебором 23.10.2026,
     * задача 0367). Удалённый документ они отдают наравне с остальными:
     * статус у него DONE, удаление — отметка deletedAt. Первый, кто их позовёт,
     * обязан отличать удалённое (StockDocument.isDeleted), иначе покажет
     * его обычным выполненным.
     */

    /** Реестр документов склада: свежие сверху, как в списке у кладовщика. */
    List<StockDocument> findByWarehouseIdOrderByIdDesc(Long warehouseId);

    List<StockDocument> findByStatusOrderByIdDesc(StockDocument.DocumentStatus status);

    List<StockDocument> findBySupplyIdOrderByIdAsc(Long supplyId);

    /**
     * Документ под блокировкой строки — для удаления (задача 0367): два
     * одновременных нажатия «Удалить» встают в очередь, и второе видит
     * отметку первого, а не пишет второе встречное движение.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT d FROM StockDocument d WHERE d.id = :id")
    java.util.Optional<StockDocument> findByIdForUpdate(
            @org.springframework.data.repository.query.Param("id") Long id);
}
