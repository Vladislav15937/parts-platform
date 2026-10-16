package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Чужая правка того же поля карточки — задача 0307.
 *
 * <p>Ответ владельца продукта 16.10.2026 дословно: «1-б, и только если правили
 * одно и то же поле сделать предупреждение с просьбой перезагрузить страницу».
 * Экран шлёт изменённые поля и рядом — что человек видел при открытии формы
 * ({@code seen}). Поле считается занятым, если увиденное разошлось с базой,
 * а записать хотят не то, что там уже стоит: одинаковая правка двух людей —
 * не конфликт (критерий 5), разные правки разных полей — тоже.
 *
 * <p>Сравнение идёт в виде формы правки ({@code GET /api/parts/{id}/editable}),
 * а не по колонкам: экран видел именно его, и именно его он присылает обратно.
 * Отсюда один перебор на все поля, включая номер производителя и машину
 * позиции, которые лежат мимо сущности.
 */
final class PartEditConflicts {

    /**
     * Название поля — словами строки формы правки ({@code PartEditForm.tsx}).
     * Сверяет их с экраном {@code PartPartialEditTest}: отказ, назвавший поле
     * не так, как подписана строка, заставляет искать, о чём речь.
     */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("number", "№ позиции"),
            Map.entry("code", "Номер товара"),
            Map.entry("condition", "Состояние"),
            Map.entry("qualityGrade", "Оценка состояния"),
            Map.entry("price", "Цена"),
            Map.entry("minPrice", "Минимальная цена"),
            Map.entry("costPrice", "Себестоимость"),
            Map.entry("installationPrice", "Цена установки"),
            Map.entry("description", "Комментарий"),
            Map.entry("warranty", "Гарантия и условия возврата"),
            Map.entry("pickup", "Самовывоз"),
            Map.entry("deliveryPayment", "Доставка и оплата"),
            Map.entry("dromKind", "Вид детали"),
            Map.entry("dromSection", "Раздел"),
            Map.entry("applicability", "Подходит"),
            Map.entry("mayFit", "Может подойти"),
            Map.entry("characteristics", "Характеристики"),
            Map.entry("adDate", "Дата (Дром)"),
            Map.entry("note", "Заметка"),
            Map.entry("barcode", "Ст. баркод"),
            Map.entry("title", "Наименование"),
            Map.entry("sideFr", "Передний / Задний"),
            Map.entry("sideLr", "Левый / Правый"),
            Map.entry("manufacturer", "Производитель"),
            Map.entry("authenticity", "Оригинальность"),
            Map.entry("oem", "Номер производителя"),
            Map.entry("crosses", "Кросс-номера"),
            Map.entry("marking", "Маркировка"),
            Map.entry("color", "Цвет"),
            Map.entry("section", "Секция"),
            Map.entry("weightKg", "Вес, кг"),
            Map.entry("lengthMm", "Длина, мм"),
            Map.entry("widthMm", "Ширина, мм"),
            Map.entry("heightMm", "Высота, мм"),
            Map.entry("packageWeightKg", "Вес в упаковке, кг"),
            Map.entry("packageLengthMm", "Длина упаковки, мм"),
            Map.entry("packageWidthMm", "Ширина упаковки, мм"),
            Map.entry("packageHeightMm", "Высота упаковки, мм"),
            Map.entry("donorCode", "Номер донора"),
            Map.entry("brand", "Марка"),
            Map.entry("model", "Модель"),
            Map.entry("body", "Модель кузова"),
            Map.entry("engine", "Модель двигателя"),
            Map.entry("year", "Год выпуска"),
            Map.entry("equipment", "Комплектация"),
            Map.entry("generation", "Поколение"),
            Map.entry("textBlock", "Текстовый блок"),
            Map.entry("videoUrl", "Видео"),
            Map.entry("published", "Выгружать"),
            Map.entry("legacyCode", "Старые данные"),
            Map.entry("dromAdNumber", "Номер объявления Дрома"),
            // В форме строки нет — ячейку правят перестановкой, — но ключ в запросе бывает.
            Map.entry("storageCellId", "Ячейка"));

    private PartEditConflicts() {
    }

    /**
     * Названия полей, которые успел изменить другой.
     *
     * @param current         карточка в базе сейчас, видом формы правки
     * @param wanted          тело запроса: что хотят записать
     * @param seen            что человек видел у изменённых полей
     * @param priceArithmetic цена приходит операцией: в {@code wanted} не новая
     *                        цена, а процент или сумма, и сравнивать её с текущей
     *                        нельзя — увиденное разошлось, значит конфликт
     */
    static List<String> conflicting(JsonNode current, JsonNode wanted, JsonNode seen,
                                    boolean priceArithmetic) {
        List<String> taken = new ArrayList<>();
        for (Iterator<String> keys = seen.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            // Сверяется только то, что правят этим запросом, и только поля формы:
            // увиденное у нетронутого поля ничего не решает.
            if (!wanted.has(key) || !current.has(key) || !LABELS.containsKey(key)) {
                continue;
            }
            if (same(seen.get(key), current.get(key))) {
                continue;
            }
            boolean arithmetic = key.equals("price") && priceArithmetic;
            if (!arithmetic && same(wanted.get(key), current.get(key))) {
                continue;
            }
            taken.add(LABELS.get(key));
        }
        return taken;
    }

    /** Текст отказа — один на все поля; номеров в нём нет, только слова экрана. */
    static String message(List<String> labels) {
        String tail = " уже изменил другой человек. Перезагрузите страницу и повторите правку";
        if (labels.size() == 1) {
            return "Поле «" + labels.get(0) + "»" + tail;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                names.append(i == labels.size() - 1 ? " и " : ", ");
            }
            names.append('«').append(labels.get(i)).append('»');
        }
        return "Поля " + names + tail;
    }

    /**
     * Одно ли это значение для человека.
     *
     * <p>Пусто, {@code null} и пустая строка — одно: форма показывает их
     * одинаково «не заполнено». Числа — по значению ({@code 1200} и
     * {@code 1200.00}), и число строкой тоже: номер позиции экран шлёт строкой
     * цифр, чтобы не округлить его. Текст — без краёв: их срезает и запись.
     */
    static boolean same(JsonNode a, JsonNode b) {
        if (blank(a) && blank(b)) {
            return true;
        }
        if (blank(a) || blank(b)) {
            return false;
        }
        BigDecimal left = decimal(a);
        BigDecimal right = decimal(b);
        if (left != null && right != null && (a.isNumber() || b.isNumber())) {
            return left.compareTo(right) == 0;
        }
        if (a.isTextual() && b.isTextual()) {
            return a.asText().strip().equals(b.asText().strip());
        }
        return a.equals(b);
    }

    private static boolean blank(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode()
                || (node.isTextual() && node.asText().isBlank());
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().strip());
            } catch (NumberFormatException notNumber) {
                return null;
            }
        }
        return null;
    }
}
