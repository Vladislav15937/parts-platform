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
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.publishing.drom.DromPriceGenerator;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ожидаемая позиция заводится с состоянием или без него (задача 0322).
 *
 * <p>Решение владельца продукта 18.10.2026 — «8-а с возможностью заводить без
 * состояния»: форма предлагает «б/у», можно выбрать любое из четырёх
 * или «Не указано». До этого состояние не спрашивалось, и сущность молча
 * ставила «б/у» — слово, которое после 0318 видят продавец и покупатель
 * предзаказа.
 *
 * <p>Позиция без состояния — новое для системы значение: {@code part.condition}
 * был {@code NOT NULL} с первого дня, и места, читающие его, этого ждали.
 * Самое опасное — прайс Дрома: состояние там пишется посреди потока ответа,
 * и падение на пустом даёт 200 с обрывком файла, который площадка читает как
 * «снять все объявления». MockMvc этого не видит (ответ в памяти), поэтому
 * класс поднят настоящим Tomcat.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class ExpectedPartConditionTest extends PostgresTestBase {

    private static final String TENANT = "t_000964";
    private static final String CODE = "sost0322";
    private static final String TOKEN = "c".repeat(43);

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

    @Autowired
    private StockLedger ledger;

    @Autowired
    private DromPriceGenerator generator;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 964");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (964, ?, 'Состояние ожидаемого', ?)""", TENANT, CODE);
        warehouse = inTenant(() -> {
            int found = jdbc.update("UPDATE tenant_member SET password_hash = ? WHERE login = 'vladelec'",
                    passwordEncoder.encode("пароль-подлиннее"));
            if (found == 0) {
                jdbc.update("""
                        INSERT INTO tenant_member (login, display_name, password_hash, role)
                        VALUES ('vladelec', 'Владелец', ?, 'OWNER')""",
                        passwordEncoder.encode("пароль-подлиннее"));
            }
            // Склад каждого прогона свой: прайс и счётчик сверяются с числом
            // строк, и чужие позиции из прошлого метода их бы сдвинули.
            jdbc.update("UPDATE part SET is_published = false");
            jdbc.update("DELETE FROM marketplace_account");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            return jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                    Long.class, branch);
        });
    }

    @Test
    @DisplayName("Каждое из четырёх состояний сохраняется и приезжает в список поставки")
    void eachConditionIsSaved() {
        Session session = signIn();
        long supply = createSupply(session);
        for (String code : List.of("NEW", "USED", "REFURBISHED", "CONTRACT")) {
            String name = "Фара " + code.toLowerCase();
            ResponseEntity<String> answer = addExpected(session, supply, name,
                    "\"condition\":\"" + code + "\",");

            assertThat(answer.getStatusCode().value()).as("состояние %s не завелось", code)
                    .isEqualTo(201);
            assertThat(conditionOf(supply, name)).as("сохранилось не то, что выбрали")
                    .isEqualTo(code);
            assertThat(answer.getBody()).contains("\"condition\":\"" + code + "\"");
        }
    }

    @Test
    @DisplayName("«Не указано» — пустое и отсутствующее — заводит позицию без состояния")
    void emptyConditionMeansNone() {
        Session session = signIn();
        long supply = createSupply(session);

        ResponseEntity<String> empty = addExpected(session, supply, "Фара пустая", "\"condition\":\"\",");
        ResponseEntity<String> spaces = addExpected(session, supply, "Фара пробелы", "\"condition\":\"  \",");
        ResponseEntity<String> absent = addExpected(session, supply, "Фара без ключа", "");

        assertThat(List.of(empty.getStatusCode().value(), spaces.getStatusCode().value(),
                absent.getStatusCode().value())).containsOnly(201);
        assertThat(conditionOf(supply, "Фара пустая"))
                .as("«Не указано» сохранилось состоянием — сущность подставила своё умолчание")
                .isNull();
        assertThat(conditionOf(supply, "Фара пробелы")).isNull();
        assertThat(conditionOf(supply, "Фара без ключа")).isNull();
        assertThat(absent.getBody()).doesNotContain("\"condition\":\"USED\"");
    }

    @Test
    @DisplayName("Незнакомое состояние отбивается словами, а не пятисоткой, и ничего не заводит")
    void unknownConditionIsRefusedInWords() {
        Session session = signIn();
        long supply = createSupply(session);

        ResponseEntity<String> answer = addExpected(session, supply, "Фара чужая",
                "\"condition\":\"OLD\",");

        assertThat(answer.getStatusCode().value())
                .as("незнакомое состояние: ответ %s", answer.getBody()).isEqualTo(400);
        assertThat(answer.getBody()).contains("Состояние «OLD» не знакомо")
                .contains("б/у").contains("Не указано");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE supply_id = ?", Integer.class, supply)))
                .as("отказ завёл позицию").isZero();
    }

    @Test
    @DisplayName("Повтор по ключу отдаёт первую позицию с её состоянием")
    void replayKeepsTheFirstCondition() {
        Session session = signIn();
        long supply = createSupply(session);
        String key = "povtor-" + System.nanoTime();
        String path = "/api/intake/supplies/%d/expected-parts".formatted(supply);

        ResponseEntity<String> first = post(session, path, """
                {"rawName":"Фара повтор","condition":"CONTRACT","quantity":2,"price":9000,"requestId":"%s"}"""
                .formatted(key));
        ResponseEntity<String> again = post(session, path, """
                {"rawName":"Фара повтор","condition":"","quantity":2,"price":9000,"requestId":"%s"}"""
                .formatted(key));

        assertThat(again.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE supply_id = ?", Integer.class, supply))).isEqualTo(1);
        assertThat(conditionOf(supply, "Фара повтор")).isEqualTo("CONTRACT");
    }

    /**
     * Прайс целиком, а позиции без состояния в нём нет.
     *
     * <p>Два пути к пустому состоянию в файле: ожидаемая позиция по поставке
     * в пути (выгрузка «товар в пути» её берёт) и она же, принятая на склад
     * (приёмка состояние ожидаемой не трогает). Обе обязаны остаться за
     * бортом, и файл — дописаться до конца: до правки писатель падал на
     * {@code switch} посреди потока.
     */
    @Test
    @DisplayName("Прайс Дрома дописывается до конца и не несёт позицию без состояния — ни ожидаемую, ни на складе")
    void feedIsWholeAndLeavesThePartWithoutConditionOut() {
        Session session = signIn();
        long supply = createSupply(session);
        assertThat(addExpected(session, supply, "Фара ожидаемая контрактная",
                "\"condition\":\"CONTRACT\",").getStatusCode().value()).isEqualTo(201);
        assertThat(addExpected(session, supply, "Фара ожидаемая неизвестная", "\"condition\":\"\",")
                .getStatusCode().value()).isEqualTo(201);
        inTenant(() -> {
            stocked("Фара на складе новая", "NEW");
            stocked("Фара на складе неизвестная", null);
            jdbc.update("""
                    INSERT INTO marketplace_account (marketplace, title, settings, feed_token)
                    VALUES ('DROM', 'Состояния', ?::jsonb, ?)""",
                    "{\"packetId\":\"1\",\"expectedGoods\":true,"
                            + "\"expectedGoodsNote\":\"Ожидается поступление\"}", TOKEN);
            return null;
        });

        ResponseEntity<String> feed = http.getForEntity(
                "http://localhost:%d/feeds/drom/%s/%s.xml".formatted(port, CODE, TOKEN), String.class);

        assertThat(feed.getStatusCode().value()).isEqualTo(200);
        String body = feed.getBody();
        assertThat(body).as("прайс оборвался посреди записи: площадка сняла бы все объявления")
                .isNotNull().contains("Фара на складе новая").contains("Фара ожидаемая контрактная")
                .endsWith(">").containsPattern("</[a-z_]+>\\s*$");
        assertThat(body).doesNotContain("неизвестная");
        int offers = body.split("<part>", -1).length - 1;
        assertThat(offers).isEqualTo(2);

        ResponseEntity<String> count = exchange(session, HttpMethod.POST,
                "/api/marketplace-accounts/filter/count", "{\"expectedGoods\":true}");
        assertThat(count.getBody()).as("счётчик обещает позицию, которая не уедет")
                .isEqualTo("{\"parts\":" + offers + "}");
    }

    @Test
    @DisplayName("Дельта не падает на позиции без состояния и её не несёт")
    void deltaSkipsThePartWithoutCondition() {
        Long none = inTenant(() -> stocked("Фара дельта неизвестная", null));
        Long known = inTenant(() -> stocked("Фара дельта б/у", "USED"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int written = inTenant(() -> generator.writeDelta(out, List.of(none, known)));

        assertThat(written).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Фара дельта б/у")
                .doesNotContain("неизвестная");
    }

    /**
     * Позиция без состояния проходит экраны, которые её показывают: склад,
     * поиск продавца, форму правки, список поставки, справочник приёмки.
     * Падение здесь — 500 на весь список, а не на одну строку.
     */
    @Test
    @DisplayName("Склад, продавец, форма правки и списки поставки отвечают на позицию без состояния без отказа")
    void surfacesAnswerWithoutCondition() {
        Session session = signIn();
        long supply = createSupply(session);
        addExpected(session, supply, "Капот ожидаемый", "\"condition\":\"\",");
        Long stockedId = inTenant(() -> stocked("Капот на складе", null));

        assertThat(get(session, "/api/parts/catalog?q=" + "Капот" + "&missing=true").getStatusCode().value())
                .isEqualTo(200);
        ResponseEntity<String> seller = get(session, "/api/parts/stock?q=Капот");
        assertThat(seller.getStatusCode().value()).isEqualTo(200);
        assertThat(seller.getBody()).contains("Капот на складе").contains("Капот ожидаемый");
        assertThat(get(session, "/api/intake/supplies/%d/expected-parts".formatted(supply))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(get(session, "/api/intake/reference").getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> editable = get(session, "/api/parts/%d/editable".formatted(stockedId));
        assertThat(editable.getStatusCode().value()).isEqualTo(200);
        assertThat(editable.getBody()).contains("\"condition\":null");
    }

    /**
     * Правка карточки: выбрать состояние позиции без него можно, снять — нет.
     *
     * <p>Снятое ушло бы из прайса ({@code HAS_CONDITION}), а полный прайс Дром
     * читает списком: объявление пропало бы с просмотрами. Пустое в форме —
     * «не трогать», как было и до задачи.
     */
    @Test
    @DisplayName("Правка выбирает состояние позиции без него, а пустое значение состояние не снимает")
    void editChoosesButDoesNotClear() {
        Session session = signIn();
        Long id = inTenant(() -> stocked("Крыло правка", null));
        String path = "/api/parts/%d".formatted(id);

        ResponseEntity<String> keep = exchange(session, HttpMethod.PUT, path, "{\"condition\":null}");
        assertThat(keep.getStatusCode().value()).as(keep.getBody()).isEqualTo(200);
        assertThat(condition(id)).isNull();

        ResponseEntity<String> chosen = exchange(session, HttpMethod.PUT, path,
                "{\"condition\":\"CONTRACT\",\"seen\":{\"condition\":null}}");
        assertThat(chosen.getStatusCode().value()).as(chosen.getBody()).isEqualTo(200);
        assertThat(condition(id)).isEqualTo("CONTRACT");

        exchange(session, HttpMethod.PUT, path, "{\"condition\":null,\"seen\":{\"condition\":\"CONTRACT\"}}");
        assertThat(condition(id)).as("пустое в форме сняло состояние").isEqualTo("CONTRACT");
    }

    // ---------- вспомогательное ----------

    /** Позиция на складе, опубликованная, с ценой; {@code null} — без состояния. */
    private Long stocked(String title, String condition) {
        Long id = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published, condition)
                VALUES (1, ?, 4000, true, ?) RETURNING id""", Long.class, title, condition);
        ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
        return id;
    }

    private String condition(Long id) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT condition FROM part WHERE id = ?", String.class, id));
    }

    private String conditionOf(long supply, String rawStart) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT condition FROM part WHERE supply_id = ? AND title ILIKE ?""",
                String.class, supply, rawStart + "%"));
    }

    private ResponseEntity<String> addExpected(Session session, long supply, String name, String extra) {
        return post(session, "/api/intake/supplies/%d/expected-parts".formatted(supply), """
                {"rawName":"%s",%s"quantity":2,"price":9000,"requestId":"k-%d"}"""
                .formatted(name, extra, System.nanoTime()));
    }

    private long createSupply(Session session) {
        ResponseEntity<String> created = post(session, "/api/intake/supplies", """
                {"kind":"CONTAINER","number":"С-%d","supplierName":"Onteco"}"""
                .formatted(System.nanoTime()));
        assertThat(created.getStatusCode().value()).as("поставка не заведена").isEqualTo(201);
        Matcher id = Pattern.compile("\"id\":(\\d+)").matcher(created.getBody());
        assertThat(id.find()).isTrue();
        return Long.parseLong(id.group(1));
    }

    private ResponseEntity<String> post(Session session, String path, String body) {
        return exchange(session, HttpMethod.POST, path, body);
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
