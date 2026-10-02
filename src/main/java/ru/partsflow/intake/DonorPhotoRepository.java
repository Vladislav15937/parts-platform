package ru.partsflow.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DonorPhotoRepository extends JpaRepository<DonorPhoto, Long> {

    List<DonorPhoto> findByDonorIdOrderBySortOrderAscIdAsc(Long donorId);

    /** Повтор запроса ссылки узнаётся здесь: тот же ключ — та же фотография. */
    Optional<DonorPhoto> findByClientRequestId(String clientRequestId);

    Optional<DonorPhoto> findByDonorIdAndMainIsTrue(Long donorId);
}
