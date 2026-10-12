package ru.partsflow.publishing.drom;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст {@code marketplace_account.last_error}: как отказ пишется и как
 * читается обратно (задача 0268). Запись и чтение в одном классе намеренно —
 * разойдясь, они дали бы карточку, которая называет не ту причину.
 *
 * <p><b>Почему метка, а не колонка.</b> Карточке нужны HTTP-статус и то, что
 * случилось с соединением, а колонка выгрузки одна — текст. Журнал
 * {@code publication_log} статус хранит, но его чистят через 90 дней, и после
 * чистки карточка потеряла бы причину вчерашнего отказа. Поэтому первой
 * строкой стоит наша метка — {@code [HTTP 502]}, {@code [TIMEOUT]},
 * {@code [CONNECT]}, {@code [DNS]}, {@code [BROKEN]}, {@code [TOO_BIG]}, —
 * а за ней ответ площадки как пришёл. Метка наша и узнаётся точно; ответ
 * разбирается {@link DromAnswer} по виду.
 *
 * <p><b>Отказ всегда непуст.</b> {@code NULL} в этой колонке значит «принято»,
 * и сбой без текста (пустое тело, исключение без сообщения) не должен
 * читаться успехом — метка делает текст непустым по построению.
 *
 * <p>Строки, записанные до метки, — это сырое тело ответа: они читаются
 * как ответ с неизвестным статусом ({@link Kind#UNTAGGED}).
 */
public final class DromSyncRecord {

    private static final Pattern TAG =
            Pattern.compile("(?s)^\\[(HTTP (\\d{1,3})|TIMEOUT|CONNECT|DNS|BROKEN|TOO_BIG)] ?(.*)$");

    public enum Kind { HTTP, TIMEOUT, CONNECT, DNS, BROKEN, TOO_BIG, UNTAGGED }

    /**
     * @param kind       что это за отказ
     * @param httpStatus статус при {@link Kind#HTTP}, иначе {@code null}
     * @param text       ответ площадки или текст сбоя без метки
     */
    public record Reading(Kind kind, Integer httpStatus, String text) {
    }

    private DromSyncRecord() {
    }

    /** Текст отказа по результату запроса. */
    static String of(DromSyncClient.Result result) {
        String body = result.body() == null ? "" : result.body();
        if (result.httpStatus() > 0) {
            return "[HTTP " + result.httpStatus() + "] " + body;
        }
        DromSyncClient.Transport kind = result.failure() == null
                ? DromSyncClient.Transport.BROKEN : result.failure();
        return "[" + kind.name() + "] " + body;
    }

    /** Текст отказа, когда дельта не влезла в лимит и не отправлялась. */
    static String tooBig(int limitBytes) {
        return "[TOO_BIG] дельта превысила лимит " + limitBytes + " байт";
    }

    /**
     * Коды отказа, которые без действия человека не пройдут (задача 0269):
     * прайс-лист не подключён к API, ключ не принят, номера или ключа нет.
     * Остальное — пятисотка, тишина, обрыв, незнакомый ответ — может пройти
     * само и повторяется на следующем заходе релея, как раньше.
     */
    static final Set<String> NEEDS_PERSON = Set.of(
            "ERROR_REASON_PACKET_NOT_FOUND",
            "ERROR_REASON_AUTH_FAILED",
            "ERROR_REASON_EMPTY_REQUEST");

    /**
     * Записан ли в {@code last_error} отказ, который без человека не пройдёт.
     *
     * <p>Тот же разбор, что у карточки ({@code DeliveryStatus.refused}): ответ
     * площадки с <b>ровно одним</b> кодом из {@link #NEEDS_PERSON}. Знакомый
     * код вперемешку с другим карточка называет незнакомым — значит и здесь
     * это не «нужен человек», а повод повторить: разойдись они, карточка
     * говорила бы «Дром ответил непонятно», а отправка молчала бы интервал.
     * Сбой соединения и дельта сверх лимита — не ответ площадки вовсе.
     */
    static boolean needsPerson(String lastError) {
        if (lastError == null) {
            return false;
        }
        Reading reading = read(lastError);
        if (reading.kind() != Kind.HTTP && reading.kind() != Kind.UNTAGGED) {
            return false;
        }
        List<String> codes = DromAnswer.parse(reading.text()).codes();
        return codes.size() == 1 && NEEDS_PERSON.contains(codes.get(0));
    }

    public static Reading read(String lastError) {
        Matcher m = TAG.matcher(lastError);
        if (!m.matches()) {
            return new Reading(Kind.UNTAGGED, null, lastError);
        }
        if (m.group(2) != null) {
            return new Reading(Kind.HTTP, Integer.valueOf(m.group(2)), m.group(3));
        }
        return new Reading(Kind.valueOf(m.group(1)), null, m.group(3));
    }
}
