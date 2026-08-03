package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * Дельта-обновление Дрома при выдаче сделки.
 *
 * <p>Живой Дром здесь не участвует: {@link DromSyncClient} подменён. Проверяется
 * то, что вокруг запроса — какие позиции попадают в дельту, что пишется
 * в журнал публикаций и что происходит при отказе площадки.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Import(DromDeltaSenderTest.StubSyncConfig.class)
class DromDeltaSenderTest extends PostgresTestBase {

    private static final String TENANT = "t_000047";

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    @Autowired
    private DromDeltaSender sender;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private StubSyncClient syncClient;

    private Long warehouse;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        syncClient.reset();

        inTenant(() -> {
            jdbc.update("DELETE FROM publication_log");
            jdbc.update("DELETE FROM listing");
            jdbc.update("DELETE FROM marketplace_account");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Без подключённого Дрома ничего не отправляется и это не ошибка")
    void withoutAccountNothingIsSent() {
        long dealId = issuedDeal(part("Фара левая", true));

        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

        assertThat(syncClient.calls()).isEmpty();
    }

    @Test
    @DisplayName("Недонастроенный аккаунт не даёт отправить пустой запрос")
    void accountWithoutPacketIdIsSkipped() {
        account(null, "ключ-кабинета");
        long dealId = issuedDeal(part("Бампер", true));

        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

        assertThat(syncClient.calls()).isEmpty();
    }

    @Test
    @DisplayName("Проданная деталь уезжает недоступной")
    void soldPartGoesAsUnavailable() {
        account("12345", "ключ-кабинета");
        Long partId = part("Стартер 1NZ-FE", true);
        long dealId = issuedDeal(partId);
        String publicCode = publicCodeOf(partId);

        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

        String delta = capturedDelta();
        assertThat(delta)
                .contains("<ordercode>" + publicCode + "</ordercode>")
                .contains("<available>false</available>");
    }

    @Test
    @DisplayName("В дельту попадают только позиции этой сделки")
    void deltaCarriesOnlyDealItems() {
        account("12345", "ключ-кабинета");
        Long sold = part("Генератор", true);
        Long untouched = part("Радиатор", true);
        long dealId = issuedDeal(sold);

        inTenant(() -> sender.onDealIssued(dealId));

        String delta = capturedDelta();
        assertThat(delta).contains(publicCodeOf(sold));
        assertThat(delta)
                .as("в дельту уехал весь склад вместо одной позиции")
                .doesNotContain(publicCodeOf(untouched));
    }

    @Test
    @DisplayName("Невыгружаемая позиция дельту не порождает")
    void unpublishedPartProducesNoDelta() {
        account("12345", "ключ-кабинета");
        long dealId = issuedDeal(part("Не для площадок", false));

        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

        assertThat(syncClient.calls()).isEmpty();
    }

    @Test
    @DisplayName("Успех пишется в журнал публикаций и отмечается на аккаунте")
    void successIsLogged() {
        Long accountId = account("12345", "ключ-кабинета");
        long dealId = issuedDeal(part("Капот", true));

        inTenant(() -> sender.onDealIssued(dealId));

        inTenant(() -> {
            var row = jdbc.queryForMap("""
                    SELECT operation, http_status, item_count, is_success
                      FROM publication_log WHERE account_id = ?""", accountId);
            assertThat(row.get("operation")).isEqualTo("SYNC");
            assertThat(row.get("http_status")).isEqualTo(200);
            assertThat(row.get("item_count")).isEqualTo(1);
            assertThat(row.get("is_success")).isEqualTo(true);

            assertThat(jdbc.queryForObject(
                    "SELECT last_sync_at IS NOT NULL FROM marketplace_account WHERE id = ?",
                    Boolean.class, accountId)).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("Отказ площадки не роняет выдачу, но остаётся в журнале")
    void failureIsRecordedWithoutBreakingTheDeal() {
        Long accountId = account("12345", "ключ-кабинета");
        syncClient.failWith(403, "ERROR_REASON_AUTH_FAILED");
        long dealId = issuedDeal(part("Стойка", true));

        // Сорванная выгрузка не повод откатывать отданный клиенту товар.
        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isFalse();

        inTenant(() -> {
            var row = jdbc.queryForMap("""
                    SELECT is_success, http_status, response_body
                      FROM publication_log WHERE account_id = ?""", accountId);
            assertThat(row.get("is_success")).isEqualTo(false);
            assertThat(row.get("http_status")).isEqualTo(403);
            assertThat((String) row.get("response_body")).contains("ERROR_REASON_AUTH_FAILED");

            assertThat(jdbc.queryForObject(
                    "SELECT last_error FROM marketplace_account WHERE id = ?",
                    String.class, accountId)).contains("ERROR_REASON_AUTH_FAILED");
            return null;
        });
    }

    @Test
    @DisplayName("Повторная доставка события даёт ту же дельту: обработчик идемпотентен")
    void repeatedDeliveryIsIdempotent() {
        account("12345", "ключ-кабинета");
        long dealId = issuedDeal(part("Дверь задняя", true));

        inTenant(() -> sender.onDealIssued(dealId));
        inTenant(() -> sender.onDealIssued(dealId));

        // Транспорт даёт at-least-once, значит событие придёт дважды. Дельта
        // несёт текущее состояние позиции, а не приращение, поэтому повтор
        // безопасен — в отличие от, скажем, повторного списания остатка.
        assertThat(syncClient.calls()).hasSize(2);
        assertThat(syncClient.calls().get(0).packetId()).isEqualTo("12345");
        assertThat(syncClient.calls().get(1).delta())
                .isEqualTo(syncClient.calls().get(0).delta());
    }

    @Test
    @DisplayName("Ключ кабинета уходит хешем, а не открытым текстом")
    void cabinetKeyIsHashed() {
        account("12345", "ключ-кабинета");
        long dealId = issuedDeal(part("Крыло", true));

        inTenant(() -> sender.onDealIssued(dealId));

        assertThat(syncClient.calls()).singleElement()
                .satisfies(call -> assertThat(call.auth())
                        .isEqualTo(DromSyncClient.authHash("ключ-кабинета"))
                        .doesNotContain("ключ-кабинета"));
    }

    // ---------- фикстуры ----------

    private Long account(String packetId, String cabinetKey) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, credentials, settings)
                VALUES ('DROM', 'Дром', ?, ?::jsonb) RETURNING id""",
                Long.class,
                cabinetKey == null ? null : cabinetKey.getBytes(StandardCharsets.UTF_8),
                packetId == null ? "{}" : "{\"packetId\": \"" + packetId + "\"}"));
    }

    private Long part(String title, boolean published) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, is_published)
                    VALUES (1, ?, 5000, 2000, ?) RETURNING id""",
                    Long.class, title, published);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
            return partId;
        });
    }

    /** Сделка, доведённая до выдачи: позиция списана, статус ISSUED. */
    private long issuedDeal(Long partId) {
        return inTenant(() -> {
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

    private String publicCodeOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));
    }

    private String capturedDelta() {
        assertThat(syncClient.calls()).as("дельта не отправлялась").hasSize(1);
        return syncClient.calls().get(0).delta();
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Заглушка HTTP-границы вместо Mockito: на этой JDK ByteBuddy не может
     * инструментировать классы, а рукописная заглушка ещё и даёт прямые
     * утверждения про отправленное вместо verify.
     */
    static class StubSyncClient extends DromSyncClient {

        private final List<Call> calls = new ArrayList<>();
        private Result answer = new Result(true, 200, "");

        @Override
        public Result sync(String packetId, String auth, byte[] deltaXml, String fileName) {
            calls.add(new Call(packetId, auth, new String(deltaXml, StandardCharsets.UTF_8), fileName));
            return answer;
        }

        void failWith(int httpStatus, String body) {
            answer = new Result(false, httpStatus, body);
        }

        void reset() {
            calls.clear();
            answer = new Result(true, 200, "");
        }

        List<Call> calls() {
            return List.copyOf(calls);
        }

        record Call(String packetId, String auth, String delta, String fileName) {
        }
    }

    @TestConfiguration
    static class StubSyncConfig {

        @Bean
        @Primary
        StubSyncClient stubSyncClient() {
            return new StubSyncClient();
        }
    }
}
