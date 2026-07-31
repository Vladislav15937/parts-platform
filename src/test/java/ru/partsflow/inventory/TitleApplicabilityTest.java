package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор машин из заголовка позиции.
 *
 * <p>Заголовки взяты с живого склада переехавшего клиента — там четверть
 * позиций без донора, и машины названы только в тексте.
 */
class TitleApplicabilityTest {

    private static final long TOYOTA = 1;
    private static final long VW = 2;
    private static final long AUDI = 3;
    private static final long KIA = 4;
    private static final long AMBER = 5;

    private static final TitleApplicability.Dictionary DICT = new TitleApplicability.Dictionary(
            List.of(new TitleApplicability.Brand(TOYOTA, "Toyota"),
                    new TitleApplicability.Brand(VW, "Volkswagen"),
                    new TitleApplicability.Brand(AUDI, "Audi"),
                    new TitleApplicability.Brand(KIA, "Kia"),
                    // Марка, названная как чужая модель: «Jetta» есть и у нас
                    // в справочнике, и моделью у Volkswagen.
                    new TitleApplicability.Brand(6, "Jetta"),
                    new TitleApplicability.Brand(AMBER, "Амберавто")),
            List.of(new TitleApplicability.Model(10, TOYOTA, "Land Cruiser"),
                    new TitleApplicability.Model(11, TOYOTA, "Land Cruiser Prado"),
                    new TitleApplicability.Model(12, TOYOTA, "Sprinter"),
                    new TitleApplicability.Model(13, TOYOTA, "Corolla"),
                    new TitleApplicability.Model(20, VW, "Golf"),
                    new TitleApplicability.Model(21, VW, "Passat"),
                    new TitleApplicability.Model(22, VW, "Jetta"),
                    new TitleApplicability.Model(23, VW, "Touran"),
                    new TitleApplicability.Model(30, AUDI, "A3"),
                    new TitleApplicability.Model(40, KIA, "Cerato"),
                    new TitleApplicability.Model(50, AMBER, "A3")));

    @Test
    @DisplayName("Одна машина в заголовке")
    void singleVehicle() {
        assertThat(TitleApplicability.parse("Брызговик Kia Cerato задн. лев. (б/у) 86861A7100", DICT))
                .containsExactly(new TitleApplicability.Vehicle(KIA, 40));
    }

    @Test
    @DisplayName("Несколько моделей одной марки")
    void severalModels() {
        assertThat(TitleApplicability.parse(
                "Уплотнитель двери Toyota Sprinter,Corolla AE111 (б/у)", DICT))
                .containsExactly(new TitleApplicability.Vehicle(TOYOTA, 12),
                        new TitleApplicability.Vehicle(TOYOTA, 13));
    }

    @Test
    @DisplayName("Несколько марок: модель привязывается к своей")
    void severalBrands() {
        // Так это и написано на живом складе: марки через запятую, потом
        // модели через запятую, и разобрать их можно только зная справочник.
        assertThat(TitleApplicability.parse(
                "Радиатор охлаждения Audi,Volkswagen A3,Golf V,Jetta,Passat 8P1,8PA", DICT))
                .containsExactly(new TitleApplicability.Vehicle(AUDI, 30),
                        new TitleApplicability.Vehicle(VW, 20),
                        new TitleApplicability.Vehicle(VW, 22),
                        new TitleApplicability.Vehicle(VW, 21));
    }

    @Test
    @DisplayName("Поколение в куске не мешает: «Golf V» — это Golf")
    void generationInToken() {
        assertThat(TitleApplicability.parse("Фара Volkswagen Golf V (б/у)", DICT))
                .containsExactly(new TitleApplicability.Vehicle(VW, 20));
    }

    @Test
    @DisplayName("Длинное имя модели побеждает короткое")
    void longestModelWins() {
        // «Land Cruiser» — тоже модель, и если взять её, деталь от Prado
        // уедет к другой машине.
        assertThat(TitleApplicability.parse(
                "Пружина Toyota Land Cruiser Prado GDJ150L перед. (б/у)", DICT))
                .containsExactly(new TitleApplicability.Vehicle(TOYOTA, 11));
    }

    @Test
    @DisplayName("Разбор кончается на первом куске, который не модель")
    void stopsAtBodyCodes() {
        // За моделями идут кузова и двигатели, тоже через запятую. Если
        // Кузова начинаются сразу за последней моделью и идут через ту же
        // запятую. Если не остановиться на первом неразобранном куске,
        // а пропускать его и читать дальше, то слово, случайно совпавшее
        // с моделью, добавит машину, которой в заголовке нет: здесь это
        // «Sprinter» — на самом деле часть перечня кузовов и двигателей.
        assertThat(TitleApplicability.parse(
                "Радиатор Toyota Corolla 1K1,8PA,Sprinter GDJ150 (б/у)", DICT))
                .containsExactly(new TitleApplicability.Vehicle(TOYOTA, 13));
    }

    @Test
    @DisplayName("Модель ищется только среди найденных марок")
    void modelBelongsToFoundBrand() {
        // «A3» есть и у Audi, и у «Амберавто». Без ограничения маркой
        // деталь уехала бы к обеим.
        assertThat(TitleApplicability.parse("Фара Audi A3 (б/у)", DICT))
                .containsExactly(new TitleApplicability.Vehicle(AUDI, 30));
    }

    @Test
    @DisplayName("Марка, слипшаяся с соседним словом, не считается")
    void brandInsideWordIsNotAMatch() {
        // В перенесённых заголовках пробел иногда теряется. Считать «Kia»
        // внутри «ПодкрылокKia» маркой значит угадывать: правило одно —
        // только точное совпадение целым словом.
        assertThat(TitleApplicability.parse("ПодкрылокKia Cerato (б/у)", DICT)).isEmpty();

        // А с пробелом — считается.
        assertThat(TitleApplicability.parse("Подкрылок Kia Cerato (б/у)", DICT))
                .containsExactly(new TitleApplicability.Vehicle(KIA, 40));
    }

    @Test
    @DisplayName("Заголовок без машины разбирается в пустоту, а не в ошибку")
    void noVehicle() {
        assertThat(TitleApplicability.parse("Теплообменник (б/у) CCLNS004", DICT)).isEmpty();
        assertThat(TitleApplicability.parse("", DICT)).isEmpty();
        assertThat(TitleApplicability.parse(null, DICT)).isEmpty();
    }

    @Test
    @DisplayName("Марка без моделей даёт пустоту, а не марку целиком")
    void brandWithoutModels() {
        // «на любой Toyota» — это утверждение, которого никто не делал:
        // в заголовке просто не назвали модель.
        assertThat(TitleApplicability.parse("Болт Toyota (б/у) 90105", DICT)).isEmpty();
    }
}
