package ru.partsflow.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Разбор непринятых событий.
 *
 * <p>Событие, которое обработчик не принял, ложилось в {@code event_dead_letter}
 * и оставалось там навсегда: ни повтора, ни экрана — достать можно было только
 * запросом в базу. На практике это значит, что выданная сделка не уехала
 * на Дром, объявление висит доступным, и звонок за проданной деталью —
 * первое, что об этом сообщает.
 *
 * <p><b>Сначала автоматически, потом руками.</b> Большинство отказов временные:
 * площадка полежала полчаса. Повторять их должен робот, а не человек. Но
 * повторять вечно нельзя — неверный ключ кабинета не починится сам, — поэтому
 * после {@link #AUTO_ATTEMPTS} попыток запись остаётся человеку и попадает
 * на экран.
 *
 * <p><b>Выдержка растёт.</b> Площадка, лежащая час, не должна получать одно
 * и то же каждую минуту: этим её не поднять, а логи разбора станет нечитаемо.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /**
     * Сколько раз пробует робот. Дальше — человек: пять неудач подряд
     * с растущей выдержкой означают, что само не починится.
     */
    static final int AUTO_ATTEMPTS = 5;

    /** Выдержки по номеру попытки. Последняя повторяется, если попыток больше. */
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(4),
    };

    private final JdbcTemplate jdbc;
    private final EventDispatcher dispatcher;

    public DeadLetterService(JdbcTemplate jdbc, EventDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
    }

    /** Неразобранное: свежие сверху, чтобы владелец видел последнее происшествие. */
    @Transactional(readOnly = true)
    public List<DeadLetter> unresolved(int limit) {
        return jdbc.query("""
                SELECT id, handler, event_id, event_type, aggregate_type, aggregate_id,
                       error, attempts, created_at, next_attempt_at
                  FROM event_dead_letter
                 WHERE resolved_at IS NULL
                 ORDER BY created_at DESC
                 LIMIT ?""", DeadLetterService::toDeadLetter, limit);
    }

    @Transactional(readOnly = true)
    public long unresolvedCount() {
        Long found = jdbc.queryForObject(
                "SELECT count(*) FROM event_dead_letter WHERE resolved_at IS NULL", Long.class);
        return found == null ? 0 : found;
    }

    /**
     * Повтор по кнопке.
     *
     * <p>Выдержку и предел попыток игнорирует намеренно: человек нажимает
     * её, когда починил причину, и заставлять его ждать четыре часа — значит
     * заставить лезть в базу, от чего экран и избавляет.
     *
     * @return причина отказа; пусто — доставлено
     */
    @Transactional
    public Optional<String> retry(long id, Long memberId) {
        DeadLetter letter = require(id);
        return attempt(letter, memberId);
    }

    /**
     * Проход робота: повторяет всё, чей срок подошёл.
     *
     * <p>Вызывается уже с установленным арендатором — как и всё остальное,
     * что работает с данными клиента.
     *
     * @return сколько записей удалось доставить
     */
    @Transactional
    public int retryDue() {
        List<DeadLetter> due = jdbc.query("""
                SELECT id, handler, event_id, event_type, aggregate_type, aggregate_id,
                       error, attempts, created_at, next_attempt_at
                  FROM event_dead_letter
                 WHERE resolved_at IS NULL
                   AND attempts < ?
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at
                 LIMIT 100""", DeadLetterService::toDeadLetter, AUTO_ATTEMPTS);

        int delivered = 0;
        for (DeadLetter letter : due) {
            // Автора нет и быть не может: это фоновый проход. Пустой
            // resolved_by и означает «отправил робот» — как и везде,
            // где автор берётся из вошедшего.
            if (attempt(letter, null).isEmpty()) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Снять с разбора без доставки.
     *
     * <p>Нужно: дельта по сделке, отменённой на прошлой неделе, площадке
     * не нужна, а висеть в списке она будет вечно. Отличается от доставленной
     * записью {@code DISCARDED} — иначе снятое руками не отличить от
     * доставленного, и это единственный след того, что событие решили
     * не отправлять.
     */
    @Transactional
    public void discard(long id, Long memberId) {
        jdbc.update("""
                UPDATE event_dead_letter
                   SET resolved_at = now(), resolution = 'DISCARDED', resolved_by = ?
                 WHERE id = ? AND resolved_at IS NULL""", memberId, id);
    }

    private Optional<String> attempt(DeadLetter letter, Long memberId) {
        byte[] payload = jdbc.queryForObject(
                "SELECT payload FROM event_dead_letter WHERE id = ?", byte[].class, letter.id());

        Optional<String> failure = dispatcher.redeliver(letter.handler(), new ConsumedEvent(
                letter.eventId(), letter.aggregateType(), letter.aggregateId(),
                letter.eventType(), payload));

        if (failure.isEmpty()) {
            // Автор пишется и здесь, а не только у снятия с разбора.
            // Метод общий у робота и у человека, и пока автора не было
            // вовсе, нажатие «Повторить» было неотличимо от прохода робота:
            // на вопрос «кто отправил это повторно» ответа не находилось,
            // хотя у соседней кнопки он есть. Пусто — значит робот.
            jdbc.update("""
                    UPDATE event_dead_letter
                       SET resolved_at = now(), resolution = 'RETRIED', error = '',
                           resolved_by = ?
                     WHERE id = ?""", memberId, letter.id());
            log.info("Событие {} доставлено повтором обработчику {}",
                    letter.eventId(), letter.handler());
            return Optional.empty();
        }

        // Счётчик растёт и здесь: он же ограничивает робота, и повтор,
        // не двигающий его, крутил бы одну запись до конца времён.
        jdbc.update("""
                UPDATE event_dead_letter
                   SET attempts = attempts + 1, error = ?, next_attempt_at = ?
                 WHERE id = ?""",
                failure.get(),
                // Timestamp, а не Instant: драйвер не выводит SQL-тип
                // для java.time.Instant и падает на «Can't infer the SQL type».
                java.sql.Timestamp.from(Instant.now().plus(backoffFor(letter.attempts() + 1))),
                letter.id());
        return failure;
    }

    private static Duration backoffFor(int attempts) {
        return BACKOFF[Math.min(attempts, BACKOFF.length) - 1];
    }

    private DeadLetter require(long id) {
        List<DeadLetter> found = jdbc.query("""
                SELECT id, handler, event_id, event_type, aggregate_type, aggregate_id,
                       error, attempts, created_at, next_attempt_at
                  FROM event_dead_letter
                 WHERE id = ? AND resolved_at IS NULL""", DeadLetterService::toDeadLetter, id);

        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "Запись разбора не найдена или уже закрыта: " + id);
        }
        return found.get(0);
    }

    private static DeadLetter toDeadLetter(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new DeadLetter(
                rs.getLong("id"),
                rs.getString("handler"),
                rs.getLong("event_id"),
                rs.getString("event_type"),
                rs.getString("aggregate_type"),
                rs.getLong("aggregate_id"),
                rs.getString("error"),
                rs.getInt("attempts"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("next_attempt_at", java.time.OffsetDateTime.class).toInstant());
    }

    /**
     * @param attempts сколько раз уже пробовали. Дошло до {@link #AUTO_ATTEMPTS} —
     *                 робот отступился, дальше решает человек
     */
    public record DeadLetter(long id, String handler, long eventId, String eventType,
                             String aggregateType, long aggregateId, String error,
                             int attempts, Instant createdAt, Instant nextAttemptAt) {

        /** Робот больше не пытается: запись ждёт человека. */
        public boolean needsAttention() {
            return attempts >= AUTO_ATTEMPTS;
        }
    }
}
