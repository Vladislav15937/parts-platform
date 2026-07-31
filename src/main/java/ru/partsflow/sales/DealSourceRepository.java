package ru.partsflow.sales;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DealSourceRepository extends JpaRepository<DealSource, Long> {

    List<DealSource> findByArchivedFalseOrderByName();
}
