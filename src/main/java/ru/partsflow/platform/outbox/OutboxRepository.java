package ru.partsflow.platform.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

@Repository
public class OutboxRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Забирает пачку неопубликованных событий и помечает её заявкой.
     *
     * <p>{@code SKIP LOCKED} позволяет нескольким экземплярам релея работать
     * параллельно: каждый берёт свою порцию, никто никого не ждёт.
     * Порядок по {@code id} сохраняет последовательность событий арендатора.
     *
     * <p><b>Блокировки строк тут мало.</b> Она живёт до конца транзакции,
     * а транзакция закрывается сразу — отправка в транспорт идёт снаружи,
     * чтобы лежащий брокер не держал соединение с базой. Поэтому пачка
     * помечается {@code claimed_at} прямо в строке: это и есть «её уже кто-то
     * отправляет». Просроченная заявка забирается заново — иначе процесс,
     * умерший между отправкой и пометкой, потерял бы события навсегда.
     *
     * <p>Запрос нативный, потому что JPQL не умеет {@code SKIP LOCKED}.
     * Имя таблицы без схемы — её подставляет {@code search_path} текущего арендатора.
     */
    @SuppressWarnings("unchecked")
    public List<OutboxRecord> claimUnpublished(int limit, Duration claimTtl) {
        List<OutboxRecord> batch = entityManager.createNativeQuery("""
                        SELECT * FROM outbox
                        WHERE published_at IS NULL
                          AND (claimed_at IS NULL
                               OR claimed_at < now() - make_interval(secs => :ttl))
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """, OutboxRecord.class)
                .setParameter("ttl", (double) claimTtl.toSeconds())
                .setParameter("limit", limit)
                .getResultList();

        if (!batch.isEmpty()) {
            entityManager.createNativeQuery(
                            "UPDATE outbox SET claimed_at = now() WHERE id IN (:ids)")
                    .setParameter("ids", ids(batch))
                    .executeUpdate();
        }
        return batch;
    }

    /**
     * Отмечает пачку опубликованной.
     *
     * <p>Своим запросом, а не изменением поля сущности: к этому моменту
     * записи отцеплены от контекста — транзакция, в которой их прочитали,
     * давно закрыта, и грязная проверка Hibernate ничего бы не записала.
     */
    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        entityManager.createNativeQuery(
                        "UPDATE outbox SET published_at = now() WHERE id IN (:ids)")
                .setParameter("ids", ids)
                .executeUpdate();
    }

    /**
     * Снимает заявку с пачки, которую не удалось отправить.
     *
     * <p>Без этого события ждали бы истечения срока заявки — минуты вместо
     * следующего же захода. Отказ транспорта бывает мгновенным (брокера нет,
     * тема запрещена), и заставлять клиента ждать пять минут из-за ошибки,
     * которая повторится через секунду, незачем.
     *
     * <p>{@code published_at IS NULL} в условии обязателен: между отправкой
     * и снятием заявки пачку мог забрать и опубликовать другой экземпляр.
     */
    public void releaseClaim(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        entityManager.createNativeQuery("""
                        UPDATE outbox SET claimed_at = NULL
                         WHERE id IN (:ids) AND published_at IS NULL""")
                .setParameter("ids", ids)
                .executeUpdate();
    }

    public long countUnpublished() {
        return ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM outbox WHERE published_at IS NULL")
                .getSingleResult()).longValue();
    }

    private static List<Long> ids(List<OutboxRecord> batch) {
        return batch.stream().map(OutboxRecord::getId).toList();
    }
}
