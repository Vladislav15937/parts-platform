package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Значения всех полей формы правки — для проверок, идущих перебором
 * по компонентам {@code UpdateRequest} (задачи 0307 и 0313).
 *
 * <p>Одно место на оба теста: новое поле формы получает значение здесь,
 * и оба перебора видят его разом. Две копии разошлись бы на первом новом поле.
 */
final class PartEditValues {

    /** Не поле карточки, а указание, что сделать с ценой. */
    static final Set<String> NOT_A_FIELD = Set.of("priceOp");

    private final ObjectMapper json;
    private final String suffix;
    private final long number;
    private final Long cellA;
    private final Long cellB;

    PartEditValues(ObjectMapper json, String suffix, long number, Long cellA, Long cellB) {
        this.json = json;
        this.suffix = suffix;
        this.number = number;
        this.cellA = cellA;
        this.cellB = cellB;
    }

    /** Заполнение позиции целиком — до проверок. */
    Map<String, JsonNode> initial() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("price", 4500);
        v.put("minPrice", 3000);
        v.put("costPrice", 1000);
        v.put("installationPrice", 500);
        v.put("condition", "USED");
        v.put("qualityGrade", "AS_NEW");
        v.put("description", "Комментарий исходный");
        v.put("note", "Заметка исходная");
        v.put("textBlock", "Блок исходный");
        v.put("videoUrl", "https://video.example/1");
        v.put("marking", "МАРК-1");
        v.put("manufacturer", "Koito");
        v.put("color", "чёрный");
        v.put("section", "С-1");
        v.put("barcode", "BC1-" + suffix);
        v.put("weightKg", 2.5);
        v.put("lengthMm", 100);
        v.put("widthMm", 200);
        v.put("heightMm", 300);
        v.put("packageLengthMm", 110);
        v.put("packageWidthMm", 210);
        v.put("packageHeightMm", 310);
        v.put("packageWeightKg", 3.5);
        v.put("storageCellId", cellA);
        v.put("published", true);
        v.put("warranty", "Гарантия 14 дней");
        v.put("pickup", "Самовывоз с Ткацкой");
        v.put("deliveryPayment", "Доставка ТК");
        v.put("dromKind", "Фара");
        v.put("dromSection", "Оптика");
        v.put("applicability", "Toyota Camry");
        v.put("characteristics", "Цвет: чёрный");
        v.put("mayFit", "Toyota Mark X");
        v.put("adDate", "2026-09-28");
        v.put("priceOp", "SET");
        v.put("number", number);
        v.put("code", "CODE1-" + suffix);
        v.put("title", "Фара исходная");
        v.put("sideLr", "LEFT");
        v.put("sideFr", "FRONT");
        v.put("authenticity", "Оригинал");
        v.put("oem", "81170-33670");
        v.put("crosses", "81170-33671");
        v.put("legacyCode", "LEG1-" + suffix);
        v.put("dromAdNumber", "1256963" + suffix);
        v.put("donorCode", "261");
        v.put("brand", "Toyota");
        v.put("model", "Camry");
        v.put("generation", "XV40");
        v.put("body", "ACV40");
        v.put("engine", "2AZ-FE");
        v.put("year", "2006");
        v.put("equipment", "полный привод");
        return nodes(v);
    }

    /** Новое значение каждого поля — другое, чем в {@link #initial()}. */
    Map<String, JsonNode> changed() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("price", 4600);
        v.put("minPrice", 3100);
        v.put("costPrice", 1100);
        v.put("installationPrice", 600);
        v.put("condition", "CONTRACT");
        v.put("qualityGrade", "WITH_DEFECTS");
        v.put("description", "Комментарий новый");
        v.put("note", "Заметка новая");
        v.put("textBlock", "Блок новый");
        v.put("videoUrl", "https://video.example/2");
        v.put("marking", "МАРК-2");
        v.put("manufacturer", "Depo");
        v.put("color", "белый");
        v.put("section", "С-2");
        v.put("barcode", "BC2-" + suffix);
        v.put("weightKg", 2.7);
        v.put("lengthMm", 101);
        v.put("widthMm", 201);
        v.put("heightMm", 301);
        v.put("packageLengthMm", 111);
        v.put("packageWidthMm", 211);
        v.put("packageHeightMm", 311);
        v.put("packageWeightKg", 3.7);
        v.put("storageCellId", cellB);
        v.put("published", false);
        v.put("warranty", "Гарантия 30 дней");
        v.put("pickup", "Самовывоз с Дальнего");
        v.put("deliveryPayment", "Доставка курьером");
        v.put("dromKind", "Фара противотуманная");
        v.put("dromSection", "Освещение");
        v.put("applicability", "Toyota Camry, Toyota Aurion");
        v.put("characteristics", "Цвет: белый");
        v.put("mayFit", "Toyota Windom");
        v.put("adDate", "2026-10-01");
        v.put("number", number + 1);
        v.put("code", "CODE2-" + suffix);
        v.put("title", "Фара новая");
        v.put("sideLr", "RIGHT");
        v.put("sideFr", "REAR");
        v.put("authenticity", "Копия");
        v.put("oem", "81170-33680");
        v.put("crosses", "81170-33681");
        v.put("legacyCode", "LEG2-" + suffix);
        v.put("dromAdNumber", "1256964" + suffix);
        v.put("donorCode", "262");
        v.put("brand", "Lexus");
        v.put("model", "ES350");
        v.put("generation", "XV50");
        v.put("body", "GSV40");
        v.put("engine", "2GR-FE");
        v.put("year", "2008");
        v.put("equipment", "передний привод");
        return nodes(v);
    }

    /** «Увиденное», которого в базе уже нет: значение, отличное от текущего. */
    JsonNode foreign(JsonNode current) {
        if (current == null || current.isNull()) {
            return json.getNodeFactory().textNode("чужое значение");
        }
        if (current.isBoolean()) {
            return json.getNodeFactory().booleanNode(!current.booleanValue());
        }
        if (current.isNumber()) {
            return json.getNodeFactory().numberNode(current.decimalValue().add(BigDecimal.valueOf(7)));
        }
        return json.getNodeFactory().textNode(current.asText() + " — чужая правка");
    }

    private Map<String, JsonNode> nodes(Map<String, Object> values) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, json.valueToTree(value)));
        return out;
    }

    /** Все поля формы — компоненты записи, кроме указания про цену. */
    static List<String> fieldKeys() {
        List<String> keys = new ArrayList<>();
        for (RecordComponent component : PartController.UpdateRequest.class.getRecordComponents()) {
            if (!NOT_A_FIELD.contains(component.getName())) {
                keys.add(component.getName());
            }
        }
        return keys;
    }

    static boolean same(JsonNode a, JsonNode b) {
        if (blank(a) && blank(b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }

    static boolean blank(JsonNode node) {
        return node == null || node.isNull() || (node.isTextual() && node.asText().isEmpty());
    }

    /**
     * Подписи строк формы правки — как их видит человек, из самого экрана,
     * а не переписанные сюда: иначе разошлись бы на первой правке подписи.
     */
    static Map<String, String> screenLabels() {
        try {
            String form = java.nio.file.Files.readString(
                    java.nio.file.Path.of("frontend/src/screens/PartEditForm.tsx"));
            Map<String, String> labels = new LinkedHashMap<>();
            var m = java.util.regex.Pattern.compile("key: '(\\w+)', label: '([^']+)'").matcher(form);
            while (m.find()) {
                labels.put(m.group(1), m.group(2));
            }
            assertThat(labels).as("подписи формы правки не нашлись в PartEditForm.tsx")
                    .containsKeys("price", "note", "oem");
            return labels;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
