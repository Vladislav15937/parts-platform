package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Наименование в прайсе Дрома — без номеров позиции, номера — своими тегами
 * (задача 0331, решение владельца продукта 18.10.2026: «В объявление дром номера
 * должны идти отдельно а названи отдельно»).
 *
 * <p>Через настоящий сервлет-контейнер и ссылку выгрузки, как прайс забирает
 * площадка: контекст тот же, что у {@link DromFeedStreamTest}, второго Spring
 * не поднимается. Дельта собирается тем же генератором, которым её шлёт
 * {@code DromDeltaSender}, — и обязана дать то же наименование: иначе первый
 * же забор и первая дельта переименовывали бы объявление туда и обратно.
 *
 * <p>Своя схема {@code t_000978}, тот же номер в реестре.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class DromNameWithoutNumbersTest extends PostgresTestBase {

    private static final String TENANT = "t_000978";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private DromPriceGenerator generator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final String token = "n".repeat(43);

    private final Map<String, Long> parts = new HashMap<>();

    /** Код позиции уникален в схеме, а схема живёт через оба метода. */
    private static final java.util.concurrent.atomic.AtomicInteger ROUND =
            new java.util.concurrent.atomic.AtomicInteger();

    private String round;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name,
                                                    status, code)
                VALUES (978, ?, 'Номера отдельно', 'ACTIVE', 'numco')
                ON CONFLICT (tenant_id) DO UPDATE SET code = excluded.code""", TENANT);
        parts.clear();
        round = "R" + ROUND.incrementAndGet();
        inTenant(() -> {
            jdbc.update("DELETE FROM marketplace_account");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM part_oem");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                    Long.class, branch);

            part(warehouse, "P331-A", "Фара левая Toyota Camry 8414012530, 8414012531",
                    null, "8414012530", List.of("8414012531"));
            part(warehouse, "P331-B", "Бампер передний ВАЗ 2106 5201", null, "5201", List.of());
            part(warehouse, "P331-C", "Бампер Mazda 3 BK 2006", null, null, List.of());
            // Перенесённая с объявления клиента на Дроме (0275): номер срезается,
            // «(б/у)» и «перед.» клиента — нет.
            part(warehouse, "P331-D", "Фара (б/у) перед. Honda Fit 33100-SAA-003",
                    "77001", "33100-SAA-003", List.of());

            jdbc.update("""
                    INSERT INTO marketplace_account (marketplace, title, settings, feed_token)
                    VALUES ('DROM', 'Номера', '{"packetId":"1"}'::jsonb, ?)""", token);
            return null;
        });
    }

    @Test
    @DisplayName("В прайсе по ссылке наименование без номеров, номера — своими тегами")
    void feedNameHasNoNumbers() throws Exception {
        ResponseEntity<String> answer = http.getForEntity(
                "http://localhost:%d/feeds/drom/numco/%s.xml".formatted(port, token),
                String.class);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);

        Map<String, Element> offers = offers(answer.getBody());

        Element headlight = offers.get("P331-A");
        assertThat(text(headlight, "name"))
                .as("номер и кросс-номер остались в названии объявления")
                .isEqualTo("Фара левая Toyota Camry");
        assertThat(text(headlight, "oem_number")).isEqualTo("8414012530");
        assertThat(text(headlight, "analog_numbers")).isEqualTo("8414012531");
        assertThat(text(headlight, "description"))
                .as("строка о номере в описании остаётся (ответ владельца, вопрос 2)")
                .contains("Номер производителя: 8414012530.");

        assertThat(text(offers.get("P331-B"), "name"))
                .as("модель «2106» — не номер позиции")
                .isEqualTo("Бампер передний ВАЗ 2106");
        assertThat(text(offers.get("P331-C"), "name"))
                .as("позиция без номера")
                .isEqualTo("Бампер Mazda 3 BK 2006");
        assertThat(text(offers.get("P331-D"), "name"))
                .as("у перенесённой с Дрома срезан только номер")
                .isEqualTo("Фара (б/у) перед. Honda Fit");

        // Срез идёт на выходе: заголовок в системе прежний.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, parts.get("P331-A"))))
                .isEqualTo("Фара левая Toyota Camry 8414012530, 8414012531");
    }

    @Test
    @DisplayName("Дельта даёт то же наименование, что полный прайс")
    void deltaNamesTheSame() throws Exception {
        Map<String, Element> full = offers(http.getForEntity(
                "http://localhost:%d/feeds/drom/numco/%s.xml".formatted(port, token),
                String.class).getBody());

        String deltaXml = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeDelta(out, List.copyOf(parts.values()));
            return out.toString(StandardCharsets.UTF_8);
        });
        Map<String, Element> delta = offers(deltaXml);

        assertThat(delta.keySet()).containsExactlyInAnyOrderElementsOf(parts.keySet());
        for (String code : parts.keySet()) {
            assertThat(text(delta.get(code), "name"))
                    .as("дельта и прайс назвали позицию %s по-разному", code)
                    .isEqualTo(text(full.get(code), "name"));
        }
        assertThat(text(delta.get("P331-A"), "name")).isEqualTo("Фара левая Toyota Camry");
    }

    private void part(Long warehouse, String code, String title, String dromAd,
                      String oem, List<String> analogs) {
        Long id = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published, condition,
                                  public_code, drom_ad_number)
                VALUES (1, ?, 3000, true, 'USED', ?, ?) RETURNING id""",
                Long.class, title, code + round, dromAd == null ? null : dromAd + ROUND.get());
        if (oem != null) {
            oem(id, oem, true);
        }
        for (String analog : analogs) {
            oem(id, analog, false);
        }
        ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouse, null));
        parts.put(code, id);
    }

    private void oem(Long partId, String raw, boolean primary) {
        jdbc.update("""
                INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                VALUES (?, ?, ?, ?)""",
                partId, raw, ru.partsflow.catalog.OemNumbers.normalize(raw), primary);
    }

    /** Позиции этого прогона по {@code ordercode} без приставки прогона, разобранные XML-парсером, как их прочтёт площадка. */
    private Map<String, Element> offers(String xml) throws Exception {
        NodeList found = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagName("offer");
        Map<String, Element> byCode = new HashMap<>();
        for (int i = 0; i < found.getLength(); i++) {
            Element offer = (Element) found.item(i);
            String code = text(offer, "ordercode");
            if (code.endsWith(round)) {
                byCode.put(code.substring(0, code.length() - round.length()), offer);
            }
        }
        return byCode;
    }

    private static String text(Element offer, String element) {
        assertThat(offer).as("позиции нет в файле").isNotNull();
        NodeList nodes = offer.getElementsByTagName(element);
        assertThat(nodes.getLength()).as("элемент <%s>", element).isEqualTo(1);
        return nodes.item(0).getTextContent();
    }

    /** Арендатор ставится до транзакции — иначе {@code search_path} не доедет. */
    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }
}
