package ru.partsflow.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Экран считает длину кросс-номеров тем же разделением, что сервер (задача 0310).
 *
 * <p>Предупреждение о длинных кросс-номерах обязано знать длину до сохранения,
 * поэтому список разделителей {@link OemNumbers} повторён во фронтенде
 * ({@code frontend/src/inventory/oemLength.ts}). Разойдись копии — экран
 * засчитывал бы в длину разделитель, которого сервер не считает знаком,
 * или наоборот, и предупреждал бы не о том, что будет сохранено.
 * Тест на стороне сервера: фронтенд Java-исходник прочитать не может
 * (та же причина, что у {@code WordingConsistencyTest}).
 */
class OemLengthClientTest {

    @Test
    @DisplayName("Разделители кросс-номеров на экране — те же, что у OemNumbers")
    void separatorsMatch() throws IOException {
        String server = Files.readString(Path.of("src/main/java/ru/partsflow/catalog/OemNumbers.java"));
        String client = Files.readString(Path.of("frontend/src/inventory/oemLength.ts"));

        String serverClass = group(server, "SEPARATORS = \"\\[([^\\]]+)\\]\\+\";")
                .replace("\\\\", "\\");
        String clientClass = group(client, "SEPARATORS = /\\[([^\\]]+)\\]\\+/;");

        assertThat(clientClass).as("разделители экрана разошлись с OemNumbers.SEPARATORS")
                .isEqualTo(serverClass);
    }

    private static String group(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        assertThat(m.find()).as("не найдено объявление «%s»", regex).isTrue();
        return m.group(1);
    }
}
