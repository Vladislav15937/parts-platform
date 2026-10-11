package ru.partsflow.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Очередь исходящих писем: поставить, отправить, узнать судьбу.
 *
 * <p><b>Письмо сначала записывается, потом отправляется.</b> Регистрация,
 * после которой письмо не ушло, не должна терять человека: запись в очереди
 * переживает и отказ оператора, и перезапуск, а повторяет её
 * {@link #dispatchDue()}. Временную ошибку (сеть, 5xx, ключ не подошёл) ждут
 * с растущей выдержкой; отказ по существу (адрес) называют статусом
 * {@code FAILED} — по нему вызывающий скажет человеку, что письма не будет.
 *
 * <p><b>Статусы честны.</b> {@code SENT} — оператор принял, {@code DELIVERED} —
 * оператор сообщил, что доставил ({@link #applyEvent}); второе приходит только
 * от него. {@code FAILED} — мы сдались (отказ по существу или кончились
 * попытки), {@code BOUNCED} — оператор принял, но не доставил.
 *
 * <p><b>Таблица в {@code public}, имя схемы — в тексте запроса.</b> Письмо
 * уходит раньше, чем есть арендатор, и от {@code TenantContext} не зависит;
 * поэтому методы не требуют транзакции арендатора, а вызванные из чужой
 * транзакции вступают в неё.
 *
 * <p><b>Идемпотентность — по ключу, и держит её индекс.</b> Повтор
 * {@link #enqueue} с тем же ключом письма не добавляет и возвращает состояние
 * первого: {@code ON CONFLICT DO NOTHING} отвечает на одновременный повтор
 * так же, как на обычный, и «нарушения целостности» человек не увидит.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";
    public static final String BOUNCED = "BOUNCED";

    /** Выдержка между повторами не растёт дальше часа. */
    private static final long MAX_DELAY_SECONDS = Duration.ofHours(1).toSeconds();

    /** Состояние письма для вызывающего. Номер строки базы наружу не идёт. */
    public record MailStatus(String key, String status, String recipient, int attempts,
                             String lastError, boolean replayed) {}

    private record Claimed(long id, String to, String subject, String text, String html,
                           int attempts) {}

    private final JdbcTemplate jdbc;
    private final MailTransport transport;
    private final MailProperties props;

    public MailService(JdbcTemplate jdbc, MailTransport transport, MailProperties props) {
        this.jdbc = jdbc;
        this.transport = transport;
        this.props = props;
    }

    /**
     * Ставит письмо в очередь. Повтор с тем же ключом возвращает состояние
     * первого письма с {@code replayed = true}.
     */
    public MailStatus enqueue(String key, String to, MailTemplates.Rendered letter) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Не задан ключ письма");
        }
        if (to == null || !to.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Адрес почты указан неверно");
        }
        int inserted = jdbc.update("""
                INSERT INTO public.mail_message
                    (idempotency_key, template, recipient, subject, body_text, body_html)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING""",
                key, letter.template(), to.trim(), letter.subject(), letter.text(), letter.html());
        MailStatus now = status(key).orElseThrow();
        return new MailStatus(now.key(), now.status(), now.recipient(), now.attempts(),
                now.lastError(), inserted == 0);
    }

    public Optional<MailStatus> status(String key) {
        List<MailStatus> rows = jdbc.query("""
                SELECT idempotency_key, status, recipient, attempts, last_error
                  FROM public.mail_message WHERE idempotency_key = ?""",
                (rs, i) -> new MailStatus(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5), false),
                key);
        return rows.stream().findFirst();
    }

    /**
     * Один проход очереди: берёт ждущие письма и отправляет каждое.
     *
     * <p>Заявка — это сдвиг {@code next_attempt_at} на срок
     * {@code claimTtl} в той же инструкции, что выбирает строки
     * ({@code FOR UPDATE SKIP LOCKED}): два экземпляра приложения не берут одно
     * письмо, а отправка идёт <b>вне</b> транзакции — соединение не ждёт
     * чужой таймаут. Процесс, умерший посреди отправки, оставляет письмо
     * заявленным до срока, а не потерянным; цена — возможный дубль письма
     * после падения (доставка at-least-once, как у outbox).
     *
     * @return сколько писем обработано
     */
    public int dispatchDue() {
        List<Claimed> batch = jdbc.query("""
                UPDATE public.mail_message m
                   SET attempts = m.attempts + 1,
                       next_attempt_at = now() + (? * interval '1 second'),
                       updated_at = now()
                 WHERE m.id IN (SELECT id FROM public.mail_message
                                 WHERE status = 'QUEUED' AND next_attempt_at <= now()
                                 ORDER BY next_attempt_at
                                 LIMIT ?
                                 FOR UPDATE SKIP LOCKED)
                RETURNING m.id, m.recipient, m.subject, m.body_text, m.body_html, m.attempts""",
                (rs, i) -> new Claimed(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6)),
                props.claimTtl().toSeconds(), props.batchSize());

        // Отчёты, чьё письмо так и не нашлось, не копим: сутки с запасом хватает
        // на любой разрыв между ответом оператора и записью результата.
        jdbc.update("DELETE FROM public.mail_event_pending WHERE received_at < now() - interval '1 day'");

        for (Claimed c : batch) {
            MailTransport.Result result;
            try {
                result = transport.send(new MailTransport.Outgoing(
                        c.id(), c.to(), c.subject(), c.text(), c.html()));
            } catch (RuntimeException e) {
                result = MailTransport.Result.temporary("сбой отправки: " + e.getClass().getSimpleName());
            }
            record(c, result);
        }
        return batch.size();
    }

    private void record(Claimed c, MailTransport.Result r) {
        switch (r.outcome()) {
            case ACCEPTED -> {
                jdbc.update("""
                        UPDATE public.mail_message
                           SET status = 'SENT', provider_id = ?, sent_at = now(),
                               last_error = NULL, updated_at = now()
                         WHERE id = ? AND status = 'QUEUED'""", r.providerId(), c.id());
                // Отчёт мог прийти, пока шёл запрос: он ждёт в буфере.
                replayPending(r.providerId());
            }
            case REJECTED -> fail(c, "отказ оператора: " + r.detail());
            case TEMPORARY -> {
                if (c.attempts() >= props.maxAttempts()) {
                    fail(c, "попытки кончились, последняя причина: " + r.detail());
                } else {
                    long delay = Math.min(MAX_DELAY_SECONDS,
                            props.retryDelay().toSeconds() << Math.min(c.attempts() - 1, 10));
                    jdbc.update("""
                            UPDATE public.mail_message
                               SET next_attempt_at = now() + (? * interval '1 second'),
                                   last_error = ?, updated_at = now()
                             WHERE id = ? AND status = 'QUEUED'""", delay, r.detail(), c.id());
                    log.warn("Письмо #{}: попытка {} не удалась ({}), повтор через {} с",
                            c.id(), c.attempts(), r.detail(), delay);
                }
            }
        }
    }

    private void fail(Claimed c, String reason) {
        jdbc.update("""
                UPDATE public.mail_message
                   SET status = 'FAILED', last_error = ?, updated_at = now()
                 WHERE id = ? AND status = 'QUEUED'""", reason, c.id());
        log.error("Письмо #{} не будет доставлено: {}", c.id(), reason);
    }

    /**
     * Отчёт оператора о судьбе письма.
     *
     * <p><b>Отчёт может обогнать запись результата.</b> Отправка идёт вне
     * транзакции, а номер задания попадает в строку только вместе с записью
     * {@code SENT}: пока оператор ответил, а мы ещё не записали, отчёт номера
     * не находит — и, получив 200, оператор не повторит. Поэтому отчёт по
     * неизвестному номеру не выбрасывается, а ждёт в {@code mail_event_pending};
     * запись результата ({@link #record}) разбирает ожидающие по своему номеру.
     * Обе половины перепроверяют друг друга после своей записи, так что при любом
     * порядке один из двух путей увидит другой. Ожидающие отчёты, чьё письмо так
     * и не нашлось (чужая рассылка с того же проекта), живут сутки.
     *
     * @return применён ли отчёт к письму сейчас
     */
    public boolean applyEvent(String providerId, String operatorStatus, String detail) {
        if (providerId == null || providerId.isBlank() || operatorStatus == null) {
            return false;
        }
        if (applyKnown(providerId, operatorStatus, detail)) {
            return true;
        }
        if (exists(providerId)) {
            // Номер есть, но статус отчёта ничего не меняет (повтор, «sent»).
            return false;
        }
        jdbc.update("""
                INSERT INTO public.mail_event_pending (provider_id, operator_status, detail)
                VALUES (?, ?, ?)""", providerId, operatorStatus, detail);
        // Запись результата могла завершиться между нашей проверкой и вставкой.
        return replayPending(providerId);
    }

    private boolean exists(String providerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.mail_message WHERE provider_id = ?)",
                Boolean.class, providerId));
    }

    /** Применяет ожидавшие отчёты по номеру, который уже записан в письме. */
    private boolean replayPending(String providerId) {
        if (!exists(providerId)) {
            return false;
        }
        List<String[]> waiting = jdbc.query("""
                SELECT operator_status, detail FROM public.mail_event_pending
                 WHERE provider_id = ? ORDER BY id""",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, providerId);
        boolean applied = false;
        for (String[] w : waiting) {
            applied |= applyKnown(providerId, w[0], w[1]);
        }
        jdbc.update("DELETE FROM public.mail_event_pending WHERE provider_id = ?", providerId);
        return applied;
    }

    private boolean applyKnown(String providerId, String operatorStatus, String detail) {
        return switch (operatorStatus) {
            case "delivered", "opened", "clicked" -> jdbc.update("""
                    UPDATE public.mail_message
                       SET status = 'DELIVERED', delivered_at = now(), updated_at = now()
                     WHERE provider_id = ? AND status = 'SENT'""", providerId) > 0;
            case "hard_bounced", "spam" -> jdbc.update("""
                    UPDATE public.mail_message
                       SET status = 'BOUNCED', last_error = ?, updated_at = now()
                     WHERE provider_id = ? AND status IN ('SENT', 'DELIVERED')""",
                    "оператор: " + operatorStatus + (detail == null ? "" : " (" + detail + ")"),
                    providerId) > 0;
            // Мягкий отказ оператор повторяет сам; фиксируем причину и ждём.
            case "soft_bounced" -> jdbc.update("""
                    UPDATE public.mail_message
                       SET last_error = ?, updated_at = now()
                     WHERE provider_id = ? AND status = 'SENT'""",
                    "оператор: soft_bounced" + (detail == null ? "" : " (" + detail + ")"),
                    providerId) > 0;
            default -> false;
        };
    }
}
