package ru.partsflow.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PartPhotoRepository extends JpaRepository<PartPhoto, Long> {

    List<PartPhoto> findByPartIdOrderBySortOrderAscIdAsc(Long partId);

    /** Повтор запроса ссылки узнаётся здесь: тот же ключ — та же фотография. */
    Optional<PartPhoto> findByClientRequestId(String clientRequestId);

    Optional<PartPhoto> findByPartIdAndMainIsTrue(Long partId);

    /**
     * Оборванные загрузки: ссылку выдали, файл не приехал.
     *
     * <p>Нужны для чистки. Без неё карточка будет показывать битую картинку,
     * а в фид уйдёт ссылка в никуда — площадка за это снимает объявление.
     */
    List<PartPhoto> findByStatusAndCreatedAtBefore(PartPhoto.PhotoStatus status, Instant before);
}
