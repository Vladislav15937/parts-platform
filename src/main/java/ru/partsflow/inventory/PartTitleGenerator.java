package ru.partsflow.inventory;

import org.springframework.stereotype.Component;
import ru.partsflow.catalog.OemNumbers;

import java.util.ArrayList;
import java.util.List;

/**
 * Собирает наименование товара из атрибутов.
 *
 * <p>Приёмщик название не пишет. Он выбирает вид детали, донора и стороны —
 * заголовок собирается сам:
 * {@code Блок подрулевых переключателей Toyota Wish ZNE10 1ZZFE 2006 8414012530}
 *
 * <p>Причина не в удобстве, а в поиске. Название, набранное руками, у каждого
 * своё: «фара лев.», «Фара левая перед», «фара L». По такому складу нельзя ни
 * искать, ни выгружать объявления, ни считать статистику по видам деталей.
 * Единый порядок частей — это то, что делает 50 тысяч позиций однородными.
 *
 * <p>Порядок частей повторяет то, как деталь спрашивают по телефону: сначала
 * что это, потом с какой машины, потом уточнения.
 *
 * <p><b>Состояния в заголовке нет</b> (задача 0315, решение владельца продукта
 * 17.10.2026). До неё сюда дописывалась пометка «(б/у)», «(новая)», «(восст.)»,
 * «(контракт)» — вторая копия поля {@code condition}, и копия врала: с 0304
 * наименование меняет только человек, поэтому смена состояния пометку
 * не обновляла, а правка названия руками её теряла. Состояние показывается
 * рядом словом ({@code conditionTitle}, 0314 и 0318). Пометки у позиций,
 * принятых раньше, снимает задача 0316; до неё карточку от них бережёт
 * {@code shownTitle}, прайс — {@code DromPriceWriter.plainName}.
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

    /**
     * @param partName   вид детали в написании арендатора, обязателен
     * @param vehicle    марка, модель, кузов, двигатель и год донора; может быть null
     * @param sides      стороны установки; null-поля просто не попадут в заголовок
     * @param oemNumber  текст поля «Номер производителя», как вписан; может быть null.
     *                   В заголовок идёт только основной номер ({@link #titleNumber})
     */
    public String generate(String partName,
                           VehicleTitlePart vehicle,
                           Sides sides,
                           String oemNumber) {

        if (partName == null || partName.isBlank()) {
            throw new IllegalArgumentException("Вид детали обязателен: без него заголовок бессмыслен");
        }

        List<String> parts = new ArrayList<>();
        parts.add(partName.trim());

        if (vehicle != null) {
            addIfPresent(parts, vehicle.brand());
            addIfPresent(parts, vehicle.model());
            addIfPresent(parts, vehicle.bodyCode());
            addIfPresent(parts, vehicle.engineCode());
            if (vehicle.year() != null) {
                parts.add(String.valueOf(vehicle.year()));
            }
        }

        if (sides != null) {
            addIfPresent(parts, sides.asText());
        }

        addIfPresent(parts, titleNumber(oemNumber));

        return String.join(" ", parts);
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

    /** Часть заголовка, описывающая машину. Любое поле может отсутствовать. */
    public record VehicleTitlePart(String brand, String model, String bodyCode,
                                   String engineCode, Integer year) {
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
