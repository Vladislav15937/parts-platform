package ru.partsflow.platform.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Самостоятельная регистрация компании клиентом (задача 0184).
 *
 * <p>Всё идёт через HTTP, а не вызовом сервиса: ответ наружу — record, и
 * тест, зовущий сервис, не увидел бы, что Jackson его не сериализует. А
 * одновременность проверяется настоящими параллельными запросами — на
 * разнице между последовательным повтором и шестью разом проект попадался
 * трижды.
 *
 * <p>Схем с фиксированными номерами класс не занимает: компании заводятся
 * самой регистрацией и берут следующий свободный номер.
 */
@SpringBootTest(properties = {
        "app.registration-pow-bits=12",
        "app.registration-max-per-hour=100"})
@AutoConfigureMockMvc
class RegistrationTest extends PostgresTestBase {

    private static final String PASSWORD = "пароль-8симв";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private RegistrationLimits limits;

    @Test
    @DisplayName("Зарегистрированная компания рабочая: владелец входит, наружу не уезжает внутренний номер")
    void registeredCompanyIsUsable() throws Exception {
        String code = uniqueCode();
        Map<String, String> form = form(code, uniqueEmail());

        MvcResult created = register(form, "10.84.0.1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyCode").value(code))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();

        // Номер схемы и номер в реестре человеку не нужны и по нему ничего
        // не найти: код компании — то, что он наберёт на входе.
        assertThat(bodyOf(created)).doesNotContain("t_0").doesNotContain("schema")
                .doesNotContain("tenantId");

        MockHttpSession session = login(code, form.get("ownerLogin"), PASSWORD);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));

        // Почта хранится как введена и отдельным ключом — как нужно, чтобы
        // подтверждение встало потом без переноса данных.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, owner_email, owner_email_key FROM public.tenant_registry "
                        + "WHERE code = ?", code);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("owner_email")).isEqualTo(form.get("email").strip());
        assertThat(row.get("owner_email_key")).isEqualTo(form.get("email").strip().toLowerCase());
    }

    @Test
    @DisplayName("Почта уникальна по всей ячейке: без учёта регистра и пробелов, словами и без новой компании")
    void emailIsUniqueAcrossTheCell() throws Exception {
        String email = "Хозяин." + UUID.randomUUID().toString().substring(0, 8) + "@Example.RU";
        register(form(uniqueCode(), email), "10.84.0.2").andExpect(status().isCreated());

        long before = tenantCount();
        MvcResult refused = register(form(uniqueCode(), "  " + email.toLowerCase() + " "),
                "10.84.0.2")
                .andExpect(status().isConflict())
                .andReturn();

        // Не «операция нарушает целостность данных»: человек должен понять,
        // что делать, — а не искать поломку сервера.
        assertThat(bodyOf(refused)).contains("уже зарегистрирована");
        assertThat(bodyOf(refused)).doesNotContain("целостност");
        assertThat(tenantCount()).as("отказанная регистрация не заводит компанию").isEqualTo(before);
    }

    @Test
    @DisplayName("Занятый код говорит «занят», а освобождённая отказом почта не залипает")
    void takenCodeIsSaidInWords() throws Exception {
        String code = uniqueCode();
        register(form(code, uniqueEmail()), "10.84.0.3").andExpect(status().isCreated());

        long before = tenantCount();
        String email = uniqueEmail();
        MvcResult refused = register(form(code, email), "10.84.0.3")
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(bodyOf(refused)).contains("занят").doesNotContain("целостност");
        assertThat(tenantCount()).isEqualTo(before);

        // Отказ по коду не должен был занять почту: человек поправит код
        // и отправит ту же форму ещё раз.
        register(form(uniqueCode(), email), "10.84.0.3").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Поля проверяются словами до всего остального, и ничего не заводится")
    void fieldsAreCheckedInWords() throws Exception {
        long before = tenantCount();

        assertRefused(with(form(uniqueCode(), uniqueEmail()), "ownerPassword", "семь567"),
                "минимум 8");
        // Поля — раньше вычислительной проверки: человек с опечаткой в пароле
        // не должен услышать «проверка устарела» и не должен тратить на неё
        // место в пределе.
        assertRefused(with(with(form(uniqueCode(), uniqueEmail()), "ownerPassword", "семь567"),
                "challenge", "мусор"), "минимум 8");
        assertRefused(with(form(uniqueCode(), uniqueEmail()), "companyCode", "Разборка №1"),
                "Код компании");
        assertRefused(with(form(uniqueCode(), uniqueEmail()), "companyCode", "a"),
                "от 2 до 31");
        assertRefused(with(form(uniqueCode(), "не-почта"), "email", "не-почта"),
                "почту");
        assertRefused(with(form(uniqueCode(), uniqueEmail()), "companyName", "  "),
                "название компании");
        assertRefused(with(form(uniqueCode(), uniqueEmail()), "ownerLogin", ""),
                "логин");
        // Больше 72 байт хеш молча обрезал бы: человек не узнал бы, что хвост
        // пароля не работает.
        assertRefused(with(form(uniqueCode(), uniqueEmail()), "ownerPassword", "п".repeat(40)),
                "72");

        assertThat(tenantCount()).as("ни одна отказанная форма не завела компанию").isEqualTo(before);
    }

    @Test
    @DisplayName("Решение вычислительной проверки: чужое, неверное и чужому ключу — отказ")
    void challengeIsEnforced() throws Exception {
        long before = tenantCount();
        Map<String, String> form = form(uniqueCode(), uniqueEmail());

        // Неверное решение: ищем число, которое задание не удовлетворяет.
        String challenge = form.get("challenge");
        String wrong = null;
        for (long n = 0; wrong == null; n++) {
            if (!meets(challenge, Long.toString(n), 12)) {
                wrong = Long.toString(n);
            }
        }
        MvcResult notSolved = register(with(form, "solution", wrong), "10.84.0.4")
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(bodyOf(notSolved)).contains("Проверка");

        // Подделанное задание не принимается, даже с верным «решением» для него.
        String forged = "v1.9999999999.00000000deadbeef." + "0".repeat(64);
        register(with(with(form, "challenge", forged), "solution",
                solve(forged, 12)), "10.84.0.4").andExpect(status().isBadRequest());

        // Одно решение — одна регистрация: иначе его раздавали бы сотне.
        Map<String, String> first = form(uniqueCode(), uniqueEmail());
        register(first, "10.84.0.4").andExpect(status().isCreated());
        Map<String, String> second = with(form(uniqueCode(), uniqueEmail()),
                "challenge", first.get("challenge"));
        second = with(second, "solution", first.get("solution"));
        register(second, "10.84.0.4").andExpect(status().isBadRequest());

        assertThat(tenantCount()).as("создана только первая").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("Повтор того же запроса отдаёт результат первого и не заводит вторую компанию")
    void sequentialReplayReturnsTheFirstResult() throws Exception {
        String code = uniqueCode();
        Map<String, String> form = form(code, uniqueEmail());

        register(form, "10.84.0.5").andExpect(status().isCreated());
        long before = tenantCount();

        // «Ответ не дошёл, нажал ещё раз» — не ошибка.
        register(form, "10.84.0.5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyCode").value(code))
                .andExpect(jsonPath("$.replayed").value(true));

        assertThat(tenantCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("Шесть одновременных одинаковых запросов: одна компания, всем один и тот же ответ")
    void sixSimultaneousRequestsCreateOneCompany() throws Exception {
        String code = uniqueCode();
        Map<String, String> form = form(code, uniqueEmail());
        long before = tenantCount();

        int count = 6;
        var barrier = new CyclicBarrier(count);
        var pool = Executors.newFixedThreadPool(count);
        List<Integer> statuses = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        try {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> (Callable<MvcResult>) () -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        return register(form, "10.84.0.6").andReturn();
                    })
                    .toList());
            for (var result : results) {
                MvcResult r = result.get();
                statuses.add(r.getResponse().getStatus());
                codes.add(json.readTree(bodyOf(r)).path("companyCode").asText());
            }
        } finally {
            pool.shutdownNow();
        }

        // Ни 409 «целостность», ни 500: проигравший уникальному индексу
        // получает результат победителя. Последовательный повтор работал
        // правильно с самого начала — ломалось только на одновременном.
        assertThat(statuses).as("ответы %s", statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201).count())
                .as("создала компанию ровно одна попытка").isEqualTo(1);
        assertThat(codes).containsOnly(code);
        assertThat(tenantCount()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.tenant_registry WHERE registration_key = ?",
                Integer.class, form.get("requestId"))).isEqualTo(1);
    }

    @Test
    @DisplayName("Сорвавшаяся регистрация отпускает почту: человек может попробовать ещё раз")
    void failedRegistrationReleasesTheEmail() throws Exception {
        String email = uniqueEmail();
        String code = uniqueCode();

        // Следующий номер занимаем схемой заранее — заведение споткнётся
        // уже после того, как номер, код и почта заняты. Так же срывается
        // оно от кончившегося места или упавшего наката.
        long next = jdbc.queryForObject(
                "SELECT GREATEST(COALESCE(max(tenant_id), 0), 1000000) + 1 "
                        + "FROM public.tenant_registry", Long.class);
        String blocker = "t_%06d".formatted(next);
        jdbc.execute("CREATE SCHEMA " + blocker);
        try {
            Map<String, String> form = form(code, email);
            register(form, "10.84.0.7").andExpect(status().isConflict());

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, owner_email_key, registration_key, owner_email "
                            + "FROM public.tenant_registry WHERE code = ?", code);
            assertThat(row.get("status")).as("запись брошена видимо, не убрана").isEqualTo("PROVISIONING");
            assertThat(row.get("owner_email_key")).as("почта отпущена").isNull();
            assertThat(row.get("registration_key")).as("ключ отпущен").isNull();
            assertThat(row.get("owner_email")).as("введённое не теряем").isEqualTo(email);

            // Без этого человек не зарегистрировался бы на свою почту никогда:
            // брошенные компании не снимаются, а почта в записи осталась бы.
            register(form(uniqueCode(), email), "10.84.0.7").andExpect(status().isCreated());
        } finally {
            jdbc.update("DELETE FROM public.tenant_registry WHERE code = ?", code);
            jdbc.execute("DROP SCHEMA IF EXISTS " + blocker + " CASCADE");
        }
    }

    @Test
    @DisplayName("Управляющий контур остаётся закрытым: регистрация его секрет не использует и не обходит")
    void provisioningStaysClosed() throws Exception {
        // Секрета в этом контексте нет, и контур отвечает отказом, как раньше.
        mvc.perform(post("/api/provisioning/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"чужой","companyCode":"%s","companyName":"К",
                                 "ownerLogin":"o","ownerPassword":"пароль-8симв"}"""
                                .formatted(uniqueCode())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Заполненность ячейки видна числами: сколько компаний и какой потолок")
    void fillingIsExposedAsNumbers() {
        assertThat(metrics.get("partsflow.registration.tenants").gauge().value())
                .isEqualTo((double) tenantCount());
        assertThat(metrics.get("partsflow.registration.limit").gauge().value())
                .isEqualTo(200.0);
        // Предупреждение владельцу — на четырёх пятых потолка: 160 из 200.
        assertThat(limits.warnAt()).isEqualTo(160);
    }

    @Test
    @DisplayName("Предел с адреса: после него 429, место возвращается за повтор, у другого адреса свой счёт")
    void rateLimitPerAddress() {
        RegistrationLimits limits = new RegistrationLimits(directJdbc(),
                new SimpleMeterRegistry(), 2, 1_000_000);

        limits.beforeRegister("10.99.0.1");
        limits.beforeRegister("10.99.0.1");
        assertThatThrownBy(() -> limits.beforeRegister("10.99.0.1"))
                .isInstanceOf(ProvisioningLimits.TooManyRequests.class)
                .hasMessageContaining("слишком много попыток");

        // Другой адрес не задет.
        limits.beforeRegister("10.99.0.2");

        // Повтор уже принятой регистрации место возвращает: двойное нажатие
        // не расходует предел. Отбитая попытка его тоже не занимает —
        // иначе долбёжка держала бы адрес закрытым вечно.
        limits.replayed("10.99.0.2");
        limits.beforeRegister("10.99.0.2");
        limits.beforeRegister("10.99.0.2");
        assertThatThrownBy(() -> limits.beforeRegister("10.99.0.2"))
                .isInstanceOf(ProvisioningLimits.TooManyRequests.class);
    }

    @Test
    @DisplayName("IPv6 считается подсетью /64: десять адресов из одной исчерпывают один предел, соседняя подсеть не задета")
    void rateLimitCountsIpv6BySubnet() {
        RegistrationLimits limits = new RegistrationLimits(directJdbc(),
                new SimpleMeterRegistry(), 10, 1_000_000);

        // Десять разных адресов одной /64: хост с провайдерской подсетью
        // сам выбирает младшие 64 бита, и по полному адресу у него был бы
        // свежий счётчик на каждую попытку.
        for (int i = 1; i <= 10; i++) {
            limits.beforeRegister("2001:db8:1:2::" + Integer.toHexString(0x1000 + i));
        }
        assertThatThrownBy(() -> limits.beforeRegister("2001:db8:1:2:ffff:eeee:dddd:1"))
                .as("одиннадцатый адрес той же подсети — в тот же предел")
                .isInstanceOf(ProvisioningLimits.TooManyRequests.class);

        // Соседняя /64 — другой хост, у него свой счёт.
        limits.beforeRegister("2001:db8:1:3::1");

        // IPv4 — адрес целиком, а не «подсеть»: соседний в той же /24 свободен.
        for (int i = 0; i < 10; i++) {
            limits.beforeRegister("10.77.0.1");
        }
        assertThatThrownBy(() -> limits.beforeRegister("10.77.0.1"))
                .isInstanceOf(ProvisioningLimits.TooManyRequests.class);
        limits.beforeRegister("10.77.0.2");

        // IPv4 в записи IPv6 — тот же счёт, что у обычной записи.
        assertThatThrownBy(() -> limits.beforeRegister("::ffff:10.77.0.1"))
                .isInstanceOf(ProvisioningLimits.TooManyRequests.class);
    }

    @Test
    @DisplayName("Карта адресов переполнена: новый ключ вытесняет самый давний, а не получает 429 — и чужой счёт не обнуляется заодно")
    void fullAddressMapDoesNotCloseRegistrationForEveryone() {
        RegistrationLimits limits = new RegistrationLimits(directJdbc(),
                new SimpleMeterRegistry(), 2, 1_000_000);

        // Недавний честный адрес с одной израсходованной попыткой из двух.
        limits.beforeRegister("10.90.255.1");
        // 4096 прочих ключей заполняют карту целиком (честный уже в ней).
        for (int i = 0; i < 4095; i++) {
            limits.beforeRegister("10.91." + (i / 250) + "." + (i % 250 + 1));
        }
        // Честный приходит снова — теперь он самый свежий.
        limits.beforeRegister("10.90.255.1");

        // 4097-й ключ: раньше отвечал 429 «слишком много регистраций» всем.
        limits.beforeRegister("10.92.0.1");
        limits.beforeRegister("10.92.0.2");

        // Вытеснен самый давний, а честный с двумя попытками остался на счёте:
        // третья ему отказывает, как и обязана.
        assertThatThrownBy(() -> limits.beforeRegister("10.90.255.1"))
                .isInstanceOf(ProvisioningLimits.TooManyRequests.class)
                .hasMessageContaining("слишком много попыток");
    }

    @Test
    @DisplayName("Неразрывный пробел по краям почты срезается, как на экране; внутри — отказ, как на экране")
    void nonBreakingSpaceIsTrimmedLikeOnTheScreen() throws Exception {
        String email = uniqueEmail();
        String code = uniqueCode();
        register(form(code, "\u00A0" + email + "\u00A0"), "10.84.0.40")
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "SELECT owner_email FROM public.tenant_registry WHERE code = ?", String.class, code))
                .isEqualTo(email);

        // Внутри адреса пробел любого вида — не почта, и экран говорит то же.
        assertRefused(form(uniqueCode(), "ivan\u00A0petrov@example.ru"), "почту");
    }

    private JdbcTemplate directJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    // --- помощники ---------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions register(
            Map<String, String> form, String address) throws Exception {
        return mvc.perform(post("/api/registration/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form))
                .with(from(address)));
    }

    private void assertRefused(Map<String, String> form, String fragment) throws Exception {
        MvcResult refused = register(form, "10.84.0.9")
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(bodyOf(refused)).containsIgnoringCase(fragment);
    }

    /**
     * Готовая форма с настоящим заданием и его решением.
     *
     * <p>Задание берётся у самого сервера, как у браузера: подпись и срок
     * проверяются те же, что в бою.
     */
    private Map<String, String> form(String code, String email) throws Exception {
        MvcResult issued = mvc.perform(get("/api/registration/challenge"))
                .andExpect(status().isOk()).andReturn();
        var node = json.readTree(bodyOf(issued));
        String challenge = node.get("challenge").asText();
        int bits = node.get("bits").asInt();
        assertThat(bits).isEqualTo(12);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("requestId", UUID.randomUUID().toString());
        form.put("challenge", challenge);
        form.put("solution", solve(challenge, bits));
        form.put("companyName", "Разборка " + code);
        form.put("companyCode", code);
        form.put("email", email);
        form.put("ownerLogin", "hozyain");
        form.put("ownerPassword", PASSWORD);
        form.put("ownerName", "Пётр Владельцев");
        return form;
    }

    private static Map<String, String> with(Map<String, String> form, String field, String value) {
        Map<String, String> copy = new LinkedHashMap<>(form);
        copy.put(field, value);
        return copy;
    }

    private static String solve(String challenge, int bits) {
        for (long n = 0; ; n++) {
            if (meets(challenge, Long.toString(n), bits)) {
                return Long.toString(n);
            }
        }
    }

    private static boolean meets(String challenge, String solution, int bits) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((challenge + ":" + solution).getBytes(StandardCharsets.UTF_8));
            return RegistrationChallenge.leadingZeroBits(hash) >= bits;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpSession login(String company, String login, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"%s","password":"%s"}"""
                                .formatted(company, login, password)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long tenantCount() {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_registry", Long.class);
    }

    /** Запрос как бы с этого адреса: предел считается по адресу. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    /** {@code getContentAsString()} кириллицу портит: читаем байты как UTF-8. */
    private static String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Реестр общий на весь прогон и не чистится: код и почта свои у каждого вызова. */
    private static String uniqueCode() {
        return "reg" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueEmail() {
        return "owner." + UUID.randomUUID().toString().substring(0, 8) + "@example.ru";
    }
}
