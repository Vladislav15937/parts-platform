package ru.partsflow.intake;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Двойное нажатие «Завести» при заведении ожидаемой позиции (задача 0170).
 *
 * <p>Проверка «нет ли уже такой позиции» ловит повтор, пришедший после
 * ответа, а два запроса в один момент проходят её оба: между чтением и
 * вставкой второй ещё ничего не видит. Дубль отбивает уникальный индекс
 * {@code part_client_request_uk}, но без ответа на одновременный повтор
 * наружу летело 409 «Операция нарушает целостность данных» — ошибка на
 * заведение, которое прошло, при том что нажимают второй раз именно потому,
 * что первое нажатие не показало результата.
 *
 * <p>Идёт настоящим сервлет-контейнером, а не MockMvc: ответ собирается и
 * сериализуется по-настоящему, а запросы не делят ни поток, ни транзакцию.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class ExpectedPartRetryHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000961";

    /** Сколько одновременных повторов пускаем: одного мало, гонка редкая. */
    private static final int PARALLEL = 6;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 961");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (961, ?, 'Повтор предзаказа', 'predpovtor')""", TENANT);
        inTenant(() -> {
            // Позиции ссылаются на автора, поэтому владельца не удаляем, а обновляем.
            int found = jdbc.update("UPDATE tenant_member SET password_hash = ? WHERE login = 'vladelec'",
                    passwordEncoder.encode("пароль-подлиннее"));
            if (found == 0) {
                jdbc.update("""
                        INSERT INTO tenant_member (login, display_name, password_hash, role)
                        VALUES ('vladelec', 'Владелец', ?, 'OWNER')""",
                        passwordEncoder.encode("пароль-подлиннее"));
            }
            return null;
        });
    }

    @Test
    @DisplayName("Одновременное двойное нажатие заводит одну позицию и отвечает всем успехом")
    void concurrentDoubleSubmitCreatesOnePart() throws Exception {
        Session session = signIn();
        long supply = createSupply(session);
        String requestId = "zavedenie-" + System.nanoTime();
        String body = body(requestId, "Фара левая");

        List<Integer> codes = parallel(PARALLEL, () -> post(session,
                "/api/intake/supplies/%d/expected-parts".formatted(supply), body)
                .getStatusCode().value());

        assertThat(codes)
                .as("двойное нажатие ответило ошибкой на заведение, которое прошло: %s", codes)
                .containsOnly(201);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE client_request_id = ?",
                Integer.class, requestId)))
                .as("повтор завёл вторую позицию")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Повтор после ответа возвращает ту же позицию, другой ключ — новую")
    void sequentialRetryReturnsTheFirstPart() {
        Session session = signIn();
        long supply = createSupply(session);
        String requestId = "posledovatelno-" + System.nanoTime();
        String path = "/api/intake/supplies/%d/expected-parts".formatted(supply);

        ResponseEntity<String> first = post(session, path, body(requestId, "Фара левая"));
        ResponseEntity<String> again = post(session, path, body(requestId, "Фара левая"));
        ResponseEntity<String> other = post(session, path, body("drugoy-" + requestId, "Фара левая"));

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(other.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getBody()).as("повтор вернул не тот же список").isEqualTo(first.getBody());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE supply_id = ?", Integer.class, supply)))
                .as("у поставки две позиции: повторённая и новая")
                .isEqualTo(2);
    }

    // ---------- вспомогательное ----------

    private static String body(String requestId, String name) {
        return """
                {"rawName":"%s","quantity":3,"price":9000,"requestId":"%s"}"""
                .formatted(name, requestId);
    }

    private long createSupply(Session session) {
        ResponseEntity<String> created = post(session, "/api/intake/supplies", """
                {"kind":"CONTAINER","number":"П-%d","supplierName":"Onteco"}"""
                .formatted(System.nanoTime()));
        assertThat(created.getStatusCode().value()).as("поставка не заведена").isEqualTo(201);
        Matcher id = Pattern.compile("\"id\":(\\d+)").matcher(created.getBody());
        assertThat(id.find()).isTrue();
        return Long.parseLong(id.group(1));
    }

    private ResponseEntity<String> post(Session session, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, session.cookies());
        headers.add("X-XSRF-TOKEN", session.csrf());
        return http.postForEntity("http://localhost:" + port + path,
                new HttpEntity<>(body, headers), String.class);
    }

    private record Session(String cookies, String csrf) {
    }

    /**
     * Вход через настоящий HTTP: CSRF-токен берётся заново перед входом, как
     * это делает браузер; cookie сессии зовётся SESSION.
     */
    private Session signIn() {
        ResponseEntity<String> csrfAnswer = http.getForEntity(
                "http://localhost:" + port + "/api/auth/csrf", String.class);
        String csrf = cookie(csrfAnswer, "XSRF-TOKEN");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        headers.add("X-XSRF-TOKEN", csrf);
        ResponseEntity<String> login = http.postForEntity(
                "http://localhost:" + port + "/api/auth/login",
                new HttpEntity<>("""
                        {"company":"predpovtor","login":"vladelec","password":"пароль-подлиннее"}""",
                        headers),
                String.class);
        assertThat(login.getStatusCode().value()).as("вход не удался").isEqualTo(200);
        return new Session("XSRF-TOKEN=" + csrf + "; SESSION=" + cookie(login, "SESSION"), csrf);
    }

    private static String cookie(ResponseEntity<?> answer, String name) {
        List<String> all = answer.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (all == null) {
            return "";
        }
        for (String raw : all) {
            if (raw.startsWith(name + "=")) {
                int end = raw.indexOf(';');
                return raw.substring(name.length() + 1, end < 0 ? raw.length() : end);
            }
        }
        return "";
    }

    /** Пускает n одинаковых запросов разом и собирает коды ответов. */
    private List<Integer> parallel(int n, ThrowingSupplier request) throws Exception {
        List<Integer> codes = new ArrayList<>();
        CyclicBarrier start = new CyclicBarrier(n);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread thread = new Thread(() -> {
                int status;
                try {
                    start.await();
                    status = request.get();
                } catch (Exception e) {
                    status = -1;
                }
                synchronized (codes) {
                    codes.add(status);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        return codes;
    }

    private interface ThrowingSupplier {
        int get() throws Exception;
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }
}
