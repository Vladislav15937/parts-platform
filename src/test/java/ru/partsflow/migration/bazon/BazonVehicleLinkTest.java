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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Перенос из Bazon записывает ссылку на машину общего справочника (задача 0377, п. 4).
 *
 * <p>Не сопоставилась — позиция ввозится без машины, название остаётся из файла,
 * строка попадает в отчёт (вариант «а» владельца). Повтор файла ссылок не дозаполняет:
 * уже перенесённые без машины позиции остаются как есть (критерий 4).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class BazonVehicleLinkTest extends PostgresTestBase {

    private static final String LINK = "t_001003";
    private static final String REPEAT = "t_001004";

    private static final String CATALOG_HEADER = """
            "Номер товара";"Запчасть";"Номер донора";"Марка";"Модель";"Год выпуска";\
            "Кузов";"Двигатель";"Комментарий";"Заметка";"Левый / Правый";\
            "Передний / Задний";"Оценка состояния";"Маркировка";"Производитель";"Цена";\
            "Номер производителя";"Ткацкая (свободно)";"Ткацкая (резерв)";\
            "Ткацкая (ожидается)";"Выгружать";"Установка"
            """;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path files;

    @BeforeAll
    static void migrate() {
        provisionTenants(LINK, REPEAT);
    }

    @Test
    @DisplayName("Сопоставилась — ссылки и слова справочника; не сопоставилась — без машины,"
            + " название из файла, строка в отчёте; без машины в файле — не считается")
    void matchedAndUnmatchedRows() throws Exception {
        Map<String, Object> codes = camry();
        ImportReport report = new BazonImporter(dataSource, LINK)
                .importAll(donors(), catalog(codes));

        // Регистр в файле не важен: «toyota» и «CAMRY».
        Map<String, Object> ok = jdbc.queryForMap("SELECT title, bazon_mark_id, bazon_model_id,"
                + " bazon_body_id, bazon_motor_id FROM " + LINK + ".part WHERE legacy_code = 'L-1'");
        assertThat(ok.get("bazon_mark_id")).isEqualTo(codes.get("markId"));
        assertThat(ok.get("bazon_model_id")).isEqualTo(codes.get("modelId"));
        assertThat(ok.get("bazon_body_id")).isEqualTo(codes.get("bodyId"));
        assertThat(ok.get("bazon_motor_id")).isEqualTo(codes.get("motorId"));
        assertThat((String) ok.get("title"))
                .as("название строится словами справочника, а не регистром файла")
                .contains("Toyota Camry " + codes.get("body") + " " + codes.get("motor"));

        // Кузова нет в справочнике: позиция без машины, название — слова файла.
        Map<String, Object> bad = jdbc.queryForMap("SELECT title, bazon_mark_id, bazon_model_id"
                + " FROM " + LINK + ".part WHERE legacy_code = 'L-2'");
        assertThat(bad.get("bazon_mark_id")).as("частичная ссылка стёрла бы слова при пересборке").isNull();
        assertThat(bad.get("bazon_model_id")).isNull();
        assertThat((String) bad.get("title")).contains("Toyota Camry NO-SUCH-BODY");

        // Машины в файле нет вовсе: не «не сопоставилась».
        assertThat(jdbc.queryForObject("SELECT bazon_mark_id FROM " + LINK
                + ".part WHERE legacy_code = 'L-3'", Long.class)).isNull();

        assertThat(report.loaded("машин сопоставлено со справочником")).isEqualTo(1);
        assertThat(report.loaded("машин не сопоставлено со справочником")).isEqualTo(1);
        assertThat(report.problems())
                .extracting(ImportReport.Problem::message)
                .anyMatch(m -> m.contains("машина не сопоставлена") && m.contains("NO-SUCH-BODY"));
        assertThat(report.problems())
                .as("несопоставленная строка названа один раз")
                .filteredOn(p -> p.message().contains("машина не сопоставлена"))
                .hasSize(1);
    }

    @Test
    @DisplayName("Повтор файла не меняет ссылок и не дозаполняет их позициям, перенесённым без машины")
    void repeatLeavesReferencesAlone() throws Exception {
        Map<String, Object> codes = camry();
        Path catalog = catalog(codes);
        new BazonImporter(dataSource, REPEAT).importAll(donors(), catalog);
        // Так лежат позиции, перенесённые до задачи: машины нет.
        jdbc.update("UPDATE " + REPEAT + ".part SET bazon_mark_id = NULL, bazon_model_id = NULL,"
                + " bazon_body_id = NULL, bazon_motor_id = NULL WHERE legacy_code = 'L-1'");
        String title = jdbc.queryForObject(
                "SELECT title FROM " + REPEAT + ".part WHERE legacy_code = 'L-1'", String.class);

        ImportReport repeat = new BazonImporter(dataSource, REPEAT).importAll(donors(), catalog);

        assertThat(jdbc.queryForObject("SELECT bazon_mark_id FROM " + REPEAT
                + ".part WHERE legacy_code = 'L-1'", Long.class))
                .as("повтор дозаполнил машину позиции, перенесённой без неё")
                .isNull();
        assertThat(jdbc.queryForObject("SELECT title FROM " + REPEAT
                + ".part WHERE legacy_code = 'L-1'", String.class)).isEqualTo(title);
        assertThat(repeat.loaded("машин сопоставлено со справочником")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + REPEAT
                + ".part WHERE bazon_mark_id IS NOT NULL", Integer.class)).isZero();
    }

    /** Запись справочника «Toyota Camry» с кодом кузова и двигателем — из самого справочника. */
    private Map<String, Object> camry() {
        Long mark = jdbc.queryForObject("SELECT id FROM catalog.bazon_mark WHERE name = 'Toyota'", Long.class);
        Long model = jdbc.queryForObject("SELECT id FROM catalog.bazon_model WHERE bazon_mark_id = ?"
                + " AND name = 'Camry'", Long.class, mark);
        Map<String, Object> body = jdbc.queryForMap("""
                SELECT b.id, b.name FROM catalog.bazon_body b
                  JOIN catalog.bazon_generation g ON g.id = b.bazon_generation_id
                 WHERE g.bazon_model_id = ? AND b.name IS NOT NULL ORDER BY b.id LIMIT 1""", model);
        Map<String, Object> motor = jdbc.queryForMap("SELECT id, name FROM catalog.bazon_motor"
                + " WHERE bazon_mark_id = ? ORDER BY id LIMIT 1", mark);
        return Map.of("markId", mark, "modelId", model, "bodyId", body.get("id"),
                "body", body.get("name"), "motorId", motor.get("id"), "motor", motor.get("name"));
    }

    private Path catalog(Map<String, Object> c) throws Exception {
        return write("catalog.csv", CATALOG_HEADER + """
                "L-1";"Фара";"";"toyota";"CAMRY";"2006";"%s";"%s";"";"";"";"";"";"";"";"9500";\
                "81150-33670";"1";"0";"0";"да";""
                "L-2";"Бампер";"";"Toyota";"Camry";"2006";"NO-SUCH-BODY";"";"";"";"";"";"";"";"";"7000";\
                "";"1";"0";"0";"да";""
                "L-3";"Стартер";"";"";"";"";"";"";"";"";"";"";"";"";"";"3500";\
                "";"1";"0";"0";"да";""
                """.formatted(c.get("body"), c.get("motor")));
    }

    private Path donors() throws Exception {
        return write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
                "Д-1";"Toyota";"Camry";"2006";"JTDBR32E060012345";"Контейнер 12";"Разбор";\
                "серебристый";"180000";"Левый";"Передний";"Автомат";"U151E";""
                """);
    }

    private Path write(String name, String content) throws Exception {
        Path path = files.resolve(name);
        Files.write(path, content.getBytes(BazonCsvReader.CHARSET));
        return path;
    }
}
