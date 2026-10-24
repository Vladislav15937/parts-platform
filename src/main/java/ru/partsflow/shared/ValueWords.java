package ru.partsflow.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Слова-значения — коробка передач и стороны установки — один словарь на все
 * поверхности.
 *
 * <p><b>Решение владельца продукта 24.10.2026</b> ({@code tasks/0044},
 * «Ответ владельца — 24 октября 2026 (развёрнутые вопросы)», пункты 3 и 4):
 * {@code DCT} — «Робот DSG», и слова-значения везде с заглавной — «Вариатор»,
 * «Робот», «Робот DSG», «АКПП», «МКПП», «Перед.», «Задн.», «Лев.», «Прав.» —
 * в карточке донора, в «Комплектации», в названии позиции и в файле склада.
 * Руль и привод — тоже с заглавной, как в карточке донора: «Левый руль,
 * Робот DSG, Передний» (ответ владельца после живого прогона волны 24.10c,
 * {@code tasks/0044}, пункт 1).
 *
 * <p><b>Зачем он один.</b> До этого словарей было пять: карточка донора
 * писала «Вариатор/Робот» с заглавной, «Комплектация» склада — «вариатор/робот»
 * строчными, файл склада — «Задн.», клетка экрана и название позиции —
 * «задн.», а {@code DCT} не знал никто — схема и перечисление его допускают,
 * клетка и файл оставались пусты. Совпадали копии только тем, что их писали
 * в один день, — и разошлись на первой же правке (разбор PR 510).
 *
 * <p>Поэтому выражение для SQL собирается отсюда же ({@link #sqlTransmission},
 * {@link #sqlSide}): второй список рядом разошёлся бы с первым, и клетка
 * экрана, считаемая запросом, назвала бы значение иначе, чем Java.
 * Копию на фронтенде сверяет с этим словарём {@code ValueWordsTest}.
 *
 * <p>Ключи — коды, как они лежат в базе (строками, а не перечислениями
 * модулей: словарь общий, и {@code shared} не зависит ни от {@code intake},
 * ни от {@code inventory}). Что каждое значение перечислений здесь названо,
 * стережёт {@code ValueWordsTest} перебором по {@code values()}.
 */
public final class ValueWords {

    private static final Map<String, String> TRANSMISSION = new LinkedHashMap<>();
    private static final Map<String, String> STEERING = new LinkedHashMap<>();
    private static final Map<String, String> DRIVE = new LinkedHashMap<>();
    private static final Map<String, String> FRONT_REAR = new LinkedHashMap<>();
    private static final Map<String, String> LEFT_RIGHT = new LinkedHashMap<>();
    private static final Map<String, String> UPPER_LOWER = new LinkedHashMap<>();

    static {
        TRANSMISSION.put("AT", "АКПП");
        TRANSMISSION.put("MT", "МКПП");
        TRANSMISSION.put("CVT", "Вариатор");
        TRANSMISSION.put("AMT", "Робот");
        // Робот с двумя сцеплениями: «Робот DSG» — слово владельца (24.10.2026).
        TRANSMISSION.put("DCT", "Робот DSG");

        STEERING.put("RIGHT", "Правый руль");
        STEERING.put("LEFT", "Левый руль");
        DRIVE.put("FWD", "Передний");
        DRIVE.put("RWD", "Задний");
        DRIVE.put("AWD", "Полный");

        FRONT_REAR.put("FRONT", "Перед.");
        FRONT_REAR.put("REAR", "Задн.");
        LEFT_RIGHT.put("LEFT", "Лев.");
        LEFT_RIGHT.put("RIGHT", "Прав.");
        // Верх и низ владелец не перечислил, но правило у него общее —
        // «слова-значения везде с заглавной»; «Перед. Лев. ниж.» в одном
        // названии читалось бы как два разных правила. Названо в PR.
        UPPER_LOWER.put("UPPER", "Верх.");
        UPPER_LOWER.put("LOWER", "Ниж.");
    }

    /** Код «обе стороны» у каждой из трёх осей ({@code BOTH} в перечислениях). */
    public static final String BOTH = "BOTH";

    /** Разделитель «обеих сторон» в клетке и в файле: «Перед., Задн.». */
    public static final String CELL_SEPARATOR = ", ";

    /** Разделитель «обеих сторон» в названии позиции: «Перед. Задн.». */
    public static final String TITLE_SEPARATOR = " ";

    /** Ось стороны установки. */
    public enum Axis {
        FRONT_REAR(ValueWords.FRONT_REAR),
        LEFT_RIGHT(ValueWords.LEFT_RIGHT),
        UPPER_LOWER(ValueWords.UPPER_LOWER);

        private final Map<String, String> words;

        Axis(Map<String, String> words) {
            this.words = words;
        }

        /** Слова оси без «обеих»: по ним сверяется копия экрана. */
        public Map<String, String> words() {
            return Map.copyOf(words);
        }
    }

    private ValueWords() {
    }

    /** Коробка целиком: по нему сверяются слова экрана и слова сервера. */
    public static Map<String, String> transmissions() {
        return Map.copyOf(TRANSMISSION);
    }

    /** Руль целиком: по нему сверяются слова. */
    public static Map<String, String> steerings() {
        return Map.copyOf(STEERING);
    }

    /** Привод целиком. */
    public static Map<String, String> drives() {
        return Map.copyOf(DRIVE);
    }

    /** Руль словом: «Левый руль»; правила те же, что у {@link #transmission}. */
    public static String steering(String code) {
        return code == null ? null : STEERING.getOrDefault(code, code);
    }

    /** Привод словом: «Передний»; правила те же, что у {@link #transmission}. */
    public static String drive(String code) {
        return code == null ? null : DRIVE.getOrDefault(code, code);
    }

    /** Руль выражением SQL; незнакомое значение — {@code NULL}. */
    public static String sqlSteering(String column) {
        return sqlCase(STEERING, column);
    }

    /** Привод выражением SQL; незнакомое значение — {@code NULL}. */
    public static String sqlDrive(String column) {
        return sqlCase(DRIVE, column);
    }

    /**
     * Коробка словом; {@code null} — {@code null}, незнакомый код возвращается
     * как есть, а не теряется молча.
     */
    public static String transmission(String code) {
        return code == null ? null : TRANSMISSION.getOrDefault(code, code);
    }

    /**
     * Сторона словом: «Перед.»; «обе» — два слова через {@code separator}
     * («Перед., Задн.» в клетке, «Перед. Задн.» в названии). {@code null} —
     * {@code null}, незнакомый код — как есть.
     */
    public static String side(Axis axis, String code, String separator) {
        if (code == null) {
            return null;
        }
        if (BOTH.equals(code)) {
            return String.join(separator, axis.words.values());
        }
        return axis.words.getOrDefault(code, code);
    }

    /**
     * Коробка выражением SQL: {@code CASE <column> WHEN 'AT' THEN 'АКПП' … END}.
     * Незнакомое значение — {@code NULL}: в «Комплектации» пустое место лучше
     * кода, и так было до словаря.
     */
    public static String sqlTransmission(String column) {
        return sqlCase(TRANSMISSION, column);
    }

    private static String sqlCase(Map<String, String> words, String column) {
        StringBuilder sql = new StringBuilder("CASE ").append(column);
        words.forEach((code, word) -> sql.append(" WHEN '").append(code)
                .append("' THEN '").append(word).append("'"));
        return sql.append(" END").toString();
    }

    /**
     * Сторона выражением SQL, «обе» — через {@code separator}. Разделитель
     * явной строкой, а не отступом текстового блока: тот съедает пробелы
     * по краям (корневой {@code CLAUDE.md}, «Текстовый блок Java…»).
     */
    public static String sqlSide(Axis axis, String column, String separator) {
        StringBuilder sql = new StringBuilder("CASE ").append(column);
        axis.words.forEach((code, word) -> sql.append(" WHEN '").append(code)
                .append("' THEN '").append(word).append("'"));
        sql.append(" WHEN '").append(BOTH).append("' THEN '")
                .append(side(axis, BOTH, separator)).append("'");
        return sql.append(" END").toString();
    }
}
