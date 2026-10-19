package ru.partsflow.intake;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Заведение позиции приёмкой (задача 0362, ответ владельца 19.10.2026 «всё как у Bazon»):
 * состояние, комментарий, заметка, производитель, кросс-номера, маркировка и цвет
 * вводятся сразу, а цена необязательна — ноль допустим.
 *
 * <p>Через HTTP: приёмку зовёт офлайн-очередь телефона, и ответ контроллера — то,
 * что та читает. Отказ на {@code price: 0} («items[0].price: должно быть больше 0»)
 * уводил такую партию в «требует внимания».
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class IntakeFormFieldsHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000362";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 362");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (362, ?, 'Поля заведения', 'intakefields')""", TENANT);

        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    SELECT 'priemshchik', 'Приёмщик', ?, 'STOREKEEPER'
                     WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login = 'priemshchik')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    SELECT 'vladelets', 'Владелец', ?, 'OWNER'
                     WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login = 'vladelets')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Основной') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Все поля формы доезжают в карточку: состояние, комментарий, заметка, производитель, маркировка, цвет")
    void fieldsAreStored() throws Exception {
        String requestId = "fields-" + System.nanoTime();
        receive(requestId, """
                {"rawName":"Фара","price":1500,"quantity":1,"condition":"CONTRACT",
                 "description":"Без пробега по РФ","note":"Снимаем только оптику",
                 "manufacturer":"Toyota","marking":"Koito 100-12345","color":"Белый"}""")
                .andExpect(status().isCreated());

        Map<String, Object> part = onlyPart(requestId);
        assertThat(part.get("condition")).isEqualTo("CONTRACT");
        assertThat(part.get("description")).isEqualTo("Без пробега по РФ");
        assertThat(part.get("note")).isEqualTo("Снимаем только оптику");
        assertThat(part.get("manufacturer")).isEqualTo("Toyota");
        assertThat(part.get("marking")).isEqualTo("Koito 100-12345");
        assertThat(part.get("color")).isEqualTo("Белый");
    }

    @Test
    @DisplayName("Цена 0 и цена без ключа — позиция заведена с ценой 0, а не отказом")
    void zeroOrMissingPriceIsAccepted() throws Exception {
        String zero = "zero-" + System.nanoTime();
        receive(zero, """
                {"rawName":"Фара","price":0,"quantity":1}""").andExpect(status().isCreated());
        assertThat(((Number) onlyPart(zero).get("price")).doubleValue()).isZero();

        String missing = "missing-" + System.nanoTime();
        receive(missing, """
                {"rawName":"Дверь","quantity":1}""").andExpect(status().isCreated());
        assertThat(onlyPart(missing).get("price"))
                .as("пустая цена легла NULL, а не нулём").isNotNull();
        assertThat(((Number) onlyPart(missing).get("price")).doubleValue()).isZero();
    }

    @Test
    @DisplayName("Отрицательная цена по-прежнему отказ")
    void negativePriceIsRefused() throws Exception {
        receive("neg-" + System.nanoTime(), """
                {"rawName":"Фара","price":-1,"quantity":1}""").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Без состояния новая позиция — «б/у», как и раньше")
    void noConditionStaysUsed() throws Exception {
        String requestId = "used-" + System.nanoTime();
        receive(requestId, """
                {"rawName":"Фара","price":100,"quantity":1}""").andExpect(status().isCreated());
        assertThat(onlyPart(requestId).get("condition")).isEqualTo("USED");
    }

    @Test
    @DisplayName("Кросс-номера из своего поля ложатся после основного и лишних номеров, повтор не заводится")
    void crossNumbersAreStored() throws Exception {
        String requestId = "cross-" + System.nanoTime();
        receive(requestId, """
                {"rawName":"Фара","price":100,"quantity":1,
                 "oemNumber":"8414012530, 8414012531",
                 "crossNumbers":"8414012532; 8414012530,  АНАЛОГ"}""")
                .andExpect(status().isCreated());

        List<Map<String, Object>> rows = inTenant(() -> jdbc.queryForList("""
                SELECT o.raw_number, o.is_primary FROM part_oem o
                  JOIN stock_document_line l ON l.part_id = o.part_id
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ? ORDER BY o.id""", requestId));
        assertThat(rows).extracting(r -> r.get("raw_number"))
                .containsExactly("8414012530", "8414012531", "8414012532");
        assertThat(rows).extracting(r -> r.get("is_primary"))
                .containsExactly(true, false, false);
    }

    @Test
    @DisplayName("Кросс-номера без основного пишутся, основным ни один не становится")
    void crossNumbersWithoutPrimary() throws Exception {
        String requestId = "cross-only-" + System.nanoTime();
        receive(requestId, """
                {"rawName":"Фара","price":100,"quantity":1,"crossNumbers":"8414012532"}""")
                .andExpect(status().isCreated());
        List<Map<String, Object>> rows = inTenant(() -> jdbc.queryForList("""
                SELECT o.is_primary FROM part_oem o
                  JOIN stock_document_line l ON l.part_id = o.part_id
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", requestId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("is_primary")).isEqualTo(false);
    }

    @Test
    @DisplayName("Кросс-номер длиннее предела — 400 словами, партия не заводится")
    void longCrossNumberIsRefused() throws Exception {
        String requestId = "cross-long-" + System.nanoTime();
        receive(requestId, """
                {"rawName":"Фара","price":100,"quantity":1,"crossNumbers":"%s"}"""
                .formatted("A".repeat(1001))).andExpect(status().isBadRequest());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM stock_document WHERE client_request_id = ?",
                Long.class, requestId))).isZero();
    }

    @Test
    @DisplayName("Ожидаемая позиция заводится с ценой 0 и без цены; отрицательная — отказ")
    void expectedPartPriceIsOptional() throws Exception {
        Long supply = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status) VALUES ('CONTAINER', 'EXP-362-' || nextval('supply_id_seq'), 'EXPECTED')
                RETURNING id""", Long.class));
        MockHttpSession owner = login("vladelets");

        expected(owner, supply, "{\"rawName\":\"Фара\",\"quantity\":2,\"price\":0,\"requestId\":\"e0-%d\"}")
                .andExpect(status().isCreated());
        expected(owner, supply, "{\"rawName\":\"Дверь\",\"quantity\":1,\"requestId\":\"e1-%d\"}")
                .andExpect(status().isCreated());
        expected(owner, supply, "{\"rawName\":\"Капот\",\"quantity\":1,\"price\":-5,\"requestId\":\"e2-%d\"}")
                .andExpect(status().isBadRequest());

        List<Map<String, Object>> rows = inTenant(() -> jdbc.queryForList(
                "SELECT price FROM part WHERE supply_id = ? ORDER BY id", supply));
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("price")).doubleValue()).isZero());
    }

    private ResultActions expected(MockHttpSession owner, Long supply, String template) throws Exception {
        return mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                .with(csrf()).session(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(template.formatted(supply)));
    }

    private ResultActions receive(String requestId, String item) throws Exception {
        return mvc.perform(post("/api/intake/receipts").with(csrf()).session(login("priemshchik"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","warehouseId":%d,"items":[%s]}"""
                        .formatted(requestId, warehouse, item)));
    }

    private Map<String, Object> onlyPart(String requestId) {
        return inTenant(() -> jdbc.queryForMap("""
                SELECT p.condition, p.description, p.note, p.manufacturer, p.marking, p.color, p.price
                  FROM part p
                  JOIN stock_document_line l ON l.part_id = p.id
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", requestId));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"intakefields","login":"%s","password":"пароль-подлиннее"}"""
                                .formatted(login)))
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
