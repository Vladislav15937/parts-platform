package ru.partsflow.platform.outbox.contract;

import java.math.BigDecimal;

/**
 * Состояние карточки на момент события.
 *
 * <p>Едет в {@code part.created.v1} и {@code part.price_changed.v1}: событие
 * несёт состояние целиком, а не приращение. Это и делает доставку
 * at-least-once безопасной — повтор перезаписывает тем же, тогда как
 * «уменьшить остаток на N» пришлось бы защищать таблицей обработанных
 * на стороне потребителя.
 *
 * <p><b>Версия — в имени типа события, не в поле.</b> Добавить поле можно
 * в {@code v1}: потребитель, который о нём не знает, его не заметит. Убрать
 * или переименовать — нельзя, это {@code v2} и второй тип, который какое-то
 * время едет рядом с первым.
 */
public record PartEvent(Long id,
                        String publicCode,
                        String title,
                        BigDecimal price,
                        String status) {
}
