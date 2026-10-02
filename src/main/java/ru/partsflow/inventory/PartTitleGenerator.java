package ru.partsflow.inventory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Собирает наименование товара из атрибутов.
 *
 * <p>Приёмщик название не пишет. Он выбирает вид детали, донора и стороны —
 * заголовок собирается сам:
 * {@code Блок подрулевых переключателей Toyota Wish ZNE10 1ZZFE 2006 (б/у) 8414012530}
 *
 * <p>Причина не в удобстве, а в поиске. Название, набранное руками, у каждого
 * своё: «фара лев.», «Фара левая перед», «фара L». По такому складу нельзя ни
 * искать, ни выгружать объявления, ни считать статистику по видам деталей.
 * Единый порядок частей — это то, что делает 50 тысяч позиций однородными.
 *
 * <p>Порядок частей повторяет то, как деталь спрашивают по телефону: сначала
 * что это, потом с какой машины, потом уточнения.
 */
@Component
public class PartTitleGenerator {

    /**
     * @param partName   вид детали в написании арендатора, обязателен
     * @param vehicle    марка, модель, кузов, двигатель и год донора; может быть null
     * @param sides      стороны установки; null-поля просто не попадут в заголовок
     * @param condition  состояние
     * @param oemNumber  номер производителя, может быть null
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

        parts.add(conditionText(condition));
        addIfPresent(parts, oemNumber);

        return String.join(" ", parts);
    }

    private static void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    private static String conditionText(PartCondition condition) {
        return (condition == null ? PartCondition.USED : condition).titleMark();
    }

    /**
     * Меняет пометку состояния в уже собранном заголовке.
     *
     * <p><b>Почему не пересборка целиком.</b> Правка состояния из карточки
     * (задача 0039) обязана подвинуть и заголовок — иначе карточка
     * разойдётся со своим же названием, уже уехавшим в объявление. Но
     * собрать заголовок заново значит собрать его из вида детали, машины
     * и сторон — а у перенесённой позиции он этим не исчерпывается:
     * {@code BazonImporter} дописывает в заголовок **список машин**
     * (у переехавшего клиента так устроены 9 417 контрактных позиций
     * из 35 841 — донора у них нет, и применимость перечислена прямо
     * в названии), а {@code TitleApplicability} эти машины оттуда же
     * и разбирает. Пересборка стёрла бы их молча, и вместе с ними —
     * применимость, по которой деталь находят.
     *
     * <p>Поэтому меняется ровно то, что в заголовок вносит состояние:
     * его пометка. Пометки известны перечислению, так что список здесь
     * не пишется руками.
     *
     * <p>Пометки нет вовсе у заголовка, который собирал не этот класс
     * («Без наименования» у битой строки переноса) — тогда она
     * дописывается: состояние подставляется всегда, и у этого правила
     * исключений нет.
     */
    public String remark(String title, PartCondition condition) {
        String mark = (condition == null ? PartCondition.USED : condition).titleMark();
        if (title == null || title.isBlank()) {
            return title;
        }
        for (String known : PartCondition.titleMarks()) {
            int at = title.indexOf(known);
            if (at >= 0) {
                return title.substring(0, at) + mark + title.substring(at + known.length());
            }
        }
        return title + " " + mark;
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
                words.add(fr == LongitudinalSide.FRONT ? "перед." : "задн.");
            }
            if (lr != null) {
                words.add(lr == LateralSide.LEFT ? "лев." : "прав.");
            }
            if (ud != null) {
                words.add(ud == VerticalSide.UPPER ? "верх." : "ниж.");
            }
            return String.join(" ", words);
        }
    }
}
