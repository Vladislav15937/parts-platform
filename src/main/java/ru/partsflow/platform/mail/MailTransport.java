package ru.partsflow.platform.mail;

/**
 * Канал, которым письмо уходит к оператору.
 *
 * <p>Реализаций две, и обе работают в каждом запуске: {@link UnisenderGoTransport}
 * (бой) и {@link LogMailTransport} (разработка и ячейка без договора).
 *
 * <p><b>Ответ — это принятие оператором, а не доставка.</b> «Принял»
 * ({@link Outcome#ACCEPTED}) переводит письмо в {@code SENT}; в {@code DELIVERED}
 * его переводит только отчёт оператора (см. {@link MailEventController}).
 * Подмена одного другим — ровно то, за что проект уже платил дважды.
 */
public interface MailTransport {

    Result send(Outgoing message);

    record Outgoing(long id, String to, String subject, String text, String html) {}

    enum Outcome {
        /** Оператор принял письмо; {@code providerId} — его номер задания. */
        ACCEPTED,
        /** Не вышло сейчас, повторять: сеть, 5xx, 429, ключ не подошёл. */
        TEMPORARY,
        /** Отказ по существу: адрес, формат. Повтор ничего не изменит. */
        REJECTED
    }

    record Result(Outcome outcome, String providerId, String detail) {
        public static Result accepted(String providerId) {
            return new Result(Outcome.ACCEPTED, providerId, null);
        }

        public static Result temporary(String detail) {
            return new Result(Outcome.TEMPORARY, null, detail);
        }

        public static Result rejected(String detail) {
            return new Result(Outcome.REJECTED, null, detail);
        }
    }
}
