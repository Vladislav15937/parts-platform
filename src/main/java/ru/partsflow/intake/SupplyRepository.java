package ru.partsflow.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplyRepository extends JpaRepository<Supply, Long> {

    /** Номер уникален в паре с видом: контейнер 17 и закупка 17 — разные вещи. */
    Optional<Supply> findByKindAndNumber(Supply.SupplyKind kind, String number);

    List<Supply> findByStatusOrderByArrivedOnDesc(Supply.SupplyStatus status);
}
