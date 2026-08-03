package ru.partsflow.inventory;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, Long> {

    Optional<Part> findByPublicCode(String publicCode);

    Page<Part> findByStatus(PartStatus status, Pageable pageable);

    /**
     * Поиск по названию и описанию через полнотекстовый индекс.
     *
     * <p>Нативный запрос: JPQL не умеет tsvector. Выражение пишется в запросе,
     * а не хранится колонкой — генерируемых колонок в схеме не осталось;
     * на то же выражение стоит GIN-индекс {@code part_search_gin}, поэтому
     * план запроса не изменился.
     */
    @Query(value = """
            SELECT * FROM part
            WHERE to_tsvector('russian', coalesce(title, '') || ' '
                    || coalesce(description, '') || ' ' || coalesce(marking, ''))
                @@ plainto_tsquery('russian', :query)
            ORDER BY ts_rank(to_tsvector('russian', coalesce(title, '') || ' '
                    || coalesce(description, '') || ' ' || coalesce(marking, '')),
                    plainto_tsquery('russian', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Part> search(@Param("query") String query, @Param("limit") int limit);

    /**
     * Поиск по номеру детали.
     *
     * <p>Номер приводится {@code OemNumbers.normalize} — тем же методом, что
     * и при записи, поэтому любое написание (с дефисами, пробелами, в любом
     * регистре) находит одну и ту же деталь. Приведённый номер передаётся
     * параметром: раньше его считала функция БД, и приведение жило в двух
     * местах сразу.
     */
    @Query(value = """
            SELECT p.* FROM part p
            JOIN part_oem o ON o.part_id = p.id
            WHERE o.normalized = :normalized
            """, nativeQuery = true)
    List<Part> findByNormalizedOem(@Param("normalized") String normalized);

    /**
     * Главный экран продавца: что есть на конкретную машину.
     *
     * <p>Фильтр по свободному остатку, а не по статусу: обещанную другому
     * клиенту деталь продавать нельзя, а статус карточки про резерв ничего
     * не знает — он про наличие. Условие ложится на частичный индекс
     * {@code part_stock_available_ix}.
     */
    @Query(value = """
            SELECT DISTINCT p.* FROM part p
            JOIN part_applicability a ON a.part_id = p.id
            JOIN part_stock ps ON ps.part_id = p.id AND ps.qty - ps.qty_reserved > 0
            WHERE a.brand_id = :brandId
              AND (:modelId IS NULL OR a.model_id = :modelId OR a.model_id IS NULL)
              AND (:generationId IS NULL OR a.generation_id = :generationId OR a.generation_id IS NULL)
            ORDER BY p.updated_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Part> findApplicableTo(@Param("brandId") Long brandId,
                                @Param("modelId") Long modelId,
                                @Param("generationId") Long generationId,
                                @Param("limit") int limit);
}
