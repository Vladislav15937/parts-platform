package ru.partsflow.sales;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Двое продавцов и одна сделка.
 *
 * <p>На разборке это обычное дело: клиент звонит одному продавцу и приезжает
 * к другому, а сделка одна. Экран у нас не держит форму открытой минутами —
 * там кнопки, — поэтому гонка выглядит не как «двое печатают», а как «двое
 * нажали»: один выдаёт, второй в ту же секунду отменяет.
 *
 * <p><b>Ожидания здесь щедрые намеренно.</b> Смысл теста — в том, что обе
 * транзакции читают сделку до того, как любая записала; сколько именно они
 * при этом ждут друг друга, не проверяется. Жёсткие десять секунд на раннере
 * с двумя ядрами превращают тест в лотерею: он падает не потому, что гонка
 * не отбита, а потому, что поток не успел добраться до защёлки.
 *
 * <p><b>Проверка статуса от этого не спасает.</b> Она читает то, что было
 * загружено в начале транзакции: обе видят {@code RESERVED}, обе проходят,
 * и обе пишут. Итог — товар списан со склада и одновременно снят с резерва,
 * то есть склад не сходится ни с чем, а документ показывает отменённую сделку
 * по уехавшей детали.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class DealConcurrencyTest extends PostgresTestBase {

    private static final String TENANT = "t_000087";

    /**
     * Верхняя граница ожидания, а не ожидаемое время: на исправной машине
     * потоки встречаются за миллисекунды.
     */
    private static final int WAIT_SECONDS = 60;

    @Autowired
    private SalesService sales;

    @Autowired
    private DealRepository deals;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;
    private Long customerId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        // Первое обращение к сущности поднимает метамодель Hibernate и прокси
        // репозитория. На холодном контексте это заметные секунды, и если бы
        // они выпали на поток внутри защёлки, тест ждал бы соседа, который
        // ещё только просыпается.
        inTenant(() -> deals.count());

        inTenant(() -> {
            jdbc.update("DELETE FROM deal_item");
            jdbc.update("DELETE FROM deal");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customerId = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Покупатель') RETURNING id", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Одновременные выдача и отмена: одна побеждает, склад сходится")
    void issueAndCancelDoNotBothSucceed() throws Exception {
        Long partId = partWithStock("Фара спорная", 1);
        Long dealId = inTenant(() -> sales.createReserved(
                customerId, null, Instant.now().plus(Duration.ofDays(1)), null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)),
                List.of()).getId());

        AtomicReference<Throwable> issueFailure = new AtomicReference<>();
        AtomicReference<Throwable> cancelFailure = new AtomicReference<>();

        // Обе транзакции читают сделку до того, как любая из них записала:
        // это и есть та секунда, в которую двое нажимают разные кнопки.
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Thread issuing = new Thread(() -> run(loaded, go, issueFailure,
                () -> sales.issue(dealId, null)));
        Thread cancelling = new Thread(() -> run(loaded, go, cancelFailure,
                () -> sales.cancel(dealId, null, "передумал")));

        issuing.start();
        cancelling.start();
        assertThat(loaded.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("потоки не дошли до защёлки за %d с — проверять гонку не на чем",
                        WAIT_SECONDS)
                .isTrue();
        go.countDown();
        issuing.join(WAIT_SECONDS * 1000L);
        cancelling.join(WAIT_SECONDS * 1000L);

        // Ровно одна из двух должна была не пройти. Если прошли обе — товар
        // списан со склада и одновременно снят с резерва.
        boolean bothWon = issueFailure.get() == null && cancelFailure.get() == null;
        assertThat(bothWon)
                .as("выдача и отмена прошли обе: склад списан и резерв снят, "
                        + "документ при этом отменён по уехавшей детали")
                .isFalse();

        String status = inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM deal WHERE id = ?", String.class, dealId));
        BigDecimal onHand = inTenant(() -> jdbc.queryForObject(
                "SELECT qty_on_hand FROM part WHERE id = ?", BigDecimal.class, partId));
        BigDecimal reserved = inTenant(() -> jdbc.queryForObject(
                "SELECT COALESCE(sum(qty_reserved), 0) FROM part_stock WHERE part_id = ?",
                BigDecimal.class, partId));

        if ("ISSUED".equals(status)) {
            // Выдали: детали на складе нет, держать нечего.
            assertThat(onHand).isEqualByComparingTo("0");
            assertThat(reserved).isEqualByComparingTo("0");
        } else {
            // Отменили: деталь на месте и свободна.
            assertThat(status).isEqualTo("CANCELLED");
            assertThat(onHand).isEqualByComparingTo("1");
            assertThat(reserved).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("Одновременные выдача и перенос позиций не проходят обе")
    void issueAndTransferDoNotBothSucceed() throws Exception {
        Long partId = partWithStock("Бампер спорный", 1);
        Long dealId = inTenant(() -> sales.createReserved(
                customerId, null, Instant.now().plus(Duration.ofDays(1)), null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)),
                List.of()).getId());
        Long itemId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM deal_item WHERE deal_id = ?", Long.class, dealId));

        AtomicReference<Throwable> issueFailure = new AtomicReference<>();
        AtomicReference<Throwable> transferFailure = new AtomicReference<>();

        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Thread issuing = new Thread(() -> run(loaded, go, issueFailure,
                () -> sales.issue(dealId, null)));
        Thread moving = new Thread(() -> run(loaded, go, transferFailure,
                () -> sales.transferItems(dealId, List.of(itemId), null)));

        issuing.start();
        moving.start();
        assertThat(loaded.await(WAIT_SECONDS, TimeUnit.SECONDS))
                .as("потоки не дошли до защёлки за %d с — проверять гонку не на чем",
                        WAIT_SECONDS)
                .isTrue();
        go.countDown();
        issuing.join(WAIT_SECONDS * 1000L);
        moving.join(WAIT_SECONDS * 1000L);

        // Здесь склад не спасает: перенос резерв не снимает и не ставит —
        // товар просто меняет документ. Значит обе операции проходят мимо
        // функций склада, и остановить их может только сам документ.
        boolean bothWon = issueFailure.get() == null && transferFailure.get() == null;
        assertThat(bothWon)
                .as("позицию выдали и одновременно перенесли в другую сделку: "
                        + "деталь уехала к клиенту и числится обещанной в новом документе")
                .isFalse();

        // Проигравший должен прочитать, что документ изменили, а не разбирать
        // сообщение про склад: там всё в порядке.
        Throwable loser = issueFailure.get() != null ? issueFailure.get() : transferFailure.get();
        assertThat(describe(loser))
                .as("отказ не назван изменением документа — продавец пойдёт "
                        + "искать поломку склада, которой нет")
                .contains("OptimisticLocking");
    }

    private static String describe(Throwable e) {
        if (e == null) {
            return "прошла";
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return e.getClass().getSimpleName() + " / " + root.getClass().getSimpleName()
                + ": " + String.valueOf(root.getMessage()).replace('\n', ' ');
    }

    private void run(CountDownLatch loaded, CountDownLatch go,
                     AtomicReference<Throwable> failure, Runnable action) {
        TenantContext.set(TENANT);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Читаем сделку внутри транзакции и ждём соседа: без этого
                // потоки разойдутся во времени и гонки не будет вовсе.
                sales.require(deal());
                loaded.countDown();
                try {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                action.run();
            });
        } catch (Throwable e) {
            failure.set(e);
        } finally {
            TenantContext.clear();
        }
    }

    private Long deal() {
        return jdbc.queryForObject("SELECT id FROM deal ORDER BY id DESC LIMIT 1", Long.class);
    }

    private Long partWithStock(String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, ?, 5000)
                    RETURNING id""", Long.class, title);
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', ?, ?)""", partId, qty, warehouseId);
            return partId;
        });
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
