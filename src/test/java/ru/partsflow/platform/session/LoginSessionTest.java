package ru.partsflow.platform.session;

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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Журнал сессий: входы, выходы, устройство, длительность, неудачные попытки.
 *
 * <p>Через HTTP, а не вызовом сервиса, и на то три причины. Первая: половина
 * этой задачи — про то, кому журнал показан и кому отказано. Вторая: ответ
 * уходит наружу record'ами, а «No acceptable representation» виден только
 * настоящему запросу. Третья, здесь главная: <b>вход, выход и отметка
 * активности живут в фильтрах и в цепочке безопасности</b> — вызовом сервиса
 * их не проверить вовсе, потому что зовёт их не сервис.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class LoginSessionTest extends PostgresTestBase {

    private static final String TENANT = "t_000124";
    private static final String COMPANY = "sessco";
    private static final String PASSWORD = "пароль-длинный";

    /** Настоящая строка Chrome под Windows: разбор проверяется на ней. */
    private static final String CHROME_ON_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/127.0.0.0 Safari/537.36";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SessionSweep sweep;

    private Long sellerId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 124");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (124, ?, 'Разборка на Ткацкой', ?)""", TENANT, COMPANY);

        inTenant(() -> {
            member("vladelec", "Пётр Владельцев", "OWNER");
            member("revizor", "Ревизоров", "AUDITOR");
            member("upravlyayushchiy", "Иванов", "MANAGER");
            sellerId = member("prodavec", "Сидоров", "SELLER");
            jdbc.update("DELETE FROM login_session");
            return null;
        });
    }

    /**
     * То, ради чего задача заведена: владелец видит, кто заходил и откуда.
     *
     * <p>Строка браузера при этом лежит в базе <b>сырой</b>, а на экран уезжает
     * разобранной — решение владельца продукта от 9 сентября 2026. Проверяется
     * и то и другое: разобрав при записи, мы через год получили бы журнал
     * с неверными данными и без возможности переразобрать.
     */
    @Test
    @DisplayName("Вход записан с человеком, устройством и адресом")
    void loginIsRecordedWithDeviceAndAddress() throws Exception {
        login("prodavec", CHROME_ON_WINDOWS);

        mvc.perform(get("/api/organization/sessions").session(login("vladelec")))
                .andExpect(status().isOk())
                // Свежее сверху: владелец только что вошёл сам.
                .andExpect(jsonPath("$.items[1].who").value("Сидоров"))
                .andExpect(jsonPath("$.items[1].role").value("SELLER"))
                .andExpect(jsonPath("$.items[1].success").value(true))
                .andExpect(jsonPath("$.items[1].device").value("Chrome · Windows"))
                .andExpect(jsonPath("$.items[1].userAgent").value(CHROME_ON_WINDOWS))
                .andExpect(jsonPath("$.items[1].ip").value("127.0.0.1"))
                // Сессия не кончилась: это и есть «работает».
                .andExpect(jsonPath("$.items[1].endReason").doesNotExist());
    }

    /**
     * «Вышел» и «истекла» — разные ответы на вопрос «почему его больше не было».
     *
     * <p>Решение владельца продукта от 9 сентября 2026: три исхода различаются.
     * Сведи их в «закрыта» — и пропадёт ровно то, ради чего смотрят: закрыл
     * человек рабочее место или бросил открытым.
     */
    @Test
    @DisplayName("Выход по кнопке записан выходом, а не истечением")
    void logoutIsRecorded() throws Exception {
        MockHttpSession seller = login("prodavec", CHROME_ON_WINDOWS);
        mvc.perform(post("/api/auth/logout").with(csrf()).session(seller))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/organization/sessions").param("member", "Сидоров")
                        .session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].endReason").value("LOGOUT"))
                .andExpect(jsonPath("$.items[0].endedAt").isNotEmpty());
    }

    /**
     * «Пять отказов подряд ценнее ста успешных входов» — ответ владельца
     * продукта от 9 сентября 2026.
     *
     * <p>Логин, которого у компании нет, показывается тем, что ввели: именно
     * по нему видно подбор. Назвать его прочерком значило бы спрятать
     * единственное, что об этой попытке известно.
     */
    @Test
    @DisplayName("Неудачная попытка входа записана — и своя, и с чужим логином")
    void failedAttemptsAreRecorded() throws Exception {
        signIn("prodavec", "не тот пароль").andExpect(status().isUnauthorized());
        signIn("vzlomshchik", "наугад").andExpect(status().isUnauthorized());

        mvc.perform(get("/api/organization/sessions").param("outcome", "FAILED")
                        .session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                // Свежая сверху: неизвестный логин был вторым.
                .andExpect(jsonPath("$.items[0].who").value("vzlomshchik"))
                .andExpect(jsonPath("$.items[0].unknown").value(true))
                .andExpect(jsonPath("$.items[0].success").value(false))
                .andExpect(jsonPath("$.items[0].failureReason").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.items[1].who").value("Сидоров"))
                .andExpect(jsonPath("$.items[1].unknown").value(false));
    }

    /**
     * Попытка с выдуманным кодом компании не принадлежит ни одной организации.
     *
     * <p>Писать её некуда: схемы нет, и показать её некому. Ответ при этом
     * не отличается ни на букву — иначе форма входа работает справочником
     * действующих компаний.
     */
    @Test
    @DisplayName("Попытка с чужим кодом компании ничей журнал не пачкает")
    void attemptWithUnknownCompanyIsNotRecordedAnywhere() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"takoy-net","login":"prodavec","password":"наугад"}"""))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/organization/sessions").param("outcome", "FAILED")
                        .session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    /**
     * Обратная сторона, названная задачей дословно: кому не разрешали —
     * тот не видит.
     */
    @Test
    @DisplayName("Журнал сессий открыт владельцу и ревизору, менеджеру — нет")
    void journalIsClosedToEveryoneElse() throws Exception {
        mvc.perform(get("/api/organization/sessions").session(login("vladelec")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/organization/sessions").session(login("revizor")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/organization/sessions").session(login("upravlyayushchiy")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/organization/sessions").session(login("prodavec")))
                .andExpect(status().isForbidden());
    }

    /**
     * Смена пароля обязана убивать сессии — иначе она не выгоняет угонщика.
     *
     * <p>«Смена пароля — всегда, это главный сценарий „меня взломали“»
     * (docs/sessions.md §6, ответы владельца продукта от 9 сентября 2026).
     * До этой правки сменивший пароль владелец считал доступ закрытым,
     * а чужая cookie работала до конца дня.
     */
    @Test
    @DisplayName("Смена пароля выкидывает чужую сессию тут же, а не со следующего входа")
    void passwordChangeKillsSessions() throws Exception {
        MockHttpSession seller = login("prodavec", CHROME_ON_WINDOWS);
        mvc.perform(get("/api/auth/me").session(seller)).andExpect(status().isOk());

        mvc.perform(post("/api/members/" + sellerId + "/password").with(csrf())
                        .session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"новый-длинный-пароль\"}"))
                .andExpect(status().isNoContent());

        // Тот же запрос той же сессией — и он больше не проходит.
        mvc.perform(get("/api/auth/me").session(seller)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/organization/sessions").param("member", "Сидоров")
                        .session(login("vladelec")))
                .andExpect(jsonPath("$.items[0].endReason").value("REVOKED"))
                .andExpect(jsonPath("$.items[0].endDetail").value("смена пароля"));
    }

    /**
     * А своя сессия остаётся работать.
     *
     * <p>Выкинуть того, кто как раз сменил себе пароль, — верный способ
     * отучить менять пароли: человек видит «пароль изменён» и тут же экран
     * входа, то есть решает, что сломалось.
     */
    @Test
    @DisplayName("Сменивший себе пароль продолжает работать")
    void ownSessionSurvivesOwnPasswordChange() throws Exception {
        MockHttpSession owner = login("vladelec", CHROME_ON_WINDOWS);
        Long ownerId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM tenant_member WHERE login = 'vladelec'", Long.class));

        mvc.perform(post("/api/members/" + ownerId + "/password").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"новый-длинный-пароль\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").session(owner)).andExpect(status().isOk());
    }

    /**
     * Выключенный сотрудник уходит с открытым кабинетом — уходил.
     *
     * <p>Роль и признак «работает» лежат в сессии снимком: выключение учётной
     * записи закрывало только следующий вход.
     */
    @Test
    @DisplayName("Выключение сотрудника закрывает его открытую сессию")
    void disablingKillsSessions() throws Exception {
        MockHttpSession seller = login("prodavec", CHROME_ON_WINDOWS);

        mvc.perform(post("/api/members/" + sellerId + "/disable").with(csrf())
                        .session(login("vladelec")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").session(seller)).andExpect(status().isUnauthorized());
    }

    /**
     * Зависшую сессию закрывает уборка — и <b>задним числом</b>.
     *
     * <p>«Закрыл ноутбук в 18:00, сессия истекла в 6:00» — написать сюда время
     * уборки значит утверждать, что человек работал до этой минуты. Проверяется
     * именно это: конец записан в прошлом, а не «сейчас».
     */
    @Test
    @DisplayName("Уборка закрывает брошенную сессию временем в прошлом, а не текущим")
    void staleSessionIsClosedBackdated() throws Exception {
        login("prodavec", CHROME_ON_WINDOWS);
        inTenant(() -> jdbc.update("""
                UPDATE login_session
                   SET started_at = now() - interval '3 hours',
                       last_seen_at = now() - interval '2 hours'
                 WHERE outcome = 'SUCCESS'"""));

        sweep.closeStale();

        String reason = inTenant(() -> jdbc.queryForObject(
                "SELECT end_reason FROM login_session WHERE outcome = 'SUCCESS'", String.class));
        assertThat(reason).isEqualTo("EXPIRED");

        Boolean inThePast = inTenant(() -> jdbc.queryForObject("""
                SELECT ended_at < now() - interval '1 hour' AND ended_at > last_seen_at
                  FROM login_session WHERE outcome = 'SUCCESS'""", Boolean.class));
        assertThat(inThePast)
                .as("конец сессии — момент, когда она перестала быть действительной, "
                        + "а не момент прохода уборки")
                .isTrue();
    }

    /**
     * Отметка активности прорежена, и это требование задачи дословно.
     *
     * <p>«Отметку активности нельзя обновлять на каждый запрос — это запись
     * в базу на каждый клик» (ответы владельца продукта от 9 сентября 2026).
     * Проверяется тем, что отметка после первого запроса <b>не двигается</b>:
     * снимите прореживание — и каждый следующий запрос перепишет её.
     */
    @Test
    @DisplayName("Отметка активности не пишется на каждый запрос")
    void activityMarkIsThrottled() throws Exception {
        MockHttpSession seller = login("prodavec", CHROME_ON_WINDOWS);
        mvc.perform(get("/api/auth/me").session(seller)).andExpect(status().isOk());

        Timestamp afterFirst = lastSeen();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/auth/me").session(seller)).andExpect(status().isOk());
        }

        assertThat(lastSeen())
                .as("пять запросов подряд — это пять записей в базу на каждого "
                        + "работающего сотрудника")
                .isEqualTo(afterFirst);
    }

    // ------------------------------------------------------------- служебное

    private Timestamp lastSeen() {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT last_seen_at FROM login_session
                 WHERE outcome = 'SUCCESS' AND login_attempted = 'prodavec'""",
                Timestamp.class));
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            // Пароль и признак «работает» возвращаются к исходным: соседний
            // метод мог сменить одно и выключить другое, а порядок методов
            // не гарантирован.
            jdbc.update("UPDATE tenant_member SET password_hash = ?, is_active = true WHERE id = ?",
                    passwordEncoder.encode(PASSWORD), found.get(0));
            return found.get(0);
        }
        return jdbc.queryForObject("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode(PASSWORD));
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String login, String password)
            throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                .header("User-Agent", CHROME_ON_WINDOWS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"company":"%s","login":"%s","password":"%s"}"""
                        .formatted(COMPANY, login, password)));
    }

    private MockHttpSession login(String login) throws Exception {
        return login(login, CHROME_ON_WINDOWS);
    }

    private MockHttpSession login(String login, String userAgent) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"%s"}"""
                                .formatted(COMPANY, login, PASSWORD)))
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
