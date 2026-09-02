package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Перенос шин и дисков из предыдущей системы.
 *
 * <p><b>Зачем.</b> Колёса лежат у Bazon на своей вкладке и в выгрузку
 * товаров не попадают вовсе: в её сорока восьми колонках нет ни ширины,
 * ни профиля, ни сезона — проверено на выгрузке живого клиента, где слово
 * «шина» не встречается в наименованиях ни разу. Пока этого прохода
 * не было, переехавший клиент терял весь колёсный склад: 65 строк файла,
 * а с учётом комплектов 221 карточка — ровно столько, сколько он видит
 * у себя в кабинете.
 *
 * <p>Строки взяты из настоящего файла клиента, включая пустые поля:
 * у диска шинных колонок нет, у шины дисковых.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class BazonWheelImportTest extends PostgresTestBase {

    /**
     * Своя схема на каждый тест: перенос идемпотентен по артикулу, и второй
     * тест в общей схеме нашёл бы колёса первого уже перенесёнными. Журнал
     * движений при этом неизменяем — почистить общую нечем.
     */
    private static final String TRAVEL = "t_000106";
    private static final String WEAR = "t_000107";
    private static final String REPEAT = "t_000108";
    private static final String PHOTOS = "t_000109";
    private static final String WRONG = "t_000110";
    private static final String NO_WAREHOUSE = "t_000111";

    private String tenant = TRAVEL;

    private static final String HEADER = String.join(";",
            "\"Артикул\"", "\"Тип (диск, шина, колесо)\"", "\"Количество в комплекте\"",
            "\"Производитель диска\"", "\"Модель диска\"", "\"Диаметр диска\"",
            "\"Тип диска\"", "\"Ширина диска\"", "\"Вылет диска\"", "\"PCD диска\"",
            "\"Диаметр осевого отверстия диска\"", "\"Производитель шины\"",
            "\"Модель шины\"", "\"Ширина профиля шины\"", "\"Высота профиля шины\"",
            "\"Посадочный диаметр шины\"", "\"Сезон шины (лето, зима, шипы)\"",
            "\"Дата производства\"", "\"Износ шин\"", "\"Новое/БУ\"", "\"Комментарий\"",
            "\"Цена\"", "\"Фото\"", "\"Статус\"", "\"Номер производителя\"");

    /** Диск поштучно — так в файле выглядит одиночная позиция. */
    private static final String DISC = String.join(";",
            "\"К30\"", "\"диск\"", "\"1\"", "\"\"", "\"\"", "\"15\"", "\"Литой\"", "\"5\"",
            "\"50\"", "\"5x114.3\"", "\"57\"", "\"\"", "\"\"", "\"\"", "\"\"", "\"\"",
            "\"\"", "\"\"", "\"\"", "\"БУ\"", "\"Контрактный, без пробега по РФ.\"",
            "\"2000\"", "\"http://cdn/rsz/200x150/pub/a.jpg, http://cdn/pub/b.jpg\"",
            "\"В наличии\"", "\"\"");

    /** Шины комплектом по четыре — так выглядит основная масса файла. */
    private static final String TYRES = String.join(";",
            "\"К122\"", "\"шина\"", "\"4\"", "\"\"", "\"\"", "\"\"", "\"\"", "\"\"", "\"\"",
            "\"\"", "\"\"", "\"Bridgestone\"", "\"Blizzak\"", "\"225\"", "\"55\"", "\"18\"",
            "\"Зимняя (липучка)\"", "\"2022\"", "\"10%\"", "\"БУ\"", "\"\"", "\"7500\"",
            "\"\"", "\"В наличии\"", "\"\"");

    @Autowired
    private BazonWheelImporter importer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TRAVEL, WEAR, REPEAT, PHOTOS, WRONG, NO_WAREHOUSE);
    }

    @BeforeEach
    void fixtures(org.junit.jupiter.api.TestInfo test) {
        tenant = switch (test.getTestMethod().orElseThrow().getName()) {
            case "wearBecomesMillimetres" -> WEAR;
            case "repeatIsSafe" -> REPEAT;
            case "photosAreQueued" -> PHOTOS;
            case "wrongFileIsRefused" -> WRONG;
            case "warehouseIsRequired" -> NO_WAREHOUSE;
            default -> TRAVEL;
        };
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Колёса переезжают: комплект даёт четыре карточки, свойства на месте")
    void wheelsTravel() {
        BazonWheelImporter.Report report = load(HEADER + "\n" + DISC + "\n" + TYRES);

        // Одна строка комплекта — четыре карточки: заводят комплектом,
        // а продают поштучно.
        assertThat(report.problems()).isEmpty();
        assertThat(report.created()).isEqualTo(5);
        assertThat(report.sets()).isEqualTo(2);

        var tyre = inTenant(() -> jdbc.queryForMap("""
                SELECT w.kind, w.brand, w.model, w.tyre_width, w.tyre_height, w.diameter,
                       w.season, w.wear_mm, w.made_year, w.set_no, p.price, p.title
                  FROM part_wheel w JOIN part p ON p.id = w.part_id
                 WHERE p.legacy_code = 'К122-1'"""));
        assertThat(tyre).containsEntry("kind", "TYRE")
                .containsEntry("brand", "Bridgestone")
                .containsEntry("tyre_width", 225)
                .containsEntry("tyre_height", 55)
                .containsEntry("season", "WINTER_FRICTION");
        assertThat((BigDecimal) tyre.get("diameter")).isEqualByComparingTo("18");
        assertThat((BigDecimal) tyre.get("price")).isEqualByComparingTo("7500");
        // Заголовок собирает WheelService, а не импортёр: свой означал бы
        // «225/55R15» рядом с «225/55 R15» на соседних строках.
        assertThat((String) tyre.get("title")).contains("225/55 R18").contains("Bridgestone");
        // Номер комплекта общий: по нему находят остальные три.
        assertThat(tyre.get("set_no")).isNotNull();

        var disc = inTenant(() -> jdbc.queryForMap("""
                SELECT w.kind, w.disc_type, w.disc_width, w.offset_mm, w.bolt_pattern,
                       w.hub_bore, w.diameter, p.note
                  FROM part_wheel w JOIN part p ON p.id = w.part_id
                 WHERE p.legacy_code = 'К30'"""));
        assertThat(disc).containsEntry("kind", "DISC")
                .containsEntry("disc_type", "Литой")
                .containsEntry("offset_mm", 50)
                .containsEntry("bolt_pattern", "5x114.3");
        assertThat((BigDecimal) disc.get("diameter")).isEqualByComparingTo("15");
        assertThat((BigDecimal) disc.get("hub_bore")).isEqualByComparingTo("57");
        assertThat((String) disc.get("note")).contains("Контрактный");

        // Остаток появляется движением, а не записью в карточку.
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(s.qty), 0) FROM part_stock s
                  JOIN part p ON p.id = s.part_id
                 WHERE p.product_line = 'WHEEL'""", BigDecimal.class)))
                .isEqualByComparingTo("5");
        assertThat(inTenant(() -> jdbc.queryForList("SELECT * FROM v_stock_discrepancy")))
                .isEmpty();
    }

    /**
     * Износ у клиента в процентах, у нас в миллиметрах.
     *
     * <p>Расхождение записано и намеренно: покупатель мерил глубиномером.
     * Обратный пересчёт опирается на те же восемь миллиметров новой шины,
     * что и выгрузка на площадку, — иначе одна и та же шина проехала бы
     * туда и обратно с разной глубиной.
     */
    @Test
    @DisplayName("Износ из процентов переводится в миллиметры остатка")
    void wearBecomesMillimetres() {
        load(HEADER + "\n" + TYRES);

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT w.wear_mm FROM part_wheel w JOIN part p ON p.id = w.part_id
                 WHERE p.legacy_code = 'К122-1'""", BigDecimal.class)))
                .as("десять процентов износа — это 7,2 мм остатка, а не 10")
                .isEqualByComparingTo("7.2");
    }

    @Test
    @DisplayName("Повтор переноса не заводит колёса второй раз")
    void repeatIsSafe() {
        load(HEADER + "\n" + DISC + "\n" + TYRES);
        BazonWheelImporter.Report again = load(HEADER + "\n" + DISC + "\n" + TYRES);

        assertThat(again.created()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE product_line = 'WHEEL'", Integer.class)))
                .as("повтор задвоил колёсный склад").isEqualTo(5);
    }

    @Test
    @DisplayName("Снимки встают в ту же очередь, что у запчастей, и без превью")
    void photosAreQueued() {
        BazonWheelImporter.Report report = load(HEADER + "\n" + DISC);

        assertThat(report.photos()).isEqualTo(2);
        var urls = inTenant(() -> jdbc.queryForList(
                "SELECT url FROM part_photo_import ORDER BY sort_order", String.class));
        // Уменьшенная копия убирается тем же разборщиком, что у запчастей:
        // перенести превью значит оставить клиента с картинками, по которым
        // товар не разглядеть.
        assertThat(urls).containsExactly("http://cdn/pub/a.jpg", "http://cdn/pub/b.jpg");
    }

    @Test
    @DisplayName("Выгрузка товаров вместо выгрузки колёс отвергается словами")
    void wrongFileIsRefused() {
        assertThatThrownBy(() -> load("\"Номер товара\";\"Цена\"\n\"A-1\";\"100\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не выгрузка шин и дисков");
    }

    @Test
    @DisplayName("Склад не подставляется: без него переносить некуда")
    void warehouseIsRequired() {
        assertThatThrownBy(() -> inTenant(() -> importer.load(
                new ByteArrayInputStream(HEADER.getBytes(Charset.forName("windows-1251"))),
                null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Не указан склад");
    }

    private BazonWheelImporter.Report load(String csv) {
        return inTenant(() -> importer.load(
                new ByteArrayInputStream(csv.getBytes(Charset.forName("windows-1251"))),
                warehouseId, null));
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(tenant);
            return transactions.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
