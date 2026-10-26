package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Колонки «Кузов» и «Двигатель» у позиции без донора — коды кузовов и двигатели
 * её собственной машины из блока «Подходит» (задача 0296).
 *
 * <p>Склад — как у клиента, перенесённого с Дрома: донора нет, блок «Подходит»
 * лежит текстом ({@code part_drom_card.applicability}), применимость ставит кнопка
 * «Разложить «Подходит»». Образцы — строки файла клиента: «Крышка рейлинга … Prado
 * 120» (одна машина в блоке, двигатели названы) и «Карданный вал … Prado 120»
 * (пять машин, кузова 120-го ряда только в «Других кузовах»).
 *
 * <p>Всё через HTTP, как экран: колонки — {@code GET /api/parts/catalog}, список
 * отбора — {@code /values}, файл — {@code /export}. Справочник машин настоящий.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogOwnBodyEngineTest extends PostgresTestBase {

    private static final String TENANT = "t_001005";
    private static final int REGISTRY_ID = 1005;
    private static final String COMPANY = "ownbody";

    /** Строка 99 файла клиента: машина в блоке одна, двигатели — её. */
    private static final String RAIL_TITLE = "Крышка рейлинга центральная R Toyota Land Cruiser Prado 120";
    private static final String RAIL_FITS = "Toyota Land Cruiser Prado — GRJ120, GRJ120W, GRJ121, GRJ121W, "
            + "KDJ120, KDJ120W, KDJ121, KDJ121W, KZJ120, LJ120, RZJ120, RZJ120W, TRJ120, TRJ120W, "
            + "VZJ120, VZJ120W, VZJ121, VZJ121W\n"
            + "Для двигателей — 1GRFE, 1KDFTV, 1KZTE, 2TRFE, 3RZFE, 5LE, 5VZFE";
    private static final String RAIL_BODIES = "GRJ120, GRJ120W, GRJ121, GRJ121W, KDJ120, KDJ120W, KDJ121, "
            + "KDJ121W, KZJ120, LJ120, RZJ120, RZJ120W, TRJ120, TRJ120W, VZJ120, VZJ120W, VZJ121, VZJ121W";
    private static final String RAIL_ENGINES = "1GRFE, 1KDFTV, 1KZTE, 2TRFE, 3RZFE, 5LE, 5VZFE";

    /** Строка файла клиента: пять машин в блоке, у своей три ряда, 120-й — в «Других кузовах». */
    private static final String SHAFT_TITLE = "Карданный вал передний (Пробег 130т. км)Toyota Land Cruiser Prado 120";
    private static final String SHAFT_FITS = "Lexus GX400 — GRJ158\n"
            + "Toyota 4Runner — 5 поколение, рестайлинг (2013–2024): GRN285; 5 поколение (2009–2013): "
            + "TRN285, GRN285; 4 поколение, рестайлинг (2005–2009): GRN215; 4 поколение (2002–2005): GRN215\n"
            + "Toyota Hilux Surf — 4 поколение, рестайлинг (2005–2009): GRN215W; 4 поколение (2002–2005): "
            + "VZN215W; Другие кузова: GRN215, VZN215\n"
            + "Toyota Land Cruiser Prado — 4 поколение, 2-й рестайлинг (2017–2024): TRJ150W, TRJ150L, "
            + "GRJ150L; 4 поколение, рестайлинг (2013–2017): GRJ150W, GRJ150L, TRJ150W, GRJ151W, TRJ150L; "
            + "4 поколение (2009–2013): GRJ150W, GRJ150L, TRJ150W, TRJ150, TRJ150L, GRJ150, LJ150, GRJ151W; "
            + "Другие кузова: GRJ120, GRJ120W, GRJ121, GRJ121W, GRJ125, GRJ125W, GRJ151, RZJ120, RZJ120W, "
            + "RZJ125, RZJ125W, TRJ120, TRJ120W, TRJ125, TRJ125W, TRJ152, VZJ120, VZJ120W, VZJ121, "
            + "VZJ121W, VZJ125, VZJ125W\n"
            + "Для двигателей — 1GRFE, 5VZFE, 2TRFE, 3RZFE, 5LE";
    /** Ряд «12» из «Prado 120»: GRJ151 и TRJ152 — 150-го ряда, отброшены. */
    private static final String SHAFT_BODIES = "GRJ120, GRJ120W, GRJ121, GRJ121W, GRJ125, GRJ125W, RZJ120, "
            + "RZJ120W, RZJ125, RZJ125W, TRJ120, TRJ120W, TRJ125, TRJ125W, VZJ120, VZJ120W, VZJ121, "
            + "VZJ121W, VZJ125, VZJ125W";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CatalogService catalog;

    private final ObjectMapper json = new ObjectMapper();
    private MockHttpSession session;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома: кузов своей машины', ?)""", REGISTRY_ID, TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("DELETE FROM part_applicability");
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part_drom_card");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM donor");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            return null;
        });
        session = login();
    }

    @Test
    @DisplayName("Одна машина в блоке: «Кузов» — весь список её кодов, «Двигатель» — двигатели блока, "
            + "источник назван")
    void theOnlyMachineGetsBodiesAndEngines() throws Exception {
        part(RAIL_TITLE, RAIL_FITS);
        press();

        JsonNode row = only(page(""));
        assertThat(row.path("body").asText()).isEqualTo(RAIL_BODIES);
        assertThat(row.path("engine").asText()).isEqualTo(RAIL_ENGINES);
        assertThat(row.path("vehicleBodySource").asText()).isEqualTo("PARSED");
        assertThat(row.path("vehicleEngineSource").asText()).isEqualTo("PARSED");
        assertThat(row.path("vehicleBodySeries").asText()).isEqualTo("120");
        assertThat(row.path("vehicleEnginesForBlock").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Пять машин в блоке: кузова — только своей машины и её ряда, из «Других кузовов»; "
            + "двигатель пуст, и подсказка знает почему")
    void severalMachinesKeepOnlyOwnBodiesAndNoEngine() throws Exception {
        part(SHAFT_TITLE, SHAFT_FITS);
        press();

        JsonNode row = only(page(""));
        assertThat(row.path("model").asText()).isEqualTo("Land Cruiser Prado");
        assertThat(row.path("body").asText())
                .as("коды ряда 120 своей машины — не 4Runner, не Hilux Surf и не 150-й ряд")
                .isEqualTo(SHAFT_BODIES);
        assertThat(row.path("engine").isNull())
                .as("двигатели блока к пяти машинам: чьи они, не сказано").isTrue();
        assertThat(row.path("vehicleEngineSource").isNull()).isTrue();
        assertThat(row.path("vehicleEnginesForBlock").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Отбор по одному коду находит клетку со списком, «содержит» — тоже; список значений "
            + "— по одному коду; сортировка и файл — по показанному")
    void filterValuesSortAndFileByTheShownCodes() throws Exception {
        part(RAIL_TITLE, RAIL_FITS);
        part(SHAFT_TITLE, SHAFT_FITS);
        part("Капот Toyota Land Cruiser Prado 150", "Toyota Land Cruiser Prado — GRJ150W, TRJ150W");
        press();

        assertThat(titles(page("&filter=body:GRJ125W")))
                .as("GRJ125W — только у вала").containsExactly(SHAFT_TITLE);
        assertThat(titles(page("&filter=body:GRJ120")))
                .as("выбранный код ищется в списке клетки, а не равенством целой клетке")
                .containsExactlyInAnyOrder(RAIL_TITLE, SHAFT_TITLE);
        assertThat(titles(page("&filter=engine:1KZTE"))).containsExactly(RAIL_TITLE);
        assertThat(titles(page("&find=body:trj150"))).containsExactly("Капот Toyota Land Cruiser Prado 150");
        assertThat(titles(page("&filter=engine:—пусто—")))
                .containsExactlyInAnyOrder(SHAFT_TITLE, "Капот Toyota Land Cruiser Prado 150");

        List<String> bodies = values("body");
        assertThat(bodies).contains("GRJ120", "GRJ125W", "GRJ150W", "KDJ121W")
                .doesNotContain(RAIL_BODIES, SHAFT_BODIES);
        assertThat(values("engine")).contains("1GRFE", "5VZFE").doesNotContain(RAIL_ENGINES);

        assertThat(titles(page("&sort=body")))
                .as("по показанному тексту: «…GRJ121W, GRJ125…» вала раньше «…GRJ121W, KDJ120…» крышки, "
                        + "обе раньше «GRJ150W…»")
                .containsExactly(SHAFT_TITLE, RAIL_TITLE, "Капот Toyota Land Cruiser Prado 150");

        assertThat(export()).contains(RAIL_BODIES).contains(RAIL_ENGINES).contains(SHAFT_BODIES);
    }

    @Test
    @DisplayName("Кириллическая «К» в коде хранится как есть, а «KZJ95W» латиницей её находит")
    void cyrillicLetterIsFoundByLatin() throws Exception {
        part("Рычаг Toyota Land Cruiser Prado 95", "Toyota Land Cruiser Prado — КZJ95W, KZJ90W, GRJ150");
        press();

        JsonNode row = only(page(""));
        assertThat(row.path("body").asText()).as("ряд 9 из «Prado 95», хранимое не правится")
                .isEqualTo("КZJ95W, KZJ90W");
        assertThat(titles(page("&filter=body:KZJ95W"))).hasSize(1);
        assertThat(titles(page("&find=body:kzj95"))).hasSize(1);
    }

    @Test
    @DisplayName("Отбор выгрузки по кузову берёт тот же список: позиция без донора находится")
    void feedFilterSeesOwnBodies() throws Exception {
        part(SHAFT_TITLE, SHAFT_FITS);
        part(RAIL_TITLE, RAIL_FITS);
        press();

        CatalogService.ColumnFilter filter = inTenant(() ->
                catalog.columnFilter(Map.of("body", "GRJ125"), null).orElseThrow());
        List<String> found = inTenant(() -> jdbc.queryForList(
                "SELECT p.title FROM part p WHERE " + filter.sql(), String.class, filter.args().toArray()));
        assertThat(found).containsExactly(SHAFT_TITLE);
    }

    @Test
    @DisplayName("Донор побеждает: позиция с донором показывает его кузов и двигатель")
    void donorWins() throws Exception {
        long part = part(RAIL_TITLE, RAIL_FITS);
        press();
        inTenant(() -> {
            Long donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, body_code, engine_code)
                    VALUES ((SELECT id FROM catalog.brand WHERE name = 'Toyota'), 'GRJ120W', '1GR-FE')
                    RETURNING id""", Long.class);
            return jdbc.update("UPDATE part SET donor_id = ? WHERE id = ?", donor, part);
        });

        JsonNode row = only(page(""));
        assertThat(row.path("body").asText()).isEqualTo("GRJ120W");
        assertThat(row.path("engine").asText()).isEqualTo("1GR-FE");
        assertThat(row.path("vehicleBodySource").asText()).isEqualTo("DONOR");
    }

    @Test
    @DisplayName("Повторный проход дополняет строки, разобранные до задачи, и ничем не двигает позицию")
    void repeatedPassFillsRowsLaidBeforeTheTask() throws Exception {
        long rail = part(RAIL_TITLE, RAIL_FITS);
        long shaft = part(SHAFT_TITLE, SHAFT_FITS);
        press();
        // Как на ПРОМ: строки разложены до задачи — без «Других кузовов» и двигателей.
        inTenant(() -> jdbc.update("UPDATE part_applicability SET other_bodies = NULL, engines = NULL"));
        assertThat(only(page("&filter=title:" + RAIL_TITLE)).path("engine").isNull()).isTrue();
        String updatedBefore = updatedAt(rail) + updatedAt(shaft);

        JsonNode report = press();

        assertThat(report.path("refined").asInt()).as("дополнены строки обеих позиций").isPositive();
        JsonNode railRow = only(page("&filter=title:" + RAIL_TITLE));
        assertThat(railRow.path("engine").asText()).isEqualTo(RAIL_ENGINES);
        assertThat(only(page("&filter=title:" + SHAFT_TITLE)).path("body").asText()).isEqualTo(SHAFT_BODIES);
        assertThat(press().path("refined").asInt()).as("третий проход — ничего нового").isZero();
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_change", Long.class)))
                .as("проход и чтение поставили отметку «позиция изменилась»").isZero();
        assertThat(updatedAt(rail) + updatedAt(shaft)).isEqualTo(updatedBefore);
    }

    @Test
    @DisplayName("Подтверждённую человеком строку проход не дополняет")
    void verifiedRowIsNotFilled() throws Exception {
        part(RAIL_TITLE, RAIL_FITS);
        press();
        inTenant(() -> jdbc.update(
                "UPDATE part_applicability SET other_bodies = NULL, engines = NULL, is_verified = true"));

        press();

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_applicability WHERE engines IS NOT NULL", Long.class)))
                .isZero();
    }

    @Test
    @DisplayName("Своя машина — одна марка, модели в наименовании нет: кузовов и двигателя нет, "
            + "чужие модели марки их не дают")
    void brandOnlyMachineGetsNoBodies() throws Exception {
        part("Крышка рейлинга центральная R Toyota", RAIL_FITS);
        press();

        JsonNode row = only(page(""));
        assertThat(row.path("brand").asText()).isEqualTo("Toyota");
        assertThat(row.path("model").isNull()).isTrue();
        assertThat(row.path("body").isNull())
                .as("модели в наименовании нет — кузова Prado не её").isTrue();
        assertThat(row.path("engine").isNull()).isTrue();
    }

    @Test
    @DisplayName("Кузова своей машины хранятся в part.own_body_codes: разбор их пишет, витрина читает "
            + "колонку, а не считает (ответ владельца 26.10.2026)")
    void bodiesAreStoredAndReadFromTheColumn() throws Exception {
        long rail = part(RAIL_TITLE, RAIL_FITS);
        press();

        assertThat(stored(rail)).as("разбор записал список").isEqualTo(RAIL_BODIES);
        inTenant(() -> jdbc.update("UPDATE part SET own_body_codes = 'STORED1, STORED2' WHERE id = ?", rail));
        JsonNode row = only(page(""));
        assertThat(row.path("body").asText())
                .as("колонка читает хранимое: подмена в базе видна на экране").isEqualTo("STORED1, STORED2");
        assertThat(titles(page("&filter=body:STORED2"))).containsExactly(RAIL_TITLE);
    }

    @Test
    @DisplayName("Снятие строки применимости пересчитывает хранимые кузова")
    void removingTheRowRefreshesTheStoredCodes() throws Exception {
        long rail = part(RAIL_TITLE, RAIL_FITS);
        press();
        assertThat(stored(rail)).isEqualTo(RAIL_BODIES);

        List<Long> rows = inTenant(() -> jdbc.queryForList(
                "SELECT id FROM part_applicability WHERE part_id = ?", Long.class, rail));
        rows.forEach(id -> inTenant(() -> {
            catalog.removeApplicability(rail, id);
            return null;
        }));

        assertThat(stored(rail)).as("строк нет — кодов нет").isNull();
        assertThat(only(page("")).path("body").isNull()).isTrue();
    }

    @Test
    @DisplayName("«Разложить «Подходит»» пересчитывает кузова со всего склада — страховка от забытого места")
    void thePassRecountsStaleCodes() throws Exception {
        long rail = part(RAIL_TITLE, RAIL_FITS);
        press();
        // Блока «Подходит» у позиции больше нет, строки применимости остались (правили
        // руками): пачки разбора её не видят, поправить хранимое может только пересчёт склада.
        inTenant(() -> {
            jdbc.update("DELETE FROM part_drom_card WHERE part_id = ?", rail);
            return jdbc.update("UPDATE part SET own_body_codes = 'STALE' WHERE id = ?", rail);
        });

        press();

        assertThat(stored(rail)).isEqualTo(RAIL_BODIES);
    }

    @Test
    @DisplayName("Смена «Номера донора» пересчитывает хранимые кузова: у позиции с донором их нет")
    void donorChangeRefreshesTheStoredCodes() throws Exception {
        long rail = part(RAIL_TITLE, RAIL_FITS);
        press();
        assertThat(stored(rail)).isEqualTo(RAIL_BODIES);
        // Донора привязали после разбора мимо пересчёта (как в donorWins): хранимое
        // осталось от своей машины, и в чтении его прячет только CASE по донору.
        long[] donors = inTenant(() -> {
            long first = donor();
            long second = donor();
            jdbc.update("UPDATE part SET donor_id = ? WHERE id = ?", first, rail);
            return new long[] {first, second};
        });

        mvc.perform(put("/api/parts/" + rail + "/donor").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"donorId\":" + donors[1] + "}"))
                .andExpect(status().isOk());

        assertThat(stored(rail))
                .as("смена донора оставила кузова прежней своей машины в part.own_body_codes")
                .isNull();
    }

    private long donor() {
        return jdbc.queryForObject("""
                INSERT INTO donor (brand_id, body_code, engine_code)
                VALUES ((SELECT id FROM catalog.brand WHERE name = 'Toyota'), 'GRJ120W', '1GR-FE')
                RETURNING id""", Long.class);
    }

    private String stored(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT own_body_codes FROM part WHERE id = ?", String.class, part));
    }

    @Test
    @DisplayName("Чтение витрины идёт без JIT: страница, список значений, файл, отбор правки списком")
    void showcaseReadsRunWithoutJit() {
        // Время в тесте не мерится — на тестовом складе в десяток позиций оценка
        // плана до порога JIT не доходит. Проверяется, что транзакция, в которой
        // витрина читала, осталась с выключенным JIT: на складе в 35 тысяч
        // позиций сортировка по кузову без этого стоит 2,2 с вместо 0,33 (PR 523).
        Map<String, Supplier<Object>> reads = new java.util.LinkedHashMap<>();
        reads.put("страница", () -> catalog.list(null, true, true, List.of(), null,
                Map.of(), Map.of(), "body", false, 0, 50));
        reads.put("список значений", () -> catalog.values("body"));
        reads.put("отбор правки списком", () -> catalog.idsMatching(null, true, true, List.of(),
                null, Map.of("body", "GRJ120"), Map.of()));
        reads.put("файл", () -> {
            catalog.export(null, true, true, List.of(), null, Map.of(), Map.of(), "body", false,
                    catalog.warehouses(), cells -> { });
            return null;
        });
        reads.forEach((name, read) -> assertThat(inTenant(() -> {
            read.get();
            return jdbc.queryForObject("SELECT current_setting('jit')", String.class);
        })).as("%s: JIT в транзакции витрины", name).isEqualTo("off"));
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT current_setting('jit')", String.class)))
                .as("SET LOCAL не протекает в следующую транзакцию").isEqualTo("on");
    }

    private String updatedAt(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT updated_at::text FROM part WHERE id = ?", String.class, part));
    }

    private JsonNode press() throws Exception {
        return json.readTree(mvc.perform(post("/api/parts/catalog/applicability/from-drom")
                        .session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode page(String query) throws Exception {
        return json.readTree(body(get("/api/parts/catalog?missing=true" + query)));
    }

    private static JsonNode only(JsonNode page) {
        assertThat(page.path("rows")).hasSize(1);
        return page.path("rows").get(0);
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.path("rows").forEach(row -> titles.add(row.path("title").asText()));
        return titles;
    }

    private List<String> values(String column) throws Exception {
        List<String> values = new ArrayList<>();
        json.readTree(body(get("/api/parts/catalog/values?column=" + column)))
                .forEach(value -> values.add(value.asText()));
        return values;
    }

    private String export() throws Exception {
        return body(get("/api/parts/catalog/export?missing=true"));
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long part(String title, String fits) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (title, price, status) VALUES (?, 1000, 'IN_STOCK') RETURNING id""",
                    Long.class, title);
            jdbc.update("INSERT INTO part_drom_card (part_id, applicability) VALUES (?, ?)", id, fits);
            return id;
        });
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"owner","password":"пароль"}"""
                                .formatted(COMPANY)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
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
