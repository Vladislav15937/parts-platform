package ru.partsflow.migration.bazon;

import ru.partsflow.catalog.BazonVehicles;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Сопоставляет текст машины из строки выгрузки Bazon с записями общего
 * справочника {@code catalog.bazon_*} (задача 0377, п. 4).
 *
 * <p><b>Правила (названы в PR):</b>
 * <ul>
 *   <li>берётся <b>первое</b> значение каждого поля — в названии стоит оно же
 *       (поля «Марка», «Модель», «Кузов», «Двигатель» у Bazon — списки через
 *       запятую);</li>
 *   <li>марка — по имени без регистра; модель — внутри этой марки; кузов — среди
 *       кузовов этой модели; двигатель — среди двигателей этой марки. Сначала
 *       сравнение без регистра, затем без регистра, пробелов, дефисов и точек —
 *       как сопоставлялись модели при сборке справочника;</li>
 *   <li>имя неоднозначно (две записи с одним именем в области) — берётся запись
 *       с меньшим номером: слова у них одинаковы, а название строится из слов;</li>
 *   <li><b>всё или ничего.</b> Если хоть одно непустое поле строки не нашлось,
 *       ссылок не записывается вовсе: позиция ввозится без машины, название
 *       остаётся из файла (решение владельца 22.10.2026, вариант «а»). Частичная
 *       ссылка (только марка) молча стёрла бы из названия слова, которые не
 *       сопоставились, при первой же пересборке по полям.</li>
 * </ul>
 *
 * <p>Справочник читается лениво и кэшируется на время импорта: марок 149,
 * моделей на марку — до нескольких сотен.
 */
final class BazonVehicleMatcher {

    private final Connection connection;
    private Map<String, Entry> marks;
    private final Map<Long, Map<String, Entry>> models = new HashMap<>();
    private final Map<Long, Map<String, Entry>> bodies = new HashMap<>();
    private final Map<Long, Map<String, Entry>> motors = new HashMap<>();

    private record Entry(long id, String name) {
    }

    BazonVehicleMatcher(Connection connection) {
        this.connection = connection;
    }

    /**
     * @param selection что записать; {@code null} — ничего (в файле машины нет
     *                  или она не сопоставилась целиком)
     * @param words     слова записей справочника — для названия позиции
     * @param unmatched названия полей, не нашедшихся в справочнике, как в файле
     *                  («Модель «Camry X»»); пусто — сопоставилось всё или
     *                  сопоставлять было нечего
     */
    record Result(BazonVehicles.Selection selection, BazonVehicles.Words words,
                  java.util.List<String> unmatched) {

        boolean matched() {
            return selection != null;
        }
    }

    Result match(String markText, String modelText, String bodyText, String engineText)
            throws SQLException {
        String mark = first(markText);
        String model = first(modelText);
        String body = first(bodyText);
        String engine = first(engineText);
        if (mark == null && model == null && body == null && engine == null) {
            return new Result(null, null, java.util.List.of());
        }
        java.util.List<String> unmatched = new java.util.ArrayList<>();
        Entry markEntry = mark == null ? null : find(marks(), mark);
        if (mark == null) {
            // Модель, кузов или двигатель без марки: опоры нет.
            if (model != null) unmatched.add("Модель «" + model + "»");
            if (body != null) unmatched.add("Кузов «" + body + "»");
            if (engine != null) unmatched.add("Двигатель «" + engine + "»");
            return new Result(null, null, unmatched);
        }
        if (markEntry == null) {
            unmatched.add("Марка «" + mark + "»");
            if (model != null) unmatched.add("Модель «" + model + "»");
            if (body != null) unmatched.add("Кузов «" + body + "»");
            if (engine != null) unmatched.add("Двигатель «" + engine + "»");
            return new Result(null, null, unmatched);
        }
        long markId = markEntry.id();
        Entry modelEntry = null;
        if (model != null) {
            modelEntry = find(modelsOf(markId), model);
            if (modelEntry == null) {
                unmatched.add("Модель «" + model + "»");
            }
        }
        Entry bodyEntry = null;
        if (body != null) {
            if (modelEntry == null) {
                // Кузов ищется внутри модели: без неё область неизвестна.
                unmatched.add("Кузов «" + body + "»");
            } else {
                bodyEntry = find(bodiesOf(modelEntry.id()), body);
                if (bodyEntry == null) {
                    unmatched.add("Кузов «" + body + "»");
                }
            }
        }
        Entry motorEntry = null;
        if (engine != null) {
            motorEntry = find(motorsOf(markId), engine);
            if (motorEntry == null) {
                unmatched.add("Двигатель «" + engine + "»");
            }
        }
        if (!unmatched.isEmpty()) {
            return new Result(null, null, unmatched);
        }
        return new Result(new BazonVehicles.Selection(markId, id(modelEntry), id(bodyEntry),
                id(motorEntry)),
                new BazonVehicles.Words(markEntry.name(), name(modelEntry), name(bodyEntry),
                        name(motorEntry)),
                java.util.List.of());
    }

    private static Long id(Entry e) {
        return e == null ? null : e.id();
    }

    private static String name(Entry e) {
        return e == null ? null : e.name();
    }

    private static String first(String value) {
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        String head = (comma < 0 ? value : value.substring(0, comma)).strip();
        return head.isEmpty() ? null : head;
    }

    /** Ключ сравнения: без регистра; второй ключ — ещё и без пробелов, дефисов и точек. */
    private static String exactKey(String name) {
        return name.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String looseKey(String name) {
        return exactKey(name).replaceAll("[\\s\\-.]+", "");
    }

    private static Entry find(Map<String, Entry> byKey, String text) {
        Entry found = byKey.get("=" + exactKey(text));
        return found != null ? found : byKey.get("~" + looseKey(text));
    }

    private static void put(Map<String, Entry> map, String name, long id) {
        // Меньший номер выигрывает: запросы идут по возрастанию id, значит первый.
        Entry entry = new Entry(id, name);
        map.putIfAbsent("=" + exactKey(name), entry);
        map.putIfAbsent("~" + looseKey(name), entry);
    }

    private Map<String, Entry> marks() throws SQLException {
        if (marks == null) {
            Map<String, Entry> loaded = new HashMap<>();
            read("SELECT id, name FROM catalog.bazon_mark ORDER BY id", null, loaded);
            marks = loaded;
        }
        return marks;
    }

    private Map<String, Entry> modelsOf(long markId) throws SQLException {
        Map<String, Entry> found = models.get(markId);
        if (found == null) {
            found = new HashMap<>();
            read("SELECT id, name FROM catalog.bazon_model WHERE bazon_mark_id = ? ORDER BY id",
                    markId, found);
            models.put(markId, found);
        }
        return found;
    }

    private Map<String, Entry> bodiesOf(long modelId) throws SQLException {
        Map<String, Entry> found = bodies.get(modelId);
        if (found == null) {
            found = new HashMap<>();
            read("""
                    SELECT b.id, b.name FROM catalog.bazon_body b
                      JOIN catalog.bazon_generation g ON g.id = b.bazon_generation_id
                     WHERE g.bazon_model_id = ? AND b.name IS NOT NULL ORDER BY b.id""",
                    modelId, found);
            bodies.put(modelId, found);
        }
        return found;
    }

    private Map<String, Entry> motorsOf(long markId) throws SQLException {
        Map<String, Entry> found = motors.get(markId);
        if (found == null) {
            found = new HashMap<>();
            read("SELECT id, name FROM catalog.bazon_motor WHERE bazon_mark_id = ? ORDER BY id",
                    markId, found);
            motors.put(markId, found);
        }
        return found;
    }

    private void read(String sql, Long parameter, Map<String, Entry> into) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (parameter != null) {
                ps.setLong(1, parameter);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    put(into, rs.getString(2), rs.getLong(1));
                }
            }
        }
    }
}
