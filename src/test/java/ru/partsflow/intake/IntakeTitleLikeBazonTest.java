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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Название позиции склада — по формуле Bazon (задача 0336, решение владельца
 * продукта 19.10.2026, «всё как в базон»): «(б/у)» у б/у и контрактной, нет
 * у новой; название производное и пересобирается правкой полей формулы,
 * руками не правится.
 *
 * <p>Через HTTP: приёмку зовёт офлайн-очередь телефона, ожидаемый товар —
 * форма владельца, правку — карточка; первые две доходят до одного
 * {@code IntakeService.buildPart}, третья — до {@code PartTitles}, и все три
 * обязаны собрать одно и то же. Своя схема ({@code t_000959}, тот же номер
 * в реестре): проверки ищут наименования своих позиций по ключу запроса.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class IntakeTitleLikeBazonTest extends PostgresTestBase {

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
                VALUES (959, ?, 'Название как у Bazon', 'nomark')""", TENANT);

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

    private static final String BASE =
            "Блок подрулевых переключателей Toyota ZNE10 1ZZFE 2006 Перед. Лев.";

    @Test
    @DisplayName("Приёмка с каждым из четырёх состояний: «(б/у)» у б/у и контрактной, нет у новой и восстановленной")
    void receiptTitleCarriesBazonMark() throws Exception {
        List<Map<String, Object>> rows = receive("mark-" + System.nanoTime(), PartCondition.values());
        assertThat(rows).extracting(r -> r.get("condition"))
                .as("состояние приёмки не дошло до поля condition")
                .containsExactly(java.util.Arrays.stream(PartCondition.values())
                        .map(Enum::name).toArray());
        for (Map<String, Object> row : rows) {
            PartCondition condition = PartCondition.valueOf((String) row.get("condition"));
            // Номер производителя — последним словом: по нему продавец ищет
            // глазами в конце строки.
            String expected = switch (condition) {
                case USED, CONTRACT -> BASE + " (б/у) 8414012530";
                case NEW, REFURBISHED -> BASE + " 8414012530";
            };
            assertThat(row.get("title")).as("наименование позиции %s", condition).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Ожидаемый товар владельца собирается тем же правилом; без состояния — без пометки")
    void expectedPartTitleCarriesBazonMark() throws Exception {
        Long supply = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO supply (kind, number, status)
                VALUES ('CONTAINER', ?, 'EXPECTED') RETURNING id""",
                Long.class, "ПБ-" + System.nanoTime()));
        for (String condition : new String[] {"CONTRACT", ""}) {
            String requestId = "mark-exp-" + System.nanoTime();
            mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                            .with(csrf()).session(login("vladelec"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"rawName":"Фара левая","donorId":%d,"quantity":2,"price":9000,
                                     "condition":"%s","requestId":"%s"}"""
                                    .formatted(donor, condition, requestId)))
                    .andExpect(status().isCreated());
            String title = inTenant(() -> jdbc.queryForObject(
                    "SELECT title FROM part WHERE client_request_id = ?", String.class, requestId));
            assertThat(title).as("состояние «%s»", condition).isEqualTo(condition.isEmpty()
                    ? "Фара левая Toyota ZNE10 1ZZFE 2006"
                    : "Фара левая Toyota ZNE10 1ZZFE 2006 (б/у)");
        }
    }

    /**
     * Название производное (решение 2): правка номера, состояния, положения
     * и машины позиции пересобирает его тем же генератором, что приёмка, —
     * и возврат прежних полей возвращает прежнее название до знака.
     */
    @Test
    @DisplayName("Правка номера, состояния, положения и машины пересобирает название; заметка — нет")
    void editRebuildsTitle() throws Exception {
        long id = idOf(receive("mark-edit-" + System.nanoTime(), PartCondition.USED));

        assertThat(edit(id, "{\"condition\":\"NEW\"}")).isEqualTo(BASE + " 8414012530");
        assertThat(edit(id, "{\"condition\":\"CONTRACT\"}")).isEqualTo(BASE + " (б/у) 8414012530");
        assertThat(edit(id, "{\"oem\":\"84140-12531, 84140-12532\"}"))
                .as("в название — основной номер как вписан")
                .isEqualTo(BASE + " (б/у) 84140-12531");
        assertThat(edit(id, "{\"sideFr\":\"REAR\",\"sideLr\":\"\"}"))
                .isEqualTo("Блок подрулевых переключателей Toyota ZNE10 1ZZFE 2006 Задн. (б/у) 84140-12531");
        assertThat(edit(id, "{\"brand\":\"Lexus, Toyota\",\"year\":\"2008\"}"))
                .as("своя машина позиции сильнее донора, первое значение списка")
                .isEqualTo("Блок подрулевых переключателей Lexus ZNE10 1ZZFE 2008 Задн. (б/у) 84140-12531");
        assertThat(edit(id, "{\"note\":\"скол\"}"))
                .as("заметка не поле формулы")
                .isEqualTo("Блок подрулевых переключателей Lexus ZNE10 1ZZFE 2008 Задн. (б/у) 84140-12531");
        // Обратно — то же, что собрала приёмка: формула одна.
        assertThat(edit(id, """
                {"brand":"","year":"","sideFr":"FRONT","sideLr":"LEFT","oem":"8414012530",
                 "condition":"USED"}"""))
                .isEqualTo(BASE + " (б/у) 8414012530");
    }

    @Test
    @DisplayName("Присланное наименование — 400 словами, ничего не записано; совпадающее — не правка")
    void titleIsNotEditable() throws Exception {
        long id = idOf(receive("mark-title-" + System.nanoTime(), PartCondition.USED));
        String body = mvc.perform(put("/api/parts/" + id).with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Моя фара\",\"note\":\"скол\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("Наименование собирается само");
        Map<String, Object> row = inTenant(() -> jdbc.queryForMap(
                "SELECT title, note FROM part WHERE id = ?", id));
        assertThat(row.get("title")).isEqualTo(BASE + " (б/у) 8414012530");
        assertThat(row.get("note")).as("отказ записал соседнее поле").isNull();

        // Вкладка, открытая до выкладки, шлёт хранимое как есть — это не правка.
        assertThat(edit(id, "{\"title\":\"" + BASE + " (б/у) 8414012530\",\"note\":\"скол\"}"))
                .isEqualTo(BASE + " (б/у) 8414012530");
    }

    /**
     * Переходное правило до разового пересчёта (0336, пункт 4): у позиции,
     * перенесённой из Bazon без донора и без своей машины, машины лежат только
     * в названии — пересборка правкой состояния стёрла бы их. Вписав машину
     * позиции, владелец даёт формуле поля — и название собирается.
     */
    @Test
    @DisplayName("Перенесённая из Bazon без донора и своей машины: правка состояния название не стирает")
    void bazonPartWithoutVehicleKeepsTitle() throws Exception {
        String stored = "Блок круиз-контроля Lincoln,Ford Navigator,Expedition UN173 TRITON54L (б/у)";
        long id = inTenant(() -> {
            Long name = jdbc.queryForObject("""
                    INSERT INTO part_name (name) VALUES ('Блок круиз-контроля')
                    ON CONFLICT (lower(btrim(name))) DO UPDATE SET name = EXCLUDED.name
                    RETURNING id""", Long.class);
            return jdbc.queryForObject("""
                    INSERT INTO part (category_id, part_name_id, title, price, legacy_code, condition)
                    VALUES (1, ?, ?, 100, ?, 'CONTRACT') RETURNING id""",
                    Long.class, name, stored, "B-" + System.nanoTime());
        });

        assertThat(edit(id, "{\"condition\":\"NEW\"}"))
                .as("пересборка стёрла машины, лежавшие только в названии")
                .isEqualTo(stored);
        assertThat(edit(id, "{\"brand\":\"Lincoln\",\"model\":\"Navigator\"}"))
                .isEqualTo("Блок круиз-контроля Lincoln Navigator");
    }

    private List<Map<String, Object>> receive(String requestId, PartCondition... conditions)
            throws Exception {
        StringBuilder items = new StringBuilder();
        for (PartCondition condition : conditions) {
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
        return inTenant(() -> jdbc.queryForList("""
                SELECT p.id, p.title, p.condition FROM part p
                  JOIN stock_document_line l ON l.part_id = p.id
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?
                 ORDER BY l.id""", requestId));
    }

    private static long idOf(List<Map<String, Object>> rows) {
        return ((Number) rows.get(0).get("id")).longValue();
    }

    /** Правка карточки через HTTP; возвращает название после неё. */
    private String edit(long id, String json) throws Exception {
        mvc.perform(put("/api/parts/" + id).with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
        return inTenant(() -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, id));
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
