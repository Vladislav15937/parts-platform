package ru.partsflow.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.intake.Donor;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.VerticalSide;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Слова-значения — коробка и стороны — одним словарём, словами владельца.
 *
 * <p><b>Зачем.</b> Решение владельца продукта 24.10.2026 ({@code tasks/0044},
 * «Ответ владельца — 24 октября 2026 (развёрнутые вопросы)», пункты 3 и 4):
 * {@code DCT} — «Робот DSG», и слова-значения с заглавной везде — в карточке
 * донора, в «Комплектации», в названии позиции, в файле склада. Копий было
 * пять, и они разошлись: «Вариатор» в карточке донора против «вариатор»
 * в «Комплектации», «Перед.» в файле против «перед.» на экране, а {@code DCT}
 * не называл никто. Перебор держит три вещи: у каждого значения перечислений
 * есть слово владельца, копия экрана равна словарю сервера, и своей копии
 * больше нигде нет — ни на сервере, ни на экране.
 *
 * <p>Тест на стороне сервера, а не фронтенда: перечисления доступны здесь сами,
 * а чтение файлов фронтенду требует типов Node, которых в его сборке нет
 * (тот же довод, что у {@code WordingConsistencyTest}).
 */
class ValueWordsTest {

    /** Слова владельца дословно — не из словаря, иначе код проверял бы сам себя. */
    private static final Map<String, String> OWNER_GEARBOX = Map.of(
            "AT", "АКПП", "MT", "МКПП", "CVT", "Вариатор", "AMT", "Робот", "DCT", "Робот DSG");
    private static final Map<String, String> OWNER_FRONT_REAR = Map.of(
            "FRONT", "Перед.", "REAR", "Задн.", "BOTH", "Перед., Задн.");
    private static final Map<String, String> OWNER_LEFT_RIGHT = Map.of(
            "LEFT", "Лев.", "RIGHT", "Прав.", "BOTH", "Лев., Прав.");
    /** Верх и низ владелец не перечислил — правило его общее; названо в PR. */
    private static final Map<String, String> OWNER_UPPER_LOWER = Map.of(
            "UPPER", "Верх.", "LOWER", "Ниж.", "BOTH", "Верх., Ниж.");

    private static final Path CLIENT = Path.of("frontend/src");
    private static final Path SERVER = Path.of("src/main/java");

    @Test
    @DisplayName("Каждая коробка названа словом владельца, «Робот DSG» тоже")
    void everyGearboxHasTheOwnersWord() {
        for (Donor.TransmissionType value : Donor.TransmissionType.values()) {
            assertThat(ValueWords.transmission(value.name()))
                    .as("коробка %s", value)
                    .isEqualTo(OWNER_GEARBOX.get(value.name()));
        }
        assertThat(ValueWords.transmissions()).isEqualTo(OWNER_GEARBOX);
    }

    @Test
    @DisplayName("Каждая сторона названа с заглавной, «обе» — двумя словами с заглавной")
    void everySideHasTheOwnersWord() {
        check(ValueWords.Axis.FRONT_REAR, LongitudinalSide.values(), OWNER_FRONT_REAR);
        check(ValueWords.Axis.LEFT_RIGHT, LateralSide.values(), OWNER_LEFT_RIGHT);
        check(ValueWords.Axis.UPPER_LOWER, VerticalSide.values(), OWNER_UPPER_LOWER);
        // В названии «обе» — через пробел: «Перед. Задн.», как было до словаря.
        assertThat(ValueWords.side(ValueWords.Axis.FRONT_REAR, "BOTH", ValueWords.TITLE_SEPARATOR))
                .isEqualTo("Перед. Задн.");
    }

    @Test
    @DisplayName("Выражение SQL собрано из того же словаря и с разделителями")
    void sqlIsBuiltFromTheDictionary() {
        assertThat(ValueWords.sqlSide(ValueWords.Axis.FRONT_REAR, "p.side_fr", ", "))
                .isEqualTo("CASE p.side_fr WHEN 'FRONT' THEN 'Перед.' WHEN 'REAR' THEN 'Задн.'"
                        + " WHEN 'BOTH' THEN 'Перед., Задн.' END");
        assertThat(ValueWords.sqlTransmission("d.transmission_type"))
                .contains("WHEN 'DCT' THEN 'Робот DSG'")
                .startsWith("CASE d.transmission_type WHEN")
                .endsWith(" END");
    }

    /**
     * Копия на экране равна словарю сервера.
     *
     * <p>Клетку «Передний / Задний», карточку и форму правки рисует фронтенд
     * по коду стороны: разойдись копия — клетка экрана скажет «перед.», а файл
     * того же склада «Перед.», ровно как было до решения владельца.
     */
    @Test
    @DisplayName("Стороны на экране — те же слова, что на сервере")
    void clientSidesMatchTheServer() throws IOException {
        String catalog = Files.readString(CLIENT.resolve("inventory/catalog.ts"));
        assertThat(clientDictionary(catalog, "SIDE_FR"))
                .isEqualTo(cellWords(ValueWords.Axis.FRONT_REAR, LongitudinalSide.values()));
        assertThat(clientDictionary(catalog, "SIDE_LR"))
                .isEqualTo(cellWords(ValueWords.Axis.LEFT_RIGHT, LateralSide.values()));
    }

    /**
     * Своего словаря сторон на экране нет — только {@code catalog.ts}.
     *
     * <p>Копий было три (витрина, карточка, форма правки), и именно их регистр
     * разошёлся с файлом. Ищется объявление, а не импорт: импорт мог бы стоять
     * рядом с вернувшейся копией.
     */
    @Test
    @DisplayName("У экранов нет своих словарей сторон")
    void screensKeepNoOwnSideWords() throws IOException {
        Pattern copy = Pattern.compile("(LEFT|FRONT|UPPER)\\s*:\\s*['\"]");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CLIENT)) {
            for (Path file : files.filter(f -> f.toString().matches(".*\\.tsx?$"))
                    .filter(f -> !f.toString().matches(".*\\.test\\.tsx?$"))
                    .filter(f -> !f.endsWith(Path.of("inventory/catalog.ts")))
                    .toList()) {
                if (copy.matcher(Files.readString(file)).find()) {
                    found.add(file.toString());
                }
            }
        }
        assertThat(found).as("своя копия словаря сторон — берите SIDE_FR/SIDE_LR из catalog.ts")
                .isEmpty();
    }

    /**
     * На сервере слова-значения пишет только {@link ValueWords}.
     *
     * <p>Разборщики переноса знают их строчными — они сравнивают со словом
     * чужого файла, приведённым к нижнему регистру, и это не показ, а разбор.
     */
    @Test
    @DisplayName("На сервере слов-значений нет нигде, кроме общего словаря")
    void serverBuildsNoOwnValueWords() throws IOException {
        Pattern word = Pattern.compile(
                "[\"'](?iu:перед|задн|лев|прав|верх|ниж)\\.|[\"'](?iu:акпп|мкпп|вариатор|робот)[\"' ]");
        Map<String, String> allowed = Map.of(
                "BazonValueParser.java", "разбор слов файла Bazon, приведённых к нижнему регистру",
                "DromCardWords.java", "разбор слов карточки Дрома, приведённых к нижнему регистру",
                "ValueWords.java", "сам словарь");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVER)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (allowed.containsKey(file.getFileName().toString())) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int at = 0; at < lines.size(); at++) {
                    if (word.matcher(lines.get(at)).find()) {
                        found.add(file + ":" + (at + 1) + "  " + lines.get(at).strip());
                    }
                }
            }
        }
        assertThat(found).as("своя копия слов-значений — берите shared/ValueWords").isEmpty();
    }

    // --- помощники ---

    private static void check(ValueWords.Axis axis, Enum<?>[] values, Map<String, String> owner) {
        assertThat(Arrays.stream(values).map(Enum::name).toList())
                .as("у стороны оси %s нет слова владельца", axis)
                .containsExactlyInAnyOrderElementsOf(owner.keySet());
        for (Enum<?> value : values) {
            assertThat(ValueWords.side(axis, value.name(), ValueWords.CELL_SEPARATOR))
                    .as("%s %s", axis, value)
                    .isEqualTo(owner.get(value.name()));
        }
    }

    private static Map<String, String> cellWords(ValueWords.Axis axis, Enum<?>[] values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Enum<?> value : values) {
            out.put(value.name(), ValueWords.side(axis, value.name(), ValueWords.CELL_SEPARATOR));
        }
        return out;
    }

    /** {@code export const NAME: Record<string, string> = { A: 'x', B: 'y' };} → карта. */
    private static Map<String, String> clientDictionary(String source, String name) {
        Matcher declaration = Pattern.compile(
                "export const " + name + "\\s*:[^=]*=\\s*\\{([^}]*)\\}").matcher(source);
        assertThat(declaration.find()).as("в catalog.ts нет словаря %s", name).isTrue();
        Map<String, String> out = new LinkedHashMap<>();
        Matcher entry = Pattern.compile("(\\w+)\\s*:\\s*'([^']*)'").matcher(declaration.group(1));
        while (entry.find()) {
            out.put(entry.group(1), entry.group(2));
        }
        return out;
    }
}
