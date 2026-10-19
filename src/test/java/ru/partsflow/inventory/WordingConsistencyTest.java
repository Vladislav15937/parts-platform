package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /**
     * Состояние названо одними словами на экране и на сервере.
     *
     * <p><b>Зачем.</b> Словарей было пять: {@code CASE} витрины, такой же
     * у вкладки колёс, словарь журнала изменений и два на фронтенде
     * ({@code catalog.ts} и {@code wheels.ts}). Совпадали они только потому,
     * что их писал один человек в один день, — а выбранное в меню слово
     * уходит на сервер и сравнивается с собранным выражением: разойдись
     * одна буква, и отбор перестаёт находить что-либо, причём причина видна
     * только в SQL. Сведены они в {@link PartCondition} (задача 0039).
     *
     * <p>Перебором по перечислению, а не по списку слов: новое состояние
     * попадает в проверку в тот же момент, когда его дописали в {@code enum}.
     */
    @Test
    @DisplayName("Состояние названо одинаково на сервере и на экране")
    void conditionWordsMatch() throws IOException {
        String client = read(CLIENT.resolve("catalog.ts"));

        for (PartCondition condition : PartCondition.values()) {
            assertThat(client)
                    .as("экран не знает состояния «%s» (%s)", condition.title(), condition.name())
                    .contains(condition.name() + ": '" + condition.title() + "'");
        }
    }

    /**
     * Вкладка колёс берёт словарь состояний, а не пишет свой.
     *
     * <p>Копия там и была, и жила ровно до первого нового значения: слово
     * поправили бы на витрине, а вкладка колёс показывала бы прежнее.
     * Проверяется отсутствие **объявления**, а не импорта: импорт мог бы
     * стоять рядом с вернувшейся копией.
     */
    @Test
    @DisplayName("У вкладки колёс нет своего словаря состояний")
    void wheelsDoNotKeepTheirOwnConditionDictionary() throws IOException {
        String wheels = read(CLIENT.resolve("wheels.ts"));

        assertThat(wheels)
                .as("во `wheels.ts` вернулась своя копия словаря состояний")
                .doesNotContain("USED: 'б/у'");
    }

    /**
     * Отбор выгрузки знает все состояния.
     *
     * <p>Состояние, которого нет в этом списке, владелец не смог бы
     * отобрать в прайс-лист вовсе — а узнал бы об этом по тому, что товар
     * уехал не в тот прайс или не уехал никуда. Слова здесь во множественном
     * числе (так подписан отбор у ориентира), поэтому сверяются **коды**:
     * именно они уходят на сервер и сравниваются с {@code part.condition}.
     */
    @Test
    @DisplayName("В отборе выгрузки перечислены все состояния")
    void feedFilterKnowsEveryCondition() throws IOException {
        String feeds = read(Path.of("frontend/src/publishing/feeds.ts"));

        for (PartCondition condition : PartCondition.values()) {
            assertThat(feeds)
                    .as("отбор выгрузки не знает состояния %s — по нему нельзя"
                            + " собрать прайс-лист", condition.name())
                    .contains("code: '" + condition.name() + "'");
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

    /**
     * Операции с ценой названы на экране и в отказе одними словами.
     *
     * <p><b>Зачем.</b> Шесть пунктов списка сняты с системы, из которой
     * приходят клиенты, и взяты дословно: «Увеличить на %», «Округлить до».
     * Выбирает операцию человек на экране, а отказывает в ней сервер —
     * «„Уменьшить на сумму“: цена сейчас 24 300…», — и если списки разойдутся,
     * владелец получит отказ про операцию, которой он не выбирал.
     *
     * <p>Перебором по перечислению, а не по списку слов: новая операция
     * попадает в проверку в тот же момент, когда её дописали в {@code enum}.
     */
    @Test
    @DisplayName("Операции с ценой названы одинаково на сервере и на экране")
    void priceOperationWordsMatch() throws IOException {
        String client = read(CLIENT.resolve("catalog.ts"));

        for (PriceOperation op : PriceOperation.values()) {
            assertThat(client)
                    .as("экран не знает операции «%s» (%s)", op.title(), op.name())
                    .contains("key: '" + op.name() + "', title: '" + op.title() + "'");
        }
    }

    /**
     * Оценка состояния названа одними словами на экране и на сервере.
     *
     * <p><b>Зачем.</b> Словарей было три, и все три разные: {@code CASE}
     * витрины переводил {@code EXCELLENT/GOOD/FAIR/POOR}, форма правки
     * предлагала их же (и сервер отвечал «Запрос не разобран» на каждое),
     * а карточка печатала {@code NO_DEFECTS} как есть. Ни одно из четырёх
     * слов {@code CASE}'а в базе встретиться не могло — их не допускает
     * {@code part_quality_grade_ck}.
     *
     * <p>Сведены они в {@link QualityGrade}; экран сверяется с ним отсюда.
     * Связь здесь ровно та же, что у состояния выше: выбранное в меню слово
     * уходит на сервер и сравнивается с тем, что собрано выражением. Разойдись
     * они на букву — выбор из списка перестал бы находить что-либо, а причина
     * была бы видна только в SQL.
     *
     * <p>Перебором по перечислению, а не по списку слов: новая градация
     * попадает в проверку в тот же момент, когда её дописали в {@code enum}.
     */
    @Test
    @DisplayName("Оценка состояния названа одинаково на сервере и на экране")
    void qualityGradeWordsMatch() throws IOException {
        String client = read(CLIENT.resolve("catalog.ts"));

        for (QualityGrade grade : QualityGrade.values()) {
            assertThat(client)
                    .as("экран не знает оценки «%s» (%s)", grade.title(), grade.name())
                    .contains("key: '" + grade.name() + "', title: '" + grade.title() + "'");
        }
    }

    /**
     * Ни один экран не держит своих слов состояния — только {@code catalog.ts}.
     *
     * <p><b>Зачем.</b> Состояние показывается теперь в десятке мест (задачи 0314,
     * 0318): склад, продавец, состав сделки, страница по ссылке, заказы, обход,
     * перевозка, отчёты, приёмка. Слово ставит {@code conditionTitle}
     * ({@code ConditionWord} в разметке), и третий источник слов появился бы
     * незаметно — экран, переписавший «контрактная» у себя, разошёлся бы
     * с остальными на первой правке, а {@link #conditionWordsMatch} сверяет
     * только {@code catalog.ts}. Перебор — по всем исходникам экрана, а слова —
     * из перечисления: новое состояние попадает в проверку само.
     *
     * <p>Разрешённое названо с причиной. Отбор выгрузки ({@code feeds.ts})
     * держит свои слова во множественном числе («новые · б/у · контрактные» —
     * так подписан отбор у ориентира), и «б/у» там совпадает с нашим случайно:
     * это подпись отбора, а не состояние позиции.
     */
    @Test
    @DisplayName("Слова состояния на экране записаны только в catalog.ts")
    void screensKeepNoOwnConditionWords() throws IOException {
        Map<String, String> allowed = Map.of(
                "frontend/src/inventory/catalog.ts", "сам словарь",
                "frontend/src/publishing/feeds.ts",
                "подпись отбора выгрузки во множественном числе; «б/у» совпадает случайно");

        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("frontend/src"))) {
            for (Path file : files.filter(p -> p.toString().matches(".*\\.tsx?$"))
                    .filter(p -> !p.getFileName().toString().contains(".test."))
                    .toList()) {
                if (allowed.containsKey(file.toString().replace('\\', '/'))) {
                    continue;
                }
                found.addAll(conditionLiterals(file));
            }
        }
        assertThat(found)
                .as("слово состояния записано мимо `CONDITION` в catalog.ts — берите"
                        + " `conditionTitle` или `ConditionWord`")
                .isEmpty();
    }

    /**
     * Сервер не собирает слово состояния сам — только {@link PartCondition#title()}.
     *
     * <p><b>Зачем.</b> Ответы, которые несут состояние (задача 0318), отдают
     * код, а тексты, которые собирает сервер (отказы продажи, возврата,
     * приёмки, предзаказа), берут слово из {@link PartCondition#named}. Своё
     * {@code CASE WHEN 'USED' THEN 'б/у'} в запросе или литерал в отказе —
     * второй словарь, и он разошёлся бы с экраном на первой правке слова.
     * Выражение для SQL у перечисления есть своё ({@link PartCondition#sqlLabel}).
     *
     * <p>Разрешённое названо с причиной: разбор файла Bazon читает их написания
     * («б/у», «контрактная»…) — это вход, а не показ, и слов там больше, чем
     * у нас («бу», «контрактный»).
     */
    @Test
    @DisplayName("Слова состояния на сервере записаны только в PartCondition")
    void serverBuildsNoOwnConditionWords() throws IOException {
        Map<String, String> allowed = Map.of(
                "src/main/java/ru/partsflow/inventory/PartCondition.java", "сам словарь",
                "src/main/java/ru/partsflow/migration/bazon/BazonValueParser.java",
                "разбор чужого файла: написания Bazon на входе, не показ",
                "src/main/java/ru/partsflow/publishing/drom/DromPriceWriter.java",
                "слова чужого файла на выходе (формат Bazon, задача 0333): «контрактный», "
                        + "«б/у», «новый» читает разбор площадки, а не человек на нашем экране — "
                        + "правка слова на экране не должна молча менять файл");

        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (allowed.containsKey(file.toString().replace('\\', '/'))) {
                    continue;
                }
                found.addAll(conditionLiterals(file));
            }
        }
        assertThat(found)
                .as("слово состояния записано мимо `PartCondition` — берите `title()`"
                        + " или `PartCondition.named`")
                .isEmpty();
    }

    /**
     * Строковые литералы, равные слову состояния целиком: {@code 'б/у'},
     * {@code "контрактная"}, {@code `новая`}. Целиком — потому что «новая»
     * встречается и в «Новая сделка», а это не состояние.
     */
    private static List<String> conditionLiterals(Path file) throws IOException {
        List<String> found = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int at = 0; at < lines.size(); at++) {
            for (PartCondition condition : PartCondition.values()) {
                Pattern literal = Pattern.compile(
                        "['\"`]" + Pattern.quote(condition.title()) + "['\"`]");
                if (literal.matcher(lines.get(at)).find()) {
                    found.add(file + ":" + (at + 1) + " — «" + condition.title() + "»");
                }
            }
        }
        return found;
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("файл, по которому сверяются слова, исчез").exists();
        return Files.readString(path);
    }
}
