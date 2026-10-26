package ru.partsflow.publishing.drom;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Карточка выгрузки помнит, что часть изменений не ушла на Дром (задача 0271).
 *
 * <p>Итог последней отправки (0268) говорит про последнюю попытку, и малая
 * принятая дельта после большой, не влезшей в лимит, честно отвечает
 * «принято» — а изменения той большой не уехали. Признак держится отдельно
 * от исхода и гаснет только тогда, когда Дром скачал полный прайс позже
 * момента неотправки (ответ владельца 12.10.2026, вариант «а», tasks/0271).
 *
 * <p>Проверяется ответом сервера, как его читает экран, а не вызовом сервиса:
 * вложенный record, не доехавший до JSON, сервисный тест не увидел бы.
 * Отправка идёт настоящим релеем, Дром подменён той же заглушкой, что
 * в {@link DromDeltaSenderTest}.
 */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none",
        "app.crypto.key=" + DromDeltaSenderTest.CRYPTO_KEY,
        "app.public-url=" + DromDeltaSenderTest.PUBLIC_URL})
@AutoConfigureMockMvc
@Import(DromDeltaSenderTest.StubSyncConfig.class)
class UnsentChangesTest extends PostgresTestBase {

    private static final String TENANT = "t_000271";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private FeedDeltaRelay relay;
    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;
    @Autowired
    private ru.partsflow.inventory.PartChangeLog partChanges;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
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
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 271");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (271, ?, 'Разборка', 'unsentco')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM publication_log");
            jdbc.update("DELETE FROM marketplace_account");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('owner', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    /**
     * Главная проверка задачи: малая принятая дельта после неотправленной
     * признак не гасит. На прежнем коде «принято» стояло одно.
     */
    @Test
    @DisplayName("Дельта сверх лимита ставит признак, и следующая принятая дельта его не гасит")
    void acceptedSmallDeltaDoesNotHideTheUnsentOne() throws Exception {
        account("Дром", "111", null);
        Long big = part("Капот", "5000");
        Long small = part("Фара", "5000");
        drainQueue();

        syncClient.limit(10);
        change(big);
        relay.relayFor(TENANT);

        Map<String, Object> afterBig = feedJson("Дром");
        assertThat(path(afterBig, "deliveryStatus.code")).isEqualTo("TOO_BIG");
        assertThat(path(afterBig, "unsent.code")).as("неотправка сверх лимита не запомнилась")
                .isEqualTo("TOO_BIG");
        assertThat(path(afterBig, "unsent.limitBytes"))
                .as("предел — из записи о неотправке, а не копия константы на экране")
                .isEqualTo(10);
        assertThat(path(afterBig, "unsent.at")).isNotNull();
        assertThat(path(afterBig, "unsent.fullPullDays")).isEqualTo(3);

        syncClient.reset();
        change(small);
        relay.relayFor(TENANT);

        Map<String, Object> afterSmall = feedJson("Дром");
        assertThat(path(afterSmall, "deliveryStatus.state")).isEqualTo("ACCEPTED");
        assertThat(path(afterSmall, "unsent.code"))
                .as("принятая малая дельта погасила признак: крупные изменения не уехали, а карточка молчит")
                .isEqualTo("TOO_BIG");
        assertThat(path(afterSmall, "unsent.at")).isEqualTo(path(afterBig, "unsent.at"));
    }

    /**
     * Ответ владельца (а): покрыто, когда Дром скачал полный прайс позже
     * момента неотправки. Скачанный раньше — не покрывает: в нём этих
     * изменений не было.
     */
    @Test
    @DisplayName("Гаснет только от забора прайса позже неотправки; забор до неё — не гасит")
    void onlyALaterFullDownloadCoversIt() throws Exception {
        Long id = account("Дром", "111", null);
        Long partId = part("Капот", "5000");
        drainQueue();
        syncClient.limit(10);
        change(partId);
        relay.relayFor(TENANT);

        downloadedRelativeToUnsent(id, "- interval '1 second'");
        assertThat(path(feedJson("Дром"), "unsent.code"))
                .as("забор прайса раньше неотправки погасил признак")
                .isEqualTo("TOO_BIG");

        downloadedRelativeToUnsent(id, "+ interval '1 second'");
        assertThat(feedJson("Дром").get("unsent"))
                .as("Дром скачал прайс позже неотправки, а признак висит")
                .isNull();
    }

    /**
     * Дельта сверх лимита не держит очередь (ответ владельца по 0269, «б»,
     * распространён на лимит этой задачей): пачка та же и размер тот же,
     * повтор каждые пятнадцать секунд не уйдёт никогда — а стоял он первым
     * в очереди и тащил за собой исправные выгрузки.
     */
    @Test
    @DisplayName("Лимит одной выгрузки не повторяет пачку в исправную и признак у соседки не ставит")
    void tooBigDoesNotDragHealthyFeedsAndIsPerFeed() throws Exception {
        account("Всё", "111", null);
        account("Дешёвое", "222", "6000");
        Long cheap = part("Фара", "5000");
        Long dear = part("Капот", "9000");
        drainQueue();

        // Размер дельты меряем на деле: в «Всё» уходят обе позиции, в «Дешёвое» — одна.
        change(cheap);
        change(dear);
        relay.relayFor(TENANT);
        int wide = deltaSize("111");
        int narrow = deltaSize("222");
        assertThat(wide).isGreaterThan(narrow);
        syncClient.reset();
        syncClient.limit((wide + narrow) / 2);

        change(cheap);
        change(dear);
        relay.relayFor(TENANT);
        relay.relayFor(TENANT);
        relay.relayFor(TENANT);

        assertThat(callsTo("222"))
                .as("исправная выгрузка получила ту же пачку по разу на каждый проход")
                .isEqualTo(1);
        assertThat(callsTo("111")).as("сверх лимита не отправляется вовсе").isZero();
        assertThat(queueSize()).as("дельта сверх лимита держит очередь").isZero();
        assertThat(path(feedJson("Всё"), "unsent.code")).isEqualTo("TOO_BIG");
        assertThat(feedJson("Дешёвое").get("unsent"))
                .as("признак соседней выгрузки встал у исправной")
                .isNull();
    }

    /**
     * Отказ «нужен человек» снимает отметки без отправки (0269, «б») — значит
     * эти изменения у отказавшей выгрузки не уехали, и после починки ключа
     * карточка обязана об этом помнить. Пропуск в интервале после отказа —
     * то же самое: изменение снято с очереди, а отказавшей не досталось,
     * поэтому момент неотправки сдвигается, и забор между ними его не покрывает.
     */
    @Test
    @DisplayName("Отказ «нужен человек» и пропуск в интервале ставят признак; Дрому лишнего не шлют")
    void refusalAndRestingSkipAreUnsent() throws Exception {
        Long refusing = account("Отказывает", "222", null);
        Long first = part("Радиатор", "5000");
        Long second = part("Крыло", "5000");
        drainQueue();
        syncClient.failFor("222", 200, "ERROR_REASON_PACKET_NOT_FOUND");

        change(first);
        relay.relayFor(TENANT);
        Map<String, Object> refused = feedJson("Отказывает");
        assertThat(path(refused, "unsent.code")).isEqualTo("PACKET_NOT_FOUND");
        String refusedAt = (String) path(refused, "unsent.at");

        // Дром скачал прайс после отказа: тот отказ покрыт…
        downloadedRelativeToUnsent(refusing, "+ interval '1 second'");
        assertThat(feedJson("Отказывает").get("unsent")).isNull();

        // …а изменение в интервале снято с очереди без отправки — и уже не покрыто.
        inTenant(() -> jdbc.update("""
                UPDATE marketplace_account SET unsent_at = unsent_at - interval '1 minute',
                                               last_feed_download_at = last_feed_download_at - interval '1 minute'
                 WHERE id = ?""", refusing));
        change(second);
        relay.relayFor(TENANT);
        relay.relayFor(TENANT);

        Map<String, Object> resting = feedJson("Отказывает");
        assertThat(path(resting, "unsent.code"))
                .as("пропуск в интервале после отказа не запомнился как неотправка")
                .isEqualTo("PACKET_NOT_FOUND");
        assertThat((String) path(resting, "unsent.at")).isNotEqualTo(refusedAt);
        assertThat(queueSize()).isZero();
        assertThat(callsTo("222"))
                .as("ради признака Дрому ушло лишнее: в интервале отказавшую не пробуют")
                .isEqualTo(1);
    }

    /**
     * Временный сбой (502, тишина, отказ соединения, обрыв) — тоже неотправка:
     * ответ владельца 26.10.2026 (tasks/0271, «Вопрос владельцу — временный
     * сбой и признак»), «признак обязан ставиться и на временный сбой». Очередь
     * при этом держится, как держалась: повтор уходит следующим заходом
     * и признак не гасит — гасит его только забор прайса позже неотправки.
     * Ради признака Дрому не уходит ничего сверх обычного повтора.
     */
    @Test
    @DisplayName("Временный сбой — 502, нет ответа, обрыв — ставит признак; принятый повтор его не гасит")
    void transientFailureIsUnsent() throws Exception {
        Long id = account("Дром", "111", null);
        Long partId = part("Дверь", "5000");
        record Failure(String name, Runnable inject, String code, Integer httpStatus) {
        }
        List<Failure> failures = List.of(
                new Failure("502", () -> syncClient.failFor("111", 502, "<html>Bad Gateway</html>"),
                        "HTTP_ERROR", 502),
                new Failure("нет ответа", () -> syncClient.dropFor("111",
                        DromSyncClient.Transport.TIMEOUT, "request timed out"), "TIMEOUT", null),
                new Failure("не соединились", () -> syncClient.dropFor("111",
                        DromSyncClient.Transport.CONNECT, "Connection refused"), "NO_CONNECTION", null),
                new Failure("обрыв", () -> syncClient.dropFor("111",
                        DromSyncClient.Transport.BROKEN, "Connection reset"), "CONNECTION_BROKEN", null));

        for (Failure failure : failures) {
            inTenant(() -> jdbc.update("""
                    UPDATE marketplace_account SET unsent_at = NULL, unsent_error = NULL,
                                                   last_sync_at = NULL, last_error = NULL
                     WHERE id = ?""", id));
            drainQueue();
            failure.inject().run();

            change(partId);
            relay.relayFor(TENANT);

            Map<String, Object> failed = feedJson("Дром");
            assertThat(path(failed, "deliveryStatus.code")).as(failure.name()).isEqualTo(failure.code());
            assertThat(path(failed, "unsent.code"))
                    .as("%s: временный сбой не поставил признак «часть изменений не ушла»", failure.name())
                    .isEqualTo(failure.code());
            assertThat(path(failed, "unsent.httpStatus")).as(failure.name()).isEqualTo(failure.httpStatus());
            assertThat(path(failed, "unsent.at")).as(failure.name()).isNotNull();
            assertThat(queueSize()).as("%s: временный сбой обязан держать очередь, как раньше",
                    failure.name()).isEqualTo(1);
            assertThat(callsTo("111")).as("%s: ради признака Дрому ушло лишнее", failure.name())
                    .isEqualTo(1);

            // Повтор следующим заходом принят — итог «принято», а признак стоит.
            syncClient.reset();
            relay.relayFor(TENANT);
            Map<String, Object> retried = feedJson("Дром");
            assertThat(callsTo("111")).as("%s: повтор после сбоя", failure.name()).isEqualTo(1);
            assertThat(path(retried, "deliveryStatus.state")).as(failure.name()).isEqualTo("ACCEPTED");
            assertThat(path(retried, "unsent.code"))
                    .as("%s: принятый повтор погасил признак — гасит только забор прайса", failure.name())
                    .isEqualTo(failure.code());
            assertThat(path(retried, "unsent.at")).isEqualTo(path(failed, "unsent.at"));
            assertThat(queueSize()).as(failure.name()).isZero();
        }

        downloadedRelativeToUnsent(id, "+ interval '1 second'");
        assertThat(feedJson("Дром").get("unsent"))
                .as("Дром скачал прайс позже сбоя, а признак висит")
                .isNull();
    }

    @Test
    @DisplayName("Выгрузка без отметки и выключенная читаются как «неотправленного нет»")
    void noMarkMeansNothingUnsent() throws Exception {
        account("Чистая", "111", null);
        Long paused = account("Выключена", "222", null);
        inTenant(() -> jdbc.update("""
                UPDATE marketplace_account
                   SET status = 'PAUSED', unsent_at = now(),
                       unsent_error = '[TOO_BIG] дельта превысила лимит 10 байт'
                 WHERE id = ?""", paused));

        assertThat(feedJson("Чистая")).containsKey("unsent");
        assertThat(feedJson("Чистая").get("unsent")).isNull();
        assertThat(feedJson("Выключена").get("unsent"))
                .as("у выключенной забора нет — обещать «обновится при заборе» нечестно")
                .isNull();
    }

    // ---------- фикстуры ----------

    private Map<String, Object> feedJson(String title) throws Exception {
        String body = mvc.perform(get("/api/marketplace-accounts").session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> found = JsonPath.read(body, "$[?(@.title=='%s')]".formatted(title));
        assertThat(found).as("выгрузка «%s» в ответе", title).hasSize(1);
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> feed, String dotted) {
        Object at = feed;
        for (String key : dotted.split("\\.")) {
            if (!(at instanceof Map)) {
                return null;
            }
            at = ((Map<String, Object>) at).get(key);
        }
        return at;
    }

    private void downloadedRelativeToUnsent(Long accountId, String shift) {
        inTenant(() -> jdbc.update(
                "UPDATE marketplace_account SET last_feed_download_at = unsent_at " + shift
                        + " WHERE id = ?", accountId));
    }

    private Long account(String title, String packetId, String priceTo) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO marketplace_account (marketplace, title, credentials, settings, price_to)
                VALUES ('DROM', ?, ?, ?::jsonb, ?::numeric) RETURNING id""",
                Long.class, title, "ключ-кабинета".getBytes(StandardCharsets.UTF_8),
                "{\"packetId\": \"" + packetId + "\"}", priceTo));
    }

    private Long part(String title, String price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, ?, ?::numeric, true) RETURNING id""", Long.class, title, price);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.valueOf(2), warehouse, null));
            return partId;
        });
    }

    private void change(Long partId) {
        inTenant(() -> {
            jdbc.update("UPDATE part SET price = price + 1 WHERE id = ?", partId);
            partChanges.changed(partId);
            return null;
        });
    }

    private void drainQueue() {
        inTenant(() -> jdbc.update("DELETE FROM part_change"));
        syncClient.reset();
    }

    private int queueSize() {
        return inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_change", Integer.class));
    }

    private long callsTo(String packetId) {
        return syncClient.calls().stream().filter(c -> packetId.equals(c.packetId())).count();
    }

    private int deltaSize(String packetId) {
        return syncClient.calls().stream().filter(c -> packetId.equals(c.packetId()))
                .findFirst().orElseThrow().delta().getBytes(StandardCharsets.UTF_8).length;
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"unsentco","login":"owner","password":"пароль"}"""))
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
