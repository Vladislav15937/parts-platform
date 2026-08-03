package ru.partsflow.publishing.drom;

import java.math.BigDecimal;
import java.util.List;

/**
 * Позиция прайса шин и дисков — ровно то, что уходит в один {@code <offer>}.
 *
 * <p>Отдельный тип от {@link DromOffer}, и это не дублирование: у площадки
 * для шин свой набор полей — маркировка, сезон, шиповка, износ, число шин
 * в комплекте, — а у запчасти их нет и быть не может. Общий тип означал бы
 * пятнадцать пустых полей у каждой фары и пятнадцать у каждой шины.
 *
 * @param marking  типоразмер с индексами, как того требует площадка:
 *                 {@code 225/55R18 100Q TL}
 * @param inSet    сколько шин в комплекте, за который названа цена
 * @param quantity сколько штук на складе
 * @param wearPercent износ в процентах — площадка меряет им, мы миллиметрами
 *                 остатка протектора; пересчёт объяснён в {@link DromWheelGenerator}
 */
public record DromWheelOffer(
        String orderCode,
        String name,
        String model,
        String marking,
        Integer inSet,
        BigDecimal quantity,
        BigDecimal price,
        String condition,
        String season,
        String type,
        String spike,
        Integer year,
        Integer wearPercent,
        List<String> photos) {

    public boolean isAvailable() {
        return quantity != null && quantity.signum() > 0;
    }
}
