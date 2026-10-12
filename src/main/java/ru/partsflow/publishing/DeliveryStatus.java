package ru.partsflow.publishing;

import ru.partsflow.publishing.drom.DromAnswer;
import ru.partsflow.publishing.drom.DromSyncRecord;

import java.time.Instant;

/**
 * Итог отправки дельт на Дром для одной выгрузки — то, что карточка говорит
 * словами (задача 0268).
 *
 * <p>Сервер отдаёт состояние, код и подробности, а не слова: слова живут
 * в одном словаре на экране, и пятая копия словаря обходится дороже первой.
 * Код ответа Дрома наружу идёт только незнакомый ({@link Code#UNKNOWN_REASON}) —
 * его и надо показать, раз мы не знаем, что он значит; знакомый заменяют
 * слова. Сырой текст целиком остаётся в {@code Account.lastError} ради
 * раскрывающегося «Ответ Дрома».
 *
 * <p><b>Откуда берётся.</b> Из колонок выгрузки, а не из {@code publication_log}:
 * журналу запланирована чистка старше 90 дней, и после неё карточка сказала бы
 * «отправок не было» про выгрузку, которой отправляли вчера. Новой колонки нет —
 * статус и сбой соединения пишутся меткой в начале {@code last_error}
 * ({@code DromSyncRecord}), ответ площадки разбирается по виду ({@code DromAnswer}),
 * а не поиском подстроки.
 *
 * <p><b>Ответ решения владельца продукта от 12 октября 2026</b> (tasks/0268,
 * «Вопрос владельцу продукта — общие слова на карточке»): «показывать реальную
 * проблему, а не общими словами». Поэтому прежние общие {@code NO_ANSWER}
 * и {@code OTHER} распались на то, что произошло на самом деле.
 *
 * @param state      одно из {@link State}; {@code null} у выгрузки, у которой
 *                   отправки нет по определению (выключена, не Дром) — «не подключено»
 *                   про неё было бы неправдой
 * @param at         когда была последняя попытка (при любом исходе); пусто, пока
 *                   попыток не было
 * @param code       только при {@code REFUSED}: одно из {@link Code}
 * @param httpStatus HTTP-статус ответа, если ответ был; пусто при сбое
 *                   соединения, лимите и у записей до метки
 * @param answerForm вид тела ответа ({@code EMPTY}, {@code JSON}, {@code PAGE},
 *                   {@code MARKUP}, {@code TEXT}), если ответ был
 * @param detail     что показать человеку как есть, коротко: незнакомый код,
 *                   начало незнакомого ответа или заголовок страницы, текст сбоя
 *                   соединения; не длиннее двухсот знаков
 */
public record DeliveryStatus(State state, Instant at, Code code,
                             Integer httpStatus, String answerForm, String detail) {

    public enum State {
        /** Нет ни ключа, ни номера прайс-листа. */
        NOT_CONNECTED,
        /** Ключ есть, номера нет. */
        NO_PACKET,
        /** Номер есть, ключа нет. */
        NO_KEY,
        /** Всё есть, отправок ещё не было. */
        WAITING,
        /** Последняя попытка принята. */
        ACCEPTED,
        /** Последняя попытка отклонена; причина — в {@code code}. */
        REFUSED
    }

    public enum Code {
        /** Дром: прайс-лист не подключён к API. */
        PACKET_NOT_FOUND,
        /** Дром: ключ не принят. */
        AUTH_FAILED,
        /** Дром: запрос без данных или без ключа. */
        EMPTY_REQUEST,
        /** Код {@code ERROR_REASON_*}, которого мы не знаем; сам код — в {@code detail}. */
        UNKNOWN_REASON,
        /** HTTP-статус не 200, кода отказа в ответе нет. */
        HTTP_ERROR,
        /** Ответ, в котором мы не узнали ни успеха, ни отказа; начало — в {@code detail}. */
        UNRECOGNIZED,
        /** Соединились, а ответа не дождались. */
        TIMEOUT,
        /** Соединение не установилось. */
        NO_CONNECTION,
        /** Имя площадки не разрешилось. */
        HOST_UNKNOWN,
        /** Связь оборвалась иначе. */
        CONNECTION_BROKEN,
        /** Дельта не влезла в лимит и не отправлялась. */
        TOO_BIG
    }

    private static final java.util.Map<String, Code> KNOWN = java.util.Map.of(
            "ERROR_REASON_PACKET_NOT_FOUND", Code.PACKET_NOT_FOUND,
            "ERROR_REASON_AUTH_FAILED", Code.AUTH_FAILED,
            "ERROR_REASON_EMPTY_REQUEST", Code.EMPTY_REQUEST);

    private static DeliveryStatus plain(State state, Instant at) {
        return new DeliveryStatus(state, at, null, null, null, null);
    }

    /**
     * @return {@code null}, если про выгрузку отправка не говорится вовсе
     */
    public static DeliveryStatus of(String marketplace, String accountStatus,
                                    boolean hasKey, String packetId,
                                    Instant lastSyncAt, String lastError) {
        if (!"DROM".equals(marketplace) || "PAUSED".equals(accountStatus)) {
            return null;
        }
        boolean hasPacket = packetId != null && !packetId.isBlank();
        // Пробелы в подключении сильнее прошлого исхода: вынули номер после
        // отказа — правдой будет «номера нет», а не вчерашний отказ.
        if (!hasKey && !hasPacket) {
            return plain(State.NOT_CONNECTED, lastSyncAt);
        }
        if (!hasPacket) {
            return plain(State.NO_PACKET, lastSyncAt);
        }
        if (!hasKey) {
            return plain(State.NO_KEY, lastSyncAt);
        }
        if (lastSyncAt == null) {
            return plain(State.WAITING, null);
        }
        if (lastError == null) {
            return plain(State.ACCEPTED, lastSyncAt);
        }
        return refused(lastSyncAt, lastError);
    }

    /**
     * Что отдать наружу как «Ответ Дрома» — одна точка на все поверхности.
     *
     * <p>Метка {@code [HTTP 502]}, {@code [TIMEOUT]} — внутренняя запись
     * {@code DromSyncRecord}, а не слова площадки: человеку отдаётся текст
     * за меткой. У {@code [TOO_BIG]} ответа нет вовсе — запрос не уходил,
     * и «Ответ Дрома» с нашей фразой был бы неправдой, поэтому {@code null}.
     * Разбор PR 399: метка уезжала в {@code <details>} целиком. У Авито меток
     * нет — текст как есть.
     */
    public static String shownAnswer(String marketplace, String lastError) {
        if (lastError == null || !"DROM".equals(marketplace)) {
            return lastError;
        }
        DromSyncRecord.Reading reading = DromSyncRecord.read(lastError);
        return reading.kind() == DromSyncRecord.Kind.TOO_BIG ? null : reading.text();
    }

    /** Отказ по тексту {@code last_error}: метка нашей записи, затем ответ по виду. */
    static DeliveryStatus refused(Instant at, String lastError) {
        DromSyncRecord.Reading reading = DromSyncRecord.read(lastError);
        String text = reading.text();
        switch (reading.kind()) {
            case TOO_BIG:
                return new DeliveryStatus(State.REFUSED, at, Code.TOO_BIG, null, null, null);
            case TIMEOUT:
                return new DeliveryStatus(State.REFUSED, at, Code.TIMEOUT, null, null, brief(text));
            case CONNECT:
                return new DeliveryStatus(State.REFUSED, at, Code.NO_CONNECTION, null, null, brief(text));
            case DNS:
                return new DeliveryStatus(State.REFUSED, at, Code.HOST_UNKNOWN, null, null, brief(text));
            case BROKEN:
                return new DeliveryStatus(State.REFUSED, at, Code.CONNECTION_BROKEN, null, null,
                        brief(text));
            default:
                break;
        }
        Integer status = reading.httpStatus();
        DromAnswer.Parsed answer = DromAnswer.parse(text);
        String form = answer.form().name();
        if (!answer.codes().isEmpty()) {
            Code known = answer.codes().size() == 1 ? KNOWN.get(answer.codes().get(0)) : null;
            if (known != null) {
                return new DeliveryStatus(State.REFUSED, at, known, status, form, null);
            }
            // Незнакомый код, или знакомый вперемешку с другим: угадывать,
            // какой из двух главный, значит выдать догадку за ответ.
            return new DeliveryStatus(State.REFUSED, at, Code.UNKNOWN_REASON, status, form,
                    brief(String.join(", ", answer.codes())));
        }
        if (status != null && status != 200) {
            return new DeliveryStatus(State.REFUSED, at, Code.HTTP_ERROR, status, form,
                    answer.preview().isEmpty() ? null : answer.preview());
        }
        return new DeliveryStatus(State.REFUSED, at, Code.UNRECOGNIZED, status, form,
                answer.preview());
    }

    private static String brief(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
