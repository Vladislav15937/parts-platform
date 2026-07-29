package ru.partsflow.sales;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DealReturnRepository extends JpaRepository<DealReturn, Long> {

    List<DealReturn> findByDealIdOrderByIdAsc(Long dealId);

    /** Реестр возвратов: свежие сверху, как в списке у менеджера. */
    List<DealReturn> findByStatusOrderByCreatedAtDesc(ReturnStatus status);
}
