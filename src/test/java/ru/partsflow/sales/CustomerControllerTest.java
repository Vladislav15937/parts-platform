package ru.partsflow.sales;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Раздел «Клиенты»: карточка, поиск с балансом, правка.
 *
 * <p>До этой задачи клиента нельзя было поправить вовсе: {@code email},
 * {@code customer_type}, {@code inn}, {@code company_name}, {@code note}
 * и {@code public_note} лежали в схеме с самого начала, а заполнить их было
 * нечем — ровно тот случай из корневого {@code CLAUDE.md}, когда поле есть
 * в базе и недоступно человеку.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class CustomerControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000117";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalesService sales;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 117");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (117, ?, 'Разборка', 'customersco')""", TENANT);

        inTenant(() -> {
            member("owner", "Хозяин", "OWNER");
            member("manager", "Менеджер", "MANAGER");
            member("seller", "Продавец", "SELLER");
            member("storekeeper", "Кладовщик", "STOREKEEPER");
            member("viewer", "Смотрящий", "VIEWER");
            return null;
        });
    }

    @Test
    @DisplayName("Карточка отдаёт все поля, включая примечание, заметку и юрлицо")
    void detailCarriesAllFields() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO customer (name, phone, email, customer_type, note, public_note,
                                       inn, company_name)
                VALUES ('Автосервис у Петра', '+79991234567', 'petr@example.com', 'COMPANY',
                        'должен перезвонить', 'заберёт сам', '7701234567', 'ООО Пётр')
                RETURNING id""", Long.class));

        mvc.perform(get("/api/customers/" + id).session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Автосервис у Петра"))
                .andExpect(jsonPath("$.customerType").value("COMPANY"))
                .andExpect(jsonPath("$.note").value("должен перезвонить"))
                .andExpect(jsonPath("$.publicNote").value("заберёт сам"))
                .andExpect(jsonPath("$.inn").value("7701234567"))
                .andExpect(jsonPath("$.companyName").value("ООО Пётр"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    @DisplayName("Несуществующий клиент отвечает словами, а не пустой карточкой")
    void unknownCustomerRefused() throws Exception {
        mvc.perform(get("/api/customers/999999").session(login("owner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("не найден")));
    }

    @Test
    @DisplayName("Директория показывает баланс, посчитанный по журналу")
    void directoryShowsAccountBalance() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент с авансом') RETURNING id", Long.class));
        Long managerId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'owner'", Long.class));
        inTenant(() -> sales.topUpAccount(id, new java.math.BigDecimal("1500"), null, managerId));

        mvc.perform(get("/api/customers/directory?q=" + "Клиент с авансом").session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].balance").value(1500));
    }

    /**
     * «Баланс» в списке обязан уметь стать отрицательным, иначе пункт 7
     * критерия приёмки («клиент с долгом показывает отрицательный баланс
     * красным») недостижим ни при каком состоянии системы: ни одна операция
     * со счётом (`top-up`, `withdraw`, `correct`) не уводит его журнал
     * в минус — это отдельный, проверенный инвариант. Долг приходит только
     * от выданной и не оплаченной целиком сделки.
     */
    @Test
    @DisplayName("Клиент с долгом по выданной сделке показывает отрицательный баланс")
    void directoryShowsDebtAsNegativeBalance() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент с долгом') RETURNING id", Long.class));
        Long managerId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'owner'", Long.class));
        inTenant(() -> sales.topUpAccount(id, new java.math.BigDecimal("300"), null, managerId));
        // Сделка выдана и оплачена частично — деталь у клиента, 700 ₽ он ещё
        // должен. Заводится прямой записью, а не через полный путь продажи:
        // здесь важно только итоговое состояние документа, а не то, как
        // до него дошли (это уже проверено в SalesControllerTest/DealConcurrencyTest).
        inTenant(() -> jdbc.update("""
                INSERT INTO deal (customer_id, manager_id, status, total_amount, paid_amount)
                VALUES (?, ?, 'ISSUED', 1000, 300)""", id, managerId));

        mvc.perform(get("/api/customers/directory?q=" + "Клиент с долгом").session(login("owner")))
                .andExpect(status().isOk())
                // Аванс 300 минус долг 700 — минус 400, а не минус 700
                // и не плюс 300: обе величины складываются в одну позицию.
                .andExpect(jsonPath("$.items[0].balance").value(-400));

        mvc.perform(get("/api/customers/" + id).session(login("owner")))
                .andExpect(jsonPath("$.balance").value(-400));
    }

    @Test
    @DisplayName("Директория считает найденное отдельно от предела выдачи")
    void directorySizeLimitsRowsNotTotal() throws Exception {
        String tag = "директория-предел-58120";
        for (int i = 0; i < 3; i++) {
            inTenant(() -> jdbc.update(
                    "INSERT INTO customer (name) VALUES (?)", tag + "-" + java.util.UUID.randomUUID()));
        }
        // Имя не совпадёт дословно из-за случайного хвоста, поэтому отбираем
        // не по нему: считаем, что "size" режет строки при заведомо большем
        // числе клиентов, чем предел, взяв малый лимит и общий подсчёт.
        mvc.perform(get("/api/customers/directory?size=1").session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("Правка сохраняет примечание и заметку в разные поля, а не путает их")
    void updateKeepsPublicNoteAndNoteApart() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент без данных') RETURNING id", Long.class));

        mvc.perform(put("/api/customers/" + id).with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Клиент дополненный","phone":"89261112233",
                                 "email":"client@example.com",
                                 "publicNote":"Выводится клиенту",
                                 "note":"Заметка только для нас",
                                 "customerType":"PERSON"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicNote").value("Выводится клиенту"))
                .andExpect(jsonPath("$.note").value("Заметка только для нас"));

        // Перепутать местами значит напечатать клиенту чужую заметку —
        // проверяется прямым чтением из базы, а не только ответом контроллера.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT public_note FROM customer WHERE id = ?", String.class, id)))
                .isEqualTo("Выводится клиенту");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT note FROM customer WHERE id = ?", String.class, id)))
                .isEqualTo("Заметка только для нас");
    }

    @Test
    @DisplayName("Включённый тумблер переводит клиента в юрлицо")
    void updateTogglesCompanyType() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Частник') RETURNING id", Long.class));

        mvc.perform(put("/api/customers/" + id).with(csrf()).session(login("manager"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ООО Частник","customerType":"COMPANY",
                                 "inn":"7712345678","companyName":"ООО Частник"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerType").value("COMPANY"))
                .andExpect(jsonPath("$.inn").value("7712345678"))
                .andExpect(jsonPath("$.companyName").value("ООО Частник"));

        mvc.perform(get("/api/customers/directory?q=ООО Частник").session(login("manager")))
                .andExpect(jsonPath("$.items[0].customerType").value("COMPANY"));
    }

    @Test
    @DisplayName("Правка без имени отклоняется словами")
    void updateRequiresName() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент') RETURNING id", Long.class));

        mvc.perform(put("/api/customers/" + id).with(csrf()).session(login("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Продавец карточку видит, но не правит")
    void sellerReadsButCannotUpdate() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент продавца') RETURNING id", Long.class));

        mvc.perform(get("/api/customers/" + id).session(login("seller")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/customers/" + id).with(csrf()).session(login("seller"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Попытка продавца\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Кладовщик и «Просмотр» раздел «Клиенты» не видят")
    void directoryHiddenFromStorekeeperAndViewer() throws Exception {
        mvc.perform(get("/api/customers/directory").session(login("storekeeper")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/directory").session(login("viewer")))
                .andExpect(status().isForbidden());
    }

    /**
     * Вкладка «Движения по счёту» карточки клиента показывает журнал целиком,
     * а не восемь последних, как экран продавца ({@code slice(0, 8)}
     * в {@code SellerScreen}). Проверяется на сервере: сам эндпоинт не режет
     * список, дальше это дело фронтенда.
     */
    @Test
    @DisplayName("Журнал счёта не обрезается до восьми записей")
    void accountEntriesAreNotTruncatedToEight() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент с десятью движениями') RETURNING id",
                Long.class));
        Long managerId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'owner'", Long.class));
        for (int i = 0; i < 10; i++) {
            int amount = i + 1;
            inTenant(() -> sales.correctAccount(
                    id, new java.math.BigDecimal(amount), "движение " + amount, managerId));
        }

        mvc.perform(get("/api/customers/" + id + "/account").session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(10));
    }

    /**
     * «Кто» и «По сделке» в движениях по счёту — имя и номер документа,
     * а не идентификаторы: «автор 3» и «сделка 7» не говорят ничего тому,
     * кто разбирает журнал через месяц.
     */
    @Test
    @DisplayName("Движение по счёту несёт имя автора и номер сделки")
    void accountEntryCarriesAuthorNameAndDealNumber() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO customer (name) VALUES ('Клиент с оплатой') RETURNING id", Long.class));
        Long managerId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'manager'", Long.class));
        Long dealId = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO deal (customer_id, manager_id) VALUES (?, ?) RETURNING id",
                Long.class, id, managerId));
        Long dealNumber = inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM deal WHERE id = ?", Long.class, dealId));
        inTenant(() -> jdbc.update("""
                INSERT INTO customer_account_entry
                    (customer_id, entry_type, amount, deal_id, created_by)
                VALUES (?, 'DEAL_PAYMENT', 100, ?, ?)""", id, dealId, managerId));

        mvc.perform(get("/api/customers/" + id + "/account").session(login("manager")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].authorName").value("Менеджер"))
                .andExpect(jsonPath("$.entries[0].dealNumber").value(dealNumber));
    }

    @Test
    @DisplayName("Заведённый через CustomerPicker клиент открывается карточкой")
    void pickerCreatedCustomerOpensAsCard() throws Exception {
        Long id = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO customer (name, phone, customer_type)
                VALUES ('Без имени', '9261234567', 'PERSON') RETURNING id""", Long.class));

        mvc.perform(get("/api/customers/" + id).session(login("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Без имени"))
                .andExpect(jsonPath("$.note").value(nullValue()));
    }

    /** Заводит сотрудника, если его ещё нет, и возвращает идентификатор. */
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
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"customersco","login":"%s","password":"пароль"}"""
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
