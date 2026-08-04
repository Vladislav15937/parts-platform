package ru.partsflow.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Русское написание машины в поисковом запросе приводится к латинскому.
 *
 * <p>Тем же словарём пользуется поиск колёс: там своя беда с размерами
 * («225 55 18» против «225/55 R18»), но марку покупатель называет так же
 * по-русски, и «бриджстоун» находил ноль при четырёх позициях на складе.
 *
 * <p><b>Зачем.</b> Покупатель звонит и говорит «есть фара на камри?».
 * Продавец набирает как слышит — и получает ноль при 1218 позициях от Camry
 * на складе, потому что в заголовке стоит «Фара Toyota Camry 2007 лев.
 * (б/у)», а поиск идёт по нему. Данные есть, спрашивают о них другими
 * знаками — ровно то же, что с размерами колёс («225 55 18» против
 * «225/55 R18») и с кодами ячеек на этикетках.
 *
 * <p><b>Почему словарь, а не транслитерация.</b> У одного латинского имени
 * несколько ходовых русских написаний: «Аутлендер» и «Оутлендер», «СРВ»
 * и «ЦРВ», «Витц» и «Виц». Правило, выдающее одно, промахивается ровно там,
 * где человек написал второе, — и промах этот молчаливый: продавец видит
 * пустую выдачу и отвечает «нет такого». Словарь проверяем и наполняется
 * миграцией, как справочник видов деталей: и тот и другой — словарь живого
 * языка, из данных он не выводится.
 *
 * <p><b>Двусловные раньше однословных.</b> «Королла Филдер» — отдельная
 * модель, а не Королла с уточнением: разобрав по одному слову, поиск отдал бы
 * все Короллы, среди которых Филдера пришлось бы искать глазами.
 *
 * <p><b>Кэш надолго.</b> Справочник наполняется миграцией, то есть меняется
 * с релизом, а не в течение дня. Читать его на каждый запрос продавца значит
 * платить обращением к базе за то, что не менялось с прошлого развёртывания.
 */
@Component
public class VehicleWords {

    /** Длиннее двух слов имён моделей у нас нет — «Mark II Wagon Qualis» ищут по «квалис». */
    private static final int MAX_WORDS = 2;

    private final JdbcTemplate jdbc;

    /** Написание → латинское имя. Заполняется один раз, при первом запросе. */
    private volatile Map<String, String> aliases;

    public VehicleWords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Заменяет русские написания марок и моделей на латинские имена.
     *
     * <p>Нераспознанное остаётся как есть: «фара лев» так и ищется словами.
     * Это важно — запрос, из которого выкинули непонятое, находит не то,
     * а продавец об этом не узнает.
     */
    public String translate(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        Map<String, String> known = known();
        if (known.isEmpty()) {
            return query;
        }

        String[] words = query.trim().split("\\s+");
        List<String> result = new ArrayList<>(words.length);

        int at = 0;
        while (at < words.length) {
            int taken = 0;
            String found = null;
            // Длинное сочетание раньше короткого: «королла филдер» — своя
            // модель, и разобранная по слову она стала бы просто Короллой.
            for (int size = Math.min(MAX_WORDS, words.length - at); size >= 1; size--) {
                String phrase = String.join(" ", List.of(words).subList(at, at + size))
                        .toLowerCase();
                String latin = known.get(phrase);
                if (latin != null) {
                    found = latin;
                    taken = size;
                    break;
                }
            }
            if (found == null) {
                result.add(words[at]);
                at++;
            } else {
                result.add(found);
                at += taken;
            }
        }
        return String.join(" ", result);
    }

    /** Сбрасывает кэш: нужен тестам, которые заводят написания на ходу. */
    public void forget() {
        aliases = null;
    }

    private Map<String, String> known() {
        Map<String, String> loaded = aliases;
        if (loaded == null) {
            loaded = load();
            aliases = loaded;
        }
        return loaded;
    }

    /**
     * Марки и модели читаются одним запросом.
     *
     * <p>Схема указана в запросе явно, поэтому чтение не зависит
     * от {@code search_path}: справочник общий на ячейку, а не арендаторский.
     */
    private Map<String, String> load() {
        Map<String, String> known = new HashMap<>();
        try {
            jdbc.query("""
                    SELECT a.alias, b.name FROM catalog.brand_alias a
                      JOIN catalog.brand b ON b.id = a.brand_id
                    UNION ALL
                    SELECT a.alias, m.name FROM catalog.model_alias a
                      JOIN catalog.model m ON m.id = a.model_id
                    UNION ALL
                    -- Шинные марки живут своей таблицей: у Bridgestone нет
                    -- строки в справочнике машин, а спрашивают о ней так же —
                    -- «есть зимняя бриджстоун 225 на 18».
                    SELECT a.alias, a.name FROM catalog.tyre_brand_alias a""",
                    rs -> {
                        known.put(rs.getString("alias").toLowerCase(), rs.getString("name"));
                    });
        } catch (RuntimeException e) {
            // Словаря ещё нет — миграция не накатана. Поиск при этом обязан
            // работать: латиницей он искал и до появления словаря.
            return Map.of();
        }
        return known;
    }
}
