package ru.partsflow.publishing.drom;

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
import ru.partsflow.inventory.StockReservationRepository;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Какие позиции попадают в файл Дрома — как у Bazon (задача 0339).
 *
 * <p>Решения владельца 19.10.2026 («Всё да, всё как в базон», записаны
 * в {@code tasks/0339-…}, разделы «Ответ владельца»), по одному на метод:
 * проданное и обнулённое из файла исчезает — и из прайса, и из дельты;
 * цена 0 попадает, а счётчик называет такие позиции отдельно; отложенное
 * целиком уходит только при включённом «Выгружать товары в резерве»,
 * и тогда с приставкой первой строкой описания; «Выгружать товары,
 * по которым ожидается поступление» работает без приставки и у новой
 * выгрузки включён; {@code state} у всех строк файла — «В наличии».
 *
 * <p>Прайс берётся по ссылке выгрузки настоящим Tomcat, счётчик — тем же
 * запросом, что зовёт экран: оба через HTTP, а не вызовом сервиса.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class DromFeedCompositionTest extends PostgresTestBase {

    private static final String TENANT = "t_000998";
    private static final String CODE = "otbor0339";
    private static final String TOKEN = "d".repeat(43);

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
    private StockReservationRepository reservations;

    @Autowired
    private DromPriceGenerator generator;

    private Long warehouse;
    private Long otherWarehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 998");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (998, ?, 'Состав файла Дрома', ?)""", TENANT, CODE);
        inTenant(() -> {
            int found = jdbc.update("UPDATE tenant_member SET password_hash = ? WHERE login = 'vladelec'",
                    passwordEncoder.encode("пароль-подлиннее"));
            if (found == 0) {
                jdbc.update("""
                        INSERT INTO tenant_member (login, display_name, password_hash, role)
                        VALUES ('vladelec', 'Владелец', ?, 'OWNER')""",
                        passwordEncoder.encode("пароль-подлиннее"));
            }
            // Склад каждого метода свой: файл и счётчик сверяются с числом
            // строк, и позиции прошлого метода их бы сдвинули.
            jdbc.update("UPDATE part SET is_published = false");
            jdbc.update("DELETE FROM marketplace_account");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад А') RETURNING id",
                    Long.class, branch);
            otherWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад Б') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    // ---------- решение 1: проданное и обнулённое исчезает ----------

    @Test
    @DisplayName("Проданного и обнулённого на складе выгрузки в файле нет; что осталось — «В наличии», и счётчик равен числу строк")
    void soldAndZeroedLeaveTheFile() {
        Long inStock = stocked("Фара в наличии", "4000", warehouse);
        Long sold = stocked("Фара проданная", "4000", warehouse);
        stocked("Фара на другом складе", "4000", otherWarehouse);
        inTenant(() -> ledger.record(StockMovement.sale(sold, BigDecimal.ONE, warehouse, null)));
        feed("{}", warehouse);

        String xml = priceFile();

        assertThat(xml).as("в файле осталась позиция, которую нельзя купить")
                .contains("Фара в наличии")
                .doesNotContain("Фара проданная")
                .doesNotContain("Фара на другом складе");
        assertThat(states(xml)).as("в файле есть слово наличия, кроме «В наличии»")
                .isNotEmpty().containsOnly("В наличии");
        assertThat(count("{\"warehouseIds\":[" + warehouse + "]}"))
                .as("счётчик «Посчитать» обещает не то, что в файле")
                .contains("\"parts\":" + parts(xml));
        assertThat(inStock).isNotNull();
    }

    @Test
    @DisplayName("Дельта проданной позиции не несёт: «недоступна» в формате Bazon сказать нечем")
    void deltaLeavesTheSoldPartOut() {
        Long inStock = stocked("Фара дельта в наличии", "4000", warehouse);
        Long sold = stocked("Фара дельта проданная", "4000", warehouse);
        inTenant(() -> ledger.record(StockMovement.sale(sold, BigDecimal.ONE, warehouse, null)));

        String delta = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, List.of(inStock, sold));
            return out.toString(StandardCharsets.UTF_8);
        });

        assertThat(delta).contains("Фара дельта в наличии")
                .as("проданная ушла в дельте словом недоступной позиции")
                .doesNotContain("Фара дельта проданная");
        assertThat(states(delta)).containsOnly("В наличии");
    }

    // ---------- решение 2: цена 0 попадает, и счётчик её называет ----------

    @Test
    @DisplayName("Позиция с ценой 0 в файле, а счётчик называет её отдельно — «с нулевой ценой»")
    void zeroPriceIsInTheFileAndNamedByTheCounter() {
        stocked("Фара с ценой", "4000", warehouse);
        stocked("Фара без цены", "0", warehouse);
        feed("{}", null);

        String xml = priceFile();

        assertThat(xml).as("позиция с ценой 0 из файла выпала").contains("Фара без цены");
        assertThat(partOf(xml, "Фара без цены")).contains("<price>0</price>");
        assertThat(count("{}")).as("счётчик не называет позиции с нулевой ценой")
                .isEqualTo("{\"parts\":2,\"zeroPrice\":1}");
    }

    // ---------- решение 3: резерв — переключателем, приставка первой строкой ----------

    @Test
    @DisplayName("Отложенная целиком при выключенном «Выгружать товары в резерве» в файл не идёт")
    void reservedIsLeftOutWhileTheSwitchIsOff() {
        stocked("Фара свободная", "4000", warehouse);
        reserved("Фара отложенная", warehouse);
        feed("{}", null);

        String xml = priceFile();

        assertThat(xml).contains("Фара свободная")
                .as("отложенная ушла в прайс при выключенном переключателе")
                .doesNotContain("Фара отложенная");
        assertThat(count("{}")).isEqualTo("{\"parts\":1,\"zeroPrice\":0}");
    }

    @Test
    @DisplayName("Включённый «Выгружать товары в резерве» выгружает отложенную с приставкой первой строкой описания")
    void reservedGoesWithThePrefixFirstWhenTheSwitchIsOn() {
        stocked("Фара свободная с описанием", "4000", warehouse);
        reserved("Фара отложенная с описанием", warehouse);
        feed("{\"reservedGoods\":true,\"reservedGoodsNote\":\"В резерве — уточняйте\"}", null);

        String xml = priceFile();

        String reservedPart = partOf(xml, "Фара отложенная с описанием");
        assertThat(description(reservedPart)).as("приставка резерва не первой строкой описания")
                .startsWith("\nВ резерве — уточняйте\n\nКомментарий клиента");
        assertThat(reservedPart).contains("<state>В наличии</state>");
        assertThat(description(partOf(xml, "Фара свободная с описанием")))
                .as("приставка резерва ушла позиции, которая не в резерве")
                .doesNotContain("В резерве");
        assertThat(count("{\"reservedGoods\":true}"))
                .as("счётчик не считает отложенное при включённом переключателе")
                .isEqualTo("{\"parts\":2,\"zeroPrice\":0}");
    }

    @Test
    @DisplayName("Без текста приставки отложенная уходит без неё — как у Bazon с пустым полем")
    void reservedWithoutPrefixGoesWithoutIt() {
        reserved("Фара отложенная без приставки", warehouse);
        feed("{\"reservedGoods\":true}", null);

        String part = partOf(priceFile(), "Фара отложенная без приставки");

        assertThat(description(part)).startsWith("\nКомментарий клиента");
        assertThat(part).as("отложенная ушла словом недоступной позиции")
                .contains("<state>В наличии</state>");
    }

    // ---------- второй переключатель: «…ожидается поступление» ----------

    @Test
    @DisplayName("«Ожидается поступление» без текста приставки сохраняется и выгружает товар в пути без неё")
    void expectedGoodsWorkWithoutPrefix() {
        Session session = signIn();
        long supply = createSupply(session);
        ResponseEntity<String> expected = post(session,
                "/api/intake/supplies/%d/expected-parts".formatted(supply), """
                        {"rawName":"Фара в пути","condition":"USED","quantity":2,"price":9000,"requestId":"k-%d"}"""
                        .formatted(System.nanoTime()));
        assertThat(expected.getStatusCode().value()).isEqualTo(201);
        long account = feed("{}", null);

        ResponseEntity<String> saved = exchange(session, HttpMethod.PUT,
                "/api/marketplace-accounts/%d/settings".formatted(account),
                "{\"expectedGoods\":true}");
        assertThat(saved.getStatusCode().value())
                .as("включённый переключатель без приставки отбит: %s", saved.getBody())
                .isEqualTo(200);

        String xml = priceFile();
        assertThat(xml).as("товар в пути не ушёл без приставки").contains("Фара в пути");
        assertThat(count("{\"expectedGoods\":true}")).contains("\"parts\":1");
    }

    @Test
    @DisplayName("Новая выгрузка на Дром заводится с включённым «ожидается поступление» и выключенным резервом")
    void newFeedExportsExpectedGoodsButNotReserved() {
        Session session = signIn();

        ResponseEntity<String> created = post(session, "/api/marketplace-accounts", """
                {"marketplace":"DROM","title":"Дром низкая","productLine":"PART"}""");

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).as("умолчания новой выгрузки не как у Bazon")
                .contains("\"expectedGoods\":true")
                .doesNotContain("\"reservedGoods\":true");
    }

    // ---------- вспомогательное ----------

    /** Позиция на складе, опубликованная, б/у, с комментарием клиента. */
    private Long stocked(String title, String price, Long where) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, description, price, is_published, condition)
                    VALUES (1, ?, 'Комментарий клиента', ?::numeric, true, 'USED') RETURNING id""",
                    Long.class, title, price);
            ledger.record(StockMovement.intake(id, BigDecimal.ONE, where, null));
            return id;
        });
    }

    /** Одна штука на складе, и она целиком отложена под клиента. */
    private Long reserved(String title, Long where) {
        Long id = stocked(title, "4000", where);
        inTenant(() -> {
            reservations.reserve(id, where, BigDecimal.ONE);
            return null;
        });
        return id;
    }

    /** Выгрузка с настройками сборки; {@code where} — отбор по складу, {@code null} — все. */
    private long feed(String settings, Long where) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, settings, feed_token, warehouse_ids)
                VALUES ('DROM', 'Дром', ?::jsonb, ?, ?::bigint[]) RETURNING id""",
                Long.class, settings, TOKEN, where == null ? null : "{" + where + "}"));
    }

    private String priceFile() {
        ResponseEntity<String> feed = http.getForEntity(
                "http://localhost:%d/feeds/drom/%s/%s.xml".formatted(port, CODE, TOKEN), String.class);
        assertThat(feed.getStatusCode().value()).isEqualTo(200);
        return feed.getBody();
    }

    private String count(String body) {
        ResponseEntity<String> answer = exchange(signIn(), HttpMethod.POST,
                "/api/marketplace-accounts/filter/count", body);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        return answer.getBody();
    }

    private static int parts(String xml) {
        return xml.split("<part>", -1).length - 1;
    }

    private static List<String> states(String xml) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("<state>([^<]*)</state>").matcher(xml);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /** Один {@code <part>} файла по куску текста внутри него. */
    private static String partOf(String xml, String marker) {
        int at = xml.indexOf(marker);
        assertThat(at).as("«%s» нет в файле", marker).isNotNegative();
        return xml.substring(xml.lastIndexOf("<part>", at), xml.indexOf("</part>", at));
    }

    /** Текст описания внутри {@code CDATA}; пустое описание — пустая строка. */
    private static String description(String part) {
        Matcher m = Pattern.compile("<description><!\\[CDATA\\[(.*?)]]></description>", Pattern.DOTALL)
                .matcher(part);
        return m.find() ? m.group(1) : "";
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
