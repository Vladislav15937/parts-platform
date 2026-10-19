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
import ru.partsflow.publishing.DeliveryStatus;
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
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none",
        "app.crypto.key=" + DromDeltaSenderTest.CRYPTO_KEY,
        "app.public-url=" + DromDeltaSenderTest.PUBLIC_URL})
@Import(DromDeltaSenderTest.StubSyncConfig.class)
class DromDeltaSenderTest extends PostgresTestBase {

    /**
     * Ключ шифрования секретов — тот же, что у {@code MarketplaceCredentialsTest}.
     * Нужен {@code FeedDeltaRelayTest}: он меняет ключ кабинета сервисом
     * (задача 0269). Стоит у обоих классов одинаково, иначе контекст Spring
     * у них стал бы разным и поднимался дважды.
     */
    static final String CRYPTO_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /**
     * Внешний адрес приложения: из него дельта строит ссылки на снимки (задача
     * 0333). Стоит у обоих классов одинаково — по той же причине, что ключ.
     */
    static final String PUBLIC_URL = "https://parts.example.ru";

    private static final String TENANT = "t_000047";

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    @Autowired
    private DromDeltaSender sender;

    @Autowired
    private ru.partsflow.publishing.MarketplaceAccountService accounts;

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

    /**
     * Номер прайс-листа появляется у владельца после того, как выгрузка заведена
     * (задача 0267): впишет, заменит — и дельта идёт уже с ним.
     */
    @Test
    @DisplayName("Номер, вписанный у заведённой выгрузки, уходит в запрос; замена уходит новым")
    void packetIdSetLaterIsUsed() {
        Long accountId = account(null, "ключ-кабинета");
        long first = issuedDeal(part("Бампер", true));
        inTenant(() -> sender.onDealIssued(first));
        assertThat(syncClient.calls()).isEmpty();

        inTenant(() -> accounts.setPacketId(accountId, "111"));
        long second = issuedDeal(part("Фара", true));
        inTenant(() -> sender.onDealIssued(second));

        inTenant(() -> accounts.setPacketId(accountId, " 222 "));
        long third = issuedDeal(part("Дверь", true));
        inTenant(() -> sender.onDealIssued(third));

        assertThat(syncClient.calls()).extracting(StubSyncClient.Call::packetId)
                .containsExactly("111", "222");
    }

    /**
     * Итог отправки свой у каждого прайс-листа (задача 0268): чужой успех не
     * затирает отказ, а отказ соседа не красит принявшую выгрузку.
     */
    @Test
    @DisplayName("Две выгрузки: одна приняла, другая отказала — каждая говорит про себя")
    void outcomeIsPerFeed() {
        account("Принимает", "111", "ключ-кабинета");
        account("Отказывает", "222", "ключ-кабинета");
        syncClient.failFor("222", 200, "ERROR_REASON_PACKET_NOT_FOUND");

        assertThat(statusOf("Принимает").state()).isEqualTo(DeliveryStatus.State.WAITING);
        assertThat(statusOf("Отказывает").state()).isEqualTo(DeliveryStatus.State.WAITING);

        long dealId = issuedDeal(part("Бампер", true));
        inTenant(() -> sender.onDealIssued(dealId));

        DeliveryStatus ok = statusOf("Принимает");
        assertThat(ok.state()).isEqualTo(DeliveryStatus.State.ACCEPTED);
        assertThat(ok.at()).isNotNull();
        assertThat(ok.code()).isNull();
        DeliveryStatus refused = statusOf("Отказывает");
        assertThat(refused.state()).isEqualTo(DeliveryStatus.State.REFUSED);
        assertThat(refused.code()).isEqualTo(DeliveryStatus.Code.PACKET_NOT_FOUND);
    }

    @Test
    @DisplayName("Принятая после отказа дельта снимает отказ; сбой без текста — обрыв связи, а не «принято»")
    void laterOutcomeReplacesEarlier() {
        account("Дром", "111", "ключ-кабинета");
        syncClient.failWith(0, null);
        inTenant(() -> sender.onDealIssued(issuedDeal(part("Фара", true))));
        // Пустое сообщение сбоя не превращается в null, а null в этой колонке — «принято».
        assertThat(statusOf("Дром").state()).isEqualTo(DeliveryStatus.State.REFUSED);
        assertThat(statusOf("Дром").code()).isEqualTo(DeliveryStatus.Code.CONNECTION_BROKEN);

        syncClient.reset();
        inTenant(() -> sender.onDealIssued(issuedDeal(part("Дверь", true))));
        assertThat(statusOf("Дром").state()).isEqualTo(DeliveryStatus.State.ACCEPTED);
    }

    /**
     * Выдача сделки зовёт тот же отправитель, что и релей (задача 0269):
     * мимо интервала после отказа «нужен человек» она не стучится, а сбой,
     * который может пройти сам, по-прежнему отдаёт событие на повтор.
     */
    @Test
    @DisplayName("Выдача сделки не стучится в отказавшую выгрузку раньше интервала; временный сбой — отказ, как раньше")
    void dealIssueRespectsTheRefusalInterval() {
        account("Принимает", "111", "ключ-кабинета");
        account("Отказывает", "222", "ключ-кабинета");
        syncClient.failFor("222", 200, "ERROR_REASON_PACKET_NOT_FOUND");

        assertThat(inTenant(() -> sender.onDealIssued(issuedDeal(part("Фара", true))))).isTrue();
        assertThat(inTenant(() -> sender.onDealIssued(issuedDeal(part("Дверь", true))))).isTrue();

        assertThat(syncClient.calls()).extracting(StubSyncClient.Call::packetId)
                .as("вторая выдача постучалась в отказавшую выгрузку раньше интервала")
                .containsExactlyInAnyOrder("111", "222", "111");

        syncClient.failFor("111", 503, "сервис недоступен");
        assertThat(inTenant(() -> sender.onDealIssued(issuedDeal(part("Капот", true)))))
                .as("временный сбой обязан вернуть событие на повтор")
                .isFalse();
    }

    @Test
    @DisplayName("Дельта сверх лимита — попытка, видимая на карточке как TOO_BIG")
    void tooBigIsAnAttempt() {
        account("Дром", "111", "ключ-кабинета");
        syncClient.limit(10);
        inTenant(() -> sender.onDealIssued(issuedDeal(part("Капот", true))));

        assertThat(syncClient.calls()).isEmpty();
        assertThat(statusOf("Дром").state()).isEqualTo(DeliveryStatus.State.REFUSED);
        assertThat(statusOf("Дром").code()).isEqualTo(DeliveryStatus.Code.TOO_BIG);
    }

    @Test
    @DisplayName("Проданная деталь уезжает недоступной")
    void soldPartGoesAsUnavailable() {
        account("12345", "ключ-кабинета");
        Long partId = part("Стартер 1NZ-FE", true);
        long dealId = issuedDeal(partId);
        String key = feedKeyOf(partId);

        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

        String delta = capturedDelta();
        assertThat(delta)
                .contains("<id>" + key + "</id>")
                .contains("<state>Нет в наличии</state>");
    }

    /**
     * Дельта несёт снимки адресами полного прайса этой выгрузки (задача 0333).
     *
     * <p>В формате Bazon {@code <photoUrls>} есть у каждой позиции, и пустой
     * значит «снимков нет»: дельта без ссылок, какой она была раньше, сказала бы
     * площадке именно это про проданную или подешевевшую деталь. Адрес — тот же,
     * что у выдачи прайса: внешний адрес, код компании из реестра и токен
     * выгрузки, иначе ссылка в дельте вела бы туда, где снимка не отдадут.
     */
    @Test
    @DisplayName("Дельта уходит со снимками по адресу выдачи своей выгрузки")
    void deltaCarriesPhotosAtTheFeedAddress() {
        Long accountId = account("12345", "ключ-кабинета");
        Long partId = part("Фара со снимком", true);
        Long photoId = inTenant(() -> {
            jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 47");
            jdbc.update("""
                    INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                    VALUES (47, ?, 'Разборка', 'delta-photo')""", TENANT);
            jdbc.update("UPDATE marketplace_account SET feed_token = 'tok-delta-0333-feed-token-of-32-chars' WHERE id = ?",
                    accountId);
            return jdbc.queryForObject("""
                    INSERT INTO part_photo (part_id, s3_key, is_main, sort_order, status)
                    VALUES (?, ?, true, 1, 'PROCESSED') RETURNING id""",
                    Long.class, partId, TENANT + "/parts/" + partId + "/1.jpg");
        });
        long dealId = issuedDeal(partId);

        try {
            assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

            assertThat(capturedDelta())
                    .as("дельта ушла без снимков — площадка прочтёт пустой <photoUrls> как «снимков нет»")
                    .contains("<photoUrls>\n<photoUrl>" + PUBLIC_URL
                            + "/feeds/drom/delta-photo/tok-delta-0333-feed-token-of-32-chars/photo/" + photoId
                            + ".jpg</photoUrl>\n</photoUrls>");
        } finally {
            inTenant(() -> jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 47"));
        }
    }

    /**
     * Дельта собирается настройками своей выгрузки — теми же, что и прайс.
     *
     * <p><b>Зачем.</b> Дельта несёт текущее состояние позиции и перебивает
     * то, что стоит на площадке. Уйдя без наценки, она вернула бы объявлению
     * складскую цену через несколько секунд после того, как полный прайс
     * поставил цену с наценкой, — и владелец увидел бы это только на чужом
     * сайте, по цене без комиссии площадки.
     *
     * <p>Та же болезнь, что была у отбора: собранная не так, как прайс, дельта
     * рассказывает площадке не то, что рассказал файл.
     */
    @Test
    @DisplayName("Дельта уходит с наценкой выгрузки, а не со складской ценой")
    void deltaCarriesTheFeedPrice() {
        Long accountId = account("12345", "ключ-кабинета");
        inTenant(() -> jdbc.update("""
                UPDATE marketplace_account
                   SET settings = settings || '{"pricePercent": 10, "priceRounding": 10}'::jsonb
                 WHERE id = ?""", accountId));
        Long partId = part("Стартер с наценкой", true);
        long dealId = issuedDeal(partId);

        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

        // Цена позиции 5 000, наценка 10 % — площадка обязана увидеть 5 500,
        // то же самое, что стоит в полном прайсе этой выгрузки.
        assertThat(capturedDelta())
                .as("дельта вернула объявлению складскую цену — наценка прайса стёрта")
                .contains("<price>5500</price>");
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
        assertThat(delta).contains("<id>" + feedKeyOf(sold) + "</id>");
        assertThat(delta)
                .as("в дельту уехал весь склад вместо одной позиции")
                .doesNotContain("<id>" + feedKeyOf(untouched) + "</id>");
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
        // Отказ ключа без человека не пройдёт, и с задачи 0269 он не держит
        // ни очередь, ни событие: повтор события раз в несколько минут стучался
        // бы в Дром тем же неверным ключом. Отсюда «отправлено» — как у выгрузки
        // без ключа; сам отказ остаётся в журнале и на карточке.
        assertThat(inTenant(() -> sender.onDealIssued(dealId))).isTrue();

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
        return account("Дром", packetId, cabinetKey);
    }

    private DeliveryStatus statusOf(String title) {
        return inTenant(() -> accounts.list().stream()
                .filter(a -> title.equals(a.title())).findFirst().orElseThrow()
                .deliveryStatus());
    }

    private Long account(String title, String packetId, String cabinetKey) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, credentials, settings)
                VALUES ('DROM', ?, ?, ?::jsonb) RETURNING id""",
                Long.class, title,
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

    /** Ключ позиции в прайсе — её номер (задача 0334), а не публичный код. */
    private String feedKeyOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT number::text FROM part WHERE id = ?", String.class, partId));
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
        private final java.util.Map<String, Result> perPacket = new java.util.HashMap<>();
        private int limit = super.maxPacketBytes();

        @Override
        public int maxPacketBytes() {
            return limit;
        }

        void limit(int bytes) {
            limit = bytes;
        }

        @Override
        public Result sync(String packetId, String auth, byte[] deltaXml, String fileName) {
            // Дельта шин и дисков — CSV в windows-1251 (0340), остальное — XML в UTF-8.
            java.nio.charset.Charset charset = fileName.endsWith(".csv")
                    ? java.nio.charset.Charset.forName("windows-1251") : StandardCharsets.UTF_8;
            calls.add(new Call(packetId, auth, new String(deltaXml, charset), fileName));
            return perPacket.getOrDefault(packetId, answer);
        }

        /** Отказ только для одного прайс-листа: остальные принимают. */
        void failFor(String packetId, int httpStatus, String body) {
            perPacket.put(packetId, new Result(false, httpStatus, body));
        }

        void failWith(int httpStatus, String body) {
            answer = new Result(false, httpStatus, body);
        }

        void reset() {
            calls.clear();
            perPacket.clear();
            limit = super.maxPacketBytes();
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
