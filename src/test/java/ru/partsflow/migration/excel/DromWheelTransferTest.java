package ru.partsflow.migration.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import ru.partsflow.migration.excel.DromTransferFile.Column;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Шины, колёса и диски из файла переноса с Дрома — колёсными позициями (задача 0291):
 * файл → импорт через HTTP, как экран «Импорт» → вкладка «Шины и диски», поиск
 * по размеру, колёсный прайс; повтор файла и перекладка уже перенесённых запчастей.
 *
 * <p>Строки — пять колёсных строк файла клиента Strikexz (лист «Объявления»,
 * строки 349, 466, 576, 946, 993) и три отрицательных: секретки (970, раздел
 * «Шины и диски → Аксессуары для колес»), запаска (773) и тормозной диск
 * (по наименованию похожи на колёсные, а это запчасти).
 *
 * <p>Комплект — одна позиция по цене из файла, продаётся только целиком,
 * в колёсный прайс уходит «за 4 шт» (решения владельца 14.10.2026 — файл задачи).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromWheelTransferTest extends PostgresTestBase {

    private static final String TENANT = "t_000993";
    private static final int REGISTRY_ID = 993;
    private static final String COMPANY = "dromwheels";

    private static final List<Map<String, String>> ROWS = List.of(
            Map.of("Номер объявления Дрома", "127641183",
                    "Наименование", "Автошина Dunlop Grandtrek PT5 235/55 R19",
                    "Вид детали", "Dunlop Grandtrek PT3, 265/65 R19",
                    "Раздел", DromWheelCardTest.SECTION_TYRES,
                    "Цена", "45000", "Прочие поля", DromWheelCardTest.TYRE_349,
                    "Фото", "https://s.auto.drom.ru/i24/a_full.jpg\nhttps://s.auto.drom.ru/i24/b_full.jpg"),
            Map.of("Номер объявления Дрома", "121359555",
                    "Наименование", "Колеса Bridgestone Blizzak DM-Z3 265/65 R17",
                    "Вид детали", "Колёса", "Раздел", "Шины и диски",
                    "Цена", "50000", "Прочие поля", DromWheelCardTest.ASSEMBLY_466,
                    // «Подходит» у колеса бывает — и остаётся только текстом: применимости
                    // у колеса нет намеренно (inventory/CLAUDE.md). Машина — из справочника,
                    // чтобы разбор 0289 её разложил, если его позовут.
                    "Применимость", "Toyota Land Cruiser Prado | 4 поколение (2009–2013) | GRJ150"),
            Map.of("Номер объявления Дрома", "135757856",
                    "Наименование", "Автошина Maxxis Bravo HT-770 265/60R18 114H",
                    "Раздел", "Шины и диски → Шины → Шины Maxxis Bravo → Maxxis Bravo HT-770",
                    "Цена", "38000", "Прочие поля", DromWheelCardTest.TYRE_576),
            Map.of("Номер объявления Дрома", "127641914",
                    "Наименование", "Диски Toyota Land Cruiser Prado 150",
                    "Вид детали", "Диски Toyota", "Раздел", "Шины и диски → Диски",
                    "Цена", "38000", "Прочие поля", DromWheelCardTest.DISC_946),
            Map.of("Номер объявления Дрома", "118479106",
                    "Наименование", "Автошина Bridgestone Dueler H/L 265/65 R17",
                    "Раздел", "Шины и диски → Шины → Шины Bridgestone Dueler → Bridgestone Dueler H/L",
                    "Цена", "10000", "Прочие поля", DromWheelCardTest.TYRE_993),
            Map.of("Номер объявления Дрома", "124849678",
                    "Наименование", "Гайка на колесо (Секретки) Subaru",
                    "Вид детали", "Секретки", "Раздел", "Шины и диски → Аксессуары для колес",
                    "Цена", "2000"),
            Map.of("Номер объявления Дрома", "130113810",
                    "Наименование", "Колесо запасное Lexus RX350",
                    "Раздел", "Автозапчасти → Расходники и комплектующие",
                    "Цена", "10000", "Номер", "4261148740"),
            Map.of("Номер объявления Дрома", "126000001",
                    "Наименование", "Диск тормозной передний Toyota Land Cruiser Prado 120",
                    "Раздел", "Автозапчасти → Тормозная система",
                    "Цена", "3000"));

    private static final List<String> WHEEL_ADS =
            List.of("127641183", "121359555", "135757856", "127641914", "118479106");
    private static final List<String> PART_ADS = List.of("124849678", "130113810", "126000001");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома: колёса', ?)""", REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            jdbc.update("DELETE FROM import_run");
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part_photo_import");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM part_name");
            jdbc.update("DELETE FROM marketplace_account");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Барнаул') RETURNING id", Long.class);
            return jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Исакова') RETURNING id",
                    Long.class, branch);
        });
    }

    @Test
    @DisplayName("Пять колёсных строк — на «Шинах и дисках» комплектом одной позицией, три запчасти — на складе")
    void wheelRowsLandAsWheelPositions() throws Exception {
        String requestId = UUID.randomUUID().toString();
        JsonNode report = load(file(), requestId);

        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped")).isEqualTo(ROWS.size());
        assertThat(report.path("wheels").path("created").asInt())
                .as("отчёт не назвал колёсные строки отдельной строкой").isEqualTo(5);
        assertThat(report.path("wheels").path("converted").asInt()).isZero();
        // Строка 349 у клиента: в наименовании 235/55 R19, в карточке Дрома 265/65 R19.
        List<String> notes = new ArrayList<>();
        report.path("wheels").path("notes").forEach(n -> notes.add(n.path("adNumber").asText()
                + " " + n.path("row").asInt() + " " + n.path("text").asText()));
        assertThat(notes).containsExactly("127641183 2 Размер: в наименовании 235/55 R19, "
                        + "в «Прочих полях» 265/65 R19 — взят из «Прочих полей»",
                "127641914 5 Вылет: 37.5 мм записан как 38 — у нас вылет целый, "
                        + "точное значение в «Характеристиках»");
        assertThat(report.path("quantityDefaulted").asInt())
                .as("число комплектов из «Прочих полей» засчитано догадкой «по одной штуке»")
                .isEqualTo(PART_ADS.size());

        // Вкладка «Шины и диски» — все пять, склад запчастей — три.
        JsonNode wheels = getJson("/api/wheels?size=50");
        assertThat(wheels.path("total").asInt()).isEqualTo(5);
        JsonNode catalog = getJson("/api/parts/catalog?size=50");
        assertThat(catalog.path("total").asInt())
                .as("колёсные строки остались на складе запчастей").isEqualTo(PART_ADS.size());
        for (String ad : PART_ADS) {
            assertThat(column("SELECT product_line FROM part WHERE drom_ad_number = ?", ad))
                    .as("№ %s — запчасть, а заведена колесом", ad).isEqualTo("PART");
        }

        Map<String, String> kinds = Map.of("127641183", "TYRE", "121359555", "ASSEMBLY",
                "135757856", "TYRE", "127641914", "DISC", "118479106", "TYRE");
        for (Map<String, String> row : ROWS.subList(0, 5)) {
            String ad = row.get("Номер объявления Дрома");
            assertThat(column("SELECT kind FROM part_wheel w JOIN part p ON p.id = w.part_id "
                    + "WHERE p.drom_ad_number = ?", ad)).as("вид у № %s", ad).isEqualTo(kinds.get(ad));
            assertThat(column("SELECT title FROM part WHERE drom_ad_number = ?", ad))
                    .as("наименование клиента не дословно (решение «дословно»)")
                    .isEqualTo(row.get("Наименование"));
            assertThat(decimal("SELECT price FROM part WHERE drom_ad_number = ?", ad))
                    .as("цена комплекта поделена или потеряна (решение «не делить»)")
                    .isEqualByComparingTo(row.get("Цена"));
            assertThat(decimal("SELECT qty_on_hand FROM part WHERE drom_ad_number = ?", ad))
                    .as("комплект не одной позицией: остаток не равен числу комплектов")
                    .isEqualByComparingTo("1");
            assertThat(column("SELECT set_pieces::text FROM part_wheel w JOIN part p ON p.id = w.part_id "
                    + "WHERE p.drom_ad_number = ?", ad)).as("штук в комплекте у № %s", ad).isEqualTo("4");
            assertThat(column("SELECT set_no::text FROM part_wheel w JOIN part p ON p.id = w.part_id "
                    + "WHERE p.drom_ad_number = ?", ad)).as("номер связки карточек у одной позиции").isNull();
            assertThat(column("SELECT characteristics FROM part_drom_card d JOIN part p ON p.id = d.part_id "
                    + "WHERE p.drom_ad_number = ?", ad))
                    .as("«Прочие поля» не остались у позиции дословно").isEqualTo(row.get("Прочие поля"));
        }
        assertThat(count("SELECT count(*) FROM part_wheel")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM part WHERE product_line = 'WHEEL' AND part_name_id IS NOT NULL"))
                .as("наименование колеса легло в справочник наименований").isZero();
        assertThat(count("SELECT count(*) FROM part_applicability"))
                .as("колесу разложили применимость").isZero();
        assertThat(count("SELECT count(*) FROM part_change c JOIN part p ON p.id = c.part_id "
                + "WHERE p.product_line = 'WHEEL'"))
                .as("колёсная позиция не отмечена изменившейся, как при заведении комплектом").isEqualTo(5);

        // Свойства строки 349 доехали до базы — каждое поле таблицы критерия 3.
        Map<String, Object> tyre = inTenant(() -> jdbc.queryForMap("""
                SELECT w.* FROM part_wheel w JOIN part p ON p.id = w.part_id
                 WHERE p.drom_ad_number = '127641183'"""));
        assertThat(tyre.get("tyre_width")).isEqualTo(265);
        assertThat(tyre.get("tyre_height")).isEqualTo(65);
        assertThat((BigDecimal) tyre.get("diameter")).isEqualByComparingTo("19");
        assertThat(tyre.get("construction")).isEqualTo("R");
        assertThat(tyre.get("season")).isEqualTo("SUMMER");
        assertThat((BigDecimal) tyre.get("wear_mm")).isEqualByComparingTo("7.6");
        assertThat(tyre.get("made_year")).isEqualTo(2023);
        assertThat(tyre.get("brand")).isEqualTo("Dunlop");
        assertThat(tyre.get("model")).isEqualTo("Grandtrek PT3");
        assertThat(tyre.get("tyre_type")).isEqualTo("Обычная");
        Map<String, Object> disc = inTenant(() -> jdbc.queryForMap("""
                SELECT w.* FROM part_wheel w JOIN part p ON p.id = w.part_id
                 WHERE p.drom_ad_number = '127641914'"""));
        assertThat((BigDecimal) disc.get("disc_width")).isEqualByComparingTo("7.5");
        assertThat(disc.get("offset_mm")).isEqualTo(38);
        assertThat(disc.get("bolt_pattern")).isEqualTo("6x139.7");
        assertThat(disc.get("disc_brand")).isEqualTo("Toyota");
        assertThat(column("SELECT url FROM part_photo_import i JOIN part p ON p.id = i.part_id "
                + "WHERE p.drom_ad_number = ? AND sort_order = 0", "127641183"))
                .as("снимки колеса не встали в очередь").isEqualTo("https://s.auto.drom.ru/i24/a_full.jpg");

        // Поиск по размеру, как звонит покупатель: две шины 265/65 R17 — сборка и Dueler.
        JsonNode found = json.readTree(mvc.perform(get("/api/wheels").param("q", "265 65 17")
                        .session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(found.path("total").asInt()).as("поиск «265 65 17» не находит перенесённое").isEqualTo(2);

        // Повтор по тому же ключу отдаёт тот же итог — и строку про колёса тоже.
        JsonNode replay = load(file(), requestId);
        assertThat(replay.path("wheels")).as("повтор по ключу потерял итог по колёсам")
                .isEqualTo(report.path("wheels"));
    }

    @Test
    @DisplayName("Колёсный прайс отдаёт комплект «45 000 за 4 шт», прайс запчастей колёс не несёт")
    void wheelFeedCarriesTheSetAsOneOffer() throws Exception {
        load(file(), UUID.randomUUID().toString());

        Map<String, Element> wheelFeed = offers(price("WHEEL"));
        assertThat(wheelFeed).as("в колёсном прайсе не все пять").hasSize(5);
        Element set = wheelFeed.get(column("SELECT public_code FROM part WHERE drom_ad_number = ?",
                "127641183"));
        assertThat(set).as("шины № 127641183 нет в колёсном прайсе").isNotNull();
        assertThat(text(set, "inSet"))
                .as("цена комплекта ушла ценой одной шины — вчетверо выше рынка").isEqualTo("4");
        assertThat(new BigDecimal(text(set, "price"))).isEqualByComparingTo("45000");
        assertThat(text(set, "quantity")).isEqualTo("1");
        assertThat(text(set, "name")).isEqualTo("Автошина Dunlop Grandtrek PT5 235/55 R19");

        Map<String, Element> partsFeed = offers(price("PART"));
        for (String ad : WHEEL_ADS) {
            assertThat(partsFeed).as("колесо № %s уехало в прайс запчастей", ad)
                    .doesNotContainKey(column("SELECT public_code FROM part WHERE drom_ad_number = ?", ad));
        }
    }

    @Test
    @DisplayName("Повтор того же файла: ни одной новой позиции, ни второго комплекта")
    void repeatLoadAddsNothing() throws Exception {
        load(file(), UUID.randomUUID().toString());
        List<Long> before = ids();

        JsonNode again = load(file(), UUID.randomUUID().toString());
        assertThat(again.path("imported").asInt()).as("повтор завёл позиции").isZero();
        assertThat(again.path("repeat").path("unchanged").asInt()).isEqualTo(ROWS.size());
        assertThat(ids()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM part_wheel")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM stock_movement")).isEqualTo(ROWS.size());
    }

    @Test
    @DisplayName("Уже перенесённые запчастями — перекладываются в колёса на месте; второй раз — ничего нового")
    void alreadyTransferredPartsBecomeWheelsInPlace() throws Exception {
        load(file(), UUID.randomUUID().toString());
        // Состояние ПРОМ до задачи: те же позиции заведены запчастями.
        inTenant(() -> {
            jdbc.update("DELETE FROM part_wheel");
            jdbc.update("UPDATE part SET product_line = 'PART'");
            jdbc.update("DELETE FROM part_change");
            // До задачи импорт заводил колёсную строку запчастью через справочник
            // наименований: у позиции есть написание, у написания — счётчик.
            for (String ad : WHEEL_ADS) {
                jdbc.update("""
                        WITH n AS (INSERT INTO part_name (name, usage_count)
                                   SELECT title, 1 FROM part WHERE drom_ad_number = ? RETURNING id)
                        UPDATE part SET part_name_id = (SELECT id FROM n) WHERE drom_ad_number = ?""",
                        ad, ad);
            }
            return null;
        });
        assertThat(count("SELECT count(*) FROM part WHERE part_name_id IS NOT NULL")).isEqualTo(ROWS.size());
        assertThat(getJson("/api/parts/catalog?size=50").path("total").asInt()).isEqualTo(ROWS.size());
        assertThat(getJson("/api/wheels").path("total").asInt()).isZero();
        List<Map<String, Object>> before = identity();
        int movements = count("SELECT count(*) FROM stock_movement");
        int photos = count("SELECT count(*) FROM part_photo_import");

        JsonNode report = load(file(), UUID.randomUUID().toString());
        assertThat(report.path("imported").asInt()).as("перекладка завела позиции заново").isZero();
        assertThat(report.path("wheels").path("converted").asInt()).isEqualTo(5);
        assertThat(report.path("wheels").path("created").asInt()).isZero();

        JsonNode wheels = getJson("/api/wheels?size=50");
        assertThat(wheels.path("total").asInt()).isEqualTo(5);
        List<String> numbers = new ArrayList<>();
        wheels.path("rows").forEach(r -> numbers.add(r.path("wheel").path("number").asText()
                + " " + r.path("wheel").path("publicCode").asText()));
        List<String> expected = new ArrayList<>();
        for (Map<String, Object> row : before) {
            if (WHEEL_ADS.contains((String) row.get("drom_ad_number"))) {
                expected.add(row.get("number") + " " + row.get("public_code"));
            }
        }
        assertThat(numbers).as("переложенная позиция сменила номер или публичный код")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(getJson("/api/parts/catalog?size=50").path("total").asInt())
                .as("переложенные остались на складе запчастей").isEqualTo(PART_ADS.size());
        assertThat(identity()).as("у позиций сменились id, номер, код или дата заведения")
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM stock_movement")).isEqualTo(movements);
        assertThat(count("SELECT count(*) FROM part_photo_import")).isEqualTo(photos);
        assertThat(count("SELECT count(*) FROM v_stock_discrepancy")).isZero();
        assertThat(count("SELECT count(*) FROM part_change")).isEqualTo(5);
        // Колесо не числится за написанием справочника: иначе оно висело бы в «Не разобрано»,
        // а сопоставление написания с эталоном переписало бы заголовок клиента.
        assertThat(count("SELECT count(*) FROM part WHERE product_line = 'WHEEL' AND part_name_id IS NOT NULL"))
                .as("переложенное колесо осталось за написанием справочника наименований").isZero();
        assertThat(count("SELECT coalesce(sum(n.usage_count), 0) FROM part_name n "
                + "WHERE n.name IN (SELECT title FROM part WHERE product_line = 'WHEEL')"))
                .as("счётчик написаний считает переложенные колёса").isZero();
        assertThat(count("SELECT coalesce(sum(usage_count), 0) FROM part_name"))
                .as("пересчёт потерял запчасти").isEqualTo(PART_ADS.size());

        JsonNode third = load(file(), UUID.randomUUID().toString());
        assertThat(third.path("imported").asInt()).isZero();
        assertThat(third.path("wheels").isNull() || third.path("wheels").isMissingNode())
                .as("третья загрузка снова что-то переложила: %s", third.path("wheels")).isTrue();
        assertThat(third.path("repeat").path("unchanged").asInt()).isEqualTo(ROWS.size());
    }

    @Test
    @DisplayName("Два комплекта в объявлении — остаток позиции 2, приход одним движением на 2, прайс отдаёт 2")
    void setsCountIsTheStock() throws Exception {
        // У клиента везде «1 шт», и на одних его строках «остаток — число комплектов»
        // неотличимо от «остаток — всегда один»: нужна строка, где комплектов два.
        Map<String, String> two = new HashMap<>(ROWS.get(4));
        two.put("Прочие поля", DromWheelCardTest.TYRE_993.replace(
                "Всего комплектов [quantity]: 1 шт", "Всего комплектов [quantity]: 2 шт"));
        assertThat(two.get("Прочие поля")).contains("[quantity]: 2 шт");
        JsonNode report = load(file(List.of(two)), UUID.randomUUID().toString());

        assertThat(report.path("imported").asInt()).isEqualTo(1);
        assertThat(report.path("quantityDefaulted").asInt())
                .as("число комплектов из «Прочих полей» засчитано догадкой «по одной штуке»").isZero();
        assertThat(decimal("SELECT qty_on_hand FROM part WHERE drom_ad_number = ?", "118479106"))
                .as("остаток не равен числу комплектов из «Прочих полей»").isEqualByComparingTo("2");
        assertThat(decimal("SELECT sum(m.qty_delta) FROM stock_movement m JOIN part p ON p.id = m.part_id "
                + "WHERE p.drom_ad_number = ?", "118479106")).isEqualByComparingTo("2");
        assertThat(count("SELECT count(*) FROM v_stock_discrepancy")).isZero();

        Element set = offers(price("WHEEL")).get(column(
                "SELECT public_code FROM part WHERE drom_ad_number = ?", "118479106"));
        assertThat(text(set, "inSet")).isEqualTo("4");
        assertThat(text(set, "quantity")).as("в прайс ушло не число комплектов").isEqualTo("2");
    }

    @Test
    @DisplayName("Повтор, принёсший «Подходит» колёсной позиции, кладёт текст и не раскладывает применимость")
    void refillDoesNotLayApplicabilityOnAWheel() throws Exception {
        // Первая загрузка — без «Применимости»: колесо заведено, раскладывать нечего.
        List<Map<String, String>> withoutFits = new ArrayList<>();
        for (Map<String, String> row : ROWS) {
            Map<String, String> copy = new HashMap<>(row);
            copy.remove("Применимость");
            withoutFits.add(copy);
        }
        load(file(withoutFits), UUID.randomUUID().toString());
        assertThat(count("SELECT count(*) FROM part_drom_card WHERE applicability IS NOT NULL")).isZero();

        // Повтор с ней: текст дозаполняется, справочная применимость колесу не заводится.
        JsonNode again = load(file(), UUID.randomUUID().toString());
        assertThat(again.path("repeat").path("refilled").asInt()).as("%s", again.path("repeat")).isEqualTo(1);
        assertThat(column("SELECT applicability FROM part_drom_card d JOIN part p ON p.id = d.part_id "
                + "WHERE p.drom_ad_number = ?", "121359555"))
                .isEqualTo(ROWS.get(1).get("Применимость"));
        assertThat(count("SELECT count(*) FROM part_applicability"))
                .as("повтор разложил колесу применимость").isZero();
    }

    // --- файл ---

    private static byte[] file() throws Exception {
        return file(ROWS);
    }

    private static byte[] file(List<Map<String, String>> rows) throws Exception {
        List<String> titles = DromTransferFile.COLUMNS.stream().map(Column::title).toList();
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Объявления");
            Row head = sheet.createRow(0);
            for (int c = 0; c < titles.size(); c++) {
                head.createCell(c).setCellValue(titles.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Map<String, String> values = new HashMap<>(Map.of(
                        "Наличие", ExcelWarehouseImporter.IN_STOCK, "Состояние", "Б/у"));
                values.putAll(rows.get(r));
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < titles.size(); c++) {
                    String value = values.getOrDefault(titles.get(c), "");
                    if (!value.isEmpty()) {
                        row.createCell(c).setCellValue(value);
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    // --- прайс ---

    private String price(String productLine) throws Exception {
        JsonNode feed = json.readTree(mvc.perform(post("/api/marketplace-accounts")
                        .with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","title":"Дром %s","settings":"{}",
                                 "productLine":"%s"}""".formatted(UUID.randomUUID(), productLine)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String path = json.readTree(mvc.perform(post("/api/marketplace-accounts/"
                                + feed.path("id").asLong() + "/feed-url")
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("path").asText();
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Map<String, Element> offers(String xml) throws Exception {
        NodeList list = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagName("offer");
        Map<String, Element> byCode = new HashMap<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element offer = (Element) list.item(i);
            byCode.put(text(offer, "ordercode"), offer);
        }
        return byCode;
    }

    private static String text(Element offer, String name) {
        NodeList found = offer.getElementsByTagName(name);
        return found.getLength() == 0 ? null : found.item(0).getTextContent();
    }

    // --- HTTP, как экран ---

    private JsonNode load(byte[] file, String requestId) throws Exception {
        JsonNode preview = json.readTree(mvc.perform(multipart("/api/import/excel/preview")
                        .file(xlsx(file)).with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        preview.path("detected").fields()
                .forEachRemaining(e -> request.param(e.getKey(), e.getValue().asText()));
        return json.readTree(mvc.perform(request.with(csrf()).session(login())
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", requestId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile xlsx(byte[] file) {
        return new MockMultipartFile("file", "perenos-s-droma.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"owner","password":"пароль"}"""
                                .formatted(COMPANY)))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    // --- база ---

    private List<Long> ids() {
        return inTenant(() -> jdbc.queryForList("SELECT id FROM part ORDER BY id", Long.class));
    }

    private List<Map<String, Object>> identity() {
        return inTenant(() -> jdbc.queryForList("""
                SELECT id, number, public_code, created_at, drom_ad_number, qty_on_hand
                  FROM part ORDER BY id"""));
    }

    private String column(String sql, String adNumber) {
        List<String> found = inTenant(() -> jdbc.queryForList(sql, String.class, adNumber));
        assertThat(found).as("нет строки для № %s: %s", adNumber, sql).hasSize(1);
        return found.get(0);
    }

    private BigDecimal decimal(String sql, String adNumber) {
        return inTenant(() -> jdbc.queryForObject(sql, BigDecimal.class, adNumber));
    }

    private int count(String sql) {
        Integer value = inTenant(() -> jdbc.queryForObject(sql, Integer.class));
        return value == null ? 0 : value;
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
