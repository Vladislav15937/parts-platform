package ru.partsflow.publishing;

import ru.partsflow.publishing.drom.DromSyncRecord;

import java.time.Instant;

/**
 * Часть изменений выгрузки не ушла на Дром и ещё не покрыта полным прайсом
 * (задача 0271).
 *
 * <p>Итог последней отправки ({@link DeliveryStatus}) говорит про последнюю
 * попытку, и малая принятая дельта после неотправленной честно отвечает
 * «принято». Этот признак — отдельная память: изменения сняли с очереди,
 * а этой выгрузке они не достались (дельта сверх лимита, отказ «нужен
 * человек», пропуск в интервале после него), либо попытка сорвалась временным
 * сбоем — 5xx, тишина, обрыв (ответ владельца 26.10.2026: признак на любую
 * неотправку; повтор при этом идёт, но гасит признак только забор прайса). Гаснет он здесь, на чтении,
 * когда Дром скачал полный прайс позже момента неотправки — ответ владельца
 * 12.10.2026, вариант «а» (tasks/0271, «Вопрос владельцу продукта — когда
 * признак гаснет»). Колонку при этом никто не обнуляет: решает сравнение двух
 * фактов, и разойтись им нечему.
 *
 * <p>Слова живут на экране, как и у итога отправки: сервер отдаёт причину
 * кодом из того же перечисления, что и итог, и предел пакета — тем, что
 * записан при неотправке, а не копией константы клиента.
 *
 * @param at           когда изменения последний раз сняли с очереди без отправки
 * @param code         причина — тот же {@link DeliveryStatus.Code}, что у итога
 * @param httpStatus   статус ответа Дрома, если он был (у 5xx его называет экран)
 * @param limitBytes   предел пакета, если причина — лимит
 * @param fullPullDays через сколько суток Дром самое позднее заберёт полный прайс
 */
public record UnsentChanges(Instant at, DeliveryStatus.Code code, Integer httpStatus,
                            Integer limitBytes, int fullPullDays) {

    /**
     * Дром забирает прайс по ссылке раз в трое суток на бесплатном размещении
     * (раз в сутки — только при платном позиционировании), источник —
     * {@code docs/drom-integration.md} §1. Настройки забора у нас нет: частоту
     * выбирает площадка, а не владелец, — поэтому число одно и здесь, а экран
     * его не повторяет.
     */
    static final int FULL_PULL_DAYS = 3;

    /**
     * @return {@code null}, если неотправленного нет или оно уже покрыто;
     *         у выключенной выгрузки и не у Дрома — тоже: забора у выключенной
     *         нет, и «обновится при заборе» было бы обещанием
     */
    static UnsentChanges of(String marketplace, String accountStatus,
                            Instant unsentAt, String unsentError, Instant lastDownloadAt) {
        if (!"DROM".equals(marketplace) || "PAUSED".equals(accountStatus)
                || unsentAt == null || unsentError == null) {
            return null;
        }
        if (lastDownloadAt != null && lastDownloadAt.isAfter(unsentAt)) {
            return null;
        }
        DeliveryStatus reason = DeliveryStatus.refused(unsentAt, unsentError);
        return new UnsentChanges(unsentAt, reason.code(), reason.httpStatus(),
                DromSyncRecord.tooBigLimitBytes(unsentError), FULL_PULL_DAYS);
    }
}
