package ru.partsflow.platform.admin;

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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Администратор сервиса в кабинете разборки — по восьми пунктам критерия
 * приёмки задачи 0042 (решения владельца продукта от 8 и 29 сентября 2026).
 *
 * <p>Через HTTP, а не вызовом сервисов, и иначе нельзя: половина этой задачи
 * живёт в цепочке безопасности (кому какой путь открыт), в фильтре
 * ({@link AdminVisitGuard}) и в смене сессии при входе и выходе — сервис
 * не зовёт ничего из этого. Восьмой пункт («изоляция остаётся настоящей»)
 * держит {@code TenantIsolationTest}, не тронутый этой задачей.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class ServiceAdminTest extends PostgresTestBase {

    private static final String TENANT = "t_000347";
    private static final long TENANT_ID = 347;
    private static final String COMPANY = "adminco";
    private static final String PASSWORD = "пароль-длинный";

    /** Вторая разборка: разрешение первой её открывать не должно. */
    private static final String OTHER_TENANT = "t_000348";
    private static final long OTHER_TENANT_ID = 348;
    private static final String OTHER_COMPANY = "adminco-sosed";

    private static final String ADMIN_LOGIN = "sluzhba347";
    private static final String ADMIN_PASSWORD = "пароль-администратора";
    private static final String ADMIN_NAME = "Владислав из поддержки";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    private Long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT, OTHER_TENANT);
    }

    @BeforeEach
    void fixtures() {
        // Разрешения и запросы ссылаются на реестр — уходят первыми.
        for (long id : new long[] {TENANT_ID, OTHER_TENANT_ID}) {
            jdbc.update("DELETE FROM public.admin_access_grant WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.admin_access_request WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", id);
        }
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка на Ткацкой', ?)""", TENANT_ID, TENANT, COMPANY);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка на Прядильной', ?)""", OTHER_TENANT_ID, OTHER_TENANT, OTHER_COMPANY);

        // Фикстура пишет то, что называет, а не принимает найденное
        // (корневой CLAUDE.md, «Тесты»): соседний метод мог выключить
        // администратора или сменить ему имя.
        jdbc.update("DELETE FROM public.admin_access_request WHERE admin_id IN "
                + "(SELECT id FROM public.service_admin WHERE login = ?)", ADMIN_LOGIN);
        int updated = jdbc.update("""
                UPDATE public.service_admin SET password_hash = ?, display_name = ?, is_active = true
                 WHERE login = ?""", passwordEncoder.encode(ADMIN_PASSWORD), ADMIN_NAME, ADMIN_LOGIN);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO public.service_admin (login, password_hash, display_name)
                    VALUES (?, ?, ?)""", ADMIN_LOGIN, passwordEncoder.encode(ADMIN_PASSWORD), ADMIN_NAME);
        }

        inTenant(() -> {
            member("vladelec", "Пётр Владельцев", "OWNER");
            jdbc.update("DELETE FROM login_session");
            jdbc.update("DELETE FROM audit_log");
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price)
                    VALUES (1, 'Фара Toyota Camry 2006 перед. лев. (б/у)', 5000)
                    RETURNING id""", Long.class);
            return null;
        });
    }

    // --- Пункт 4: вход только с разрешения владельца, и разрешение двумя путями

    @Test
    @DisplayName("Без разрешения владельца администратор в кабинет не входит — и сказано, что делать")
    void noEntryWithoutPermission() throws Exception {
        MockHttpSession admin = adminLogin();

        mvc.perform(post("/api/admin/tenants/" + COMPANY + "/enter").with(csrf()).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("не открывал доступа")));
    }

    @Test
    @DisplayName("Владелец даёт доступ своей кнопкой, без запроса — и администратор входит")
    void ownerGrantsWithOwnButtonWithoutRequest() throws Exception {
        grant(null);

        MockHttpSession admin = enter();
        mvc.perform(get("/api/auth/me").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.displayName").value(ADMIN_NAME))
                .andExpect(jsonPath("$.companySchema").value(TENANT));
    }

    @Test
    @DisplayName("Запрос кнопкой виден владельцу, и выдача доступа его закрывает")
    void requestIsSeenByOwnerAndAnsweredByGrant() throws Exception {
        MockHttpSession admin = adminLogin();
        mvc.perform(post("/api/admin/tenants/" + COMPANY + "/request").with(csrf()).session(admin))
                .andExpect(status().isNoContent());
        // Двойное нажатие — тот же один запрос, а не отказ.
        mvc.perform(post("/api/admin/tenants/" + COMPANY + "/request").with(csrf()).session(admin))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/organization/admin-access").session(ownerLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.adminName").value(ADMIN_NAME))
                .andExpect(jsonPath("$.grant").value(nullValue()));
        mvc.perform(get("/api/admin/tenants").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == '" + COMPANY + "')].requestedAt").isNotEmpty());

        grant(null);

        mvc.perform(get("/api/organization/admin-access").session(ownerLogin()))
                .andExpect(jsonPath("$.request").value(nullValue()))
                .andExpect(jsonPath("$.grant.expiresAt").value(nullValue()));
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM public.admin_access_request
                 WHERE tenant_id = ? AND closed_at IS NULL""", Integer.class, TENANT_ID);
        assertThat(open).as("выданный доступ закрывает запрос как исполненный").isZero();
    }

    // --- Пункт 5: срок выбирает клиент, конец виден обеим сторонам

    @Test
    @DisplayName("Срок в часах: конец виден владельцу, в списке администратора и в его кабинете")
    void termIsVisibleToBothSides() throws Exception {
        // «Сейчас» берётся у базы, а не у JVM: срок разрешения ставит, проверяет
        // и показывает база (`now()` в AdminAccess) — у продукта часы одни.
        // Часы теста от них отличаться вправе: задача CI «Часы вперёд» сдвигает
        // JVM на 31 день, а Postgres в контейнере живёт по настоящим, и
        // `Instant.now()` здесь давал «-740 ч» при верном сроке в три часа.
        Instant before = jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
        grant(3);

        Timestamp stored = jdbc.queryForObject("""
                SELECT expires_at FROM public.admin_access_grant
                 WHERE tenant_id = ? AND closed_at IS NULL""", Timestamp.class, TENANT_ID);
        assertThat(stored).isNotNull();
        assertThat(Duration.between(before, stored.toInstant()))
                .isBetween(Duration.ofMinutes(179), Duration.ofMinutes(181));

        mvc.perform(get("/api/organization/admin-access").session(ownerLogin()))
                .andExpect(jsonPath("$.grant.expiresAt").isNotEmpty());

        MockHttpSession admin = adminLogin();
        mvc.perform(get("/api/admin/tenants").session(admin))
                .andExpect(jsonPath("$[?(@.code == '" + COMPANY + "')].grant.expiresAt").isNotEmpty());

        enter(admin);
        mvc.perform(get("/api/admin/me").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.code").value(COMPANY))
                .andExpect(jsonPath("$.visit.expiresAt").isNotEmpty());
    }

    // --- Пункты 1–3: свой участник, настоящий автор, входы видны

    @Test
    @DisplayName("Администратор виден в организации участником, которого владелец не выключает")
    void adminIsAParticipantOwnerCannotTakeAway() throws Exception {
        grant(null);
        enter();

        MockHttpSession owner = ownerLogin();
        mvc.perform(get("/api/members").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role == 'ADMIN')].displayName").value(hasItem(ADMIN_NAME)));

        Long adminMember = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE role = 'ADMIN'", Long.class));
        mvc.perform(post("/api/members/" + adminMember + "/disable").with(csrf()).session(owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Отозвать доступ")));
        mvc.perform(post("/api/members/" + adminMember + "/password").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"новый-пароль-123\"}"))
                .andExpect(status().isConflict());
        // И завести «администратора» руками нельзя: роли нет в списке выдаваемых.
        mvc.perform(post("/api/members").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"poddelka","password":"пароль-длинный","role":"ADMIN"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Правка администратора подписана им, а не владельцем, и отличима от правки владельца")
    void journalNamesTheRealAuthor() throws Exception {
        changePrice(ownerLogin(), "4800");
        grant(null);
        changePrice(enter(), "4500");

        mvc.perform(get("/api/organization/audit").session(ownerLogin()))
                .andExpect(status().isOk())
                // Свежее сверху: последней была правка администратора.
                .andExpect(jsonPath("$.items[0].author").value(org.hamcrest.Matchers.startsWith(ADMIN_NAME + " (ID ")))
                .andExpect(jsonPath("$.items[0].authorRole").value("ADMIN"))
                .andExpect(jsonPath("$.items[0].changes[0].after").value("4500"))
                .andExpect(jsonPath("$.items[1].author").value(org.hamcrest.Matchers.startsWith("Пётр Владельцев (")))
                .andExpect(jsonPath("$.items[1].authorRole").value("OWNER"));
    }

    @Test
    @DisplayName("Владелец видит вход администратора на той же вкладке «Входы»")
    void ownerSeesAdminEntries() throws Exception {
        grant(null);
        enter();

        mvc.perform(get("/api/organization/sessions").session(ownerLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.role == 'ADMIN')].who").value(hasItem(org.hamcrest.Matchers.startsWith(ADMIN_NAME + " (ID "))))
                .andExpect(jsonPath("$.items[?(@.role == 'ADMIN')].success").value(hasItem(true)));
    }

    // --- Пункт 6: отзыв убивает живую сессию немедленно

    @Test
    @DisplayName("Отзыв, пока администратор внутри: следующий же его запрос отвечает отказом")
    void revokeKillsTheLiveSession() throws Exception {
        grant(null);
        MockHttpSession admin = enter();
        mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isOk());

        mvc.perform(post("/api/organization/admin-access/revoke").with(csrf()).session(ownerLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grant").value(nullValue()));

        mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1,\"published\":false}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/organization/sessions").session(ownerLogin()))
                .andExpect(jsonPath("$.items[?(@.role == 'ADMIN')].endDetail")
                        .value(hasItem(AdminAccess.REVOKED_DETAIL)));
    }

    @Test
    @DisplayName("Истёкший срок закрывает доступ сам, без чьего-либо нажатия")
    void expiredTermClosesAccessByItself() throws Exception {
        grant(1);
        MockHttpSession admin = enter();
        mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isOk());

        // Час прошёл: разрешение выдано два часа назад и кончилось минуту назад.
        jdbc.update("""
                UPDATE public.admin_access_grant
                   SET granted_at = now() - interval '2 hours', expires_at = now() - interval '1 minute'
                 WHERE tenant_id = ? AND closed_at IS NULL""", TENANT_ID);

        mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isUnauthorized());
        // И вошедшим он больше не считается: сессия уничтожена, а не просто отбита.
        mvc.perform(get("/api/admin/me").session(admin)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/organization/sessions").session(ownerLogin()))
                .andExpect(jsonPath("$.items[?(@.role == 'ADMIN')].endDetail")
                        .value(hasItem(AdminVisitGuard.EXPIRED_DETAIL)));
    }

    @Test
    @DisplayName("Давать и отзывать доступ — только владелец, не администратор в его кабинете")
    void adminCannotExtendHisOwnAccess() throws Exception {
        grant(1);
        MockHttpSession admin = enter();

        mvc.perform(get("/api/organization/admin-access").session(admin)).andExpect(status().isOk());
        mvc.perform(post("/api/organization/admin-access/grant").with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hours\":null}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/organization/admin-access/revoke").with(csrf()).session(admin))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Выход из кабинета возвращает в систему администрирования, во «Входах» — «Вышел»")
    void leaveReturnsToAdministration() throws Exception {
        grant(null);
        MockHttpSession admin = enter();

        mvc.perform(post("/api/admin/visit/leave").with(csrf()).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit").value(nullValue()));
        mvc.perform(get("/api/admin/tenants").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isForbidden());

        mvc.perform(get("/api/organization/sessions").session(ownerLogin()))
                .andExpect(jsonPath("$.items[?(@.role == 'ADMIN')].endReason").value(hasItem("LOGOUT")));
    }

    // --- Пункт 4, вторая половина: вход, оставленный администратором, не переживает отзыва
    //
    // Права у администратора внутри — владельца, но кто входит в кабинет,
    // решает только владелец: иначе после первого визита у администратора
    // остался бы вход мимо разрешения (разбор PR 393). Каждый путь — отдельным
    // методом, чтобы отчёт называл, какой из них открылся.

    @Test
    @DisplayName("Администратор не заводит сотрудника — ни владельца, ни продавца — и после отзыва входа нет")
    void adminCannotCreateMembers() throws Exception {
        grant(null);
        MockHttpSession admin = enter();

        for (String role : new String[] {"OWNER", "SELLER"}) {
            mvc.perform(post("/api/members").with(csrf()).session(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"login":"zapasnoy","password":"%s","role":"%s"}"""
                                    .formatted(PASSWORD, role)))
                    .andExpect(status().isForbidden());
        }
        Integer created = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM tenant_member WHERE login = 'zapasnoy'", Integer.class));
        assertThat(created).as("учётная запись администратора не заведена").isZero();

        mvc.perform(post("/api/organization/admin-access/revoke").with(csrf()).session(ownerLogin()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"zapasnoy","password":"%s"}"""
                                .formatted(COMPANY, PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Администратор не меняет пароль владельцу — и владелец входит своим")
    void adminCannotChangeOwnersPassword() throws Exception {
        grant(null);
        MockHttpSession admin = enter();
        Long owner = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'vladelec'", Long.class));

        mvc.perform(post("/api/members/" + owner + "/password").with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"пароль-администратора-1\"}"))
                .andExpect(status().isForbidden());

        // Входит прежним паролем — значит пароль не тронут.
        ownerLogin();
    }

    @Test
    @DisplayName("Администратор не меняет пароль сотруднику и не включает и не выключает никого")
    void adminCannotChangeOtherMembersAccess() throws Exception {
        grant(null);
        MockHttpSession admin = enter();
        Long seller = inTenant(() -> member("prodavec347", "Продавец Сидоров", "SELLER"));
        Long former = inTenant(() -> {
            Long id = member("byvshiy347", "Бывший Кладовщиков", "STOREKEEPER");
            jdbc.update("UPDATE tenant_member SET is_active = false WHERE id = ?", id);
            return id;
        });

        mvc.perform(post("/api/members/" + seller + "/password").with(csrf()).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"пароль-администратора-1\"}"))
                .andExpect(status().isForbidden());
        // Включить выключенного — это вернуть вход человеку, пароль которого
        // администратор мог знать; выключить — распорядиться входом чужого.
        mvc.perform(post("/api/members/" + former + "/enable").with(csrf()).session(admin))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/members/" + seller + "/disable").with(csrf()).session(admin))
                .andExpect(status().isForbidden());

        Boolean formerActive = inTenant(() -> jdbc.queryForObject(
                "SELECT is_active FROM tenant_member WHERE id = ?", Boolean.class, former));
        assertThat(formerActive).as("выключенный остался выключенным").isFalse();
        // Сотрудник при этом свой пароль меняет как раньше: правило про
        // администратора, а не про путь.
        mvc.perform(post("/api/members/" + seller + "/password").with(csrf())
                        .session(memberLogin("prodavec347"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Перебор: ни один изменяющий путь сотрудников и разрешения администратору ему не открыт")
    void everyAccessChangingPathIsClosedToAdmin() throws Exception {
        grant(null);
        MockHttpSession admin = enter();
        Long owner = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'vladelec'", Long.class));

        // Пути берутся у самого приложения, а не списком в тесте: путь,
        // добавленный под эти адреса завтра, проверяется сам.
        java.util.List<String> checked = new java.util.ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var info = entry.getKey();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/members") && !pattern.startsWith("/api/organization/admin-access")) {
                    continue;
                }
                for (var method : info.getMethodsCondition().getMethods()) {
                    if (method == org.springframework.web.bind.annotation.RequestMethod.GET) {
                        continue;
                    }
                    String path = pattern.replaceAll("\\{[^}]+}", String.valueOf(owner));
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .request(org.springframework.http.HttpMethod.valueOf(method.name()), path)
                                    .with(csrf()).session(admin)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"login":"perebor","password":"%s","role":"OWNER","hours":null}"""
                                            .formatted(PASSWORD)))
                            .andExpect(result -> assertThat(result.getResponse().getStatus())
                                    .as("%s %s из сессии администратора", method, path)
                                    .isEqualTo(403));
                    checked.add(method + " " + pattern);
                }
            }
        }
        // Семь известных сегодня: заведение, пароль, включение, выключение,
        // выдача, отзыв, отклонение. Меньше — значит перебор ослеп.
        assertThat(checked).as("перебор нашёл пути").hasSizeGreaterThanOrEqualTo(7);
    }

    // --- Пробы разбора: вход только в ту разборку и только пока разрешение действует

    @Test
    @DisplayName("Истёкшее разрешение в кабинет не пускает — 409 словами")
    void expiredGrantDoesNotLetIn() throws Exception {
        grant(1);
        jdbc.update("""
                UPDATE public.admin_access_grant
                   SET granted_at = now() - interval '2 hours', expires_at = now() - interval '1 minute'
                 WHERE tenant_id = ? AND closed_at IS NULL""", TENANT_ID);

        mvc.perform(post("/api/admin/tenants/" + COMPANY + "/enter").with(csrf()).session(adminLogin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("не открывал доступа")));
    }

    @Test
    @DisplayName("Разрешение одной разборки не открывает другую — 409")
    void grantOfOneCompanyDoesNotOpenAnother() throws Exception {
        grant(null);

        mvc.perform(post("/api/admin/tenants/" + OTHER_COMPANY + "/enter").with(csrf()).session(adminLogin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("не открывал доступа")));
    }

    @Test
    @DisplayName("Неизвестный код компании — 400 словами, без внутреннего номера")
    void unknownCompanyCodeIsNamedWithoutInternalId() throws Exception {
        var body = mvc.perform(post("/api/admin/tenants/net-takoy-kompanii/enter").with(csrf()).session(adminLogin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Компания не найдена")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).as("в отказе нет номера строки и схемы").doesNotContainPattern("\\d");
    }

    // --- Пункт 7: система администрирования вне всех кабинетов

    @Test
    @DisplayName("Список клиентов сервиса не виден ни одному кабинету, а кабинет — системе администрирования")
    void administrationAndCabinetsDoNotSeeEachOther() throws Exception {
        MockHttpSession owner = ownerLogin();
        mvc.perform(get("/api/admin/tenants").session(owner)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/tenants/" + COMPANY + "/enter").with(csrf()).session(owner))
                .andExpect(status().isForbidden());

        MockHttpSession admin = adminLogin();
        mvc.perform(get("/api/admin/tenants").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(hasItem(COMPANY)));
        // Вошедший в систему администрирования не читает кабинет мимо входа
        // в него: схемы у него нет, и путь кабинета ему закрыт.
        mvc.perform(get("/api/members").session(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/organization/audit").session(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(admin)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Неверный пароль администратора — 401 без объяснений")
    void wrongPasswordIsRefused() throws Exception {
        mvc.perform(post("/api/admin/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"%s\",\"password\":\"не тот\"}".formatted(ADMIN_LOGIN)))
                .andExpect(status().isUnauthorized());
        // Логин сотрудника разборки сюда не подходит: у входа в кабинет
        // и у входа в администрирование разные учётные записи.
        mvc.perform(post("/api/admin/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"vladelec\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    // --- помощники

    private void grant(Integer hours) throws Exception {
        mvc.perform(post("/api/organization/admin-access/grant").with(csrf()).session(ownerLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":%s}".formatted(hours == null ? "null" : hours)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grant").value(not(nullValue())));
    }

    private MockHttpSession enter() throws Exception {
        return enter(adminLogin());
    }

    private MockHttpSession enter(MockHttpSession admin) throws Exception {
        mvc.perform(post("/api/admin/tenants/" + COMPANY + "/enter").with(csrf()).session(admin))
                .andExpect(status().isOk());
        return admin;
    }

    private void changePrice(MockHttpSession who, String price) throws Exception {
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":%s,\"published\":false}".formatted(price)))
                .andExpect(status().isOk());
    }

    private MockHttpSession adminLogin() throws Exception {
        var result = mvc.perform(post("/api/admin/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"%s\",\"password\":\"%s\"}"
                                .formatted(ADMIN_LOGIN, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession ownerLogin() throws Exception {
        return memberLogin("vladelec");
    }

    private MockHttpSession memberLogin(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"%s"}"""
                                .formatted(COMPANY, login, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            jdbc.update("""
                            UPDATE tenant_member
                               SET password_hash = ?, is_active = true, display_name = ?, role = ?
                             WHERE id = ?""",
                    passwordEncoder.encode(PASSWORD), displayName, role, found.get(0));
            return found.get(0);
        }
        return jdbc.queryForObject("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode(PASSWORD));
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
