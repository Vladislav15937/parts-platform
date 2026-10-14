package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.DromApplicability.Miss;
import ru.partsflow.inventory.DromApplicability.Reason;
import ru.partsflow.inventory.DromApplicability.Target;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор блока «Подходит» карточки Дрома (задача 0289).
 *
 * <p>Строки — с живого склада клиента, переехавшего с Дрома 13 октября 2026
 * (написание через « — »), и из образца договора файла (через « | »).
 * Годы поколений в словаре — как в справочнике: у Дрома в карточке
 * «2013–2024», у нас «2013–2023».
 */
class DromApplicabilityTest {

    private static final long TOYOTA = 1;
    private static final long LEXUS = 2;
    private static final long AUDI = 3;
    private static final long AMBER = 4;
    private static final long LAND_ROVER = 5;
    private static final long LAND = 6;

    private static final long RUNNER = 10;
    private static final long PRADO = 11;
    private static final long CRUISER = 12;
    private static final long GX460 = 20;
    private static final long A3 = 30;
    private static final long AMBER_A3 = 40;
    private static final long DEFENDER = 50;

    private static final long RUNNER_4 = 100;
    private static final long RUNNER_4R = 101;
    private static final long RUNNER_5 = 102;
    private static final long RUNNER_5R = 103;
    private static final long PRADO_4 = 110;
    private static final long PRADO_4R = 111;
    /** Два поколения с одним годом начала: различает год конца. */
    private static final long PRADO_X = 112;
    private static final long PRADO_Y = 113;

    private static final DromApplicability.Dictionary DICT = new DromApplicability.Dictionary(
            List.of(new DromApplicability.Brand(TOYOTA, "Toyota"),
                    new DromApplicability.Brand(LEXUS, "Lexus"),
                    new DromApplicability.Brand(AUDI, "Audi"),
                    new DromApplicability.Brand(AMBER, "Амберавто"),
                    new DromApplicability.Brand(LAND, "Land"),
                    new DromApplicability.Brand(LAND_ROVER, "Land Rover")),
            List.of(new DromApplicability.Model(RUNNER, TOYOTA, "4Runner"),
                    new DromApplicability.Model(PRADO, TOYOTA, "Land Cruiser Prado"),
                    new DromApplicability.Model(CRUISER, TOYOTA, "Land Cruiser"),
                    new DromApplicability.Model(GX460, LEXUS, "GX460"),
                    new DromApplicability.Model(A3, AUDI, "A3"),
                    new DromApplicability.Model(AMBER_A3, AMBER, "A3"),
                    new DromApplicability.Model(DEFENDER, LAND_ROVER, "Defender")),
            List.of(new DromApplicability.Generation(RUNNER_4, RUNNER, 2002, 2004),
                    new DromApplicability.Generation(RUNNER_4R, RUNNER, 2005, 2008),
                    new DromApplicability.Generation(RUNNER_5, RUNNER, 2009, 2012),
                    new DromApplicability.Generation(RUNNER_5R, RUNNER, 2013, 2023),
                    new DromApplicability.Generation(PRADO_4, PRADO, 2009, 2012),
                    new DromApplicability.Generation(PRADO_4R, PRADO, 2013, 2017),
                    new DromApplicability.Generation(PRADO_X, PRADO, 2020, 2021),
                    new DromApplicability.Generation(PRADO_Y, PRADO, 2020, null)));

    private static List<Target> targets(String text) {
        return DromApplicability.parse(text, DICT).targets();
    }

    private static List<Miss> misses(String text) {
        return DromApplicability.parse(text, DICT).misses();
    }

    @Test
    @DisplayName("Написание клиента через « — »: поколение по году начала, годы и кузова из пункта")
    void dashedBlockOfTheLiveClient() {
        String block = "Toyota 4Runner — 5 поколение, рестайлинг (2013–2024): GRN285; "
                + "5 поколение (2009–2013): TRN285, GRN285; Другие кузова: KZN215\n"
                + "Для двигателей — 1GRFE, 5VZFE";
        assertThat(targets(block)).containsExactly(
                new Target(TOYOTA, RUNNER, RUNNER_5R, 2013, 2024, "Кузова: GRN285", "5 поколение"),
                new Target(TOYOTA, RUNNER, RUNNER_5, 2009, 2013, "Кузова: TRN285, GRN285", "5 поколение"));
        assertThat(misses(block)).isEmpty();
    }

    @Test
    @DisplayName("Написание договора через « | » даёт то же самое; «Другие кузова» и двигатели мимо")
    void pipedBlockOfTheContract() {
        String block = """
                Toyota 4Runner | 5 поколение, рестайлинг (2013–2024) | GRN285
                Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285
                Toyota Land Cruiser Prado | Другие кузова | GRJ120, GRJ120W
                Для двигателей | 1GRFE, 5VZFE, 2TRFE""";
        assertThat(targets(block)).containsExactly(
                new Target(TOYOTA, RUNNER, RUNNER_5R, 2013, 2024, "Кузова: GRN285", "5 поколение"),
                new Target(TOYOTA, RUNNER, RUNNER_5, 2009, 2013, "Кузова: TRN285, GRN285", "5 поколение"));
    }

    @Test
    @DisplayName("Невидимый U+2060 вокруг тире и «н.в.» в годах разбираются")
    void wordJoinerAndUpToNow() {
        assertThat(targets("Toyota 4Runner | 5 поколение (2009⁠–⁠2013) | TRN285"))
                .containsExactly(new Target(TOYOTA, RUNNER, RUNNER_5, 2009, 2013, "Кузова: TRN285", "5 поколение"));
        assertThat(targets("Toyota Land Cruiser Prado — 4 поколение, рестайлинг (2013–н.в.)"))
                .containsExactly(new Target(TOYOTA, PRADO, PRADO_4R, 2013, null, null, "4 поколение"));
    }

    @Test
    @DisplayName("Модель — остаток заголовка целиком: «Land Cruiser Prado» не уезжает в «Land Cruiser»")
    void modelIsTheWholeRest() {
        assertThat(targets("Toyota Land Cruiser Prado — 4 поколение (2009–2013): GRJ150"))
                .extracting(Target::modelId).containsExactly(PRADO);
        assertThat(targets("Land Rover Defender — GRJ1"))
                .as("марка из двух слов не уступает короткой «Land»")
                .containsExactly(new Target(LAND_ROVER, DEFENDER, null, null, null, "Кузова: GRJ1", null));
    }

    @Test
    @DisplayName("Модель ищется только у своей марки: «A3» у Audi и у Амберавто не путаются")
    void modelBelongsToItsBrand() {
        assertThat(targets("Audi A3 — 8P1")).extracting(Target::modelId).containsExactly(A3);
        assertThat(targets("Амберавто A3 — X1")).extracting(Target::modelId).containsExactly(AMBER_A3);
    }

    @Test
    @DisplayName("Марка — целым словом: «Toyotas Camry» не Toyota")
    void brandIsAWholeWord() {
        assertThat(targets("Toyotas Camry — 1 поколение (2001–2005)")).isEmpty();
        assertThat(misses("Toyotas Camry — 1 поколение (2001–2005)"))
                .containsExactly(new Miss(Reason.NO_BRAND, "Toyotas Camry"));
    }

    @Test
    @DisplayName("Не легло — названо по причине, а марка с моделью и годами ложатся без поколения")
    void missesAreNamed() {
        String block = """
                Datsun Bluebird — 1 поколение (1990–1995)
                Lexus GX400 — GRJ158
                Toyota Land Cruiser Prado — 3 поколение (2002–2009): LJ120
                Toyota 4Runner — 5 поколение""";
        assertThat(misses(block)).containsExactly(
                new Miss(Reason.NO_BRAND, "Datsun Bluebird"),
                new Miss(Reason.NO_MODEL, "Lexus GX400"),
                new Miss(Reason.NO_GENERATION, "Toyota Land Cruiser Prado — 3 поколение (2002–2009)"),
                new Miss(Reason.NO_YEARS, "Toyota 4Runner — 5 поколение"));
        assertThat(targets(block)).as("поколение не нашлось — годы клиента всё равно ложатся")
                .containsExactly(
                        new Target(TOYOTA, PRADO, null, 2002, 2009, "Кузова: LJ120", "3 поколение"),
                        new Target(TOYOTA, RUNNER, null, null, null, null, null));
    }

    @Test
    @DisplayName("Два поколения с одним годом начала различает год конца; не различились — не угадываем")
    void sameStartYear() {
        assertThat(targets("Toyota Land Cruiser Prado — 5 поколение (2020–2021)"))
                .extracting(Target::generationId).containsExactly(PRADO_X);
        assertThat(targets("Toyota Land Cruiser Prado — 5 поколение (2020–н.в.)"))
                .extracting(Target::generationId).containsExactly(PRADO_Y);
        assertThat(targets("Toyota Land Cruiser Prado — 5 поколение (2020–2024)"))
                .extracting(Target::generationId).containsExactly((Long) null);
    }

    @Test
    @DisplayName("Машина без годов, у которой в блоке есть та же точнее, отдельной строкой не заводится")
    void coarseIsDroppedNextToPrecise() {
        String block = """
                Toyota — \s
                Toyota Land Cruiser Prado — GRJ120, GRJ120W
                Toyota Land Cruiser Prado — 4 поколение (2009–2013): GRJ150""";
        assertThat(targets(block)).containsExactly(
                new Target(TOYOTA, PRADO, PRADO_4, 2009, 2013, "Кузова: GRJ150", "4 поколение"));
    }

    @Test
    @DisplayName("Пустые строки и строка « — » без заголовка ничего не дают и не называются")
    void emptyLinesAreSilent() {
        assertThat(DromApplicability.parse(" — \n\n   \n", DICT).targets()).isEmpty();
        assertThat(DromApplicability.parse(" — \n\n   \n", DICT).misses()).isEmpty();
    }

    @Test
    @DisplayName("Метка поколения Дрома — номер и слово из текста клиента: «4 поколение, 2-й рестайлинг» → «4 поколение» (0297)")
    void dromGenerationLabelIsTheClientsWords() {
        String block = "Toyota Land Cruiser Prado — 4 поколение (2009–2013): GRJ150; "
                + "4 поколение, рестайлинг (2013–2017): GRJ150W; "
                + "4 поколение, 2-й рестайлинг (2017–2024): GDJ150W";
        assertThat(targets(block)).extracting(Target::generationLabel)
                .containsExactly("4 поколение", "4 поколение", "4 поколение");
        assertThat(DromApplicability.dromGeneration("4 поколение, 2-й рестайлинг (2017–2024)"))
                .isEqualTo("4 поколение");
        assertThat(DromApplicability.dromGeneration("рестайлинг (2013–2017)"))
                .as("пункт без номера поколения метки не получает — она не выводится").isNull();
        assertThat(DromApplicability.dromGeneration("3 и 4 поколения (2002–2017)"))
                .as("«3 и 4 поколения» — не одно поколение").isNull();
    }

    @Test
    @DisplayName("Пункты, слитые в одну строку справочника, называющие разные поколения Дрома, метки не дают (0297)")
    void mergedDifferentGenerationsGiveNoLabel() {
        // Оба пункта — без поколения в справочнике (год начала 2003 нигде не начинается),
        // значит одна строка «Prado без поколения» с годами обоих.
        String block = "Toyota Land Cruiser Prado — 3 поколение (2003–2009): LJ120; "
                + "4 поколение (2003–2010): GRJ150";
        assertThat(targets(block)).containsExactly(
                new Target(TOYOTA, PRADO, null, 2003, 2010, "Кузова: LJ120; Кузова: GRJ150", null));
        String same = "Toyota Land Cruiser Prado — 3 поколение (2003–2005): LJ120; "
                + "3 поколение, рестайлинг (2006–2009): LJ125";
        assertThat(targets(same)).extracting(Target::generationLabel).containsExactly("3 поколение");
    }
}
