package ru.partsflow.inventory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Адрес хранения из пяти частей: склад, зона, ряд, секция, полка —
 * {@code 01-01-03-01-02} (задача 0368).
 *
 * <p><b>Почему текст, а не справочник.</b> Решение владельца продукта
 * 19.10.2026 «Всё как у bazon» (раздел «Ответ владельца — 19 октября 2026»
 * в {@code tasks/0368-…}): у ориентира адрес заранее не заводится — он
 * рождается этикеткой, напечатанной диапазоном, и привязкой позиции. Поэтому
 * адрес живёт в {@code part.section} (колонка «Секция», та же, куда лёг адрес
 * переехавшего клиента), а части из него выводятся разбором: отбор по частям
 * (0355) может взять их тем же разбором или {@code split_part} в запросе,
 * не заводя колонок.
 *
 * <p><b>Существование полки не проверяется, формат — проверяется.</b> Опечатка
 * «01-01-3-1-2» не должна давать новую полку рядом с настоящей, поэтому
 * короткая часть дополняется нулём до двух цифр: это та же полка
 * {@code 01-01-03-01-02}, а не другая. Буква, шестая часть или трёхзначное
 * число — уже не адрес, и это отказ словами.
 */
public record StorageAddress(int warehouse, int zone, int row, int section, int shelf) {

    /** Пример, которым отказ объясняет схему. */
    public static final String EXAMPLE = "01-01-03-01-02";

    /**
     * Пять групп по одной-две цифры через дефис. Пробелы по краям частей
     * допускаются: «01 - 01 - 03 - 01 - 02» набрано руками, а не другим
     * адресом. Длинное тире и минус приводятся к дефису там же — их подставляет
     * автозамена телефона.
     */
    private static final Pattern FORM = Pattern.compile(
            "\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*");

    /**
     * Разбор адреса.
     *
     * @return пусто, если это не адрес по схеме — в том числе прежняя ячейка
     *         со свободным названием («А-01-1»): она остаётся как есть
     *         и называется «не по схеме», а не переименовывается молча
     */
    public static Optional<StorageAddress> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORM.matcher(text.replace('–', '-').replace('—', '-').replace('−', '-'));
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new StorageAddress(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                Integer.parseInt(m.group(5))));
    }

    /** Записан ли текст по схеме — для пометки «не по схеме». */
    public static boolean bySchema(String text) {
        return parse(text).isPresent();
    }

    /** {@code 01-01-03-01-02}: так он напечатан на этикетке и так хранится. */
    public String text() {
        return "%02d-%02d-%02d-%02d-%02d".formatted(warehouse, zone, row, section, shelf);
    }
}
