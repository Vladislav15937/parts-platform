package ru.partsflow.publishing.drom;

import java.util.List;

/**
 * Строка файла шин и дисков — ровно то, что уходит в одну запись CSV Bazon
 * (25 колонок, порядок и слова — {@link DromWheelWriter#HEADER}).
 *
 * <p>Все значения уже словами файла: писатель ничего не пересчитывает, только
 * ставит кавычки. Пустое — {@code null}: в файле это {@code ""}, как у Bazon.
 *
 * <p>Отдельный тип от {@link DromOffer} по-прежнему не дублирование: у шины и
 * диска свои пятнадцать колонок, которых у запчасти нет.
 *
 * @param article   «Артикул»: русская «К» (U+041A) и номер позиции (задача 0334)
 * @param type      «шина», «диск» или «колесо» (в сборе: обе половины заполнены)
 * @param inSet     «Количество в комплекте» — за сколько колёс названа цена
 * @param wear      «Износ шин» вместе со знаком процента: {@code 30%}
 * @param price     цена целым, как в карточке: на штуку её никто не делит
 * @param photos    ссылки на снимки в порядке карточки. «Статуса» в записи нет:
 *                  он у всех строк «В наличии» — что нельзя купить, в файл
 *                  не попадает (задача 0339)
 */
public record DromWheelOffer(
        String article,
        String type,
        int inSet,
        String discBrand,
        String discModel,
        String discDiameter,
        String discType,
        String discWidth,
        String offset,
        String boltPattern,
        String hubBore,
        String tyreBrand,
        String tyreModel,
        String tyreWidth,
        String tyreHeight,
        String tyreDiameter,
        String season,
        String year,
        String wear,
        String condition,
        String comment,
        String price,
        List<String> photos) {
}
