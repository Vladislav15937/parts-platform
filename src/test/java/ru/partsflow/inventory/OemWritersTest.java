package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * и показать, где он чистит номер. Вызов ищется в коде без комментариев:
 * упоминание в комментарии рядом со снятым вызовом проверку не проходит.
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

    /**
     * Файл → чем он приводит номер к правилу хранения.
     *
     * <p>С задачи 0323 у каждого, кто пишет основной номер, это
     * {@code OemNumbers.primary(} — деление «один номер производителя, остальное —
     * кросс-номера», — а у того, кто пишет кросс-номера из своего поля, ещё
     * {@code OemNumbers.list(}. Обе функции чистят части {@code clean} сами.
     * Пересчёт (0317, 0319) пока чистит и делит кросс-номера; основной номер
     * он поделит тем же {@code primary} в задаче 0324.
     */
    private static final Map<String, List<String>> WRITERS = Map.of(
            "inventory/PartIdentity.java", List.of("OemNumbers.primary(", "OemNumbers.list("),
            "inventory/OemRecount.java", List.of("OemNumbers.clean("),
            "intake/IntakeService.java", List.of("OemNumbers.primary("),
            "migration/excel/ExcelWarehouseImporter.java", List.of("OemNumbers.primary(", "OemNumbers.list("),
            "migration/bazon/BazonImporter.java", List.of("OemNumbers.primary(", "OemNumbers.list("));

    /**
     * Второе правило деления номеров рядом с записью в {@code part_oem}:
     * {@code BazonValueParser.parseList} делил кросс-номера только запятой (до 0323),
     * и строка с «;» ложилась одним номером. Его зовёт разбор ссылок на снимки —
     * там, а не здесь.
     */
    private static final String FOREIGN_SPLIT = "parseList(";

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
        // Вызов ищется в коде, а не в тексте файла: комментарий «хранится
        // OemNumbers.clean» рядом со снятым вызовом проверку не проходит.
        found.forEach((file, text) -> {
            for (String call : WRITERS.get(file)) {
                assertThat(withoutComments(text))
                        .as("%s пишет в part_oem, не зовя %s в коде", file, call)
                        .contains(call);
            }
        });
    }

    @Test
    @DisplayName("Пишущие в part_oem не делят номера своим parseList")
    void noOwnSplitInWriters() throws IOException {
        Path root = Path.of("src/main/java/ru/partsflow");
        for (String file : WRITERS.keySet()) {
            assertThat(withoutComments(Files.readString(root.resolve(file), StandardCharsets.UTF_8)))
                    .as("%s делит номера своим %s, а не OemNumbers.list", file, FOREIGN_SPLIT)
                    .doesNotContain(FOREIGN_SPLIT);
        }
    }

    /** Исходник без комментариев {@code //…} и {@code /* … *}{@code /}; строки не трогаются. */
    static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' && source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(source, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(source, i, j);
                i = j;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
