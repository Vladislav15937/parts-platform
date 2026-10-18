package ru.partsflow.inventory;

import org.springframework.stereotype.Component;
import ru.partsflow.catalog.OemNumbers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Собирает наименование позиции склада — по формуле Bazon (задача 0336).
 *
 * <p>{@code Вид Марка Модель Кузов Двигатель Год положение (б/у) Номер}:
 * {@code Кнопка стеклоподъемника Toyota Harrier ACU30 2AZFE 2010 задн. лев. (б/у) 7428048120}.
 * Решение владельца продукта 19.10.2026: «на 100% соответствовать логике
 * формирования склада … в базон». Переехавший клиент читает название
 * по телефону, и покупатель узнаёт деталь по тем же словам, что раньше.
 *
 * <ul>
 *   <li><b>Вид</b> — написание клиента, а не эталон справочника: «Ремень
 *       поликлиновой» остаётся им, даже сопоставленный с «Ремнём приводным».
 *       Сопоставление живёт отдельно (вид детали, категория).</li>
 *   <li><b>Марка, модель, кузов, двигатель</b> — первое значение каждого поля:
 *       {@code Lincoln,Ford} → Lincoln ({@link #firstOf}).</li>
 *   <li><b>Год</b> — первое число поля: {@code 03-08} → 03 ({@link #yearOf}).</li>
 *   <li><b>Положение</b> — сокращениями, «перед./задн.», потом «лев./прав.».</li>
 *   <li><b>Пометка состояния</b> — {@link PartCondition#stockMark}: «(б/у)»
 *       у б/у и контрактной, нет у новой и у позиции без состояния.</li>
 *   <li><b>Номер</b> — основной, как вписан, последним ({@link #titleNumber}).</li>
 * </ul>
 *
 * <p><b>Название производное и руками не правится</b> (решение 2 там же):
 * его пересобирает {@link PartTitles} при правке любого поля формулы.
 * Записи 0304 («название меняет только человек») и 0315 («состояния
 * в названии нет») с этого дня — история, {@code inventory/CLAUDE.md}.
 *
 * <p><b>Номер производителя в заголовке — один, основной</b> (задача 0330, решение
 * владельца продукта 18.10.2026: «у детали один номер производителя, остальное —
 * кросс-номера»). Вписанное «8414012530, 8414012531» даёт «… 8414012530»: второй
 * номер уходит кросс-номером, и в названии ему не место. Делит
 * {@link OemNumbers#primary} — то же правило, по которому пишется {@code part_oem};
 * своего делителя здесь нет. См. {@link #titleNumber}.
 */
@Component
public class PartTitleGenerator {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /**
     * @param partName   вид детали в написании клиента, обязателен
     * @param vehicle    марка, модель, кузов, двигатель и год; может быть null
     * @param sides      стороны установки; null-поля просто не попадут в заголовок
     * @param condition  состояние; пометку даёт {@link PartCondition#stockMark}, null — без пометки
     * @param oemNumber  текст поля «Номер производителя», как вписан; может быть null.
     *                   В заголовок идёт только основной номер ({@link #titleNumber})
     */
    public String generate(String partName,
                           VehicleTitlePart vehicle,
                           Sides sides,
                           PartCondition condition,
                           String oemNumber) {

        if (partName == null || partName.isBlank()) {
            throw new IllegalArgumentException("Вид детали обязателен: без него заголовок бессмыслен");
        }

        List<String> parts = new ArrayList<>();
        parts.add(partName.trim());

        if (vehicle != null) {
            addIfPresent(parts, firstOf(vehicle.brand()));
            addIfPresent(parts, firstOf(vehicle.model()));
            addIfPresent(parts, firstOf(vehicle.bodyCode()));
            addIfPresent(parts, firstOf(vehicle.engineCode()));
            addIfPresent(parts, yearOf(vehicle.year()));
        }

        if (sides != null) {
            addIfPresent(parts, sides.asText());
        }

        if (condition != null) {
            addIfPresent(parts, condition.stockMark());
        }

        addIfPresent(parts, titleNumber(oemNumber));

        return String.join(" ", parts);
    }

    /**
     * Первое значение поля, перечисляющего несколько: у Bazon марка, модель,
     * кузов и двигатель контрактной детали — списки через запятую
     * ({@code Lexus, Toyota}, {@code UN173 1997-2002 Г.В.,UN93}), а в название
     * идёт первое. Без запятой — поле как есть.
     */
    static String firstOf(String value) {
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        return comma < 0 ? value : value.substring(0, comma);
    }

    /**
     * Год в названии — первое число поля «Год выпуска»: {@code 2010} → 2010,
     * {@code 03-08} → 03 (ведущий ноль сохраняется, как у Bazon). Поле
     * без цифр слова не даёт.
     */
    static String yearOf(String year) {
        if (year == null) {
            return null;
        }
        Matcher found = NUMBER.matcher(year);
        return found.find() ? found.group() : null;
    }

    /**
     * Что из поля «Номер производителя» встаёт в заголовок.
     *
     * <p>Если {@link OemNumbers#primary} отделил от текста кросс-номера (или пустые
     * куски, «АНАЛОГ»), — его основной номер, в виде хранения ({@code raw_number}),
     * а не приведённом. Иначе — текст как пришёл, байт в байт как до задачи 0330:
     * номер без разделителя; «не номер» в начале («АНАЛОГ, FP-3» — основной
     * не угадывается, 0323) и текст без номера вовсе («б/н») — тоже целиком,
     * иначе пропало бы то, что человек вписал. Признак «отделил» — основной
     * не совпал с {@link OemNumbers#clean} всего текста: так одно правило решает
     * и деление, и «не номер», а не вторая копия разделителей.
     */
    static String titleNumber(String oemNumber) {
        if (oemNumber == null) {
            return null;
        }
        String primary = OemNumbers.primary(oemNumber).primary();
        if (primary == null || primary.equals(OemNumbers.clean(oemNumber))) {
            return oemNumber;
        }
        return primary;
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    /**
     * Часть заголовка, описывающая машину. Любое поле может отсутствовать.
     *
     * <p>Год — текстом, а не числом: у Bazon это поле «Год выпуска» как оно
     * записано ({@code 2010}, {@code 03-08}), и в название идёт его первое
     * число ({@link #yearOf}). Число года донора передаётся своей записью.
     */
    public record VehicleTitlePart(String brand, String model, String bodyCode,
                                   String engineCode, String year) {

        public static String year(Integer year) {
            return year == null ? null : String.valueOf(year);
        }
    }

    /**
     * Стороны установки в порядке, принятом у разборщиков: сначала продольная
     * («перед.»), затем поперечная («лев.»), затем вертикальная («ниж.»).
     * Именно так деталь называют вслух, и менять порядок нельзя — иначе
     * одинаковые детали получат разные заголовки.
     */
    public record Sides(LongitudinalSide fr, LateralSide lr, VerticalSide ud) {

        String asText() {
            List<String> words = new ArrayList<>();
            if (fr != null) {
                words.add(switch (fr) {
                    case FRONT -> "перед.";
                    case REAR -> "задн.";
                    case BOTH -> "перед. задн.";
                });
            }
            if (lr != null) {
                // «Обе» — двумя словами, а не своим сокращением: каждое
                // из них прайс уже умеет снимать с заголовка (plainName).
                words.add(switch (lr) {
                    case LEFT -> "лев.";
                    case RIGHT -> "прав.";
                    case BOTH -> "лев. прав.";
                });
            }
            if (ud != null) {
                words.add(switch (ud) {
                    case UPPER -> "верх.";
                    case LOWER -> "ниж.";
                    case BOTH -> "верх. ниж.";
                });
            }
            return String.join(" ", words);
        }
    }
}
