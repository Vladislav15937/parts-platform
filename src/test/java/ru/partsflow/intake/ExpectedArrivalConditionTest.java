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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Приёмка ожидаемой позиции применяет состояние, выбранное на приёмке (задача 0326).
 *
 * <p>Решение владельца продукта 18.10.2026, «6 - в, с предвыбором прошлого
 * выбора клиента но с возможностью изменить»: состояние ожидаемой выбирают
 * заранее, по контейнеру в пути, а приезжает бывает другое. До задачи
 * приёмка принимала позицию «как есть», и поправить состояние можно было
 * только потом в карточке — а позиция «Не указано» до того на Дром не уходит.
 *
 * <p>Через настоящий Tomcat: состояние приходит телом запроса, отказ — словами
 * обработчика ошибок, история — из {@code audit_log}, который пишет слушатель
 * Hibernate, а прайс — потоком.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class ExpectedArrivalConditionTest extends PostgresTestBase {

    private static final String TENANT = "t_000968";
    private static final String CODE = "prihod0326";
    private static final String TOKEN = "e".repeat(43);

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

    private Long warehouse;
    private Long farWarehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 968");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (968, ?, 'Состояние на приёмке', ?)""", TENANT, CODE);
        List<Long> both = inTenant(() -> {
            int found = jdbc.update("""
                    UPDATE tenant_member SET password_hash = ?, display_name = 'Владелец'
                     WHERE login = 'vladelec'""",
                    passwordEncoder.encode("пароль-подлиннее"));
            if (found == 0) {
                jdbc.update("""
                        INSERT INTO tenant_member (login, display_name, password_hash, role)
                        VALUES ('vladelec', 'Владелец', ?, 'OWNER')""",
                        passwordEncoder.encode("пароль-подлиннее"));
            }
            // Прайс и счётчик сверяются с числом строк: позиции прошлых методов
            // их бы сдвинули.
            jdbc.update("UPDATE part SET is_published = false");
            jdbc.update("DELETE FROM marketplace_account");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long near = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            Long far = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            return List.of(near, far);
        });
        warehouse = both.get(0);
        farWarehouse = both.get(1);
    }

    @Test
    @DisplayName("Заказали «б/у», пришло «контрактное»: позиция на складе контрактной, в ленте правок «было/стало», название то же")
    void otherConditionIsAppliedAndLogged() {
        Session session = signIn();
        long supply = createSupply(session);
        long part = addExpected(session, supply, "Фара заказана б/у", "USED");
        String title = titleOf(part);

        ResponseEntity<String> receipt = receive(session, supply, warehouse, part, 2,
                "\"condition\":\"CONTRACT\",", key());

        assertThat(receipt.getStatusCode().value()).as(receipt.getBody()).isEqualTo(201);
        assertThat(condition(part)).as("приёмка не применила выбранное состояние")
                .isEqualTo("CONTRACT");
        assertThat(stock(part, warehouse)).isEqualByComparingTo("2");
        assertThat(titleOf(part)).as("название меняет только человек (0304)").isEqualTo(title);
        // Сущность пишется целиком, а остаток и статус проведение ставит мимо
        // неё: отложенная запись состояния вернула бы позиции черновик и ноль.
        assertThat(inTenant(() -> jdbc.queryForMap(
                "SELECT status, qty_on_hand FROM part WHERE id = ?", part)))
                .as("запись состояния затёрла то, что поставило проведение")
                .containsEntry("status", "IN_STOCK")
                .hasEntrySatisfying("qty_on_hand", qty ->
                        assertThat((BigDecimal) qty).isEqualByComparingTo("2"));

        String history = get(session, "/api/parts/%d/history".formatted(part)).getBody();
        assertThat(history).as("в ленте правок нет смены состояния")
                .contains("\"author\":\"Владелец\"")
                .contains("{\"label\":\"Состояние\",\"before\":\"Б/у\",\"after\":\"Контрактная\"");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_change WHERE part_id = ?", Integer.class, part)))
                .as("Дром не узнает о смене состояния дельтой").isEqualTo(1);
    }

    @Test
    @DisplayName("Приехало то же — состояние не тронуто и правки в журнале нет")
    void sameConditionIsNotAnEdit() {
        Session session = signIn();
        long supply = createSupply(session);
        long part = addExpected(session, supply, "Фара заказана новая", "NEW");

        ResponseEntity<String> receipt = receive(session, supply, warehouse, part, 1,
                "\"condition\":\"NEW\",", key());

        assertThat(receipt.getStatusCode().value()).as(receipt.getBody()).isEqualTo(201);
        assertThat(condition(part)).isEqualTo("NEW");
        assertThat(get(session, "/api/parts/%d/history".formatted(part)).getBody())
                .doesNotContain("\"label\":\"Состояние\"");
    }

    @Test
    @DisplayName("Без ключа «condition» состояние не тронуто; «Не указано» у позиции без состояния — приём проходит, состояния нет")
    void absentOrUnsetLeavesTheCondition() {
        Session session = signIn();
        long supply = createSupply(session);
        long used = addExpected(session, supply, "Фара старая вкладка", "USED");
        long unset = addExpected(session, supply, "Фара без состояния", "");

        ResponseEntity<String> oldTab = receive(session, supply, warehouse, used, 1, "", key());
        ResponseEntity<String> keptUnset = receive(session, supply, warehouse, unset, 1,
                "\"condition\":null,", key());
        ResponseEntity<String> blank = receive(session, supply, warehouse, unset, 1,
                "\"condition\":\"\",", key());

        assertThat(List.of(oldTab.getStatusCode().value(), keptUnset.getStatusCode().value(),
                blank.getStatusCode().value())).containsOnly(201);
        assertThat(condition(used)).as("вкладка без поля сбросила состояние").isEqualTo("USED");
        assertThat(condition(unset)).as("«Не указано» подставилось умолчанием").isNull();
        assertThat(stock(unset, warehouse)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Незнакомое состояние — 400 словами, ничего не записано и остаток прежний")
    void unknownConditionIsRefusedInWords() {
        Session session = signIn();
        long supply = createSupply(session);
        long part = addExpected(session, supply, "Фара чужое слово", "USED");

        ResponseEntity<String> answer = receive(session, supply, warehouse, part, 1,
                "\"condition\":\"OLD\",", key());

        assertThat(answer.getStatusCode().value()).as(answer.getBody()).isEqualTo(400);
        assertThat(answer.getBody()).contains("Состояние «OLD» не знакомо")
                .contains("контрактная").doesNotContain("Запрос не разобран");
        assertThat(condition(part)).isEqualTo("USED");
        assertThat(stock(part, warehouse)).isEqualByComparingTo("0");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM stock_document WHERE supply_id = ?", Integer.class, supply)))
                .as("отказ завёл документ").isZero();
    }

    @Test
    @DisplayName("Повтор по ключу с другим состоянием отдаёт первый результат; шесть одновременных — ни ошибки, ни второго прихода")
    void replayKeepsTheFirstResult() throws Exception {
        Session session = signIn();
        long supply = createSupply(session);
        long part = addExpected(session, supply, "Фара повтор", "USED");
        String requestId = key();

        ResponseEntity<String> first = receive(session, supply, warehouse, part, 1,
                "\"condition\":\"NEW\",", requestId);
        ResponseEntity<String> again = receive(session, supply, warehouse, part, 1,
                "\"condition\":\"REFURBISHED\",", requestId);

        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(condition(part)).as("первая приёмка не применила состояние или повтор его переписал")
                .isEqualTo("NEW");

        long racing = addExpected(session, supply, "Фара гонка", "USED");
        String raceKey = key();
        List<Integer> codes = parallel(6, () -> receive(session, supply, warehouse, racing, 1,
                "\"condition\":\"CONTRACT\",", raceKey).getStatusCode().value());

        assertThat(codes).as("одновременный повтор получил ошибку: %s", codes).containsOnly(201);
        assertThat(stock(racing, warehouse)).as("повтор принял деталь второй раз")
                .isEqualByComparingTo("1");
        assertThat(condition(racing)).isEqualTo("CONTRACT");
    }

    @Test
    @DisplayName("Второй приход на другой склад: состояние применяется снова, последний выигрывает, в ленте обе правки")
    void secondArrivalWins() {
        Session session = signIn();
        long supply = createSupply(session);
        long part = addExpected(session, supply, "Фара частями", "USED");

        assertThat(receive(session, supply, warehouse, part, 1, "\"condition\":\"NEW\",", key())
                .getStatusCode().value()).isEqualTo(201);
        assertThat(receive(session, supply, farWarehouse, part, 1,
                "\"condition\":\"CONTRACT\",", key()).getStatusCode().value()).isEqualTo(201);

        assertThat(condition(part)).isEqualTo("CONTRACT");
        assertThat(stock(part, warehouse)).isEqualByComparingTo("1");
        assertThat(stock(part, farWarehouse)).isEqualByComparingTo("1");
        assertThat(get(session, "/api/parts/%d/history".formatted(part)).getBody())
                .contains("\"before\":\"Б/у\",\"after\":\"Новая\"")
                .contains("\"before\":\"Новая\",\"after\":\"Контрактная\"");
    }

    @Test
    @DisplayName("Принятая с состоянием уходит в прайс Дрома и в счётчик, оставленная «Не указано» — нет")
    void acceptedWithConditionGoesToDrom() {
        Session session = signIn();
        long supply = createSupply(session);
        long chosen = addExpected(session, supply, "Капот выбран на приёмке", "");
        long left = addExpected(session, supply, "Капот оставлен пустым", "");
        inTenant(() -> jdbc.update("""
                INSERT INTO marketplace_account (marketplace, title, settings, feed_token)
                VALUES ('DROM', 'Приёмка', '{"packetId":"1"}'::jsonb, ?)""", TOKEN));

        assertThat(receive(session, supply, warehouse, chosen, 2, "\"condition\":\"NEW\",", key())
                .getStatusCode().value()).isEqualTo(201);
        assertThat(receive(session, supply, warehouse, left, 2, "\"condition\":\"\",", key())
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> feed = http.getForEntity(
                "http://localhost:%d/feeds/drom/%s/%s.xml".formatted(port, CODE, TOKEN), String.class);
        assertThat(feed.getStatusCode().value()).isEqualTo(200);
        String body = feed.getBody();
        assertThat(body).as("принятая с состоянием не ушла на Дром")
                .isNotNull().contains("Капот выбран на приёмке").doesNotContain("оставлен пустым");
        int offers = body.split("<part>", -1).length - 1;
        assertThat(offers).isEqualTo(1);
        ResponseEntity<String> count = exchange(session, HttpMethod.POST,
                "/api/marketplace-accounts/filter/count", "{}");
        assertThat(count.getBody()).isEqualTo("{\"parts\":" + offers + "}");
    }

    // ---------- вспомогательное ----------

    private ResponseEntity<String> receive(Session session, long supply, long warehouseId, long part,
                                           int quantity, String condition, String requestId) {
        return exchange(session, HttpMethod.POST, "/api/intake/receipts", """
                {"warehouseId":%d,"supplyId":%d,"requestId":"%s","items":[
                  {"rawName":"принимаем","quantity":%d,"price":9000,%s"partId":%d}]}"""
                .formatted(warehouseId, supply, requestId, quantity, condition, part));
    }

    private long addExpected(Session session, long supply, String name, String condition) {
        ResponseEntity<String> answer = exchange(session, HttpMethod.POST,
                "/api/intake/supplies/%d/expected-parts".formatted(supply), """
                        {"rawName":"%s","condition":"%s","quantity":2,"price":9000,"requestId":"%s"}"""
                        .formatted(name, condition, key()));
        assertThat(answer.getStatusCode().value()).as(answer.getBody()).isEqualTo(201);
        return inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part WHERE supply_id = ? AND title ILIKE ?",
                Long.class, supply, name + "%"));
    }

    private static String key() {
        return "k-" + System.nanoTime();
    }

    private String condition(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT condition FROM part WHERE id = ?", String.class, part));
    }

    private String titleOf(long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, part));
    }

    private BigDecimal stock(long part, long warehouseId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT coalesce(sum(qty), 0) FROM part_stock WHERE part_id = ? AND warehouse_id = ?""",
                BigDecimal.class, part, warehouseId));
    }

    private long createSupply(Session session) {
        ResponseEntity<String> created = exchange(session, HttpMethod.POST, "/api/intake/supplies", """
                {"kind":"CONTAINER","number":"П-%d","supplierName":"Onteco"}"""
                .formatted(System.nanoTime()));
        assertThat(created.getStatusCode().value()).as("поставка не заведена").isEqualTo(201);
        Matcher id = Pattern.compile("\"id\":(\\d+)").matcher(created.getBody());
        assertThat(id.find()).isTrue();
        return Long.parseLong(id.group(1));
    }

    private ResponseEntity<String> get(Session session, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session.cookies());
        return http.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> exchange(Session session, HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, session.cookies());
        headers.add("X-XSRF-TOKEN", session.csrf());
        return http.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>(body, headers), String.class);
    }

    /** Пускает n одинаковых запросов разом и собирает коды ответов. */
    private static List<Integer> parallel(int n, Supplier<Integer> request) throws Exception {
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

    private record Session(String cookies, String csrf) {
    }

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
                        {"company":"%s","login":"vladelec","password":"пароль-подлиннее"}"""
                        .formatted(CODE), headers),
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

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }
}
