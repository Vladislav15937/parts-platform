package ru.partsflow.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Строка блока «&lt;Вид&gt; подходит:» — как её пишет Bazon, до знака (задача 0342).
 *
 * <p>Чистое форматирование, без базы: что брать из справочника {@code catalog.bazon_*}
 * и как выбирать поколение, решает {@link BazonFitsResolver}. Разделение нужно, чтобы
 * «до знака» проверялось перебором строк без Postgres.
 *
 * <p>Формат — из файлов выгрузки Bazon (обходы 19.10.2026):
 * {@code Марка Модель N поколение ГГГГ–ГГГГ [Платформа ][(кузова)]} и либо
 * {@code , любой двигатель (коды)}, либо {@code  двигатель КОД (объём Б|Д|Г|Э), …}.
 * Годы — через короткое тире {@code –} (в справочнике Bazon длинное {@code —}), конец — {@code н.в.}.
 */
public final class BazonFits {

    /** Двигатель поколения: объём в куб. см ({@code null} — в справочнике нет) и топливо Bazon. */
    public record Motor(String name, Integer volumeCc, String fuel) {
    }

    /** Поколение Bazon с его кузовами и двигателями — всё, что нужно строке. */
    public record Generation(String mark, String model, String name, String years, String series,
                             List<String> bodies, List<Motor> motors) {
    }

    private BazonFits() {
    }

    /**
     * Одна строка блока.
     *
     * @param anyEngine {@code true} — «любой двигатель (коды)», {@code false} — перечень
     *                  двигателей с объёмом и топливом
     * @return {@code null}, если у поколения нет двигателей: строка без двигателя — не форма
     *         Bazon, и выдумывать её за него нельзя
     */
    public static String line(Generation g, boolean anyEngine) {
        if (g.motors().isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        out.append(g.mark()).append(' ').append(g.model()).append(' ')
                .append(g.name()).append(' ').append(years(g.years()));
        if (g.series() != null && !g.series().isBlank()) {
            out.append(' ').append(g.series());
        }
        if (!g.bodies().isEmpty()) {
            out.append(" (").append(String.join(", ", g.bodies())).append(')');
        }
        if (anyEngine) {
            out.append(", любой двигатель (")
                    .append(g.motors().stream().map(Motor::name).collect(Collectors.joining(", ")))
                    .append(')');
        } else {
            out.append(" двигатель ").append(g.motors().stream()
                    .map(m -> m.name() + " (" + volume(m.volumeCc()) + ' ' + fuelLetter(m.fuel()) + ')')
                    .collect(Collectors.joining(", ")));
        }
        return out.toString();
    }

    /** Блок целиком: заголовок и по строке на машину; пусто — {@code null}. */
    public static String block(String kind, List<String> lines) {
        if (kind == null || kind.isBlank() || lines.isEmpty()) {
            return null;
        }
        return kind + " подходит:\n" + String.join("\n", lines);
    }

    /**
     * Годы как в строке: длинное тире справочника — коротким, открытый конец —
     * {@code н.в.} ({@code 2018—...} → {@code 2018–н.в.}). Остальное как есть.
     */
    static String years(String years) {
        String shown = years.replace('—', '–');
        return shown.endsWith("...") ? shown.substring(0, shown.length() - 3) + "н.в." : shown;
    }

    /**
     * Объём литрами с точкой и одним знаком. Нет объёма в справочнике — {@code 0.0}:
     * так Bazon пишет электро ({@code Y4F1 (0.0 Э)}) и не опускает объём. Округление
     * половины вверх — догадка: на объёмах вроде 1 950 куб. см образца нет.
     */
    static String volume(Integer cc) {
        if (cc == null) {
            return "0.0";
        }
        return BigDecimal.valueOf(cc).divide(BigDecimal.valueOf(1000))
                .setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Буква топлива в строке. Гибрид отдельной буквы не имеет: пишется буква топлива
     * теплового двигателя, а в справочнике он {@code hybrid} без уточнения — берём
     * {@code Б}; дизельный гибрид в файле клиента не встретился.
     */
    static String fuelLetter(String fuel) {
        return switch (fuel) {
            case "diesel" -> "Д";
            case "gas" -> "Г";
            case "electric" -> "Э";
            default -> "Б"; // gasoline и hybrid
        };
    }
}
