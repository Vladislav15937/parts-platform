package ru.partsflow.publishing;

import java.time.Instant;

/**
 * Итог отправки дельт на Дром для одной выгрузки — то, что карточка говорит
 * словами (задача 0268).
 *
 * <p>Сервер отдаёт состояние и код, а не слова: слова живут в одном словаре
 * на экране, и пятая копия словаря обходится дороже первой. Код ответа Дрома
 * (<code>ERROR_REASON_…</code>) наружу не идёт вовсе — только наше перечисление;
 * сырой текст остаётся в {@code Account.lastError} ради раскрывающегося
 * «Ответ Дрома».
 *
 * <p><b>Откуда берётся.</b> Из колонок выгрузки, а не из {@code publication_log}:
 * журналу запланирована чистка старше 90 дней, и после неё карточка сказала бы
 * «отправок не было» про выгрузку, которой отправляли вчера. Новой колонки нет —
 * код читается из текста {@code last_error}; для двух исходов, у которых
 * текст нашего авторства, он начинается с метки ({@link #NO_ANSWER_PREFIX},
 * {@link #TOO_BIG_PREFIX}), иначе сетевой сбой было бы не отличить от ответа.
 *
 * @param state одно из {@link State}; {@code null} у выгрузки, у которой
 *              отправки нет по определению (выключена, не Дром) — «не подключено»
 *              про неё было бы неправдой
 * @param at    когда была последняя попытка (при любом исходе); пусто, пока
 *              попыток не было
 * @param code  только при {@code REFUSED}: одно из {@link Code}
 */
public record DeliveryStatus(State state, Instant at, Code code) {

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
        PACKET_NOT_FOUND, AUTH_FAILED, EMPTY_REQUEST, TOO_BIG, NO_ANSWER, OTHER
    }

    /** Метка текста {@code last_error} при сетевом сбое (HTTP 0). */
    public static final String NO_ANSWER_PREFIX = "Нет ответа от Дрома: ";

    /** Метка текста {@code last_error}, когда дельта не влезла в лимит. */
    public static final String TOO_BIG_PREFIX = "дельта превысила лимит";

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
            return new DeliveryStatus(State.NOT_CONNECTED, lastSyncAt, null);
        }
        if (!hasPacket) {
            return new DeliveryStatus(State.NO_PACKET, lastSyncAt, null);
        }
        if (!hasKey) {
            return new DeliveryStatus(State.NO_KEY, lastSyncAt, null);
        }
        if (lastSyncAt == null) {
            return new DeliveryStatus(State.WAITING, null, null);
        }
        if (lastError == null) {
            return new DeliveryStatus(State.ACCEPTED, lastSyncAt, null);
        }
        return new DeliveryStatus(State.REFUSED, lastSyncAt, codeOf(lastError));
    }

    static Code codeOf(String lastError) {
        if (lastError.startsWith(NO_ANSWER_PREFIX)) {
            return Code.NO_ANSWER;
        }
        if (lastError.startsWith(TOO_BIG_PREFIX)) {
            return Code.TOO_BIG;
        }
        if (lastError.contains("ERROR_REASON_PACKET_NOT_FOUND")) {
            return Code.PACKET_NOT_FOUND;
        }
        if (lastError.contains("ERROR_REASON_AUTH_FAILED")) {
            return Code.AUTH_FAILED;
        }
        if (lastError.contains("ERROR_REASON_EMPTY_REQUEST")) {
            return Code.EMPTY_REQUEST;
        }
        return Code.OTHER;
    }
}
