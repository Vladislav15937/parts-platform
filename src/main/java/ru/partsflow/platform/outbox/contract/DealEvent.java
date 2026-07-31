package ru.partsflow.platform.outbox.contract;

import java.math.BigDecimal;

/**
 * Состояние сделки на момент события.
 *
 * <p>Едет в {@code deal.issued.v1}, {@code deal.cancelled.v1}
 * и {@code deal.returned.v1}. Тип события отвечает на вопрос «что случилось»,
 * payload — «как теперь выглядит документ»: два потребителя одного события
 * обычно хотят разного, и складывать в payload признак «что именно
 * произошло» значит дублировать имя типа.
 *
 * <p>{@code number} может быть пустым только у документа, который ещё
 * не сохранён, — то есть никогда в событии: номер генерирует БД, и Hibernate
 * вычитывает его через {@code @Generated(event = INSERT)}.
 */
public record DealEvent(Long id,
                        Long number,
                        String status,
                        BigDecimal total,
                        BigDecimal paid) {
}
