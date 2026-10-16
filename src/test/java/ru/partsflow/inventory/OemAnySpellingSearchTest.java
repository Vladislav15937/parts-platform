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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Номер производителя находится в любом написании — задача 0306.
 *
 * <p>Номер с 15.10.2026 вписывают руками и хранят как набран («ab-123 456x»),
 * а покупатель по телефону называет его без дефисов и пробелов: поиск
 * подстрокой по набранному отдавал ноль, и продавец отвечал «нет такого» про
 * деталь на полке (замерено на ПРОМ, компания smoke). Через HTTP и на обе
 * поверхности сразу — витрину и продавца: два поиска по одному складу обязаны
 * находить одно и то же (`inventory/CLAUDE.md`).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OemAnySpellingSearchTest extends PostgresTestBase {

    private static final String TENANT = "t_000997";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private ObjectMapper json;

    /** Номер «ab-123 456x», кросс-номера «aa111, BB222, dd-444» — из критерия задачи. */
    private static final String MAIN = "AAAAAAAAAAA1";
    /** Кросс-номер записан слитно: «dd444». */
    private static final String JOINED = "AAAAAAAAAAA2";
    /** Номер записан без тире: «8117033670». */
    private static final String PLAIN = "AAAAAAAAAAA3";
    /** Номер без «123» вовсе — контроль, что подстрока не тащит лишнего. */
    private static final String OTHER = "AAAAAAAAAAA4";

    private MockHttpSession session;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 997");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (997, ?, 'Поиск номеров', 'oemsearch')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM tenant_member WHERE login = 'vladelec0306'");
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('vladelec0306', 'Владелец', ?, 'OWNER')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            Long main = part("Фара левая", MAIN, warehouse);
            oem(main, "ab-123 456x", true);
            oem(main, "aa111", false);
            oem(main, "BB222", false);
            oem(main, "dd-444", false);
            oem(part("Фара правая", JOINED, warehouse), "dd444", false);
            oem(part("Стартер", PLAIN, warehouse), "8117033670", true);
            oem(part("Генератор", OTHER, warehouse), "XZ-9876", true);
            return null;
        });
        session = login();
    }

    @Test
    @DisplayName("Номер производителя без дефисов, пробелов и в другом регистре находит позицию (п. 1)")
    void numberInAnySpelling() throws Exception {
        for (String query : List.of("AB123456X", "ab123456x", "AB 123 456 X",
                // Набранное как было — находит, как и до задачи.
                "ab-123 456x", "ab-123",
                // Русскими буквами: приводится тем же OemNumbers, что запись (0309).
                "\u0410\u0412123456\u0425")) {
            assertBoth(query).contains(MAIN);
        }
    }

    @Test
    @DisplayName("Кросс-номер в обе стороны: записано слитно — находит с дефисом и пробелом, и наоборот (п. 2)")
    void crossNumberBothWays() throws Exception {
        for (String query : List.of("DD-444", "dd 444", "DD444")) {
            assertBoth(query).contains(MAIN, JOINED);
        }
        assertBoth("bb-222").containsExactly(MAIN);
    }

    @Test
    @DisplayName("Тире любого вида и его отсутствие равны (п. 3)")
    void anyDash() throws Exception {
        for (String query : List.of("81170–33670", "81170—33670", "81170-33670",
                "81170 33670")) {
            assertBoth(query).containsExactly(PLAIN);
        }
    }

    @Test
    @DisplayName("Короткий цифровой запрос — подстрокой, как раньше; слова с цифрой не становятся номером (п. 4, 6)")
    void shortAndWordQueriesStayAsBefore() throws Exception {
        assertBoth("123").containsExactly(MAIN);
        // «фара 1» привела бы к «1» и отдала бы всё, где есть единица: Стартер
        // и Генератор сюда не попадают — у них ни «фары», ни «фара 1» в тексте.
        assertBoth("фара 1").doesNotContain(PLAIN, OTHER);
        // То же с тремя цифрами: «фара 123» не номер, и «123» из него не ищется
        // по приведённому виду — иначе фара с номером «ab-123…» нашлась бы по слову,
        // которого в ней нет, а «фара 2» принесла бы пол-склада.
        assertBoth("фара 123").doesNotContain(MAIN);
        // После приведения не осталось букв и цифр — поведение прежнее, без ошибки.
        assertBoth("--").doesNotContain(MAIN, JOINED, PLAIN, OTHER);
    }

    /** Коды позиций, найденных и витриной, и продавцом; обе обязаны совпасть. */
    private org.assertj.core.api.ListAssert<String> assertBoth(String query) throws Exception {
        List<String> storefront = new ArrayList<>();
        JsonNode catalog = read(mvc.perform(get("/api/parts/catalog").session(session)
                .param("q", query).param("missing", "true").param("size", "200")));
        catalog.path("rows").forEach(r -> storefront.add(r.path("code").asText()));
        List<String> seller = new ArrayList<>();
        JsonNode stock = read(mvc.perform(get("/api/parts/stock").session(session)
                .param("q", query)));
        stock.path("rows").forEach(r -> seller.add(r.path("publicCode").asText()));
        assertThat(seller)
                .as("продавец по «%s» нашёл не то, что витрина", query)
                .containsExactlyInAnyOrderElementsOf(storefront);
        return assertThat(storefront).as("витрина и продавец по «%s»", query);
    }

    private JsonNode read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Long part(String title, String code, Long warehouse) {
        Long id = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published)
                VALUES (1, ?, 5000, true) RETURNING id""", Long.class, title);
        jdbc.update("UPDATE part SET public_code = ? WHERE id = ?", code, id);
        ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
        return id;
    }

    private void oem(Long part, String raw, boolean primary) {
        jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                        + " VALUES (?, ?, ?, ?)", part, raw,
                ru.partsflow.catalog.OemNumbers.normalize(raw), primary);
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"oemsearch","login":"vladelec0306","password":"пароль-подлиннее"}"""))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
