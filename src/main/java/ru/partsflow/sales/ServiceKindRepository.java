package ru.partsflow.sales;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceKindRepository extends JpaRepository<ServiceKind, Long> {

    List<ServiceKind> findByArchivedFalseOrderByName();

    Optional<ServiceKind> findByName(String name);
}
