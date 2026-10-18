package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.intake.IntakeService;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.publishing.drom.DromPriceGenerator;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Номер позиции у перенесённой с Bazon — её «Номер товара» (задача 0334).
 *
 * <p>Ключ позиции в прайсе Дрома — этот же номер: сменив файл, клиент
 * оставляет Дрому прежние ключи, и опубликованные объявления узнаются,
 * а не заводятся заново рядом с прежними, которые Дром снимет.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class BazonItemNumberTest extends PostgresTestBase {

    private static final String NUMBERS = "t_004341";
    private static final String FEED = "t_004342";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private IntakeService intake;

    @Autowired
    private DromPriceGenerator generator;

    @TempDir
    Path files;

    @BeforeAll
    static void migrate() {
        provisionTenants(NUMBERS, FEED);
    }

    @Test
    @DisplayName("«Номер товара» становится номером позиции; занятый и нецелый названы,"
            + " нумерация идёт за наибольшим, повтор номеров не двигает")
    void itemNumberBecomesPartNumber() throws Exception {
        // Первым переносом «500» занимает № 500 — дальше его попросит «0500»,
        // другая позиция с тем же числом.
        ImportReport first = new BazonImporter(dataSource, NUMBERS)
                .importAll(donors(), catalog(row("500", "Стартер", "2000", "1")));
        assertThat(first.problems()).isEmpty();
        assertThat(numberOf(NUMBERS, "500")).isEqualTo(500L);

        Path file = catalog(
                row("27079", "Фара левая", "9500", "2"),
                row("1 349", "Бампер передний", "12000", "1"),
                row("A-7", "Капот", "8000", "1"),
                row("0500", "Генератор", "4000", "1"),
                row("114", "Зеркало правое", "1500", "1"));
        ImportReport report = new BazonImporter(dataSource, NUMBERS).importAll(donors(), file);

        assertThat(numberOf(NUMBERS, "27079")).isEqualTo(27079L);
        assertThat(numberOf(NUMBERS, "1 349"))
                .as("разделитель разрядов снимается: в карточке Bazon пишут «1 349»")
                .isEqualTo(1349L);
        assertThat(numberOf(NUMBERS, "114")).isEqualTo(114L);

        long nonInteger = numberOf(NUMBERS, "A-7");
        long taken = numberOf(NUMBERS, "0500");
        assertThat(nonInteger)
                .as("позиция без годного номера берёт номер за наибольшим из файла")
                .isGreaterThan(27079L);
        assertThat(taken).isGreaterThan(27079L).isNotEqualTo(nonInteger);

        String occupant = codeOf(NUMBERS, "500");
        assertThat(report.problems()).extracting(ImportReport.Problem::message)
                .containsExactlyInAnyOrder(
                        "номер товара «A-7» не целое число — позиция получила № " + nonInteger,
                        "номер товара 500 занят позицией " + occupant
                                + " — перенесённая получила № " + taken);
        assertThat(report.loaded("товаров")).as("занятый и нецелый номер не роняют заведение")
                .isEqualTo(5);

        // Приёмка после переноса — номер больше всех перенесённых.
        long received = receiveOne(NUMBERS);
        assertThat(received).isGreaterThan(Math.max(nonInteger, taken));

        // Повтор: номера не двигаются, потери номеров не повторяются.
        ImportReport repeat = new BazonImporter(dataSource, NUMBERS).importAll(donors(), file);
        assertThat(numberOf(NUMBERS, "27079")).isEqualTo(27079L);
        assertThat(numberOf(NUMBERS, "A-7")).isEqualTo(nonInteger);
        assertThat(numberOf(NUMBERS, "0500")).isEqualTo(taken);
        assertThat(numberOf(NUMBERS, "500")).isEqualTo(500L);
        assertThat(repeat.problems()).isEmpty();
        assertThat(repeat.loaded("товаров")).isZero();
    }

    @Test
    @DisplayName("Ключ позиции в прайсе Дрома — номер позиции, и у позиции с номером"
            + " объявления тоже")
    void feedKeyIsThePartNumber() throws Exception {
        new BazonImporter(dataSource, FEED).importAll(donors(), catalog(
                row("27079", "Фара левая", "9500", "2"),
                row("1349", "Бампер передний", "12000", "1")));
        // Позиция, связанная с объявлением Дрома (задача 0274): ключ всё равно
        // номер позиции, а не номер объявления.
        jdbc.update("UPDATE " + FEED + ".part SET drom_ad_number = '126500777' WHERE legacy_code = '1349'");

        String xml = inTenant(FEED, () -> {
            var out = new ByteArrayOutputStream();
            generator.writeTo(out);
            return out.toString(StandardCharsets.UTF_8);
        });

        assertThat(keys(xml)).containsExactlyInAnyOrder("27079", "1349");
        assertThat(xml).doesNotContain(codeOf(FEED, "27079"));
    }

    // ---------- фикстуры ----------

    private static final String HEADER = """
            "Номер товара";"Запчасть";"Номер донора";"Цена";"Ткацкая (свободно)";\
            "Ткацкая (резерв)";"Ткацкая (ожидается)";"Выгружать"
            """;

    private static String row(String number, String name, String price, String qty) {
        return "\"%s\";\"%s\";\"\";\"%s\";\"%s\";\"0\";\"0\";\"да\"\n"
                .formatted(number, name, price, qty);
    }

    private Path catalog(String... rows) throws Exception {
        return write("catalog-" + UUID.randomUUID() + ".csv", HEADER + String.join("", rows));
    }

    private Path donors() throws Exception {
        return write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус"
                """);
    }

    private Path write(String name, String content) throws Exception {
        Path path = files.resolve(name);
        Files.write(path, content.getBytes(BazonCsvReader.CHARSET));
        return path;
    }

    private long numberOf(String schema, String legacyCode) {
        return jdbc.queryForObject("SELECT number FROM " + schema + ".part WHERE legacy_code = ?",
                Long.class, legacyCode);
    }

    private String codeOf(String schema, String legacyCode) {
        return jdbc.queryForObject("SELECT public_code FROM " + schema + ".part WHERE legacy_code = ?",
                String.class, legacyCode);
    }

    private long receiveOne(String schema) {
        return inTenant(schema, () -> {
            Long warehouse = jdbc.queryForObject(
                    "SELECT id FROM warehouse ORDER BY id LIMIT 1", Long.class);
            Long cell = jdbc.queryForObject(
                    "INSERT INTO storage_cell (warehouse_id, code) VALUES (?, 'А-01-1') RETURNING id",
                    Long.class, warehouse);
            var receipt = intake.receive(warehouse, null, null, List.of(
                    new IntakeService.ItemRequest("фара правая", BigDecimal.ONE,
                            new BigDecimal("7000"), new BigDecimal("2800"), cell,
                            LateralSide.RIGHT, LongitudinalSide.FRONT, null,
                            PartCondition.USED, null, null, null, null, null)),
                    null, UUID.randomUUID().toString());
            return jdbc.queryForObject("SELECT number FROM part WHERE id = ?", Long.class,
                    receipt.parts().get(0).getId());
        });
    }

    private static List<String> keys(String xml) {
        // Тег ключа — «ordercode» до формата Bazon и «id» после него (задача 0333):
        // проверяется значение ключа, а не его имя.
        Matcher m = Pattern.compile("<(ordercode|id)>([^<]*)</\\1>").matcher(xml);
        List<String> found = new java.util.ArrayList<>();
        while (m.find()) {
            found.add(m.group(2));
        }
        return found;
    }

    private <T> T inTenant(String schema, Supplier<T> action) {
        try {
            TenantContext.set(schema);
            return transactions.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
