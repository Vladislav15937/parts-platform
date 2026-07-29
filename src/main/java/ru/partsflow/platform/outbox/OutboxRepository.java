package ru.partsflow.platform.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OutboxRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Забирает неопубликованные события, блокируя строки.
     *
     * <p>{@code SKIP LOCKED} позволяет нескольким экземплярам релея работать
     * параллельно: каждый берёт свою порцию, никто никого не ждёт.
     * Порядок по {@code id} сохраняет последовательность событий арендатора.
     *
     * <p>Запрос нативный, потому что JPQL не умеет {@code SKIP LOCKED}.
     * Имя таблицы без схемы — её подставляет {@code search_path} текущего арендатора.
     */
    @SuppressWarnings("unchecked")
    public List<OutboxRecord> lockUnpublished(int limit) {
        return entityManager.createNativeQuery("""
                        SELECT * FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """, OutboxRecord.class)
                .setParameter("limit", limit)
                .getResultList();
    }

    public long countUnpublished() {
        return ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .getSingleResult()).longValue();
    }
}
