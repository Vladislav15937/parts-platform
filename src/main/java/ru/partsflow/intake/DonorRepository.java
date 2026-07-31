package ru.partsflow.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DonorRepository extends JpaRepository<Donor, Long> {

    Optional<Donor> findByPublicCode(String publicCode);

    Optional<Donor> findByVin(String vin);

    /** Машины одной поставки: контейнер разбирают целиком. */
    List<Donor> findBySupplyIdOrderByIdAsc(Long supplyId);

    List<Donor> findByStatusOrderByIdDesc(Donor.DonorStatus status);
}
