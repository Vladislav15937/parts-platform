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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Журнал действий организации: кто, что и когда.
 *
 * <p>Через HTTP, а не вызовом сервиса: половина этой задачи — про то, кому
 * журнал показан и кому отказано, а роль проверяется аннотацией на методе.
 * Плюс ответ уходит наружу record'ами, и «No acceptable representation»
 * виден только настоящему запросу.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class OrganizationAuditTest extends PostgresTestBase {

    private static final String TENANT = "t_000119";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long partId;
    private Long managerId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 119");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (119, ?, 'Разборка', 'revizco')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Пётр Владельцев", "OWNER");
            managerId = member("ivanov", "Иванов", "MANAGER");
            member("prodavec", "Сидоров", "SELLER");
            member("revizor", "Ревизоров", "AUDITOR");
            member("smotritel", "Смотрителев", "VIEWER");

            jdbc.update("DELETE FROM audit_log");
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price)
                    VALUES (1, 'Фара Toyota Camry 2006 перед. лев. (б/у)', 5000)
                    RETURNING id""", Long.class);
            // Роль сотрудника возвращается к исходной: соседний тест мог
            // перевести Иванова в продавцы, а порядок методов не гарантирован.
            jdbc.update("UPDATE tenant_member SET role = 'MANAGER' WHERE id = ?", managerId);
            return null;
        });
    }

    /**
     * То, ради чего задача и заведена: словами человека, а не полями таблицы.
     *
     * <p>Дословная формулировка владельца продукта — «Иванов изменил цену
     * „Фара Toyota Camry“ с 5000 на 4500», — и проверяются здесь именно эти
     * пять вещей: кто, что за вещь, каким полем, с чего и на что.
     */
    @Test
    @DisplayName("Владелец видит, кто уронил цену, у какой детали и с чего на что")
    void ownerSeesWhoChangedThePrice() throws Exception {
        changePrice("ivanov", "4500");

        mvc.perform(get("/api/organization/audit").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].author").value("Иванов"))
                .andExpect(jsonPath("$.items[0].kind").value("Товар"))
                .andExpect(jsonPath("$.items[0].subject")
                        .value("Фара Toyota Camry 2006 перед. лев. (б/у)"))
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Цена"))
                .andExpect(jsonPath("$.items[0].changes[0].before").value("5000"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("4500"));
    }

    /**
     * Роль пишется снимком, и это не придирка.
     *
     * <p>Сотрудника переводят из менеджеров в продавцы, и «менеджер уронил
     * цену» обязано остаться правдой: подтянутая при чтении роль соврала бы
     * про человека задним числом, при полностью исправном журнале.
     */
    @Test
    @DisplayName("Роль в журнале — та, что была на момент правки, а не сегодняшняя")
    void roleIsRecordedAtTheTimeOfTheChange() throws Exception {
        changePrice("ivanov", "4500");

        inTenant(() -> jdbc.update(
                "UPDATE tenant_member SET role = 'SELLER' WHERE id = ?", managerId));

        mvc.perform(get("/api/organization/audit").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].author").value("Иванов"))
                .andExpect(jsonPath("$.items[0].authorRole").value("MANAGER"));
    }

    /**
     * Снятая запись автора обязана дать пустоту, а не догадку.
     *
     * <p>Доказательство откатом, названное задачей дословно: подставленный
     * автор здесь хуже отсутствующего — он выглядит как ответ на вопрос
     * «кто», будучи догадкой. Правка без вошедшего (перенос, фоновая задача,
     * прямой SQL) обязана приехать на экран прочерком.
     *
     * <p>Записывается она тем же путём, что и настоящая, — вставкой
     * в {@code audit_log} без автора и без роли: именно так лежат все
     * 141 955 записей переехавшего клиента.
     */
    @Test
    @DisplayName("Правка без записанного автора показана пустотой, а не текущим пользователем")
    void missingAuthorIsShownEmpty() throws Exception {
        inTenant(() -> jdbc.update("""
                INSERT INTO audit_log (table_name, record_id, operation, old_value, new_value)
                VALUES ('part', ?, 'UPDATE', '{"price": 5000}', '{"price": 4500}')""", partId));

        mvc.perform(get("/api/organization/audit").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].author").doesNotExist())
                .andExpect(jsonPath("$.items[0].authorRole").doesNotExist())
                .andExpect(jsonPath("$.items[0].changes[0].label").value("Цена"));
    }

    /** Тот, кому владелец разрешил, — читает. */
    @Test
    @DisplayName("Ревизор журнал видит")
    void auditorReadsTheJournal() throws Exception {
        mvc.perform(get("/api/organization/audit").session(login("revizor")))
                .andExpect(status().isOk());
    }

    /**
     * Обратная сторона обязательна: снятие проверки даёт {@code 403 → 200}.
     *
     * <p>Роли берутся заведомо разные — тот, кто продаёт, тот, кто ведёт
     * склад и деньги, и тот, кому названо «только смотреть»: журнал заводят
     * в том числе затем, чтобы проверять распоряжающихся, и менеджер здесь
     * не исключение, а главный случай.
     */
    @Test
    @DisplayName("Кому владелец не разрешал — журнала не видит")
    void othersAreRefused() throws Exception {
        for (String login : java.util.List.of("prodavec", "ivanov", "smotritel")) {
            mvc.perform(get("/api/organization/audit").session(login(login)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/organization/audit/values").param("column", "author")
                            .session(login(login)))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * Отбор — тем же механизмом, что на витрине склада: список отбираемых
     * колонок приходит с сервера, значения — отдельным запросом.
     */
    @Test
    @DisplayName("Меню колонки берёт значения с сервера, а не из копии во фронтенде")
    void columnMenuIsFedByTheServer() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(get("/api/organization/audit").session(session))
                .andExpect(jsonPath("$.filterable").isArray())
                .andExpect(jsonPath("$.filterable[0]").value("author"));

        mvc.perform(get("/api/organization/audit/values").param("column", "kind")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Товар"));

        mvc.perform(get("/api/organization/audit/values").param("column", "author")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem("Иванов")));
    }

    /** Отбор по человеку и по вещи — то, что владелец назвал дословно. */
    @Test
    @DisplayName("Отбор по человеку и по вещи сужает журнал")
    void filtersNarrowTheJournal() throws Exception {
        changePrice("ivanov", "4500");
        MockHttpSession session = login("vladelec");

        mvc.perform(get("/api/organization/audit").param("author", "Иванов").session(session))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/organization/audit").param("author", "Сидоров").session(session))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/organization/audit").param("q", "Camry").session(session))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/organization/audit").param("q", "Бампер").session(session))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/organization/audit").param("field", "Цена").session(session))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/organization/audit").param("field", "Заметка").session(session))
                .andExpect(jsonPath("$.total").value(0));
    }

    /** Неразобранная дата — ошибка запроса, а не поломка сервера. */
    @Test
    @DisplayName("Кривая дата периода отвечает четырёхсотым, а не пятисоткой")
    void badDateIsFourHundred() throws Exception {
        mvc.perform(get("/api/organization/audit").param("from", "вчера")
                        .session(login("vladelec")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- служебное

    private void changePrice(String login, String price) throws Exception {
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(login(login))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":%s,\"published\":false}".formatted(price)))
                .andExpect(status().isOk());
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"revizco","login":"%s","password":"пароль"}"""
                                .formatted(login)))
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
