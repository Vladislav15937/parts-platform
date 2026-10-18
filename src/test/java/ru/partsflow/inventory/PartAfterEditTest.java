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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.audit.AuditJournal;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.publishing.MarketplaceAccountService;
import ru.partsflow.publishing.drom.DromPriceGenerator;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * После 0302 — задача 0303: три правки, которые система принимала со словом
 * «сохранено» и дальше не доносила или отказывала без причины.
 *
 * <ol>
 *   <li>«№ позиции» на миллионы вперёд — без предела «похоже на опечатку»;
 *       граница только у типа, словами; гонка с приёмкой не отдаёт номер двоим.</li>
 *   <li>Машина, исправленная в карточке, — в прайсе Дрома, отборе выгрузки,
 *       её счётчике, отборе продавца и подборе «что подходит».</li>
 *   <li>Собранный заголовок идёт за номером производителя, вписанный руками — нет.</li>
 * </ol>
 *
 * <p>Через HTTP там, где ответ видит человек (код и слова отказа, витрина,
 * продавец), и вызовом генератора там, где читатель — площадка (файл прайса).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartAfterEditTest extends PostgresTestBase {

    private static final String TENANT = "t_000995";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private DromPriceGenerator generator;

    @Autowired
    private MarketplaceAccountService accounts;

    @Autowired
    private AuditJournal journal;

    /** Своё слово этого метода: поиск продавца и витрины находят только его позиции. */
    private String marking;
    private Long partId;
    private Long neighbour;
    private Long toyota;
    private Long lexus;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 995");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (995, ?, 'Разборка', 'afteredit')""", TENANT);
        toyota = jdbc.queryForObject("SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);
        lexus = jdbc.queryForObject("SELECT id FROM catalog.brand WHERE slug = 'lexus'", Long.class);
        Long camry = jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND slug = 'camry'", Long.class, toyota);

        // Одни буквы: цифры в запросе продавец разбирает как номер или размер колеса.
        marking = "zq" + Long.toString(System.nanoTime(), 26).replaceAll("[0-9]", "x")
                .chars().map(c -> c < 'a' ? c : 'a' + (c - 'a') % 26)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append);
        inTenant(() -> {
            // Склад остаётся от прошлого метода: схема своя у класса, позиции — свои у метода.
            jdbc.update("DELETE FROM part_change");
            // Позиции прошлых методов уходят из всех отборов: без машины и с выгрузки.
            jdbc.update("""
                    UPDATE part SET is_published = false, donor_id = NULL, manual_brand = NULL,
                           manual_model = NULL, manual_year = NULL, manual_body = NULL""");
            member("vladelec0303", "Владелец", "OWNER");
            Long warehouse = jdbc.queryForList("SELECT id FROM warehouse ORDER BY id", Long.class)
                    .stream().findFirst().orElseGet(() -> {
                        Long branch = jdbc.queryForObject(
                                "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
                        return jdbc.queryForObject("""
                                INSERT INTO warehouse (branch_id, name) VALUES (?, 'Основной')
                                RETURNING id""", Long.class, branch);
                    });
            Long donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, model_id, year, body_code, engine_code)
                    VALUES (?, ?, 2006, 'ACV40', '2AZ-FE') RETURNING id""",
                    Long.class, toyota, camry);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, donor_id, is_published, marking)
                    VALUES (1, 'Фара левая (б/у) 81170-33670', 4500, ?, true, ?)
                    RETURNING id""", Long.class, donor, marking);
            jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                    VALUES (?, '81170-33670', '8117033670', true)""", partId);
            neighbour = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, donor_id, is_published, marking)
                    VALUES (1, 'Фара правая (б/у)', 4700, ?, true, ?)
                    RETURNING id""", Long.class, donor, marking);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            ledger.record(StockMovement.intake(neighbour, BigDecimal.ONE, warehouse, null));
            return null;
        });
    }

    // ---------------------------------------------------------- пункт 1: № позиции

    @Test
    @DisplayName("№ позиции на миллионы выше выданного принимается, и приёмка продолжает нумерацию после него")
    void numberFarAheadIsAccepted() throws Exception {
        MockHttpSession owner = login();
        long ahead = inTenant(() -> jdbc.queryForObject(
                "SELECT max(number) + 5000000 FROM part", Long.class));

        put(owner, "\"number\":" + ahead).andExpect(status().isOk());

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, partId))).isEqualTo(ahead);
        Long next = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, 'Следующая', 100)
                RETURNING number""", Long.class));
        assertThat(next).as("приёмка выдала номер не выше вписанного").isGreaterThan(ahead);
    }

    @Test
    @DisplayName("Номер за границей типа отвечает словами, а не «запрос не разобран» и не 500")
    void numberBeyondTheTypeIsRefusedInWords() throws Exception {
        MockHttpSession owner = login();
        for (String sent : List.of("9007199254740992", "99999999999999999999",
                "\"99999999999999999999\"")) {
            String message = put(owner, "\"number\":" + sent)
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(message).as("номер %s", sent).contains("9 007 199 254 740 991");
        }
        // Сама граница — принимается: это номер, а не запрет. Номер и нумерация
        // возвращаются после проверки: остальным методам класса нужен запас.
        Map<String, Object> was = inTenant(() -> jdbc.queryForMap(
                "SELECT (SELECT number FROM part WHERE id = ?) AS number, last_value, is_called"
                        + " FROM part_number_seq", partId));
        try {
            put(owner, "\"number\":9007199254740991").andExpect(status().isOk());
        } finally {
            inTenant(() -> {
                jdbc.update("UPDATE part SET number = ? WHERE id = ?", was.get("number"), partId);
                return jdbc.queryForObject("SELECT setval('part_number_seq', ?, ?)", Long.class,
                        was.get("last_value"), was.get("is_called"));
            });
        }
    }

    @Test
    @DisplayName("Правка номера и приёмка в одну минуту не выдают одинаковый номер и не отказывают")
    void numberEditAndIntakeDoNotCollide() throws Exception {
        long target = inTenant(() -> jdbc.queryForObject(
                "SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END + 1"
                        + " FROM part_number_seq", Long.class));
        JdbcTemplate other = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        AtomicReference<Object> intake = new AtomicReference<>();
        Thread[] racer = new Thread[1];
        // Приёмка встаёт ровно между «номер свободен» и записью номера: без
        // очереди она взяла бы тот же номер, и одна из двух получила бы отказ.
        HookJdbc hooked = new HookJdbc(jdbc, sql -> {
            if (racer[0] == null && sql.contains("SELECT public_code FROM part WHERE number")) {
                racer[0] = new Thread(() -> {
                    try {
                        intake.set(other.queryForObject("""
                                INSERT INTO %s.part (category_id, title, price)
                                VALUES (1, 'Приёмка во время правки', 100) RETURNING number"""
                                .formatted(TENANT), Long.class));
                    } catch (RuntimeException e) {
                        intake.set(e);
                    }
                });
                racer[0].start();
                join(racer[0], 700);
            }
        });
        PartIdentity identity = new PartIdentity(hooked, journal);

        Throwable edit = null;
        try {
            inTenant(() -> identity.apply(partId, new PartIdentity.Edit(BigInteger.valueOf(target),
                    null, null, null, null, null, null, null, null, null, null, null, null, null),
                    null));
        } catch (RuntimeException e) {
            edit = e;
        }
        join(racer[0], 10_000);

        assertThat(edit).as("правка номера отказала: приёмка заняла его между проверкой и записью")
                .isNull();
        assertThat(intake.get()).as("приёмка во время правки номера получила отказ")
                .isInstanceOf(Long.class);
        assertThat((Long) intake.get()).as("приёмка выдала номер, вписанный правкой")
                .isGreaterThan(target);
    }

    // ---------------------------------------------------------- пункт 2: машина

    @Test
    @DisplayName("Исправленная в карточке марка уходит в теги прайса Дрома, соседняя деталь — нет")
    void manualVehicleGoesToTheDromPrice() throws Exception {
        save(login(), "\"brand\":\"Lexus\",\"model\":\"ES250\",\"body\":\"GSV40\",\"year\":\"2008\"");

        String xml = price(DromPriceGenerator.FeedFilter.everything());
        String own = offer(xml, "Фара левая");
        assertThat(own).contains("<brandcars>Lexus</brandcars>", "<modelcars>ES250</modelcars>",
                "GSV40", "2008");
        assertThat(own).doesNotContain("Toyota", "Camry");
        String other = offer(xml, "Фара правая");
        assertThat(other).as("соседняя деталь того же донора")
                .contains("<brandcars>Toyota</brandcars>", "<modelcars>Camry</modelcars>");
    }

    @Test
    @DisplayName("Отбор выгрузки по марке берёт исправленную марку, и счётчик считает то же")
    void exportBrandFilterSeesTheManualBrand() throws Exception {
        save(login(), "\"brand\":\"Lexus\"");

        String onlyLexus = price(filterOf(lexus, false));
        assertThat(onlyLexus).contains("Фара левая").doesNotContain("Фара правая");
        String onlyToyota = price(filterOf(toyota, false));
        assertThat(onlyToyota).as("исправленная на Lexus уехала в «только Toyota»")
                .contains("Фара правая").doesNotContain("Фара левая");
        String exceptToyota = price(filterOf(toyota, true));
        assertThat(exceptToyota).contains("Фара левая").doesNotContain("Фара правая");

        for (var c : List.of(Map.entry(lexus, onlyLexus), Map.entry(toyota, onlyToyota))) {
            long counted = inTenant(() -> accounts.countMatching(null, null, null, null, null,
                    false, List.of(c.getKey()), false, "PART", Map.of(), Map.of(), false));
            assertThat(counted).as("счётчик выгрузки разошёлся с прайсом")
                    .isEqualTo(c.getValue().split("<offer>", -1).length - 1);
        }
    }

    @Test
    @DisplayName("Отбор продавца находит позицию по исправленной марке, а не по донору; список значений тот же")
    void sellerFiltersByTheManualBrand() throws Exception {
        MockHttpSession owner = login();
        save(owner, "\"brand\":\"Lexus\",\"model\":\"ES250\",\"year\":\"2008\"");

        JsonNode lexusFound = stock(owner, "brand", "Lexus");
        assertThat(ids(lexusFound)).containsExactly(partId);
        assertThat(lexusFound.path("total").asLong()).isEqualTo(1);
        assertThat(ids(stock(owner, "brand", "Toyota"))).containsExactly(neighbour);
        assertThat(ids(stock(owner, "model", "ES250"))).containsExactly(partId);
        assertThat(ids(stock(owner, "yearFrom", "2008"))).containsExactly(partId);

        JsonNode facets = stock(owner, null, null).path("facets").path("vehicles");
        assertThat(facets.toString()).contains("\"brand\":\"Lexus\",\"model\":\"ES250\"",
                "\"brand\":\"Toyota\",\"model\":\"Camry\"");
    }

    @Test
    @DisplayName("Подбор «что подходит к машине» находит исправленную марку, и число в списке машин совпадает")
    void vehiclePickerSeesTheManualBrand() throws Exception {
        MockHttpSession owner = login();
        save(owner, "\"brand\":\"lexus\",\"model\":\"ES250\"");
        Long es250 = jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND name = 'ES250'", Long.class, lexus);

        assertThat(catalogCodes(owner, "brandId", lexus)).containsExactly(codeOf(partId));
        assertThat(catalogCodes(owner, "brandId", toyota)).containsExactly(codeOf(neighbour));
        assertThat(catalogCodes(owner, "modelId", es250, "brandId", lexus))
                .containsExactly(codeOf(partId));

        JsonNode list = json.readTree(mvc.perform(get("/api/parts/catalog/vehicles").session(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode lexusRow = null;
        for (JsonNode row : list) {
            if (lexus.equals(row.path("brandId").asLong())) {
                lexusRow = row;
            }
        }
        assertThat(lexusRow).as("в списке подбора нет марки, вписанной у позиции").isNotNull();
        assertThat(lexusRow.path("modelId").asLong()).isEqualTo(es250);
        assertThat(lexusRow.path("parts").asLong()).as("число у модели разошлось с отбором")
                .isEqualTo(1);
        assertThat(lexusRow.path("brandParts").asLong()).isEqualTo(1);
    }

    // ---------------------------------------------------------- название (0336)

    /**
     * Название производное (задача 0336), но собрать его можно, только если
     * у позиции есть вид: эта заведена мимо справочника наименований, и правка
     * номера её названия не трогает — пересобирать не из чего. Пересборку
     * у позиции с видом стережёт {@code intake.IntakeTitleLikeBazonTest}.
     */
    @Test
    @DisplayName("Позиция без вида: номер производителя название не трогает, «Наименование» прислать нельзя")
    void titleWithoutKindIsKeptAndNotEditable() throws Exception {
        MockHttpSession owner = login();
        String history = historyOf(owner);

        save(owner, "\"oem\":\"81170-33671\"");
        assertThat(titleOf(partId)).isEqualTo("Фара левая (б/у) 81170-33670");
        save(owner, "\"oem\":\"\"");
        assertThat(titleOf(partId)).isEqualTo("Фара левая (б/у) 81170-33670");

        put(owner, "\"title\":\"Моя фара\"").andExpect(status().isBadRequest());
        assertThat(titleOf(partId)).isEqualTo("Фара левая (б/у) 81170-33670");
        assertThat(historyOf(owner).split("Наименование", -1).length)
                .as("правка номера или отказ написали в историю «Наименование: … → …»")
                .isEqualTo(history.split("Наименование", -1).length);
    }

    // ------------------------------------------------------------------ помощники

    private org.springframework.test.web.servlet.ResultActions put(MockHttpSession session,
                                                                   String fields) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/parts/" + partId).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                // Форма уезжает целиком, и пустая маркировка — «очищено»: шлём свою.
                .content("{\"price\":4500,\"published\":true,\"marking\":\"" + marking + "\","
                        + fields + "}"));
    }

    private String historyOf(MockHttpSession owner) throws Exception {
        return mvc.perform(get("/api/parts/" + partId + "/history").session(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void save(MockHttpSession session, String fields) throws Exception {
        put(session, fields).andExpect(status().isOk());
    }

    private static DromPriceGenerator.FeedFilter filterOf(Long brand, boolean excluded) {
        return new DromPriceGenerator.FeedFilter(null, null, List.of(), List.of(), List.of(), false,
                List.of(brand), excluded);
    }

    private String price(DromPriceGenerator.FeedFilter filter) {
        return inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, filter);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    private static String offer(String xml, String title) {
        int at = xml.indexOf(title);
        assertThat(at).as("в прайсе нет «%s»", title).isNotNegative();
        return xml.substring(xml.lastIndexOf("<offer>", at), xml.indexOf("</offer>", at));
    }

    private JsonNode stock(MockHttpSession session, String param, String value) throws Exception {
        var request = get("/api/parts/stock").session(session).param("q", marking);
        if (param != null) {
            request = request.param(param, value);
        }
        return json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<Long> ids(JsonNode search) {
        List<Long> found = new java.util.ArrayList<>();
        search.path("rows").forEach(r -> found.add(r.path("partId").asLong()));
        return found;
    }

    private List<String> catalogCodes(MockHttpSession session, Object... params) throws Exception {
        var request = get("/api/parts/catalog").session(session).param("size", "200")
                .param("q", marking);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param((String) params[i], String.valueOf(params[i + 1]));
        }
        List<String> codes = new java.util.ArrayList<>();
        json.readTree(mvc.perform(request).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("rows").forEach(r -> codes.add(r.path("code").asText()));
        return codes;
    }

    private String titleOf(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, part));
    }

    private String codeOf(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, part));
    }

    private void member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            jdbc.update("UPDATE tenant_member SET display_name = ?, role = ? WHERE id = ?",
                    displayName, role, found.get(0));
            return;
        }
        jdbc.update("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?)""", displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"afteredit","login":"vladelec0303","password":"пароль"}"""))
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

    private static void join(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** JdbcTemplate того же источника, который зовёт подкладку после каждого запроса. */
    private static final class HookJdbc extends JdbcTemplate {
        private final java.util.function.Consumer<String> after;

        HookJdbc(JdbcTemplate base, java.util.function.Consumer<String> after) {
            super(base.getDataSource());
            this.after = after;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            List<T> r = super.queryForList(sql, type, args);
            after.accept(sql);
            return r;
        }
    }
}
