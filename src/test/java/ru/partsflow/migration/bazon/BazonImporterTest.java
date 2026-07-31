package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Перенос склада из выгрузки предыдущей системы.
 *
 * <p>Разбор выгрузки был закрыт тестами давно, а сам импорт — нет: его никто
 * не вызывал. Между «строка разобралась» и «склад перенесён» лежит всё, ради
 * чего клиент к нам и переходит.
 *
 * <p>Главная проверка — повторный запуск. Она же поймала на импорте из Excel
 * самую разрушительную ошибку в системе: ответ упал на сериализации, владелец
 * нажал ещё раз и получил вторую копию склада целиком.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class BazonImporterTest extends PostgresTestBase {

    /**
     * Своя схема на каждый тест.
     *
     * <p>Общую не почистить: журнал движений неизменяем по замыслу, его
     * запрещает удалять триггер. А импорт проверяется абсолютными числами —
     * «товаров ровно два», — и остатки соседнего теста их ломают.
     */
    private static final String IMPORT = "t_000077";
    private static final String REPEAT = "t_000078";
    private static final String HEADER = "t_000079";
    private static final String NAMES = "t_000080";
    private static final String BAD_ROW = "t_000081";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path files;

    @BeforeAll
    static void migrate() {
        provisionTenants(IMPORT, REPEAT, HEADER, NAMES, BAD_ROW);
    }

    @Test
    @DisplayName("Выгрузка переносится: доноры, наименования, склады, остаток")
    void importsWarehouse() throws Exception {
        ImportReport report = importFixture(IMPORT);

        assertThat(count(IMPORT, "donor")).isEqualTo(1);
        assertThat(count(IMPORT, "supply")).isEqualTo(1);
        assertThat(count(IMPORT, "part")).isEqualTo(2);

        // Остаток появляется движением, а не записью в part.qty_on_hand:
        // иначе кэш разъедется с журналом на первой же продаже.
        //
        // Свободное плюс резерв: отложенная деталь физически лежит на полке,
        // и остаток склада её включает. Взять только свободное значило бы
        // списать обещанное другому клиенту.
        assertThat(qtyOf(IMPORT, "A-100")).isEqualByComparingTo("3");
        assertThat(qtyOf(IMPORT, "A-200")).isEqualByComparingTo("1");

        // Резерв из выгрузки — это обещание другому клиенту, и терять его
        // нельзя: продавец пообещает деталь второму.
        assertThat(reservedOf(IMPORT, "A-100")).isEqualByComparingTo("1");

        assertThat(report.problems()).isEmpty();
    }

    @Test
    @DisplayName("Повторный запуск не удваивает склад")
    void secondRunIsIdempotent() throws Exception {
        importFixture(REPEAT);
        ImportReport second = importFixture(REPEAT);

        assertThat(count(REPEAT, "part"))
                .as("склад загрузился второй раз — отменяется это только "
                        + "восстановлением из бэкапа")
                .isEqualTo(2);
        assertThat(count(REPEAT, "donor")).isEqualTo(1);
        assertThat(count(REPEAT, "supply")).isEqualTo(1);
        assertThat(qtyOf(REPEAT, "A-100"))
                .as("остаток удвоился: движения записались повторно")
                .isEqualByComparingTo("3");
        assertThat(second.loaded("товаров пропущено (уже есть)"))
                .as("повтор не отчитался о пропуске — значит он что-то создал заново")
                .isEqualTo(2);
        assertThat(second.loaded("машин пропущено (уже есть)")).isEqualTo(1);

        // Без этой строки проверка сходится и по неверной причине: машину
        // не пускает уникальный индекс, импорт ловит отказ и пишет проблему.
        // Дубля нет, но повтор перестаёт быть штатным — а он штатный.
        assertThat(second.problems())
                .as("повтор прошёл с ошибками: он обязан быть обычным действием, "
                        + "а не аварией, которую спас индекс")
                .isEmpty();
    }

    @Test
    @DisplayName("Склады берутся из заголовка, а не выдумываются")
    void warehousesComeFromHeader() throws Exception {
        importFixture(HEADER);

        // Имена складов у каждого клиента свои, и придумать их за него нельзя:
        // деталь ляжет на склад, которого у клиента нет.
        assertThat(jdbc.queryForList(
                "SELECT name FROM " + HEADER + ".warehouse ORDER BY name", String.class))
                .contains("Ткацкая", "Ангар");
    }

    @Test
    @DisplayName("Наименования заводятся в справочник, а не в заголовок карточки")
    void partNamesGoToDictionary() throws Exception {
        importFixture(NAMES);

        // Ради этого справочник и существует: «фара левая» из чужой системы
        // должна встать рядом с нашей приёмкой, а не остаться строкой.
        assertThat(jdbc.queryForList(
                "SELECT name FROM " + NAMES + ".part_name", String.class))
                .containsExactlyInAnyOrder("Фара левая", "Бампер передний");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + NAMES + ".part WHERE part_name_id IS NULL",
                Integer.class))
                .as("карточка без наименования не попадёт в разбор нераспознанных")
                .isZero();
    }

    @Test
    @DisplayName("Строка, которую не принимает база, не уносит остальной склад")
    void badRowDoesNotKillTheImport() throws Exception {
        // Отрицательная цена не проходит проверку part_price_ck. Мусор такого
        // рода в чужой выгрузке встречается, и одна такая строка не должна
        // оставить клиента без склада: без точки сохранения транзакция уходит
        // в aborted, и все последующие строки падают тоже.
        //
        // Цена буквами сюда не годится — разборщик приводит её к пустой,
        // и до базы такая строка доезжает целой.
        Path catalog = write("catalog.csv", CATALOG_HEADER + """
                "A-100";"Фара левая";"Д-1";"";"";"";"";"";"";"";"";"";"";"";"";"9500";"";"2";"1";"0";"0";"0";"0";"да"
                "A-300";"Дверь";"";"";"";"";"";"";"";"";"";"";"";"";"";"-500";"";"1";"0";"0";"0";"0";"0";"да"
                """);

        ImportReport report = new BazonImporter(dataSource, BAD_ROW)
                .importAll(donorsFixture(), catalog);

        assertThat(count(BAD_ROW, "part"))
                .as("плохая строка унесла с собой хорошие")
                .isEqualTo(1);
        assertThat(report.problems()).isNotEmpty();
    }

    private ImportReport importFixture(String schema) throws Exception {
        return new BazonImporter(dataSource, schema)
                .importAll(donorsFixture(), catalogFixture());
    }

    /**
     * Заголовок выгрузки товаров: колонки складов идут тройками
     * «свободно / резерв / ожидается» — так их отдаёт чужая система.
     */
    private static final String CATALOG_HEADER = """
            "Номер товара";"Запчасть";"Номер донора";"Марка";"Модель";"Год выпуска";\
            "Кузов";"Двигатель";"Комментарий";"Заметка";"Левый / Правый";\
            "Передний / Задний";"Оценка состояния";"Маркировка";"Производитель";"Цена";\
            "Номер производителя";"Ткацкая (свободно)";"Ткацкая (резерв)";\
            "Ткацкая (ожидается)";"Ангар (свободно)";"Ангар (резерв)";\
            "Ангар (ожидается)";"Выгружать"
            """;

    private Path catalogFixture() throws Exception {
        return write("catalog.csv", CATALOG_HEADER + """
                "A-100";"Фара левая";"Д-1";"Toyota";"Camry";"2006";"";"";"";"";"Левый";"Передний";\
                "Хорошее";"";"";"9500";"81150-33670";"2";"1";"0";"0";"0";"0";"да"
                "A-200";"Бампер передний";"";"";"";"";"";"";"";"";"";"";"";"";"";"12000";"";\
                "0";"0";"0";"1";"0";"0";"да"
                """);
    }

    private Path donorsFixture() throws Exception {
        return write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
                "Д-1";"Toyota";"Camry";"2006";"JTDBR32E060012345";"Контейнер 12";"Разбор";\
                "серебристый";"180000";"Левый";"Передний";"Автомат";"U151E";""
                """);
    }

    /**
     * Файл пишется в windows-1251: выгрузка приходит именно в ней, и тест
     * на UTF-8 проверял бы не тот путь, которым файл попадает в систему.
     */
    private Path write(String name, String content) throws Exception {
        Path path = files.resolve(name);
        Files.write(path, content.getBytes(BazonCsvReader.CHARSET));
        return path;
    }

    private int count(String schema, String table) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + "." + table, Integer.class);
        return found == null ? 0 : found;
    }

    private BigDecimal qtyOf(String schema, String legacyCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(s.qty), 0) FROM %s.part_stock s
                  JOIN %s.part p ON p.id = s.part_id
                 WHERE p.legacy_code = ?""".formatted(schema, schema),
                BigDecimal.class, legacyCode);
    }

    private BigDecimal reservedOf(String schema, String legacyCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(s.qty_reserved), 0) FROM %s.part_stock s
                  JOIN %s.part p ON p.id = s.part_id
                 WHERE p.legacy_code = ?""".formatted(schema, schema),
                BigDecimal.class, legacyCode);
    }
}
