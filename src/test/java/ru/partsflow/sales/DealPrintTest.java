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
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Печатные формы сделки: чек, накладная, счёт (задача 0051).
 *
 * <p><b>Главная проверка здесь — {@link #requisitesComeFromTheIssuingWarehouse()}:</b>
 * две сделки с разных складов обязаны напечатать <b>разных</b> продавцов.
 * У живого клиента ориентира на двух складах стоят разные ИП, и это и есть
 * довод задачи; проверка «в документе есть реквизиты» прошла бы и на общем
 * блоке, то есть не сторожила бы ничего — а покупатель, пришедший по гарантии,
 * предъявил бы чек чужого предпринимателя.
 *
 * <p>Своя схема: реквизиты и настройки печати принадлежат арендатору, и сосед,
 * поменявший их на свои, менял бы их и здесь.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DealPrintTest extends PostgresTestBase {

    private static final String TENANT = "t_000161";

    private static final long TENANT_ID = 161;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    private Long tkatskaya;
    private Long dalniy;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка', 'pechat')""", TENANT_ID, TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("menedzher", "Менеджер", "MANAGER");
            member("prodavec", "Продавец", "SELLER");

            // Настройки печати между тестами класса не копятся: каждый из них
            // рассчитывает на то, что сам же и задал, а поле у настройки одно
            // на схему.
            jdbc.update("""
                    UPDATE company_setting
                       SET print_extra_text = NULL, print_vat_note = NULL,
                           print_client_signature = true, print_issuer_signature = false,
                           legal_name = NULL, legal_inn = NULL, legal_kpp = NULL,
                           legal_bank_name = NULL, legal_bank_account = NULL""");

            Long branch = soleId("SELECT id FROM branch ORDER BY id LIMIT 1");
            if (branch == null) {
                branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            }
            tkatskaya = warehouse(branch, "Ткацкая");
            dalniy = warehouse(branch, "Дальний");

            // Разные ИП на двух складах — то, что стоит у живого клиента
            // ориентира, и ровно то, из-за чего реквизиты задаются на склад.
            jdbc.update("UPDATE warehouse SET print_details = ? WHERE id = ?",
                    "ИП Санин Д.В.\nБарнаул, Ткацкая 626\nтел. +7 3852 00-00-00", tkatskaya);
            jdbc.update("UPDATE warehouse SET print_details = ? WHERE id = ?",
                    "ИП Иванов И.В.\nОмск, проспект Дзержинского 102", dalniy);

            customer = soleId("SELECT id FROM customer WHERE name = 'Автосервис на Русской'");
            if (customer == null) {
                customer = jdbc.queryForObject("""
                        INSERT INTO customer (name, phone, inn, company_name, public_note,
                                              customer_type)
                        VALUES ('Автосервис на Русской', '+79990001122', '2222333344',
                                'ООО «Автосервис»', 'Отгрузка по доверенности', 'COMPANY')
                        RETURNING id""", Long.class);
            }
            return null;
        });
    }

    /**
     * Пункт 4 критерия приёмки, проверенный **разницей**, а не наличием.
     *
     * <p>Заводятся два склада с разными блоками реквизитов и две сделки —
     * по одной с каждого, — и требуется, чтобы продавец в документах
     * различался. Утверждение «реквизиты не пусты» было бы положительным
     * и прошло бы на общем для всей компании блоке, то есть ровно на том
     * дефекте, который задача и закрывает.
     */
    @Test
    @DisplayName("Чек берёт реквизиты того склада, с которого выдача")
    void requisitesComeFromTheIssuingWarehouse() throws Exception {
        long first = createDeal(part(tkatskaya, "Фара левая для чека"), tkatskaya);
        long second = createDeal(part(dalniy, "Бампер для накладной"), dalniy);

        mvc.perform(get("/api/deals/" + first + "/print").session(login("prodavec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.warehouseName").value("Ткацкая"))
                .andExpect(jsonPath("$.seller.details").value(containsString("ИП Санин")))
                .andExpect(jsonPath("$.seller.problem").doesNotExist());

        mvc.perform(get("/api/deals/" + second + "/print").session(login("prodavec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.warehouseName").value("Дальний"))
                .andExpect(jsonPath("$.seller.details").value(containsString("ИП Иванов")));
    }

    /**
     * Пункт 3: в документе есть всё, из чего он состоит.
     *
     * <p>Проверяется списком, а не по одному полю: «что-то в ответе есть»
     * прошло бы и на документе без покупателя или без долга — а это как раз
     * то, что покупатель читает первым.
     */
    @Test
    @DisplayName("В документе есть номер, дата, покупатель, позиции, итог, оплачено и долг")
    void documentCarriesEverythingItPrints() throws Exception {
        Long partId = part(tkatskaya, "Стартер для состава чека");
        long dealId = createDeal(partId, tkatskaya);

        mvc.perform(post("/api/deals/" + dealId + "/payments").with(csrf())
                        .session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":2000}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/deals/" + dealId + "/print").session(login("prodavec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").isNumber())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.buyer.name").value("Автосервис на Русской"))
                .andExpect(jsonPath("$.buyer.companyName").value("ООО «Автосервис»"))
                // Примечание клиенту обещано «при печати накладных» подписью
                // поля в карточке клиента — до этой задачи печати не было вовсе.
                .andExpect(jsonPath("$.buyer.note").value("Отгрузка по доверенности"))
                .andExpect(jsonPath("$.lines[0].title").value("Стартер для состава чека"))
                .andExpect(jsonPath("$.lines[0].amount").value(5000.00))
                .andExpect(jsonPath("$.total").value(5000.00))
                .andExpect(jsonPath("$.paid").value(2000.00))
                .andExpect(jsonPath("$.debt").value(3000.00));
    }

    /**
     * Пункт 5: счёт выставляет организация, и её реквизиты в документе —
     * не блок склада.
     *
     * <p>Сервер отдаёт обе половины одним ответом (формы различаются вёрсткой),
     * поэтому здесь проверяется, что реквизиты организации приезжают
     * <b>своими</b> и не подменяются блоком склада. Что именно печатает форма
     * «Счёт на юр. лицо», стережёт `dealPrint.test.tsx` на стороне экрана.
     */
    @Test
    @DisplayName("Счёт берёт реквизиты организации, а не блок склада")
    void invoiceTakesOrganizationRequisites() throws Exception {
        mvc.perform(put("/api/company/print-settings").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legal":{"name":"ООО «Разборка»","inn":"7701234567",
                                          "kpp":"770101001","bankName":"Сбербанк",
                                          "bankAccount":"40702810000000000001",
                                          "director":"Петров П.П."},
                                 "clientSignature":true,"issuerSignature":false,
                                 "warehouses":[]}"""))
                .andExpect(status().isOk());

        long dealId = createDeal(part(tkatskaya, "Диск для счёта"), tkatskaya);

        mvc.perform(get("/api/deals/" + dealId + "/print").session(login("prodavec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legal.name").value("ООО «Разборка»"))
                .andExpect(jsonPath("$.legal.inn").value("7701234567"))
                .andExpect(jsonPath("$.legal.kpp").value("770101001"))
                .andExpect(jsonPath("$.legal.bankAccount").value("40702810000000000001"))
                .andExpect(jsonPath("$.legal.director").value("Петров П.П."))
                // Блок склада остался своим: подмена одного другим — это
                // и есть тот дефект, от которого спасают два уровня реквизитов.
                .andExpect(jsonPath("$.seller.details").value(containsString("ИП Санин")));
    }

    /**
     * Пункт 6: пустая пометка об НДС не печатает строку.
     *
     * <p>Проверяется с двух сторон одним методом: заданная приезжает, снятая
     * приезжает <b>пустой</b>, а не пустой строкой. Пустая строка в колонке
     * дала бы в документе пустую строку — ту же природу уже разбирали
     * у снятого штрихкода и у нулевой цены установки.
     */
    @Test
    @DisplayName("Пометка об НДС приезжает только заданной, снятая — пусто")
    void vatNoteIsPrintedOnlyWhenSet() throws Exception {
        MockHttpSession owner = login("vladelec");
        long dealId = createDeal(part(tkatskaya, "Фара для НДС"), tkatskaya);

        mvc.perform(put("/api/company/print-settings").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vatNote":"в том числе НДС 5% - 250 руб.",
                                 "extraText":"Гарантия 14 календарных дней",
                                 "clientSignature":true,"issuerSignature":false,
                                 "warehouses":[]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vatNote").value("в том числе НДС 5% - 250 руб."));

        mvc.perform(get("/api/deals/" + dealId + "/print").session(login("prodavec")))
                .andExpect(jsonPath("$.vatNote").value("в том числе НДС 5% - 250 руб."))
                .andExpect(jsonPath("$.extraText").value("Гарантия 14 календарных дней"));

        // Снятая пометка — это NULL, а не пустая строка: иначе документ
        // напечатал бы пустую строку про НДС.
        mvc.perform(put("/api/company/print-settings").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vatNote":"   ","clientSignature":true,
                                 "issuerSignature":false,"warehouses":[]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vatNote").doesNotExist());

        mvc.perform(get("/api/deals/" + dealId + "/print").session(login("prodavec")))
                .andExpect(jsonPath("$.vatNote").doesNotExist());
    }

    /**
     * Пункт 7: настройку правит владелец, печатают все, кто работает
     * со сделкой.
     *
     * <p>Проверяются обе половины: отказ настройке и <b>успех</b> печати. Одна
     * половина без другой проходит тривиально и неправильно — закрой печать
     * теми же ролями, и «менеджер и продавец печатают» перестало бы
     * выполняться при зелёном тесте на 403.
     */
    @Test
    @DisplayName("Настройку печати меняет только владелец, а печатают и менеджер, и продавец")
    void ownerSetsPrintingAndEveryoneElsePrints() throws Exception {
        long dealId = createDeal(part(tkatskaya, "Фара для прав"), tkatskaya);

        for (String login : new String[]{"menedzher", "prodavec"}) {
            MockHttpSession session = login(login);
            mvc.perform(get("/api/company/print-settings").session(session))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/company/print-settings").with(csrf()).session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"vatNote":"чужая пометка","clientSignature":true,
                                     "issuerSignature":false,"warehouses":[]}"""))
                    .andExpect(status().isForbidden());

            mvc.perform(get("/api/deals/" + dealId + "/print").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.seller.details").value(containsString("ИП Санин")));
        }

        mvc.perform(get("/api/company/print-settings").session(login("vladelec")))
                .andExpect(status().isOk());
    }

    /**
     * Позиции с разных складов не дают реквизитов — и причина названа словами.
     *
     * <p>Выбрать «первый попавшийся» склад значило бы напечатать покупателю
     * чужое ИП, то есть тихо соврать в документе, по которому он придёт
     * по гарантии. То же правило, по которому не подставляется склад
     * в приёмке и склад возврата: подставлять можно то, что система знает.
     */
    @Test
    @DisplayName("Сделка с позициями двух складов печатается без продавца и говорит почему")
    void mixedWarehousesLeaveSellerEmptyWithAReason() throws Exception {
        Long onTkatskaya = part(tkatskaya, "Фара с Ткацкой");
        Long onDalniy = part(dalniy, "Фара с Дальнего");

        var result = mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d},
                                          {"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, onTkatskaya, tkatskaya, onDalniy, dalniy)))
                .andExpect(status().isCreated())
                .andReturn();

        long dealId = idOf(result.getResponse().getContentAsString());

        mvc.perform(get("/api/deals/" + dealId + "/print").session(login("prodavec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.details").doesNotExist())
                .andExpect(jsonPath("$.seller.problem").value(containsString("с разных складов")));
    }

    /**
     * Склад без заполненных реквизитов печатается с объяснением, а не молча.
     *
     * <p>Продавец отдаёт бумагу покупателю в руки и обязан увидеть, что
     * продавца в ней нет, — иначе он узнает об этом от покупателя.
     */
    @Test
    @DisplayName("Незаполненные реквизиты склада названы, а не пропущены молча")
    void emptyWarehouseRequisitesAreNamed() throws Exception {
        inTenant(() -> jdbc.update(
                "UPDATE warehouse SET print_details = NULL WHERE id = ?", dalniy));

        long dealId = createDeal(part(dalniy, "Фара без реквизитов"), dalniy);

        mvc.perform(get("/api/deals/" + dealId + "/print").session(login("prodavec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.warehouseName").value("Дальний"))
                .andExpect(jsonPath("$.seller.details").doesNotExist())
                .andExpect(jsonPath("$.seller.problem").value(containsString("не заполнены")));
    }

    /** Реквизиты склада правит владелец той же формой, что и остальное. */
    @Test
    @DisplayName("Владелец задаёт реквизиты склада, и они доезжают до документа")
    void ownerWritesWarehouseRequisites() throws Exception {
        mvc.perform(put("/api/company/print-settings").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientSignature":true,"issuerSignature":true,
                                 "warehouses":[{"id":%d,"name":"Ткацкая",
                                                "details":"ИП Новый Н.Н.\\nБарнаул"}]}"""
                                .formatted(tkatskaya)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuerSignature").value(true));

        long dealId = createDeal(part(tkatskaya, "Фара после правки реквизитов"), tkatskaya);

        mvc.perform(get("/api/deals/" + dealId + "/print").session(login("prodavec")))
                .andExpect(jsonPath("$.seller.details").value(containsString("ИП Новый")))
                .andExpect(jsonPath("$.issuerSignature").value(true));
    }

    private long createDeal(Long partId, Long warehouseId) throws Exception {
        var result = mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, partId, warehouseId)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result.getResponse().getContentAsString());
    }

    /**
     * Идентификатор из ответа — поиском, а не срезом строки.
     *
     * <p>{@code getContentAsString()} читает тело не в UTF-8, и среди побитых
     * символов попадается U+0085 (NEL) — для регулярного выражения Java это
     * разделитель строк, который {@code .} не покрывает. Поймано живым
     * прогоном в {@code CompanyReservationTest}: та же строка разбора работала
     * на одном наименовании и отказывала на другом, разница была в букве «х».
     */
    private static long idOf(String body) {
        var found = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!found.find()) {
            throw new AssertionError("В ответе нет идентификатора: " + body);
        }
        return Long.parseLong(found.group(1));
    }

    private Long warehouse(Long branch, String name) {
        Long found = soleId("SELECT id FROM warehouse WHERE name = '" + name + "'");
        if (found != null) {
            return found;
        }
        return jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, ?) RETURNING id",
                Long.class, branch, name);
    }

    private Long soleId(String query) {
        var found = jdbc.queryForList(query, Long.class);
        return found.isEmpty() ? null : found.get(0);
    }

    private Long part(Long warehouseId, String title) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (1, ?, 5000, 2000) RETURNING id""", Long.class, title);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouseId, null));
            return partId;
        });
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
                                {"company":"pechat","login":"%s","password":"пароль"}"""
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

    private void inTenant(Runnable body) {
        inTenant(() -> {
            body.run();
            return null;
        });
    }

    /**
     * Сумма строк документа сходится с итогом — и услуга в сделке есть
     * намеренно.
     *
     * <p>Без неё проверка проходит сама собой: у сделки из одних позиций итог
     * и так равен их сумме. Ровно эта ошибка в проекте уже случалась дважды —
     * ссылка клиенту и экран продавца рисовали только позиции, и «итого 7 500»
     * стояло под деталями на 7 000. В бумаге, которую покупатель уносит
     * с собой, спор об этом начинается у прилавка.
     */
    @Test
    @DisplayName("Сумма строк документа сходится с его итогом, включая услуги")
    void linesAddUpToTheTotal() throws Exception {
        // Справочник услуг наполняет миграция, и «Доставка» в нём уже есть:
        // уникальный индекс `service_uk` отбивает вторую строку с тем же
        // именем. Берём заведённую, а не заводим свою, — иначе фикстура
        // зависела бы от того, что в справочнике ничего нет.
        Long delivery = inTenant(() -> {
            Long found = soleId("SELECT id FROM service WHERE NOT is_archived ORDER BY id LIMIT 1");
            return found != null ? found : jdbc.queryForObject(
                    "INSERT INTO service (name, price) VALUES ('Доставка', 500) RETURNING id",
                    Long.class);
        });
        Long partId = part(tkatskaya, "Фара для суммы");

        var created = mvc.perform(post("/api/deals").with(csrf()).session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}],
                                 "services":[{"serviceId":%d,"quantity":1,"price":500}]}"""
                                .formatted(customer, partId, tkatskaya, delivery)))
                .andExpect(status().isCreated())
                .andReturn();
        long dealId = idOf(created.getResponse().getContentAsString());

        var body = mvc.perform(get("/api/deals/" + dealId + "/print")
                        .session(login("prodavec")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Сумма строк против итога документа — то, из-за чего спор начинается
        // в момент оплаты: «итого 7 500» под деталями на 7 000. В бумаге,
        // которую покупатель уносит с собой, это хуже всего.
        var amounts = java.util.regex.Pattern.compile("\"amount\":([0-9.]+)").matcher(body);
        BigDecimal sum = BigDecimal.ZERO;
        while (amounts.find()) {
            sum = sum.add(new BigDecimal(amounts.group(1)));
        }
        var total = java.util.regex.Pattern.compile("\"total\":([0-9.]+)").matcher(body);
        assertThat(total.find()).as("в документе нет итога").isTrue();
        assertThat(sum).as("сумма строк документа не сходится с итогом")
                .isEqualByComparingTo(new BigDecimal(total.group(1)));
    }
}
