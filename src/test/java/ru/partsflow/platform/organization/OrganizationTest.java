package ru.partsflow.platform.organization;

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
import ru.partsflow.platform.tenant.TenantProvisioning;
import ru.partsflow.support.PostgresTestBase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Структура склада через API.
 *
 * <p>Проверяется то, что вскрыл сквозной прогон: свежий арендатор был пуст,
 * и завести склад можно было только запросом в базу. Поэтому главная проверка
 * тут — «подключили клиента и сразу приняли деталь», без единого psql.
 */
@SpringBootTest(properties = "app.provisioning-token=секрет-структуры")
@AutoConfigureMockMvc
class OrganizationTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantProvisioning provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("У подключённого клиента сразу есть склад")
    void provisioningCreatesFirstWarehouse() throws Exception {
        String code = tenant("Разборка на Ткацкой");

        // Провижининг, после которого нужен ещё запрос в базу, ничем не лучше
        // прежних четырёх шагов руками.
        mvc.perform(get("/api/organization/warehouses").session(login(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Основной"))
                .andExpect(jsonPath("$[0].branchName").value("Разборка на Ткацкой"));
    }

    @Test
    @DisplayName("Клиент принимает деталь сразу после подключения")
    void newTenantCanReceiveParts() throws Exception {
        String code = tenant("Вторая разборка");
        MockHttpSession session = login(code);

        long warehouseId = Long.parseLong(mvc.perform(
                        get("/api/organization/warehouses").session(session))
                .andReturn().getResponse().getContentAsString()
                .replaceAll("^\\[\\{\"id\":(\\d+).*$", "$1"));

        mvc.perform(post("/api/organization/warehouses/" + warehouseId + "/cells")
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"А-01-1\",\"А-01-2\",\"А-01-3\"],\"zone\":\"Стеллаж А\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3));

        // Весь путь без единого запроса в базу — ради этого всё и делалось.
        mvc.perform(post("/api/intake/receipts").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"requestId":"%s",
                                 "items":[{"rawName":"фара","quantity":1,"price":9500}]}"""
                                .formatted(warehouseId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parts.length()").value(1));
    }

    @Test
    @DisplayName("Повторная ячейка не ломает заведение стеллажа")
    void duplicateCellIsSkipped() throws Exception {
        String code = tenant("Третья разборка");
        MockHttpSession session = login(code);
        long warehouseId = warehouseOf(session);

        mvc.perform(cells(warehouseId, session, "[\"Б-01-1\"]")).andExpect(status().isCreated());

        // Список адресов набирают руками, и одна повторённая строка не повод
        // отменять заведение всего стеллажа.
        mvc.perform(cells(warehouseId, session, "[\"Б-01-1\",\"Б-01-2\"]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("Б-01-2"));
    }

    @Test
    @DisplayName("Склад заводится без указания филиала, пока он один")
    void branchIsOptionalWhileSole() throws Exception {
        String code = tenant("Четвёртая разборка");
        MockHttpSession session = login(code);

        mvc.perform(post("/api/organization/warehouses").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Контейнерная площадка\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Контейнерная площадка"));
    }

    /**
     * Чужие филиал и склад отбиваются словами, а не ограничением схемы.
     *
     * <p>Роль сотрудника проверяется белым списком и отказывает внятно,
     * а чужой филиал и чужой склад доезжали до внешнего ключа: «Операция
     * нарушает целостность данных». Владелец, заводящий склад или стеллаж,
     * по такому ответу идёт искать поломку сервера вместо того, чтобы
     * посмотреть, что он выбрал.
     */
    @Test
    @DisplayName("Чужой филиал и чужой склад отказывают словами")
    void unknownReferencesAreRefusedWithWords() throws Exception {
        String code = tenant("Шестая разборка");
        MockHttpSession session = login(code);

        mvc.perform(post("/api/organization/warehouses").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Склад\",\"branchId\":999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Филиал не найден")));

        mvc.perform(post("/api/organization/warehouses/999999/cells").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codes\":[\"А-01-1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Склад не найден")));

        // Сотрудник в несуществующем филиале — то же самое.
        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"chuzhoy","password":"пароль-подлиннее",
                                 "role":"SELLER","branchId":999999}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Филиал не найден")));
    }

    /**
     * Текст наличия склада и вилка дней заказа задаются через API (задача 0008).
     *
     * <p>Проверяется через HTTP, а не вызовом службы: ответ наружу — запись,
     * и обычный класс Jackson не сериализует вовсе.
     */
    @Test
    @DisplayName("Наличие склада задаётся, пустое становится «не задано»")
    void warehouseAvailabilityIsStored() throws Exception {
        String code = tenant("Разборка с двумя складами");
        MockHttpSession session = login(code);
        long warehouseId = warehouseOf(session);

        mvc.perform(availability(warehouseId, session,
                        "{\"availabilityNote\":\"под заказ\",\"orderDaysFrom\":2,\"orderDaysTo\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availabilityNote").value("под заказ"))
                .andExpect(jsonPath("$.orderDaysFrom").value(2))
                .andExpect(jsonPath("$.orderDaysTo").value(4));

        // И видно это в списке, а не только в ответе на запись: иначе владелец
        // с тремя складами не знает, у какого из них срок уже задан.
        mvc.perform(get("/api/organization/warehouses").session(session))
                .andExpect(jsonPath("$[0].availabilityNote").value("под заказ"))
                .andExpect(jsonPath("$[0].orderDaysTo").value(4));

        // Ноль дней — значение, а не пустота: «забрать можно сегодня».
        mvc.perform(availability(warehouseId, session,
                        "{\"availabilityNote\":\"в наличии\",\"orderDaysFrom\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderDaysFrom").value(0))
                .andExpect(jsonPath("$.orderDaysTo").value(org.hamcrest.Matchers.nullValue()));

        // А вычищенный руками текст приезжает пустой строкой, и записать её
        // значило бы выгружать в объявление пустое наличие. Пусто — NULL.
        mvc.perform(availability(warehouseId, session,
                        "{\"availabilityNote\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availabilityNote")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * Перевёрнутая вилка и отрицательные дни отказывают словами.
     *
     * <p>`CHECK` в схеме — последний рубеж, и приезжает он отказом базы
     * без слов о том, что не так: владелец пошёл бы искать поломку сервера.
     */
    @Test
    @DisplayName("Перевёрнутая вилка дней отказывает словами, а не отказом базы")
    void brokenLeadTimeIsRefusedWithWords() throws Exception {
        String code = tenant("Разборка с вилкой наоборот");
        MockHttpSession session = login(code);
        long warehouseId = warehouseOf(session);

        mvc.perform(availability(warehouseId, session,
                        "{\"orderDaysFrom\":4,\"orderDaysTo\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("меньше нижней")));

        mvc.perform(availability(warehouseId, session, "{\"orderDaysFrom\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("меньше нуля")));

        // И склада, которого нет, это тоже касается: номер строки базы
        // человеку не говорит ничего.
        mvc.perform(availability(999999, session, "{\"availabilityNote\":\"есть\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Склад не найден")));
    }

    /**
     * Наличие правит только владелец.
     *
     * <p>Решение исполнителя: экран «Склады» показан владельцу и больше никому
     * (`screens/tabs.ts`, `OWNER_ONLY`), а право, которым не воспользоваться
     * ни с одного экрана, читается следующим как готовый путь. Расширить потом
     * безопасно, сузить обратно — нет. Поэтому менеджер, заводящий склады,
     * текст наличия не правит.
     */
    @Test
    @DisplayName("Менеджер склады заводит, а наличие в объявлении не правит")
    void onlyOwnerSetsAvailability() throws Exception {
        String code = tenant("Разборка с менеджером");
        MockHttpSession owner = login(code);
        long warehouseId = warehouseOf(owner);
        String schema = jdbc.queryForObject(
                "SELECT schema_name FROM public.tenant_registry WHERE code = ?", String.class, code);
        jdbc.update("""
                INSERT INTO %s.tenant_member (display_name, role, login, password_hash)
                VALUES ('Менеджер', 'MANAGER', 'menedzher', ?)""".formatted(schema),
                passwordEncoder.encode("пароль-8симв"));
        MockHttpSession manager = login(code, "menedzher");

        mvc.perform(post("/api/organization/warehouses").with(csrf()).session(manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Склад менеджера\"}"))
                .andExpect(status().isCreated());

        mvc.perform(availability(warehouseId, manager, "{\"availabilityNote\":\"есть\"}"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.RequestBuilder availability(
            long warehouseId, MockHttpSession session, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/organization/warehouses/" + warehouseId + "/availability")
                .with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    @DisplayName("Продавец склады не заводит")
    void sellerCannotCreateWarehouse() throws Exception {
        String code = tenant("Пятая разборка");
        MockHttpSession owner = login(code);
        String schema = jdbc.queryForObject(
                "SELECT schema_name FROM public.tenant_registry WHERE code = ?", String.class, code);
        jdbc.update("""
                INSERT INTO %s.tenant_member (display_name, role, login, password_hash)
                VALUES ('Продавец', 'SELLER', 'prodavec', ?)""".formatted(schema),
                passwordEncoder.encode("пароль-8симв"));

        mvc.perform(post("/api/organization/warehouses").with(csrf())
                        .session(login(code, "prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Свой склад\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/organization/warehouses").session(owner))
                .andExpect(jsonPath("$.length()").value(1));
    }

    private org.springframework.test.web.servlet.RequestBuilder cells(
            long warehouseId, MockHttpSession session, String codes) {
        return post("/api/organization/warehouses/" + warehouseId + "/cells")
                .with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codes\":" + codes + "}");
    }

    private long warehouseOf(MockHttpSession session) throws Exception {
        return Long.parseLong(mvc.perform(get("/api/organization/warehouses").session(session))
                .andReturn().getResponse().getContentAsString()
                .replaceAll("^\\[\\{\"id\":(\\d+).*$", "$1"));
    }

    private String tenant(String companyName) {
        String code = "org" + UUID.randomUUID().toString().substring(0, 8);
        provisioning.provision(new TenantProvisioning.Request(
                code, companyName, "vladelec", "пароль-8симв", "Владелец"));
        return code;
    }

    private MockHttpSession login(String code) throws Exception {
        return login(code, "vladelec");
    }

    private MockHttpSession login(String code, String user) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"пароль-8симв"}"""
                                .formatted(code, user)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
