package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила поколения по коду кузова без базы (задача 0295): номер кузова
 * в наименовании, ряд кода, своя машина, решение по сопоставлению.
 */
class BodyGenerationTest {

    private static final long TOYOTA = 1;
    private static final long LEXUS = 2;
    private static final long PRADO = 11;
    private static final long CRUISER = 12;
    private static final long RX = 20;

    private static final DromApplicability.Dictionary DICT = new DromApplicability.Dictionary(
            List.of(new DromApplicability.Brand(TOYOTA, "Toyota"),
                    new DromApplicability.Brand(LEXUS, "Lexus")),
            List.of(new DromApplicability.Model(PRADO, TOYOTA, "Land Cruiser Prado"),
                    new DromApplicability.Model(CRUISER, TOYOTA, "Land Cruiser"),
                    new DromApplicability.Model(RX, LEXUS, "RX350")),
            List.of(new DromApplicability.Generation(300, PRADO, 2002, 2008),
                    new DromApplicability.Generation(400, PRADO, 2009, 2012),
                    new DromApplicability.Generation(401, PRADO, 2013, 2016)));

    @Test
    @DisplayName("Номер кузова — отдельное число после модели; вплотную и через косую — нет")
    void titleBody() {
        assertThat(BodyGeneration.titleBody("Капот Toyota Land Cruiser Prado 120", "Land Cruiser Prado"))
                .isEqualTo("120");
        assertThat(BodyGeneration.titleBody("Фара Toyota Land Cruiser Prado 95 левая", "Land Cruiser Prado"))
                .isEqualTo("95");
        assertThat(BodyGeneration.titleBody("Фара Lexus RX350", "RX350")).isNull();
        assertThat(BodyGeneration.titleBody("Фара Toyota Land Cruiser Prado120", "Land Cruiser Prado"))
                .isNull();
        assertThat(BodyGeneration.titleBody("Фара Toyota Land Cruiser Prado 120/150", "Land Cruiser Prado"))
                .isNull();
        assertThat(BodyGeneration.titleBody("Фара Toyota Land Cruiser Prado", "Land Cruiser Prado")).isNull();
    }

    @Test
    @DisplayName("Ряд кода: 120 — ряд «12», 95 — «9»; «12» сокращением 120-го не считается")
    void sameSeries() {
        assertThat(BodyGeneration.sameSeries("GRJ121W", "120")).isTrue();
        assertThat(BodyGeneration.sameSeries("LJ150", "120")).isFalse();
        assertThat(BodyGeneration.sameSeries("KZJ95W", "95")).isTrue();
        assertThat(BodyGeneration.sameSeries("KZJ90", "95")).isTrue();
        assertThat(BodyGeneration.sameSeries("GRJ120", "12"))
                .as("«Prado 12» — не 120-й ряд (владелец: «не знаю» — не ставить)").isFalse();
    }

    @Test
    @DisplayName("Своя машина — названная в наименовании, длиннейшая на одном месте; без модели — нет")
    void own() {
        String title = "Капот Toyota Land Cruiser Prado 120";
        BodyGeneration.Own own = BodyGeneration.own(title, List.of(
                new BodyGeneration.Fit(LEXUS, RX),
                new BodyGeneration.Fit(TOYOTA, CRUISER),
                new BodyGeneration.Fit(TOYOTA, PRADO)), DICT);
        assertThat(own).isEqualTo(new BodyGeneration.Own(TOYOTA, PRADO, "Land Cruiser Prado"));
        assertThat(BodyGeneration.own("Магнитола Toyota", List.of(
                new BodyGeneration.Fit(TOYOTA, PRADO), new BodyGeneration.Fit(TOYOTA, null)), DICT))
                .as("марка названа, модели нет").isNull();
        assertThat(BodyGeneration.own("Магнитола", List.of(new BodyGeneration.Fit(TOYOTA, PRADO)), DICT))
                .isNull();
    }

    @Test
    @DisplayName("Пары из блока: годы клиента, поколение справочника по году начала, коды заглавными")
    void pairsAndDecision() {
        BodyGeneration.Index index = new BodyGeneration.Index();
        DromApplicability.bodyPairs("Toyota Land Cruiser Prado — 3 поколение (2002–2009): GRJ120, kdj120; "
                + "4 поколение (2009⁠–⁠2013): GRJ150; Другие кузова: LJ120\nДля двигателей — 1GRFE",
                DICT).forEach(index::add);
        assertThat(index.size()).isEqualTo(3);

        BodyGeneration.Outcome found = index.decide(PRADO, List.of("GRJ120", "GRJ120W", "KDJ120"));
        assertThat(found.found()).isTrue();
        assertThat(found.generationId()).isEqualTo(300);
        assertThat(found.yearFrom()).isEqualTo(2002);
        assertThat(found.yearTo()).isEqualTo(2009);
        assertThat(found.decisive()).as("код без пары ничего не даёт").containsExactly("GRJ120", "KDJ120");

        assertThat(index.decide(PRADO, List.of("GRJ120", "GRJ150")).reason())
                .isEqualTo(BodyGeneration.Reason.TWO_GENERATIONS);
        assertThat(index.decide(PRADO, List.of("LJ120")).reason())
                .as("«Другие кузова» к поколению не привязаны — пар не дают")
                .isEqualTo(BodyGeneration.Reason.UNKNOWN_BODY);
        assertThat(index.decide(CRUISER, List.of("GRJ120")).reason())
                .as("пара одной модели не годится другой").isEqualTo(BodyGeneration.Reason.UNKNOWN_BODY);

        DromApplicability.bodyPairs("Toyota Land Cruiser Prado — 4 поколение, рестайлинг (2013–2017): GRJ151",
                DICT).forEach(index::add);
        assertThat(index.decide(PRADO, List.of("GRJ150")).reason())
                .isEqualTo(BodyGeneration.Reason.RESTYLING);

        DromApplicability.bodyPairs("Toyota Land Cruiser Prado — 9 поколение (1950–1955): XJ9", DICT)
                .forEach(index::add);
        assertThat(index.decide(PRADO, List.of("XJ9")).reason())
                .isEqualTo(BodyGeneration.Reason.NOT_IN_CATALOG);
    }

    @Test
    @DisplayName("Кузова из примечания: слитые части и пометка не путаются")
    void codesOfNote() {
        assertThat(BodyGeneration.codesOf("Кузова: GRJ120, grj121W; Кузова: KDJ120; "
                + "Поколение по коду кузова: XX1")).containsExactly("GRJ120", "GRJ121W", "KDJ120");
        assertThat(BodyGeneration.note(List.of("GRJ120", "GRJ120W"), List.of("GRJ120")))
                .isEqualTo("Кузова: GRJ120, GRJ120W; Поколение по коду кузова: GRJ120");
    }
}
