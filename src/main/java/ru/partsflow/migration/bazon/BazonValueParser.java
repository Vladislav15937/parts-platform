package ru.partsflow.migration.bazon;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.QualityGrade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор значений из выгрузки предыдущей учётной системы.
 *
 * <p>Вся сложность миграции сидит здесь: выгрузка отдаёт человекочитаемые
 * строки, в которые склеено по несколько полей. Разбор вынесен в отдельный
 * класс без зависимостей от БД, чтобы его можно было закрыть тестами на
 * реальных строках — а они в выгрузке разнообразнее, чем кажется по первому
 * десятку записей.
 *
 * <p>Правило на все методы: **непонятное значение не выбрасывается и не
 * заменяется догадкой**. Либо разобрали, либо вернули пусто и сохранили
 * исходную строку. При переносе чужого склада потерять данные молча — худшее,
 * что можно сделать: клиент обнаружит пропажу через месяц и не поверит больше
 * ничему.
 */
public final class BazonValueParser {

    private BazonValueParser() {
    }

    /** {@code Контейнер №17 | 01.07.2026 | Onteco 6} */
    private static final Pattern CONTAINER = Pattern.compile(
            "^Контейнер\\s*№\\s*(?<number>\\S+)\\s*\\|\\s*(?<date>[\\d.]+)\\s*\\|\\s*(?<supplier>.+)$");

    /**
     * {@code 418 (1417)} — номер и код лота; скобочная часть необязательна.
     * Номер не обязан быть числом: в выгрузке есть доноры {@code BMW22} —
     * машины, купленные не контейнером, а поштучно.
     */
    private static final Pattern DONOR_NUMBER = Pattern.compile(
            "^(?<number>[^()]+?)(?:\\s*\\((?<external>[^)]*)\\))?$");

    /**
     * {@code 2006-2010} или {@code 10.09-09.15} — период выпуска, а не год.
     * Правая граница необязательна: {@code 2005-} означает «с 2005 и далее».
     */
    private static final Pattern YEAR_RANGE = Pattern.compile(
            "^(?<from>[\\d.]+)\\s*[-—–]\\s*(?<to>[\\d.]+)?$");

    /** Разряды, разделённые точкой: {@code 124.000} — это 124 000, а не 124. */
    private static final Pattern DOT_THOUSANDS = Pattern.compile("\\d{1,3}(\\.\\d{3})+");

    /** {@code Золотистый (4T8)} — название и заводской код цвета. */
    private static final Pattern COLOR = Pattern.compile(
            "^(?<name>.+?)\\s*\\((?<code>[^)]*)\\)$");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Поставка.
     *
     * <p>Формат «Контейнер №N | дата | поставщик» покрывает не всё: встречаются
     * записи вроде «Автозапчасти BMW» — без номера и даты. Такую поставку нельзя
     * ни отбросить (на неё ссылаются товары), ни притвориться, что разобрали:
     * возвращаем как прочую с исходной строкой вместо номера.
     */
    public static SupplyRef parseSupply(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }

        Matcher m = CONTAINER.matcher(value);
        if (!m.matches()) {
            return new SupplyRef(SupplyKind.OTHER, value, null, null, value);
        }

        String supplier = m.group("supplier").trim();
        // Поставщик часто записан в скобках: «(DDI-22)», «(40FT)».
        if (supplier.startsWith("(") && supplier.endsWith(")")) {
            supplier = supplier.substring(1, supplier.length() - 1).trim();
        }

        return new SupplyRef(SupplyKind.CONTAINER, m.group("number"),
                parseDate(m.group("date")), emptyToNull(supplier), value);
    }

    /** Номер донора: {@code 418 (1417)} или просто {@code 396}. */
    public static DonorNumber parseDonorNumber(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        Matcher m = DONOR_NUMBER.matcher(value);
        if (!m.matches()) {
            return new DonorNumber(null, null, value);
        }
        return new DonorNumber(m.group("number").trim(),
                emptyToNull(m.group("external")), value);
    }

    /**
     * Год выпуска, который на деле бывает периодом.
     *
     * <p>У товара это не «год машины», а применимость: {@code 2006-2010}, а
     * иногда в форме месяц.год — {@code 10.09-09.15}. Разбирать это как одно
     * число нельзя: получите 2006 вместо диапазона и потеряете применимость
     * на четыре года.
     */
    public static YearRange parseYearRange(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }

        Matcher m = YEAR_RANGE.matcher(value);
        if (m.matches()) {
            Integer from = extractYear(m.group("from"));
            String to = m.group("to");
            return from == null ? null : new YearRange(from, to == null ? null : extractYear(to));
        }

        Integer single = extractYear(value);
        return single == null ? null : new YearRange(single, single);
    }

    /** {@code 2006} → 2006, {@code 10.09} → 2009, {@code 09.15} → 2015, {@code 2005~} → 2005. */
    private static Integer extractYear(String raw) {
        // Тильда — пометка «примерно»: год установлен по косвенным признакам.
        // Точность мы всё равно не храним, а терять из-за неё год глупо.
        String value = raw.trim().replaceAll("[~≈]+$", "").trim();
        int dot = value.lastIndexOf('.');
        String yearPart = dot >= 0 ? value.substring(dot + 1) : value;
        try {
            int year = Integer.parseInt(yearPart);
            if (yearPart.length() <= 2) {
                // Двузначный год: разборки торгуют машинами не старше 1970-х,
                // поэтому «09» — это 2009, а не 1909.
                year += year <= 40 ? 2000 : 1900;
            }
            return year >= 1900 && year <= 2100 ? year : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Цвет с заводским кодом: {@code Серебро (1C0)}. Код есть не всегда. */
    public static ColorValue parseColor(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        Matcher m = COLOR.matcher(value);
        if (!m.matches()) {
            return new ColorValue(value, null);
        }
        return new ColorValue(m.group("name").trim(), emptyToNull(m.group("code")));
    }

    /** Пробег и прочие числа с пробелом-разделителем разрядов: {@code 47 064}. */
    public static Integer parseInteger(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        // Пробел, неразрывный пробел и узкий неразрывный — в выгрузках встречаются все три.
        String digits = value.replaceAll("[\\s  ]", "");
        // Единица измерения дописана прямо в значение: «100131км», «52 000 км.».
        digits = digits.replaceAll("(?iu)км\\.?$", "");
        // Пробег пишут и как «124.000». Без этой ветки машина с пробегом
        // 124 000 км приедет в базу со 124 километрами.
        if (DOT_THOUSANDS.matcher(digits).matches()) {
            digits = digits.replace(".", "");
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal parseAmount(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[\\s  ]", "").replace(',', '.');
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Список значений через запятую: кросс-номера и ссылки на фотографии.
     * Разделитель пишется то с пробелом, то без — в товарах и донорах по-разному.
     */
    public static List<String> parseList(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Ссылки на фото приходят и протокол-относительными ({@code //cdn.baz-on.ru/...}),
     * и с явным http. Приводим к абсолютному виду, иначе загрузчик их не возьмёт.
     */
    public static List<String> parsePhotoUrls(String raw) {
        return parseList(raw).stream()
                .map(url -> url.startsWith("//") ? "https:" + url : url)
                .map(BazonValueParser::fullSize)
                .toList();
    }

    /**
     * Ссылка на оригинал вместо уменьшенной копии.
     *
     * <p>CDN отдаёт по одному пути и превью, и оригинал: разница в отрезке
     * {@code /rsz/<размер>/} перед {@code /pub/}. Превью — это 49×37,
     * оригинал — 1020×770 (замерено). Перенести превью значит навсегда
     * оставить клиента с картинками, по которым деталь не разглядеть,
     * а исходники к тому времени останутся только в прежней системе.
     */
    private static String fullSize(String url) {
        return RESIZED.matcher(url).replaceFirst("/pub/");
    }

    private static final java.util.regex.Pattern RESIZED =
            java.util.regex.Pattern.compile("/rsz/[^/]+/pub/");

    public static String parseSteering(String raw) {
        return switch (normalize(raw)) {
            case "левый руль" -> "LEFT";
            case "правый руль" -> "RIGHT";
            default -> null;
        };
    }

    public static String parseDriveType(String raw) {
        return switch (normalize(raw)) {
            case "передний" -> "FWD";
            case "задний" -> "RWD";
            case "полный" -> "AWD";
            default -> null;
        };
    }

    public static String parseTransmissionType(String raw) {
        return switch (normalize(raw)) {
            case "мкпп" -> "MT";
            case "акпп" -> "AT";
            case "вариатор" -> "CVT";
            // «Робот» — это AMT, а не DCT: у японских машин на разборках
            // это почти всегда одно сцепление.
            case "робот" -> "AMT";
            default -> null;
        };
    }

    /**
     * Флаг «Выгружать» — разрешение публиковать позицию на площадках.
     *
     * <p>Колонка в выгрузке по умолчанию отсутствует: в Bazon она неактивна,
     * её надо включить в настройках таблицы перед экспортом. Формат значений
     * поэтому не подтверждён на живых данных — принимаем все обычные написания
     * и отдаём {@code null}, когда значение непонятно, чтобы вызывающий решал
     * сам, а не получал молчаливое «не публиковать».
     */
    public static Boolean parsePublishFlag(String raw) {
        return switch (normalize(raw)) {
            case "да", "yes", "true", "1", "+", "выгружать" -> Boolean.TRUE;
            case "нет", "no", "false", "0", "-", "не выгружать" -> Boolean.FALSE;
            default -> null;
        };
    }

    public static LateralSide parseLateralSide(String raw) {
        return switch (normalize(raw)) {
            case "лев.", "лев", "левый", "левая", "лево" -> LateralSide.LEFT;
            case "прав.", "прав", "правый", "правая", "право" -> LateralSide.RIGHT;
            default -> null;
        };
    }

    public static LongitudinalSide parseLongitudinalSide(String raw) {
        return switch (normalize(raw)) {
            case "перед.", "перед", "передний", "передняя" -> LongitudinalSide.FRONT;
            case "задн.", "задн", "задний", "задняя", "зад" -> LongitudinalSide.REAR;
            default -> null;
        };
    }

    public static QualityGrade parseQualityGrade(String raw) {
        return switch (normalize(raw)) {
            case "как новая" -> QualityGrade.AS_NEW;
            case "без дефектов" -> QualityGrade.NO_DEFECTS;
            case "с дефектами" -> QualityGrade.WITH_DEFECTS;
            case "требует ремонт", "требует ремонта" -> QualityGrade.NEEDS_REPAIR;
            default -> null;
        };
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim(), DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalize(String raw) {
        String value = trimToNull(raw);
        return value == null ? "" : value.toLowerCase().replace('ё', 'е');
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static String emptyToNull(String raw) {
        return trimToNull(raw);
    }

    public enum SupplyKind {
        CONTAINER,
        PURCHASE,
        OTHER
    }

    /**
     * @param raw исходная строка целиком — сохраняется всегда, чтобы при
     *            разборе полётов можно было увидеть, что именно пришло
     */
    public record SupplyRef(SupplyKind kind, String number, LocalDate arrivedOn,
                            String supplierName, String raw) {

        /** Разобрана ли поставка целиком, или это спасённый в OTHER свободный текст. */
        public boolean isStructured() {
            return kind == SupplyKind.CONTAINER && arrivedOn != null;
        }
    }

    /**
     * @param number номер донора как строка: он не всегда числовой ({@code BMW22})
     */
    public record DonorNumber(String number, String externalCode, String raw) {

        /** Номер разобрать удалось. Иначе в {@link #raw} лежит то, что пришло. */
        public boolean isParsed() {
            return number != null;
        }
    }

    /** Период выпуска. Для одиночного года {@code from} и {@code to} совпадают. */
    public record YearRange(Integer from, Integer to) {

        public boolean isSingleYear() {
            return from != null && from.equals(to);
        }
    }

    public record ColorValue(String name, String code) {
    }
}
