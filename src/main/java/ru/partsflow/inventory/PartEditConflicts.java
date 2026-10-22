package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * <p><b>Не сохраняются только занятые поля, остальные сохраняются</b> (задача
 * 0313, ответ владельца продукта 17.10.2026 дословно: «3 - не сохраняются только
 * конфликтные поля, а остальные сохраняются»). До неё отказ по одному полю
 * не сохранял из запроса ничего — решение исполнителя в PR 449, отменённое.
 * Единица — поле формы, кроме связок ({@link #BUNDLES}): их нельзя записать
 * врозь, и занятый член связки снимает с записи всю связку.
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
            Map.entry("bazonVehicle", "Машина из справочника"),
            Map.entry("generation", "Поколение"),
            Map.entry("textBlock", "Текстовый блок"),
            Map.entry("textBlockId", "Текстовый блок"),
            Map.entry("videoUrl", "Видео"),
            Map.entry("published", "Выгружать"),
            Map.entry("legacyCode", "Старые данные"),
            Map.entry("dromAdNumber", "Номер объявления Дрома"),
            // В форме строки нет — ячейку правят перестановкой, — но ключ в запросе бывает.
            Map.entry("storageCellId", "Ячейка"));

    /**
     * Поля, которые пишутся только вместе.
     *
     * <p>Номер производителя и кросс-номера — одна связка: набор номеров
     * переписывается целиком, когда тронут хоть один ({@code PartIdentity.applyOem}),
     * и номер, ставший главным, мог стоять среди кросс-номеров, — записать одну
     * половину набора поверх чужой второй значит собрать набор, которого не видел
     * ни один из двоих. Цена с арифметикой связана с {@code priceOp}, но это
     * не поле формы: операция просто уходит вместе с ценой ({@link #PRICE_OP}).
     *
     * <p>Марка и модель — <b>не</b> связка, и это проверено по коду: у позиции
     * свои колонки на каждое ({@code PartIdentity.apply}, по одной на поле), и
     * «пустое своё — по полю» решено в 0302 — марку правят, не трогая модель.
     * Так же размеры, стороны и цены с минимальной ценой: каждое пишется само.
     */
    private static final List<Set<String>> BUNDLES = List.of(Set.of("oem", "crosses"));

    /** Указание, что сделать с ценой: не поле формы, а часть цены. */
    static final String PRICE_OP = "priceOp";

    private PartEditConflicts() {
    }

    /** Подпись поля словами строки формы; ключ без строки — сам ключ не печатается. */
    static String label(String key) {
        return LABELS.getOrDefault(key, "поле формы");
    }

    /**
     * Что из запроса не сохраняется: занятые поля и пришедшие вместе с ними
     * члены их связок. Порядок — порядок занятых, за каждым — его связка.
     *
     * @param taken  ключи занятых полей ({@link #conflicting})
     * @param wanted тело запроса: член связки не пришёл — и снимать нечего
     */
    static List<PartService.NotSaved> notSaved(List<String> taken, JsonNode wanted) {
        Map<String, PartService.NotSaved> out = new LinkedHashMap<>();
        for (String key : taken) {
            out.put(key, new PartService.NotSaved(key, label(key), label(key)));
        }
        for (String key : taken) {
            for (Set<String> bundle : BUNDLES) {
                if (!bundle.contains(key)) {
                    continue;
                }
                for (String partner : bundle) {
                    if (wanted.has(partner) && !out.containsKey(partner)) {
                        out.put(partner, new PartService.NotSaved(partner, label(partner), label(key)));
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * Ключи полей, которые успел изменить другой.
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
            taken.add(key);
        }
        return taken;
    }

    /**
     * Текст отказа, когда занято всё присланное, — один на все поля; номеров
     * в нём нет, только слова экрана.
     */
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
