package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Динамическое обновление площадки: изменилась позиция — уехала дельта.
 *
 * <p>Проверяется вся цепочка, кроме самого HTTP: триггер ставит отметку,
 * релей забирает пачку, отправитель собирает дельту отбором каждой выгрузки.
 * Живой Дром подменён той же заглушкой, что и в {@link DromDeltaSenderTest},
 * — заодно это переиспользует его контекст Spring вместо подъёма ещё одного.
 *
 * <p>Свой арендатор: очередь изменений общая на схему, и соседний тест,
 * трогающий склад, ломал бы утверждения про размер пачки.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Import(DromDeltaSenderTest.StubSyncConfig.class)
class FeedDeltaRelayTest extends PostgresTestBase {

    private static final String TENANT = "t_000093";

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    @Autowired
    private FeedDeltaRelay relay;

    @Autowired
    private DromDeltaSender sender;

    /**
     * Отметку здесь ставим руками: этот тест про релей, а не про источник.
     * Что отметку ставит каждая операция над позицией, стережёт
     * {@code PartChangeLogTest} — до 3 августа 2026 это делал триггер базы,
     * и тогда её было достаточно записать прямым SQL.
     */
    @Autowired
    private ru.partsflow.inventory.PartChangeLog partChanges;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DromDeltaSenderTest.StubSyncClient syncClient;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        syncClient.reset();
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 93");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (93, ?, 'Барнаул', 'feeddelta')""", TENANT);

        inTenant(() -> {
            // Позиции между тестами не чистятся: журнал движений неизменяем,
            // а удаление позиции унесло бы его каскадом. Тесты вместо этого
            // трогают только свои строки, а очередь опустошается явно.
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM publication_log");
            jdbc.update("DELETE FROM marketplace_account");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Правка цены доезжает до площадки сама")
    void priceChangeReachesTheMarketplace() {
        account("12345", null, null);
        Long partId = part("Фара левая", "5000");
        String code = publicCodeOf(partId);
        drainQueue();

        inTenant(() -> {
            jdbc.update("UPDATE part SET price = 7000 WHERE id = ?", partId);
            partChanges.changed(partId);
            return null;
        });
        relay.relayFor(TENANT);

        assertThat(syncClient.calls()).singleElement()
                .satisfies(call -> assertThat(call.delta())
                        .contains("<ordercode>" + code + "</ordercode>")
                        .contains("<price>7000"));
        assertThat(queueSize()).isZero();
    }

    @Test
    @DisplayName("Списанная позиция уезжает недоступной")
    void writtenOffGoesAsUnavailable() {
        account("12345", null, null);
        Long partId = part("Стартер", "5000");
        drainQueue();

        // Списание идёт движением журнала — остаток и статус позиции пока
        // ещё меняет триггер базы (пункт 1 в docs/triggers-to-java.md),
        // поэтому отметку кладёт тот, кто движение записал.
        inTenant(() -> {
            ledger.record(StockMovement.writeOff(partId, java.math.BigDecimal.ONE, warehouse));
            partChanges.changed(partId);
            return null;
        });

        assertThat(queueSize()).isEqualTo(1);
        relay.relayFor(TENANT);
        assertThat(capturedDelta()).contains("<available>false</available>");
    }

    @Test
    @DisplayName("Правка сотни позиций уходит одной дельтой, а не сотней запросов")
    void bulkEditIsSentAsOneDelta() {
        account("12345", null, null);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids.add(part("Позиция " + i, "5000"));
        }
        drainQueue();

        String places = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        inTenant(() -> {
            jdbc.update("UPDATE part SET note = 'распродажа' WHERE id IN (" + places + ")",
                    ids.toArray());
            partChanges.changed(ids);
            return null;
        });
        relay.relayFor(TENANT);

        // Отметка лежит на позиции, а не на изменении, и пачка забирается
        // целиком: площадка получает один запрос вместо ста.
        assertThat(syncClient.calls()).hasSize(1);
        assertThat(countOffers(capturedDelta())).isEqualTo(100);
    }

    @Test
    @DisplayName("Отказ площадки оставляет позицию в очереди")
    void failureKeepsThePartQueued() {
        account("12345", null, null);
        Long partId = part("Бампер", "5000");
        drainQueue();
        syncClient.failWith(403, "ERROR_REASON_AUTH_FAILED");

        inTenant(() -> {
            jdbc.update("UPDATE part SET price = 9000 WHERE id = ?", partId);
            partChanges.changed(partId);
            return null;
        });
        relay.relayFor(TENANT);

        // Не ушло — значит уедет следующим заходом. Заявка при этом снята,
        // иначе позиция ждала бы истечения срока из-за мгновенного отказа.
        assertThat(queueSize()).isEqualTo(1);
        assertThat(claimedCount()).isZero();
    }

    @Test
    @DisplayName("Дельта уходит во все выгрузки площадки, а не в первую")
    void deltaGoesToEveryFeed() {
        account("11111", null, null);
        account("22222", null, null);
        Long partId = part("Радиатор", "5000");
        drainQueue();

        inTenant(() -> {
            jdbc.update("UPDATE part SET price = 6000 WHERE id = ?", partId);
            partChanges.changed(partId);
            return null;
        });
        relay.relayFor(TENANT);

        // Прайс-листов у клиента несколько, и позиция, сменившая цену, могла
        // покинуть один и попасть в другой — знать должны оба.
        assertThat(syncClient.calls())
                .extracting(DromDeltaSenderTest.StubSyncClient.Call::packetId)
                .containsExactlyInAnyOrder("11111", "22222");
    }

    @Test
    @DisplayName("Отбор выгрузки соблюдается: чужая позиция в неё не уезжает")
    void feedFilterIsRespected() {
        account("дорогая", "10000", null);
        Long cheap = part("Дешёвая", "5000");
        drainQueue();

        inTenant(() -> {
            jdbc.update("UPDATE part SET note = 'правка' WHERE id = ?", cheap);
            partChanges.changed(cheap);
            return null;
        });
        relay.relayFor(TENANT);

        // Позиция не проходит отбор прайс-листа — уехав туда, она создала бы
        // объявление, которого владелец не заводил.
        assertThat(syncClient.calls()).isEmpty();
        assertThat(queueSize()).as("отметку всё равно надо убрать").isZero();
    }

    @Test
    @DisplayName("Колесо уезжает в выгрузку колёс своим форматом")
    void wheelGoesToTheWheelFeed() {
        wheelAccount("77777");
        Long wheelId = wheel("Шина 195/65 R15 Nokian зимняя");
        drainQueue();

        inTenant(() -> {
            jdbc.update("UPDATE part SET price = 7000 WHERE id = ?", wheelId);
            partChanges.changed(wheelId);
            return null;
        });
        relay.relayFor(TENANT);

        // Формат тот же, что у полного прайса колёс: площадка разбирает
        // дельту той же настройкой, и «marking» ей нужен не меньше, чем цена.
        assertThat(syncClient.calls()).singleElement()
                .satisfies(call -> {
                    assertThat(call.packetId()).isEqualTo("77777");
                    assertThat(call.delta()).contains("<marking>");
                });
    }

    @Test
    @DisplayName("Колесо отмечается, но в прайс запчастей не уезжает")
    void wheelIsMarkedButNotSentToPartsFeed() {
        account("12345", null, null);
        Long wheelId = wheel("Шина 195/65 R15 Nokian зимняя");
        drainQueue();

        inTenant(() -> {
            jdbc.update("UPDATE part_wheel SET wear_mm = 5 WHERE part_id = ?", wheelId);
            partChanges.changed(wheelId);
            return null;
        });

        // Отметка ставится — свойства колеса тоже часть товара (WheelService
        // кладёт её при заведении комплекта); но прайс запчастей отбирает
        // product_line = 'PART', и слать нечего.
        // Выгрузки для шин и дисков у нас пока нет, и это её место.
        assertThat(queueSize()).isEqualTo(1);
        relay.relayFor(TENANT);
        assertThat(syncClient.calls()).isEmpty();
        assertThat(queueSize()).isZero();
    }

    @Test
    @DisplayName("Продажа не уезжает дважды: обработчик убирает за собой")
    void saleIsNotSentTwice() {
        account("12345", null, null);
        Long partId = part("Дверь", "5000");
        drainQueue();

        long dealId = issuedDeal(partId);
        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();
        relay.relayFor(TENANT);

        // Обработчик события шлёт сразу, не дожидаясь захода релея, — но
        // продажу отмечает и триггер очереди. Без уборки площадка получила бы
        // то же самое второй раз через несколько секунд.
        assertThat(syncClient.calls()).hasSize(1);
    }

    @Test
    @DisplayName("Изменение во время отправки снимает заявку — оно не потеряется")
    void changeDuringSendingSurvives() {
        Long partId = part("Крыло", "5000");
        inTenant(() -> {
            jdbc.update("UPDATE part_change SET claimed_at = now() WHERE part_id = ?", partId);
            // Правка, случившаяся, пока дельта в пути: уборка после успешной
            // отправки прежнего состояния обязана её пощадить.
            jdbc.update("UPDATE part SET price = 8000 WHERE id = ?", partId);
            partChanges.changed(partId);
            return null;
        });

        assertThat(claimedCount())
                .as("отметка обязана снять заявку, иначе правка уедет в мусор")
                .isZero();
    }

    // ---------- фикстуры ----------

    /** Выгрузка шин и дисков: у неё свой формат и свой генератор. */
    private Long wheelAccount(String packetId) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO marketplace_account
                    (marketplace, title, credentials, settings, product_line)
                VALUES ('DROM', ?, ?, ?::jsonb, 'WHEEL') RETURNING id""",
                Long.class,
                "Дром колёса " + packetId,
                "ключ-кабинета".getBytes(StandardCharsets.UTF_8),
                "{\"packetId\": \"" + packetId + "\"}"));
    }

    private Long account(String packetId, String priceFrom, String priceTo) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO marketplace_account
                    (marketplace, title, credentials, settings, price_from, price_to)
                VALUES ('DROM', ?, ?, ?::jsonb, ?::numeric, ?::numeric) RETURNING id""",
                Long.class,
                "Дром " + packetId,
                "ключ-кабинета".getBytes(StandardCharsets.UTF_8),
                "{\"packetId\": \"" + packetId + "\"}",
                priceFrom, priceTo));
    }

    private Long part(String title, String price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, ?, ?::numeric, true) RETURNING id""",
                    Long.class, title, price);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
            return partId;
        });
    }

    private Long wheel(String title) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published, product_line)
                    VALUES (1, ?, 4000, true, 'WHEEL') RETURNING id""", Long.class, title);
            jdbc.update("""
                    INSERT INTO part_wheel (part_id, kind, diameter, tyre_width, tyre_height)
                    VALUES (?, 'TYRE', 15, 195, 65)""", partId);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
            return partId;
        });
    }

    private long issuedDeal(Long partId) {
        return inTenant(() -> {
            Long customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            Long dealId = jdbc.queryForObject("""
                    INSERT INTO deal (customer_id, status, total_amount, issued_at, closed_at)
                    VALUES (?, 'ISSUED', 5000, now(), now()) RETURNING id""",
                    Long.class, customer);
            jdbc.update("""
                    INSERT INTO deal_item (deal_id, part_id, quantity, price, warehouse_id, status)
                    VALUES (?, ?, 1, 5000, ?, 'ISSUED')""", dealId, partId, warehouse);
            ledger.record(StockMovement.sale(partId, java.math.BigDecimal.ONE, warehouse, dealId));
            return dealId;
        });
    }

    /** Заведение позиции — тоже изменение; очередь после него надо опустошить. */
    private void drainQueue() {
        inTenant(() -> jdbc.update("DELETE FROM part_change"));
        syncClient.reset();
    }

    private int queueSize() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_change", Integer.class));
    }

    private int claimedCount() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_change WHERE claimed_at IS NOT NULL", Integer.class));
    }

    private String publicCodeOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));
    }

    private String capturedDelta() {
        assertThat(syncClient.calls()).as("дельта не отправлялась").hasSize(1);
        return syncClient.calls().get(0).delta();
    }

    private static int countOffers(String delta) {
        return delta.split("<offer>", -1).length - 1;
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
