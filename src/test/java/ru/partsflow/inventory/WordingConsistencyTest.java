package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import ru.partsflow.shared.SupplyKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Слова на экране и слова в отборе — одни и те же.
 *
 * <p><b>Зачем.</b> Колонку «Состояние» рисует словарь клиента, а список
 * значений для отбора собирает сервер выражением
 * {@code CASE p.condition WHEN 'USED' THEN 'б/у' …} — и отбор сравнивает
 * с ним же. То есть слово, выбранное в меню, уходит на сервер и обязано
 * совпасть с написанным там. Сегодня они совпадают буква в букву, но
 * связаны были только тем, что писались в один день: правка одного слова
 * ломает отбор молча, а причина видна лишь в SQL.
 *
 * <p>Та же связка у сезонов на вкладке колёс.
 *
 * <p>Тест на стороне сервера, а не фронтенда: там чтение файла требует
 * типов Node, которых в сборке нет, — а проверка нужна на каждой правке
 * слова, с какой бы стороны её ни сделали.
 */
class WordingConsistencyTest {

    private static final Path SERVER = Path.of("src/main/java/ru/partsflow/inventory");
    private static final Path CLIENT = Path.of("frontend/src/inventory");

    @Test
    @DisplayName("Состояние показано теми же словами, какими отбирается")
    void conditionWordsMatch() throws IOException {
        String server = read(SERVER.resolve("CatalogService.java"));
        String client = read(CLIENT.resolve("catalog.ts"));

        for (String word : List.of("новая", "б/у", "восстановленная")) {
            assertThat(server)
                    .as("отбор витрины не знает состояния «%s»", word)
                    .contains("'" + word + "'");
            assertThat(client)
                    .as("экран не показывает состояние «%s», а отбор его отдаёт", word)
                    .contains("'" + word + "'");
        }
    }

    @Test
    @DisplayName("Сезон показан теми же словами, какими отбирается")
    void seasonWordsMatch() throws IOException {
        String server = read(SERVER.resolve("WheelService.java"));
        String client = read(CLIENT.resolve("wheels.ts"));

        for (String word : List.of("летняя", "зимняя", "зимняя (шипы)",
                "зимняя (липучка)", "всесезонная")) {
            assertThat(server)
                    .as("отбор колёс не знает сезона «%s»", word)
                    .contains("\"" + word + "\"");
            assertThat(client)
                    .as("экран не показывает сезон «%s», а отбор его отдаёт", word)
                    .contains("'" + word + "'");
        }
    }

    /** Путь от корня репозитория: тесты Maven запускает из него. */
    /**
     * Виды поставки на экране и на сервере — одни и те же слова.
     *
     * <p><b>Зачем.</b> «CONTAINER №18» показывалось владельцу в семи местах
     * сразу: витрина (таблица, список значений отбора, скачанный файл),
     * вкладка колёс (те же три) и история карточки. Починено это было
     * однажды — в карточке машины, у которой была своя копия словаря, —
     * и именно копия оставила остальные шесть без исправления. Теперь
     * словарь один ({@code SupplyKinds}), но у экрана заведения поставки
     * свой список: он рисует те же виды в выпадающем списке, и разойдись
     * они — владелец завёл бы «Прочее», а увидел бы «Поставка».
     */
    @Test
    @DisplayName("Виды поставки названы одинаково на сервере и на экране")
    void supplyKindWordsMatch() throws IOException {
        String client = read(Path.of("frontend/src/screens/SupplyList.tsx"));

        SupplyKinds.titles().forEach((code, title) -> {
            assertThat(client)
                    .as("экран поставок не знает вида «%s» (%s)", title, code)
                    .contains(code + ": '" + title + "'");
        });
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("файл, по которому сверяются слова, исчез").exists();
        return Files.readString(path);
    }
}
