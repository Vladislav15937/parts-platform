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
     * Нативный запрос: JPQL не умеет tsvector.
     */
    @Query(value = """
            SELECT * FROM part
            WHERE search_vector @@ plainto_tsquery('russian', :query)
            ORDER BY ts_rank(search_vector, plainto_tsquery('russian', :query)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Part> search(@Param("query") String query, @Param("limit") int limit);

    /**
     * Поиск по номеру детали. Номер нормализуется той же функцией, что и при
     * записи, поэтому любое написание (с дефисами, пробелами, в любом регистре)
     * находит одну и ту же деталь.
     */
    @Query(value = """
            SELECT p.* FROM part p
            JOIN part_oem o ON o.part_id = p.id
            WHERE o.normalized = catalog.normalize_oem(:number)
            """, nativeQuery = true)
    List<Part> findByOemNumber(@Param("number") String number);

    /**
     * Главный экран продавца: что есть на конкретную машину.
     */
    @Query(value = """
            SELECT DISTINCT p.* FROM part p
            JOIN part_applicability a ON a.part_id = p.id
            WHERE p.status = 'IN_STOCK'
              AND a.brand_id = :brandId
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
