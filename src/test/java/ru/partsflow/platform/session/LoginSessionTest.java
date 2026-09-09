package ru.partsflow.platform.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
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

    /**
     * Своя схема, ничья больше.
     *
     * <p>Была {@code t_000124}, и её же взял {@code SoldItemsReportTest}.
     * Пока обе фикстуры заводили разных людей, это ничего не значило; но обе
     * заводят логин {@code vladelec} — там он «Владелец», здесь «Пётр
     * Владельцев», — а помощник {@code member} ищет по логину и найденного
     * не переименовывает. Кто из двух классов отработал первым, тот и назвал
     * владельца, и отбор журнала честно отдавал чужое имя. Падало при этом
     * не то, что виновато: сам отчёт продаж оставался зелёным.
     */
    private static final String TENANT = "t_000125";
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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 125");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (125, ?, 'Разборка на Ткацкой', ?)""", TENANT, COMPANY);

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

        // Три утверждения, и каждое отбивает свой способ ошибиться. Первое —
        // конец в прошлом, а не «сейчас»: время прохода уборки означало бы
        // «работал до этой минуты». Второе и третье привязывают конец
        // к последней активности: взяв started_at (вход был на час раньше),
        // мы получили бы момент раньше самой активности, а взяв now() —
        // позже её на два часа.
        Boolean fromLastSeen = inTenant(() -> jdbc.queryForObject("""
                SELECT ended_at < now() - interval '1 hour'
                   AND ended_at > last_seen_at
                   AND ended_at < last_seen_at + interval '1 hour'
                  FROM login_session WHERE outcome = 'SUCCESS'""", Boolean.class));
        assertThat(fromLastSeen)
                .as("конец сессии отсчитывается от последней активности — это момент, "
                        + "когда она перестала быть действительной, а не момент прохода "
                        + "уборки и не время входа")
                .isTrue();
    }

    /**
     * Первая половина требования об отметке активности: она вообще пишется.
     *
     * <p>Без неё соседняя проверка прореживания зеленеет на коде, который
     * не отмечает <b>ничего</b>: {@code last_seen_at} ставится ещё при входе,
     * и «отметка не двигается» выполняется сама собой. А без отметки нет
     * ни длительности работы («работал до 18:00»), ни уборки зависших —
     * та идёт как раз по этому полю.
     *
     * <p>Отметка отодвигается в прошлое запросом, а не ожиданием: проверяется
     * то, что фильтр её <b>перепишет</b>, и для этого нужно, чтобы прежнее
     * значение отличалось от нового.
     */
    @Test
    @DisplayName("Отметка активности пишется при работе, а не только при входе")
    void activityMarkIsWritten() throws Exception {
        MockHttpSession seller = login("prodavec", CHROME_ON_WINDOWS);
        inTenant(() -> jdbc.update("""
                UPDATE login_session SET last_seen_at = now() - interval '2 hours'
                 WHERE outcome = 'SUCCESS' AND login_attempted = 'prodavec'"""));

        mvc.perform(get("/api/auth/me").session(seller)).andExpect(status().isOk());

        Boolean fresh = inTenant(() -> jdbc.queryForObject("""
                SELECT last_seen_at > now() - interval '1 minute' FROM login_session
                 WHERE outcome = 'SUCCESS' AND login_attempted = 'prodavec'""", Boolean.class));
        assertThat(fresh)
                .as("запрос работающего сотрудника обязан двигать отметку активности: "
                        + "по ней считается длительность и по ней же уборка отличает "
                        + "брошенную сессию от живой")
                .isTrue();
    }

    /**
     * Вторая половина того же требования, и она дословно из задачи.
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

    /**
     * Список отбора не растёт от чужого перебора паролей.
     *
     * <p>У журнала изменений «кто» берётся из `tenant_member` — их десятки,
     * и число их задаёт владелец. Здесь рядом стоят логины, <b>введённые
     * в форме входа</b>: сколько их будет, решает тот, кто подбирает пароль.
     * Без потолка тысяча попыток даёт экрану владельца список на тысячу
     * строк, набранный чужими руками, — и ровно тогда, когда журнал
     * открывают по делу.
     *
     * <p>Свои при этом не теряются: сотрудник в списке остаётся, сколько бы
     * ни было чужих логинов.
     */
    @Test
    @DisplayName("Отбор «кто» не раздувается чужим перебором логинов")
    void attemptedLoginsInFilterAreCapped() throws Exception {
        MockHttpSession owner = login("vladelec", CHROME_ON_WINDOWS);
        inTenant(() -> jdbc.update("""
                INSERT INTO login_session (login_attempted, outcome, failure_reason, started_at)
                SELECT 'podbor-' || i, 'FAILED', 'BAD_CREDENTIALS', now() - (i * interval '1 second')
                  FROM generate_series(1, 150) AS i"""));

        String body = mvc.perform(get("/api/organization/sessions/values")
                        .param("column", "member").session(owner))
                .andExpect(status().isOk())
                // Явная кодировка: без неё MockMvc отдаёт тело как ISO-8859-1,
                // и «Пётр Владельцев» превращается в кракозябры — проверка
                // на длину при этом проходит, а на имя нет.
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> names = new ObjectMapper().readValue(body, new TypeReference<>() { });

        assertThat(names)
                .as("сто чужих логинов плюс сам вошедший владелец: список отбора "
                        + "ограничен сотней последних, иначе его длину задаёт "
                        + "подбиравший пароль, а не владелец")
                .hasSize(101)
                .as("свой человек из списка не выпадает, сколько бы ни было чужих")
                .contains("Пётр Владельцев");
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
            //
            // Имя и роль — тоже, и это не про соседний метод, а про соседний
            // класс. Фикстура, молча принявшая чужое имя для своего логина,
            // подставляет проверкам не того человека: отбор журнала отдавал
            // «Владелец» вместо «Пётр Владельцев», и красный тест указывал
            // не на виноватого. Пишем то, что назвали, а не то, что нашли.
            jdbc.update("""
                            UPDATE tenant_member
                               SET password_hash = ?, is_active = true,
                                   display_name = ?, role = ?
                             WHERE id = ?""",
                    passwordEncoder.encode(PASSWORD), displayName, role, found.get(0));
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
