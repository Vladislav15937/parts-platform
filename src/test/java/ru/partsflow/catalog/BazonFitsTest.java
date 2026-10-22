package ru.partsflow.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.catalog.BazonFits.Generation;
import ru.partsflow.catalog.BazonFits.Motor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Строка «подходит» до знака (задача 0342): строки из файлов Bazon клиента, обезличенные
 * (модель выдуманная, форма и знаки — как в файле), разобраны в поколение и собраны обратно.
 */
class BazonFitsTest {

    /** Строка и то, что в справочнике должно ей соответствовать. */
    private record Sample(String line, Generation generation, boolean anyEngine) {
    }

    private static Motor m(String name, Integer cc, String fuel) {
        return new Motor(name, cc, fuel);
    }

    private static final List<Sample> SAMPLES = List.of(
            // длинное тире справочника → короткое; платформа; кузова через запятую; бензин
            new Sample("Nissan Тест 2 поколение 2007–2015 T31 (T31, TNT31) двигатель QR25DE (2.5 Б)",
                    new Generation("Nissan", "Тест", "2 поколение", "2007—2015", "T31",
                            List.of("T31", "TNT31"), List.of(m("QR25DE", 2500, "gasoline"))), false),
            // дизель и несколько двигателей через запятую
            new Sample("Toyota Тест 3 поколение 1996–2002 E100 (A, B) двигатель 1NDTV (1.4 Д), 4JJ1TCN (3.0 Д)",
                    new Generation("Toyota", "Тест", "3 поколение", "1996—2002", "E100",
                            List.of("A", "B"), List.of(m("1NDTV", 1400, "diesel"),
                                    m("4JJ1TCN", 3000, "diesel"))), false),
            // газ: буква Г
            new Sample("Toyota Тест 1 поколение 2000–2005 X1 (K1) двигатель 1NZFNE (1.5 Г), 3URFBE (5.7 Г)",
                    new Generation("Toyota", "Тест", "1 поколение", "2000—2005", "X1",
                            List.of("K1"), List.of(m("1NZFNE", 1500, "gas"), m("3URFBE", 5700, "gas"))), false),
            // электро: объём 0.0 и буква Э, открытый конец — н.в., платформы нет
            new Sample("Lexus Тест 1 поколение 2019–н.в. (KMA10) двигатель 4KM (0.0 Э)",
                    new Generation("Lexus", "Тест", "1 поколение", "2019—...", null,
                            List.of("KMA10"), List.of(m("4KM", null, "electric"))), false),
            // кузовов нет — скобок нет
            new Sample("Mitsubishi Тест 1 поколение 2009–2013 HA3W двигатель Y4F1 (0.0 Э), Y51 (0.0 Э)",
                    new Generation("Mitsubishi", "Тест", "1 поколение", "2009—2013", "HA3W",
                            List.of(), List.of(m("Y4F1", null, "electric"), m("Y51", null, "electric"))), false),
            // гибрид отдельной буквы не имеет — буква теплового двигателя
            new Sample("Toyota Тест 2 поколение 2003–2009 XW20 (ZZW30) двигатель 1NZFXE (1.5 Б)",
                    new Generation("Toyota", "Тест", "2 поколение", "2003—2009", "XW20",
                            List.of("ZZW30"), List.of(m("1NZFXE", 1500, "hybrid"))), false),
            // «любой двигатель» с кодами
            new Sample("Toyota Тест 5 поколение 2001–2006 XV30 (ACV30), любой двигатель (1MZ-FE)",
                    new Generation("Toyota", "Тест", "5 поколение", "2001—2006", "XV30",
                            List.of("ACV30"), List.of(m("1MZ-FE", 3000, "gasoline"))), true),
            new Sample("Toyota Тест 5 поколение 2001–2006 XV30 (ACV30, MCV30), любой двигатель (1MZ-FE, 2AZ-FE)",
                    new Generation("Toyota", "Тест", "5 поколение", "2001—2006", "XV30",
                            List.of("ACV30", "MCV30"),
                            List.of(m("1MZ-FE", 3000, "gasoline"), m("2AZ-FE", 2400, "gasoline"))), true));

    @Test
    @DisplayName("Строки из файлов Bazon собираются из справочника обратно до знака")
    void lineIsRebuiltToTheCharacter() {
        for (Sample s : SAMPLES) {
            assertThat(BazonFits.line(s.generation(), s.anyEngine())).isEqualTo(s.line());
        }
    }

    @Test
    @DisplayName("Годы: длинное тире справочника — коротким, «...» — «н.в.»")
    void years() {
        assertThat(BazonFits.years("2007—2015")).isEqualTo("2007–2015");
        assertThat(BazonFits.years("2018—...")).isEqualTo("2018–н.в.");
        assertThat(BazonFits.years("1996")).isEqualTo("1996");
    }

    @Test
    @DisplayName("Объём: куб. см → литры с точкой и одним знаком, нет объёма — 0.0")
    void volume() {
        assertThat(BazonFits.volume(2500)).isEqualTo("2.5");
        assertThat(BazonFits.volume(1498)).isEqualTo("1.5");
        assertThat(BazonFits.volume(600)).isEqualTo("0.6");
        assertThat(BazonFits.volume(null)).isEqualTo("0.0");
    }

    @Test
    @DisplayName("Поколение без двигателей строки не даёт — выдумывать её за Bazon нельзя")
    void noMotorsNoLine() {
        Generation g = new Generation("Nissan", "Тест", "1 поколение", "2000—2006", null,
                List.of("A"), List.of());
        assertThat(BazonFits.line(g, false)).isNull();
        assertThat(BazonFits.line(g, true)).isNull();
    }

    @Test
    @DisplayName("Блок: «<Вид> подходит:» и строка на машину; нет строк или вида — блока нет")
    void block() {
        assertThat(BazonFits.block("Фара", List.of("a", "b"))).isEqualTo("Фара подходит:\na\nb");
        assertThat(BazonFits.block("Фара", List.of())).isNull();
        assertThat(BazonFits.block(null, List.of("a"))).isNull();
    }
}
