package ru.partsflow.publishing.avito;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.Part;
import ru.partsflow.inventory.PartCondition;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AvitoFeedWriterTest {

    private final AvitoFeedWriter writer = new AvitoFeedWriter();
    private final AvitoMappingResolver mapping = new StubMapping();

    @Test
    @DisplayName("Фид содержит объявления и корректно экранирует спецсимволы")
    void writesValidFeed() throws Exception {
        Part part = part("Фара левая <Camry> \"V50\"", new BigDecimal("8500"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = writer.write(out, List.of(part).iterator(), mapping);

        String xml = out.toString(StandardCharsets.UTF_8);
        assertThat(count).isEqualTo(1);
        assertThat(xml).contains("<Ads formatVersion=\"3\" target=\"Avito.ru\">");
        assertThat(xml).contains("<Price>8500</Price>");
        assertThat(xml).contains("<Condition>Б/у</Condition>");
        // Экранирование обязательно: в названиях запчастей регулярно попадаются
        // кавычки и угловые скобки, а невалидный XML отклоняется целиком.
        assertThat(xml).contains("&lt;Camry&gt;").doesNotContain("<Camry>");
    }

    @Test
    @DisplayName("Пустые поля не попадают в фид")
    void skipsEmptyElements() throws Exception {
        Part part = part("Бампер", null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, List.of(part).iterator(), mapping);

        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("<Price>");
    }

    @Test
    @DisplayName("50 000 объявлений пишутся без роста потребления памяти")
    void streamsLargeFeedWithoutMaterializing() throws Exception {
        Iterator<Part> many = Stream.generate(() -> part("Деталь", BigDecimal.TEN))
                .limit(50_000)
                .iterator();

        CountingOutputStream out = new CountingOutputStream();
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();

        int count = writer.write(out, many, mapping);

        long after = runtime.totalMemory() - runtime.freeMemory();
        long grownMb = (after - before) / (1024 * 1024);

        assertThat(count).isEqualTo(50_000);
        assertThat(out.bytes).isGreaterThan(1_000_000);
        // Материализация фида в память дала бы сотни мегабайт роста.
        assertThat(grownMb)
                .as("фид не должен накапливаться в памяти, рост %d МБ", grownMb)
                .isLessThan(64);
    }

    private Part part(String title, BigDecimal price) {
        Part part = new Part(1L, title, price);
        part.setCondition(PartCondition.USED);
        return part;
    }

    private static class StubMapping extends AvitoMappingResolver {
        StubMapping() {
            super(null);
        }

        @Override
        public Mapping resolve(Long categoryId) {
            return new Mapping("Запчасти и аксессуары", "Запчасти");
        }
    }

    private static class CountingOutputStream extends OutputStream {
        long bytes;

        @Override
        public void write(int b) {
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bytes += len;
        }
    }
}
