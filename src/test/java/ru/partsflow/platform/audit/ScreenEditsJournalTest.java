package ru.partsflow.platform.audit;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правка через экран попадает в журнал действий организации (задача 0325).
 *
 * <p>Решение владельца продукта 18.10.2026: «все правки через UI должны
 * фиксироваться». Каждый метод — нажатие на экране, то есть запрос, который
 * шлёт экран, и чтение тем же путём, что у экрана журнала
 * ({@code GET /api/organization/audit}): тест, смотрящий в таблицу, не увидел бы,
 * что строка не доехала до экрана или доехала без слов.
 *
 * <p>Своя схема ({@code t_000969}): числа и первые строки журнала здесь
 * абсолютные, и чужая правка в общей схеме сдвинула бы их.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScreenEditsJournalTest extends PostgresTestBase {

    private static final String TENANT = "t_000969";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession owner;
    private long brandId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 969");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (969, ?, 'Разборка', 'journal0325')""", TENANT);
        brandId = jdbc.queryForObject(
                "SELECT id FROM catalog.brand ORDER BY id LIMIT 1", Long.class);
        inTenant(() -> {
            jdbc.update("DELETE FROM audit_log");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'vladelec'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец Журналов', 'OWNER', 'vladelec', ?)""",
                        passwordEncoder.encode("пароль"));
            }
            if (jdbc.queryForList("SELECT id FROM branch WHERE name = 'Филиал журнала'").isEmpty()) {
                Long branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал журнала') RETURNING id", Long.class);
                jdbc.update("INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая')", branch);
            }
            return null;
        });
        owner = login();
    }

    // -------------------------------------------------- позиция прямым SQL

    @Test
    @DisplayName("Сопоставление написания: вид детали у каждой карточки — было → стало, автор и роль")
    void matchingSpellingJournalsEveryCard() throws Exception {
        Long kind = jdbc.queryForObject(
                "SELECT id FROM catalog.part_kind ORDER BY id LIMIT 1", Long.class);
        long name = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part_name (name, match_status) VALUES ('фара лев. журнал', 'UNMATCHED')
                RETURNING id""", Long.class));
        long first = part("Фара журнала первая", name);
        long second = part("Фара журнала вторая", name);

        send(post("/api/part-names/" + name + "/match"), "{\"partKindId\":" + kind + "}")
                .andExpect(status().isOk());

        assertThat(rows("part", first)).as("сопоставление не записало карточку в журнал").isEqualTo(1);
        assertThat(rows("part", second)).as("вторая карточка написания без записи").isEqualTo(1);
        journal("Товар").andExpect(jsonPath("$.items[?(@.subject=='Фара журнала первая')]"
                        + ".changes[0].label").value("Вид детали"))
                .andExpect(jsonPath("$.items[?(@.subject=='Фара журнала первая')].author")
                        .value("Владелец Журналов"))
                .andExpect(jsonPath("$.items[?(@.subject=='Фара журнала первая')].authorRole")
                        .value("OWNER"));
        // Само написание — сущность, и его сопоставление видно своей строкой.
        journal("Наименование").andExpect(jsonPath("$.items[0].subject").value("фара лев. журнал"))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Сопоставление')].after")
                        .value("вручную"));
    }

    @Test
    @DisplayName("«Выгружать» списком: строка на каждую позицию, у которой флаг сменился")
    void publicationJournalsEachChangedPart() throws Exception {
        long one = part("Бампер журнала", null);
        long two = part("Капот журнала", null);

        send(post("/api/parts/publication"),
                "{\"partIds\":[%d,%d],\"published\":false}".formatted(one, two))
                .andExpect(status().isOk());
        // Повтор того же — флаг уже снят, журналу записывать нечего.
        send(post("/api/parts/publication"),
                "{\"partIds\":[%d],\"published\":false}".formatted(one))
                .andExpect(status().isOk());

        assertThat(rows("part", one)).as("«Выгружать» списком не записан, либо повтор записан второй раз").isEqualTo(1);
        assertThat(rows("part", two)).as("вторая позиция пачки без записи").isEqualTo(1);
        journal("Товар").andExpect(jsonPath("$.items[?(@.subject=='Бампер журнала')]"
                        + ".changes[0].before").value("да"))
                .andExpect(jsonPath("$.items[?(@.subject=='Бампер журнала')]"
                        + ".changes[0].after").value("нет"));
    }

    @Test
    @DisplayName("Колесо, заведённое комплектом, — «Товар заведён»")
    void wheelSetIsJournaled() throws Exception {
        long warehouse = warehouse();
        send(post("/api/wheels/sets"), """
                {"kind":"TYRE","warehouseId":%d,"quantity":2,"diameter":16,
                 "tyreWidth":205,"tyreHeight":55,"season":"SUMMER","price":3000}"""
                .formatted(warehouse)).andExpect(status().isCreated());

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM audit_log a JOIN part p ON p.id = a.record_id
                 WHERE a.table_name = 'part' AND a.operation = 'INSERT'
                   AND p.product_line = 'WHEEL'""", Integer.class)))
                .as("колёса комплекта заведены мимо журнала").isEqualTo(2);
        journal("Товар").andExpect(jsonPath("$.items[0].action").value("Товар заведён"));
    }

    // ------------------------------------------------------- машина и поставка

    @Test
    @DisplayName("Затрата по машине: записана и удалена — с машиной, и после удаления тоже")
    void donorCostIsJournaled() throws Exception {
        long donor = donor();
        String body = send(post("/api/intake/donors/" + donor + "/costs"),
                "{\"type\":\"DELIVERY\",\"amount\":4000,\"note\":\"эвакуатор\"}")
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long cost = idOf(body);

        mvc.perform(delete("/api/intake/donors/" + donor + "/costs/" + cost)
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());

        journal("Затрата по машине").andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].action").value("Затрата удалена"))
                .andExpect(jsonPath("$.items[0].context").value(containsString("Машина")))
                .andExpect(jsonPath("$.items[1].action").value("Затрата записана"))
                .andExpect(jsonPath("$.items[1].author").value("Владелец Журналов"));
    }

    @Test
    @DisplayName("Машина: место и перевод в разбор — словами, состояние кодом для экрана")
    void donorEditsAreJournaled() throws Exception {
        long donor = donor();
        mvc.perform(post("/api/intake/donors/" + donor + "/location").param("location", "ряд 3")
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());
        mvc.perform(post("/api/intake/donors/" + donor + "/dismantling")
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());

        journal("Машина").andExpect(jsonPath("$.items[0].changes[?(@.label=='Состояние машины')]"
                        + ".after").value("DISMANTLING"))
                .andExpect(jsonPath("$.items[1].changes[?(@.label=='Место')].after")
                        .value("ряд 3"));
    }

    @Test
    @DisplayName("Поставка: заведена и приехала")
    void supplyIsJournaled() throws Exception {
        String body = send(post("/api/intake/supplies"),
                "{\"kind\":\"CONTAINER\",\"number\":\"Ж-325\"}")
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long supply = idOf(body);
        mvc.perform(post("/api/intake/supplies/" + supply + "/arrived").with(csrf()).session(owner))
                .andExpect(status().isOk());

        journal("Поставка").andExpect(jsonPath("$.items[1].action").value("Поставка заведена"))
                .andExpect(jsonPath("$.items[0].subject").value(containsString("Ж-325")))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Состояние поставки')].after")
                        .value("ARRIVED"));
    }

    // ------------------------------------------------------------- карточка

    @Test
    @DisplayName("Применимость из карточки: добавлена и снята — с машиной в строке события")
    void applicabilityIsJournaled() throws Exception {
        long part = part("Фара применимости", null);
        String body = send(post("/api/parts/catalog/" + part + "/applicability"),
                "{\"brandId\":" + brandId + "}")
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long fit = idOf(body);
        mvc.perform(delete("/api/parts/catalog/" + part + "/applicability/" + fit)
                        .with(csrf()).session(owner))
                .andExpect(status().isOk());

        journal("Применимость").andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].subject").value("Фара применимости"))
                .andExpect(jsonPath("$.items[0].action")
                        .value(containsString("Машина убрана из применимости: ")))
                .andExpect(jsonPath("$.items[1].action")
                        .value(containsString("Машина добавлена в применимость: ")));
    }

    @Test
    @DisplayName("Главный снимок — правка снимка позиции")
    void mainPhotoIsJournaled() throws Exception {
        long part = part("Фара со снимками", null);
        long photo = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part_photo (part_id, s3_key, sort_order, is_main, status)
                VALUES (?, 't_000969/parts/x.jpg', 1, false, 'PROCESSED') RETURNING id""",
                Long.class, part));
        mvc.perform(post("/api/parts/" + part + "/photos/" + photo + "/main")
                        .with(csrf()).session(owner))
                .andExpect(status().isNoContent());

        journal("Снимок товара").andExpect(jsonPath("$.items[0].subject").value("Фара со снимками"))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Главный снимок"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("да"));
    }

    // ----------------------------------------------------- продажи и справочники

    @Test
    @DisplayName("Клиент: заведён и поправлен — было → стало по полю, поиск находит по имени")
    void customerIsJournaled() throws Exception {
        String body = send(post("/api/customers"), "{\"name\":\"Гридин Журнальный\",\"phone\":\"111\"}")
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        long customer = idOf(body);
        send(put("/api/customers/" + customer),
                "{\"name\":\"Гридин Журнальный\",\"phone\":\"222\"}").andExpect(status().isOk());
        // Сохранение без правки строки не пишет.
        send(put("/api/customers/" + customer),
                "{\"name\":\"Гридин Журнальный\",\"phone\":\"222\"}").andExpect(status().isOk());

        assertThat(rows("customer", customer))
                .as("клиент: заведение и одна правка, повтор без правки строки не пишет").isEqualTo(2);
        journal("Клиент").andExpect(jsonPath("$.items[0].subject").value("Гридин Журнальный"))
                .andExpect(jsonPath("$.items[0].changes.length()").value(1))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Телефон"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("111"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("222"))
                .andExpect(jsonPath("$.items[1].action").value("Клиент заведён"));
        mvc.perform(get("/api/organization/audit").session(owner).param("query", "Гридин"))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @DisplayName("Источники платежа и сделки: заведение и архив")
    void sourcesAreJournaled() throws Exception {
        long paySource = idOf(send(post("/api/payment-sources"),
                "{\"name\":\"Касса журнала\",\"sourceType\":\"CASH\"}")
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(post("/api/payment-sources/" + paySource + "/archive").with(csrf()).session(owner))
                .andExpect(status().isOk());
        send(post("/api/deal-sources"), "{\"name\":\"Авито журнала\"}").andExpect(status().isCreated());

        journal("Источник платежа").andExpect(jsonPath("$.items[0].subject").value("Касса журнала"))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("В архиве"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("да"))
                .andExpect(jsonPath("$.items[1].action").value("Источник платежа заведён"));
        journal("Источник сделки").andExpect(jsonPath("$.items[0].action")
                .value("Источник сделки заведён"));
    }

    // -------------------------------------------------- организация и сотрудники

    @Test
    @DisplayName("Склад, ячейка, наличие под заказ — заведение и правка")
    void organizationIsJournaled() throws Exception {
        long warehouse = idOf(send(post("/api/organization/warehouses"),
                "{\"name\":\"Склад журнала\"}").andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        send(post("/api/organization/warehouses/" + warehouse + "/cells"),
                "{\"codes\":[\"Ж-01-1\"]}").andExpect(status().isCreated());
        send(put("/api/organization/warehouses/" + warehouse + "/availability"),
                "{\"availabilityNote\":\"под заказ\",\"orderDaysFrom\":2,\"orderDaysTo\":5}")
                .andExpect(status().isOk());

        journal("Склад").andExpect(jsonPath("$.items[0].subject").value("Склад журнала"))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Дней заказа до')].after")
                        .value("5"))
                .andExpect(jsonPath("$.items[1].action").value("Склад заведён"));
        journal("Ячейка").andExpect(jsonPath("$.items[0].subject").value("Склад журнала · Ж-01-1"));
    }

    @Test
    @DisplayName("Сотрудник: заведён, пароль сменён фактом, выключен — без хэша и значения")
    void memberIsJournaledWithoutSecrets() throws Exception {
        long member = idOf(send(post("/api/members"),
                "{\"login\":\"kladovschik\",\"password\":\"секрет-77aa\",\"role\":\"STOREKEEPER\","
                        + "\"displayName\":\"Кладовщик Журнала\"}")
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        send(post("/api/members/" + member + "/password"), "{\"password\":\"секрет-88bb\"}")
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/members/" + member + "/disable").with(csrf()).session(owner))
                .andExpect(status().isNoContent());

        journal("Сотрудник").andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].subject").value("Кладовщик Журнала"))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Работает"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("нет"))
                .andExpect(jsonPath("$.items[1].changes[0].label").value("Пароль"))
                .andExpect(jsonPath("$.items[1].changes[0].after").value("заменён"))
                .andExpect(jsonPath("$.items[2].action").value("Сотрудник заведён"));
        String stored = inTenant(() -> String.join(" ", jdbc.queryForList("""
                SELECT coalesce(old_value::text, '') || coalesce(new_value::text, '')
                  FROM audit_log WHERE table_name = 'tenant_member'""", String.class)));
        String hash = inTenant(() -> jdbc.queryForObject(
                "SELECT password_hash FROM tenant_member WHERE id = ?", String.class, member));
        assertThat(stored).as("секрет сотрудника попал в журнал").doesNotContain("77aa").doesNotContain("88bb").doesNotContain(hash);
    }

    @Test
    @DisplayName("Настройки компании: срок резервирования — было → стало")
    void companySettingsAreJournaled() throws Exception {
        send(put("/api/company/settings"), "{\"reservationDays\":3}").andExpect(status().isOk());
        send(put("/api/company/settings"), "{\"reservationDays\":7}").andExpect(status().isOk());

        journal("Настройки компании")
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Срок резервирования, дней"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("3"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("7"));
    }

    @Test
    @DisplayName("Печатные формы: реквизиты компании и блок склада — было → стало")
    void printSettingsAreJournaled() throws Exception {
        long warehouse = warehouse();
        send(put("/api/company/print-settings"), """
                {"extraText":"Гарантия 14 дней","clientSignature":true,"issuerSignature":false,
                 "legal":{"inn":"7701000000"},
                 "warehouses":[{"id":%d,"details":"ИП Петров"}]}""".formatted(warehouse))
                .andExpect(status().isOk());

        journal("Настройки компании")
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='Текст про гарантию и возврат')]"
                        + ".after").value("Гарантия 14 дней"))
                .andExpect(jsonPath("$.items[0].changes[?(@.label=='ИНН организации')].after")
                        .value("7701000000"));
        journal("Склад").andExpect(jsonPath("$.items[0].subject").value("Ткацкая"))
                .andExpect(jsonPath("$.items[0].changes[0].label")
                        .value("Реквизиты продавца для печати"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("ИП Петров"));
    }

    @Test
    @DisplayName("«Пересопоставить по справочнику»: вид детали у доведённой карточки — в журнале")
    void rematchJournalsCards() throws Exception {
        String kindName = jdbc.queryForObject(
                "SELECT name FROM catalog.part_kind ORDER BY id LIMIT 1", String.class);
        long name = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part_name (name, match_status) VALUES (?, 'UNMATCHED') RETURNING id""",
                Long.class, kindName));
        long card = part("Карточка пересопоставления", name);

        mvc.perform(post("/api/part-names/rematch").with(csrf()).session(owner))
                .andExpect(status().isOk());

        assertThat(rows("part", card)).as("доведённая карточка не попала в журнал").isEqualTo(1);
        journal("Товар").andExpect(jsonPath("$.items[?(@.subject=='Карточка пересопоставления')]"
                + ".changes[0].label").value("Вид детали"));
    }

    @Test
    @DisplayName("Меню вида вещи предлагает все новые виды")
    void kindMenuOffersNewKinds() throws Exception {
        mvc.perform(get("/api/organization/audit/values").param("column", "kind").session(owner))
                .andExpect(jsonPath("$[?(@=='Клиент')]").exists())
                .andExpect(jsonPath("$[?(@=='Сотрудник')]").exists())
                .andExpect(jsonPath("$[?(@=='Машина')]").exists())
                .andExpect(jsonPath("$[?(@=='Применимость')]").exists());
    }

    // ------------------------------------------------------------- помощники

    private long part(String title, Long partNameId) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, part_name_id)
                VALUES (1, ?, 1000, ?) RETURNING id""", Long.class, title, partNameId));
    }

    private long donor() {
        return inTenant(() -> jdbc.queryForObject(
                "INSERT INTO donor (brand_id) VALUES (?) RETURNING id", Long.class, brandId));
    }

    private long warehouse() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM warehouse WHERE name = 'Ткацкая'", Long.class));
    }

    private int rows(String table, long id) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE table_name = ? AND record_id = ?",
                Integer.class, table, id));
    }

    private ResultActions journal(String kind) throws Exception {
        return mvc.perform(get("/api/organization/audit").session(owner).param("kind", kind))
                .andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(csrf()).session(owner)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Поиском, а не срезом всей строки: MockMvc портит кириллицу (корневой CLAUDE.md). */
    private static long idOf(String body) {
        var found = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!found.find()) {
            throw new AssertionError("В ответе нет идентификатора: " + body);
        }
        return Long.parseLong(found.group(1));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"journal0325\",\"login\":\"vladelec\",\"password\":\"пароль\"}"))
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
