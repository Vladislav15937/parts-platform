package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Все места записи в {@code part_oem} ведут к одному правилу хранения номера —
 * {@code OemNumbers.clean} / {@code OemNumbers.list} (задачи 0309, 0317).
 *
 * <p>Перебором исходников, а не списком в голове: пересчёт 0317 приводит уже
 * сохранённое к правилу записи, и новый путь записи мимо {@code clean} снова
 * наплодил бы позиции, которые надо пересчитывать. Новый файл, пишущий
 * в {@code part_oem}, этот тест красит — его надо дописать в {@link #WRITERS}
 * и показать, где он чистит номер.
 *
 * <p>Тест текстовый и ловит только форму, а не смысл: что каждая поверхность
 * действительно хранит {@code clean}, доказывают её HTTP-тесты
 * ({@code IntakeOemHttpTest}, {@code ExcelImportTest}, {@code PartAllFieldsEditTest},
 * {@code OemRecountTest.bazonStoresCleanNumbers}).
 */
class OemWritersTest {

    /** Запись — вставка или правка, где рядом с глаголом стоит имя таблицы. */
    private static final Pattern WRITE = Pattern.compile(
            "(INSERT\\s+INTO|UPDATE)[\\s\"+]*(?:\\w+\\s*\\+\\s*\"*)?[\\s\"]*\\.?part_oem\\b",
            Pattern.CASE_INSENSITIVE);

    /** Файл → чем он приводит номер к правилу хранения. */
    private static final Map<String, String> WRITERS = Map.of(
            "inventory/PartIdentity.java", "OemNumbers.clean",
            "inventory/OemRecount.java", "OemNumbers.clean",
            "intake/IntakeService.java", "OemNumbers.clean",
            "migration/excel/ExcelWarehouseImporter.java", "OemNumbers.clean",
            "migration/bazon/BazonImporter.java", "OemNumbers.clean");

    @Test
    @DisplayName("Каждый файл, пишущий в part_oem, известен и зовёт OemNumbers.clean")
    void everyWriterCleans() throws IOException {
        Path root = Path.of("src/main/java/ru/partsflow");
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (WRITE.matcher(text).find()) {
                    found.put(root.relativize(file).toString().replace('\\', '/'), text);
                }
            }
        }
        assertThat(found.keySet()).as("пути записи в part_oem").containsExactlyInAnyOrderElementsOf(WRITERS.keySet());
        found.forEach((file, text) -> assertThat(text)
                .as("%s пишет в part_oem, не приводя номер к правилу хранения", file)
                .contains(WRITERS.get(file)));
    }
}
