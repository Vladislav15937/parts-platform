package ru.partsflow.migration.excel;

import ru.partsflow.inventory.WheelService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Колёсная строка файла переноса с Дрома: шина, колесо в сборе или диск
 * (задача 0291).
 *
 * <p><b>Узнаётся по «Прочим полям» и разделу, а не по наименованию.</b>
 * «Диск тормозной» и «Колесо запасное» — запчасти (у клиента таких двенадцать),
 * а «Гайка на колесо» стоит в разделе «Шины и диски → Аксессуары для колес»
 * и тоже остаётся запчастью. Колёсная — та, у которой в «Прочих полях» есть
 * служебная строка Дрома {@code model~tireFirmAndModel}, либо раздел «Шины
 * и диски» без подраздела, с подразделом «Шины» или «Диски».
 *
 * <p><b>Свойства читаются по ключу в квадратных скобках, а не по подписи.</b>
 * Подпись у Дрома меняется от рубрики к рубрике («Посадочный диаметр»
 * и «Диаметр» — оба {@code [marking]}), ключ — нет. Ключ {@code marking}
 * при этом несёт пять разных величин (маркировку, диаметр, ширину, высоту
 * профиля и каркас), и различаются они видом значения: {@code 19"},
 * {@code 265 мм}, {@code 65 %}.
 *
 * <p><b>Не разобралось — свойство пусто, и строка называется.</b> Угаданный
 * сезон или размер — это объявление клиента, переехавшее неправдой; пустое
 * поле с названной причиной владелец заполнит сам. Сам текст ячейки при этом
 * лежит у позиции дословно («Характеристики», задача 0292).
 *
 * <p>Класс только разбирает: строку {@code part} и остаток заводит импорт,
 * свойства колеса пишет {@link WheelService#attachWheel}.
 */
final class DromWheelCard {

    /** Раздел Дрома, под которым у клиента лежат шины, колёса и диски. */
    static final String SECTION = "Шины и диски";

    /** Служебная строка Дрома, стоящая у каждой колёсной карточки. */
    static final String MARKER = "model~tireFirmAndModel";

    /**
     * Ключи «Прочих полей», которые разбираются в свойства колеса. Договор
     * файла ({@code docs/drom-transfer-file.md}) обязан назвать каждый —
     * держит {@code DromTransferFileTest}.
     */
    static final List<String> KEYS = List.of(
            MARKER, "tireFirmAndModel", "inSetQuantity", "quantity", "year",
            "wheelSeason", "wheelTireWear", "marking", "wheelDiameter", "diskType",
            "discFirmAndModel", "discParameters", "wheelPcd", "predestination");

    /** Глубина протектора новой шины — то же число, что у переноса с Bazon и у прайса. */
    private static final BigDecimal NEW_TREAD_MM = new BigDecimal("8");

    /**
     * Сезон словами Дрома. «Зимние» — просто зимняя: шипы или липучку Дром
     * пишет отдельно, а выдумать их нельзя (`publishing/CLAUDE.md`, «Шиповка
     * не выдумывается»).
     */
    private static final Map<String, String> SEASONS = Map.of(
            "летние", "SUMMER", "зимние", "WINTER", "всесезонные", "ALL_SEASON");

    private static final Pattern KEYED = Pattern.compile("^(.*?)\\s*\\[([^\\[\\]]+)]\\s*:\\s*(.*)$");
    private static final Pattern LABELLED = Pattern.compile("^([^:\\[]+):\\s*(.*)$");

    /**
     * Маркировка шины: «265/65 R19», «265/60R18 114H», «265\65 R17», «245/40 ZR18 97Y».
     * Терпима к пробелам, обратной косой и «ZR»; индексы — необязательные.
     */
    private static final Pattern MARKING = Pattern.compile(
            "^(\\d{3})\\s*[/\\\\]\\s*(\\d{2})\\s*(ZR|R)\\s*(\\d{2}(?:[.,]\\d)?)"
                    + "(?:\\s*(\\d{2,3})\\s*([A-Z]))?\\s*$", Pattern.CASE_INSENSITIVE);

    /** Размер шины внутри наименования клиента — для сверки с «Прочими полями». */
    private static final Pattern TITLE_SIZE = Pattern.compile(
            "(\\d{3})\\s*[/\\\\]\\s*(\\d{2})\\s*Z?R\\s*(\\d{2})", Pattern.CASE_INSENSITIVE);

    private static final Pattern INCHES = Pattern.compile("^(\\d{2}(?:[.,]\\d+)?)\\s*[\"”″]$");
    private static final Pattern MILLIMETRES = Pattern.compile("^(\\d{2,3})\\s*мм$");
    private static final Pattern PERCENT = Pattern.compile("^(\\d{2})\\s*%$");
    private static final Pattern PIECES = Pattern.compile("^(\\d{1,3})\\s*шт\\.?$");
    // UNICODE_CASE обязателен: без него CASE_INSENSITIVE не сводит «До» к «до».
    private static final Pattern WEAR = Pattern.compile("^(?:до\\s*)?(\\d{1,3})\\s*%$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");
    private static final Pattern DISC_WIDTH = Pattern.compile("Ширина диска\\s*:?\\s*(\\d{1,2}(?:[.,]\\d+)?)");
    private static final Pattern DISC_OFFSET = Pattern.compile("Вылет[^\\d-]*(-?\\d{1,3}(?:[.,]\\d+)?)");
    private static final Pattern NUMBER = Pattern.compile("^(\\d{1,2}(?:[.,]\\d+)?)\\s*[\"”″]?$");

    private DromWheelCard() {
    }

    /**
     * Что вышло из строки.
     *
     * @param request   свойства колеса; цены и состояния в нём нет — они у позиции
     * @param setPieces колёс в комплекте ({@code inSetQuantity}); {@code null} — не названо
     * @param sets      комплектов ({@code quantity}) — это остаток позиции; {@code null} — не названо
     * @param problems  что не разобралось, по полю и словами человека
     */
    record Parsed(WheelService.WheelRequest request, Integer setPieces, Integer sets,
                  List<String> problems) {
    }

    /** Колёсная ли строка (правило — в описании класса). */
    static boolean recognizes(String section, String characteristics) {
        if (characteristics != null && !read(lines(characteristics), MARKER).isEmpty()) {
            return true;
        }
        List<String> path = sectionPath(section);
        if (path.isEmpty() || !SECTION.equalsIgnoreCase(path.get(0))) {
            return false;
        }
        return path.size() == 1 || "Шины".equalsIgnoreCase(path.get(1))
                || "Диски".equalsIgnoreCase(path.get(1));
    }

    /** Разбирает колёсную строку; звать после {@link #recognizes}. */
    static Parsed parse(String title, String section, String characteristics) {
        Map<String, List<Line>> byKey = characteristics == null ? Map.of() : lines(characteristics);
        List<Line> unkeyed = characteristics == null ? List.of() : unkeyed(characteristics);
        List<String> problems = new ArrayList<>();

        boolean tyre = has(byKey, "marking", "wheelSeason", "wheelTireWear", "tireFirmAndModel",
                "predestination");
        boolean disc = has(byKey, "diskType", "discFirmAndModel", "discParameters", "wheelPcd",
                "wheelDiameter");
        String kind = tyre && disc ? "ASSEMBLY" : tyre ? "TYRE" : disc ? "DISC" : kindOf(section);
        boolean tyreKind = !"DISC".equals(kind);

        // Маркировка и её четыре спутника под тем же ключом различаются видом значения.
        BigDecimal diameter = null;
        Integer width = null;
        Integer height = null;
        String construction = null;
        String markingType = null;
        String speedIndex = null;
        Integer loadIndex = null;
        String carcass = null;
        String marking = null;
        for (Line line : read(byKey, "marking")) {
            String value = line.value();
            Matcher m;
            if ((m = INCHES.matcher(value)).matches()) {
                diameter = decimal(m.group(1));
            } else if ((m = MILLIMETRES.matcher(value)).matches()) {
                width = Integer.valueOf(m.group(1));
            } else if ((m = PERCENT.matcher(value)).matches()) {
                height = Integer.valueOf(m.group(1));
            } else if (value.contains("/") || value.contains("\\")) {
                marking = value;
            } else if (!value.isEmpty()) {
                carcass = value;
            }
        }
        if (marking != null) {
            Matcher m = MARKING.matcher(marking);
            if (m.matches()) {
                width = Integer.valueOf(m.group(1));
                height = Integer.valueOf(m.group(2));
                construction = m.group(3).toUpperCase(java.util.Locale.ROOT);
                diameter = decimal(m.group(4));
                markingType = "METRIC";
                if (m.group(5) != null) {
                    int load = Integer.parseInt(m.group(5));
                    // Индекс нагрузки — код по таблице 20…279 (CHECK в part_wheel).
                    if (load >= 20 && load <= 279) {
                        loadIndex = load;
                    } else {
                        problems.add("Индекс нагрузки: «" + m.group(5) + "» вне таблицы индексов");
                    }
                    speedIndex = m.group(6).toUpperCase(java.util.Locale.ROOT);
                }
            } else {
                problems.add("Размер шины: «" + marking + "» не разобран");
            }
        }
        if (construction == null && carcass != null && carcass.toLowerCase(java.util.Locale.ROOT)
                .startsWith("радиальн")) {
            construction = "R";
        }

        for (Line line : read(byKey, "wheelDiameter")) {
            Matcher m = NUMBER.matcher(line.value());
            if (m.matches()) {
                diameter = diameter == null ? decimal(m.group(1)) : diameter;
            } else {
                problems.add("Диаметр: «" + line.value() + "» не разобран");
            }
        }

        // Сверка с наименованием: у клиента в одной строке там 235/55, а в
        // карточке Дрома 265/65 (лист «Объявления», строка 349). Берём карточку —
        // по ней работает фильтр Дрома, — а расхождение называем.
        if (tyreKind && width != null && height != null && diameter != null && title != null) {
            Matcher t = TITLE_SIZE.matcher(title);
            if (t.find()) {
                String inTitle = t.group(1) + "/" + t.group(2) + " R" + t.group(3);
                String inCard = width + "/" + height + " R" + plain(diameter);
                if (!inTitle.equals(inCard)) {
                    problems.add("Размер: в наименовании " + inTitle + ", в «Прочих полях» "
                            + inCard + " — взят из «Прочих полей»");
                }
            }
        }

        String season = null;
        String seasonWord = first(byKey, "wheelSeason");
        if (seasonWord != null) {
            season = SEASONS.get(seasonWord.toLowerCase(java.util.Locale.ROOT));
            if (season == null) {
                problems.add("Сезон: «" + seasonWord + "» не разобран");
            }
        }

        BigDecimal wearMm = null;
        String wear = first(byKey, "wheelTireWear");
        if (wear != null) {
            Matcher m = WEAR.matcher(wear);
            int percent = m.matches() ? Integer.parseInt(m.group(1)) : -1;
            if (percent >= 0 && percent <= 100) {
                // «До 5 %» читается как 5 %: тем же пересчётом, что BazonWheelImporter.wearMm.
                wearMm = NEW_TREAD_MM.multiply(BigDecimal.valueOf(100 - percent))
                        .divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);
            } else {
                problems.add("Износ: «" + wear + "» не разобран");
            }
        }

        Integer year = null;
        String yearText = first(byKey, "year");
        if (yearText != null) {
            Matcher m = YEAR.matcher(yearText);
            if (m.matches()) {
                year = Integer.valueOf(m.group(1));
            } else {
                problems.add("Год выпуска: «" + yearText + "» не разобран");
            }
        }

        // Производитель и модель: первое слово — производитель, остаток — модель.
        // У диска это его производитель («Toyota»), у шины и сборки — шины.
        String brand = null;
        String model = null;
        String discBrand = null;
        String discModel = null;
        String firmAndModel = first(byKey, MARKER);
        if (firmAndModel == null) {
            firmAndModel = first(byKey, "tireFirmAndModel");
        }
        if (firmAndModel != null) {
            String[] parts = firmAndModel.split("\\s+", 2);
            if (tyreKind) {
                brand = parts[0];
                model = parts.length > 1 ? parts[1] : null;
            } else {
                discBrand = parts[0];
                discModel = parts.length > 1 ? parts[1] : null;
            }
        }
        String discFirm = first(byKey, "discFirmAndModel");
        if (discFirm != null && discModel == null) {
            // «noname» не прячется: так клиент и написал.
            discModel = discFirm;
        }

        BigDecimal discWidth = null;
        Integer offset = null;
        for (Line line : read(byKey, "discParameters")) {
            Matcher w = DISC_WIDTH.matcher(line.value());
            if (w.find()) {
                discWidth = decimal(w.group(1));
            }
            Matcher o = DISC_OFFSET.matcher(line.value());
            if (o.find()) {
                // offset_mm у нас целое (031-wheels.sql), а у клиента бывает 37.5:
                // целое округляется, точное значение лежит в «Характеристиках» дословно.
                BigDecimal exact = decimal(o.group(1));
                offset = exact.setScale(0, RoundingMode.HALF_UP).intValueExact();
                if (exact.compareTo(BigDecimal.valueOf(offset)) != 0) {
                    // Округлённое число — уже не слово клиента, и молча его не подменяем.
                    problems.add("Вылет: " + plain(exact) + " мм записан как " + offset
                            + " — у нас вылет целый, точное значение в «Характеристиках»");
                }
            }
            if (discWidth == null && offset == null) {
                problems.add("Параметры диска: «" + line.value() + "» не разобраны");
            }
        }
        for (Line line : unkeyed) {
            if (discWidth == null && line.label().equalsIgnoreCase("Ширина диска")) {
                Matcher m = NUMBER.matcher(line.value());
                if (m.matches()) {
                    discWidth = decimal(m.group(1));
                }
            }
        }

        Integer setPieces = pieces(byKey, "inSetQuantity", "Штук в комплекте", problems);
        Integer sets = pieces(byKey, "quantity", "Количество комплектов", problems);

        WheelService.WheelRequest request = new WheelService.WheelRequest(
                kind, diameter, tyreKind ? width : null, tyreKind ? height : null,
                tyreKind ? construction : null, first(byKey, "predestination"),
                season, wearMm, year,
                first(byKey, "diskType"), discWidth, offset, first(byKey, "wheelPcd"), null,
                brand, model, discBrand, discModel,
                tyreKind ? markingType : null, null, null, null,
                tyreKind ? speedIndex : null, tyreKind ? loadIndex : null,
                null, null, null);
        return new Parsed(request, setPieces, sets, List.copyOf(problems));
    }

    private static Integer pieces(Map<String, List<Line>> byKey, String key, String field,
                                  List<String> problems) {
        String text = first(byKey, key);
        if (text == null) {
            return null;
        }
        Matcher m = PIECES.matcher(text);
        int count = m.matches() ? Integer.parseInt(m.group(1)) : 0;
        if (count < 1) {
            problems.add(field + ": «" + text + "» не разобрано");
            return null;
        }
        return count;
    }

    /** Без свойств вид берётся по подразделу: «Шины» — шина, «Диски» — диск, без него — колёса. */
    private static String kindOf(String section) {
        List<String> path = sectionPath(section);
        if (path.size() > 1 && "Шины".equalsIgnoreCase(path.get(1))) {
            return "TYRE";
        }
        if (path.size() > 1 && "Диски".equalsIgnoreCase(path.get(1))) {
            return "DISC";
        }
        return "ASSEMBLY";
    }

    private static List<String> sectionPath(String section) {
        if (section == null || section.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(section.split("→")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }

    private record Line(String label, String value) {
    }

    /** Строки «Подпись [ключ]: значение» по ключу, в порядке ячейки. */
    private static Map<String, List<Line>> lines(String cell) {
        Map<String, List<Line>> byKey = new LinkedHashMap<>();
        for (String raw : cell.split("\n")) {
            Matcher m = KEYED.matcher(raw.strip());
            if (m.matches()) {
                byKey.computeIfAbsent(m.group(2).strip(), k -> new ArrayList<>())
                        .add(new Line(m.group(1).strip(), m.group(3).strip()));
            }
        }
        return byKey;
    }

    /** Строки «Подпись: значение» без ключа — Дром пишет так «Ширина диска: 7.5"». */
    private static List<Line> unkeyed(String cell) {
        List<Line> found = new ArrayList<>();
        for (String raw : cell.split("\n")) {
            String line = raw.strip();
            if (KEYED.matcher(line).matches()) {
                continue;
            }
            Matcher m = LABELLED.matcher(line);
            if (m.matches()) {
                found.add(new Line(m.group(1).strip(), m.group(2).strip()));
            }
        }
        return found;
    }

    private static boolean has(Map<String, List<Line>> byKey, String... keys) {
        for (String key : keys) {
            if (!read(byKey, key).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String first(Map<String, List<Line>> byKey, String key) {
        for (Line line : read(byKey, key)) {
            if (!line.value().isEmpty()) {
                return line.value();
            }
        }
        return null;
    }

    /**
     * Строки под ключом — единственный вход к ячейке. Ключ вне {@link #KEYS}
     * — отказ, а не пустота: договор файла называет ровно {@code KEYS}
     * ({@code DromTransferFileTest.wheelKeysAreTheContract}), и ключ, который
     * разбор научили читать мимо списка, разошёлся бы с договором молча.
     */
    private static List<Line> read(Map<String, List<Line>> byKey, String key) {
        if (!KEYS.contains(key)) {
            throw new IllegalStateException("ключ «Прочих полей» " + key
                    + " читается, но не назван в DromWheelCard.KEYS и договоре файла");
        }
        return byKey.getOrDefault(key, List.of());
    }

    private static BigDecimal decimal(String text) {
        return new BigDecimal(text.replace(',', '.'));
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
