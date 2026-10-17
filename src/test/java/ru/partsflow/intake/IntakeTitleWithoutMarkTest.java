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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Приёмка больше не дописывает в наименование пометку состояния (задача 0315,
 * решение владельца 17.10.2026, вариант «в»).
 *
 * <p>Пометка «(б/у)» была второй копией поля {@code condition}, и копия врала:
 * с 0304 наименование меняет только человек, поэтому смена состояния её
 * не обновляла, а правка названия руками теряла. Состояние показывается рядом
 * словом (0314, 0318), а наименование остаётся тем, что собрано из вида детали,
 * машины, сторон и номера.
 *
 * <p>Через HTTP: приёмку зовёт офлайн-очередь телефона, ожидаемый товар —
 * форма владельца, и обе доходят до одного {@code IntakeService.buildPart}.
 * Своя схема ({@code t_000959}, тот же номер в реестре): проверки ищут
 * наименования своих позиций по ключу запроса.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class IntakeTitleWithoutMarkTest extends PostgresTestBase {

    private static final String TENANT = "t_000959";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static Long warehouse;
    private static Long donor;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 959");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (959, ?, 'Приёмка без пометки', 'nomark')""", TENANT);

        inTenant(() -> {
            // Схема у класса своя, и заводится всё один раз: позиции первого
            // теста уже ссылаются на сотрудника, удалить его нельзя.
            if (warehouse != null) {
                return null;
            }
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('priemshchik', 'Приёмщик', ?, 'STOREKEEPER'),
                           ('vladelec', 'Владелец', ?, 'OWNER')""",
                    passwordEncoder.encode("пароль-подлиннее"),
                    passwordEncoder.encode("пароль-подлиннее"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            // Машина задаёт кузов, двигатель и год руками — справочник
            // поколений тут ни при чём, а заголовок обязан их нести.
            donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, body_code, engine_code, year, status)
                    VALUES ((SELECT id FROM catalog.brand WHERE slug = 'toyota'),
                            'ZNE10', '1ZZFE', 2006, 'DISMANTLING')
                    RETURNING id""", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Приёмка с каждым из четырёх состояний: наименование без пометки, состояние в поле")
    void receiptTitleCarriesNoConditionMark() throws Exception {
        String requestId = "nomark-" + System.nanoTime();
        StringBuilder items = new StringBuilder();
        for (PartCondition condition : PartCondition.values()) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            items.append("""
                    {"rawName":"Блок подрулевых переключателей","price":100,"quantity":1,
                     "sideFr":"FRONT","sideLr":"LEFT","condition":"%s","oemNumber":"8414012530"}"""
                    .formatted(condition.name()));
        }
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login("priemshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,"donorId":%d,"items":[%s]}"""
                                .formatted(requestId, warehouse, donor, items)))
                .andExpect(status().isCreated());

        List<Map<String, Object>> rows = inTenant(() -> jdbc.queryForList("""
                SELECT p.title, p.condition FROM part p
                  JOIN stock_document_line l ON l.part_id = p.id
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?
                 ORDER BY l.id""", requestId));
        assertThat(rows).extracting(r -> r.get("condition"))
                .as("состояние приёмки не дошло до поля condition")
                .containsExactly(java.util.Arrays.stream(PartCondition.values())
                        .map(Enum::name).toArray());
        for (Map<String, Object> row : rows) {
            String title = (String) row.get("title");
            assertTitleWithoutMark(title, (String) row.get("condition"));
            // Номер производителя — последним словом, как и до задачи:
            // по нему продавец ищет глазами в конце строки.
            assertThat(title)
                    .as("у %s собрано не то наименование", row.get("condition"))
                    .isEqualTo("Блок подрулевых переключателей Toyota ZNE10 1ZZFE 2006"
                            + " перед. лев. 8414012530");
        }
        assertThat(rows).extracting(r -> r.get("title"))
                .as("наименование зависит от состояния — значит, пометка где-то осталась")
                .containsOnly(rows.get(0).get("title"));
    }

    @Test
    @DisplayName("Ожидаемый товар владельца собирается тем же правилом — без «(б/у)»")
    void expectedPartTitleCarriesNoConditionMark() throws Exception {
        Long supply = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status)
                VALUES ('CONTAINER', ?, 'EXPECTED') RETURNING id""",
                Long.class, "ПБ-" + System.nanoTime()));
        String requestId = "nomark-exp-" + System.nanoTime();
        mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                        .with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rawName":"Фара левая","donorId":%d,"quantity":2,"price":9000,
                                 "requestId":"%s"}""".formatted(donor, requestId)))
                .andExpect(status().isCreated());

        Map<String, Object> row = inTenant(() -> jdbc.queryForMap(
                "SELECT title, condition FROM part WHERE client_request_id = ?", requestId));
        assertTitleWithoutMark((String) row.get("title"), (String) row.get("condition"));
        assertThat(row.get("title")).isEqualTo("Фара левая Toyota ZNE10 1ZZFE 2006");
    }

    /**
     * Поиск по наименованию без пометки находит и новую позицию, и старую,
     * собранную до задачи: пометки с хранимого снимает 0316, а до неё склад
     * двух видов, и продавец, набравший «Toyota 2006 8414012599», обязан
     * получить обе.
     */
    @Test
    @DisplayName("Поиск без пометки находит и новую позицию, и ещё не вычищенную старую")
    void searchFindsNewAndOldTitles() throws Exception {
        String requestId = "nomark-search-" + System.nanoTime();
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(login("priemshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","warehouseId":%d,"donorId":%d,"items":[
                                 {"rawName":"Фара","price":100,"quantity":1,"condition":"USED",
                                  "oemNumber":"8414012599"},
                                 {"rawName":"Фара","price":100,"quantity":1,"condition":"USED",
                                  "oemNumber":"8414012599"}]}"""
                                .formatted(requestId, warehouse, donor)))
                .andExpect(status().isCreated());
        List<Long> ids = inTenant(() -> jdbc.queryForList("""
                SELECT l.part_id FROM stock_document_line l
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ? ORDER BY l.id""", Long.class, requestId));
        // Вторая — как её собрала бы приёмка до задачи 0315.
        inTenant(() -> jdbc.update(
                "UPDATE part SET title = 'Фара Toyota ZNE10 1ZZFE 2006 (б/у) 8414012599' WHERE id = ?",
                ids.get(1)));

        String body = mvc.perform(get("/api/parts/stock").session(login("priemshchik"))
                        .param("q", "Toyota 2006 8414012599"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (Long id : ids) {
            assertThat(body).as("поиск не нашёл позицию %d", id).contains("\"partId\":" + id + ",");
        }
    }

    private static void assertTitleWithoutMark(String title, String condition) {
        for (String mark : PartCondition.titleMarks()) {
            assertThat(title)
                    .as("в наименование позиции %s дописана пометка «%s»", condition, mark)
                    .doesNotContain(mark);
        }
        assertThat(title).as("на месте пометки остался двойной пробел").doesNotContain("  ");
        assertThat(title).isEqualTo(title.strip());
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"nomark","login":"%s","password":"пароль-подлиннее"}"""
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
