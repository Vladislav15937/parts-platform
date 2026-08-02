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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Лицевой счёт клиента: остаток и зачёт в оплату сделки.
 *
 * <p>Переплата ложилась на счёт с самого начала, а читать его было нечем
 * и тратить — тоже: деньги клиента в системе есть, продавец их не видит,
 * и при следующем приезде про свою тысячу помнит только клиент.
 *
 * <p>Самое дорогое здесь — не остаток, а то, что зачёт **не создаёт платежа**.
 * Деньги получены раньше, тогда же записан приход; второй платёж задвоил бы
 * выручку, и в отчёте появилась бы тысяча, которую никто не приносил.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class CustomerAccountTest extends PostgresTestBase {

    private static final String TENANT = "t_000089";

    @Autowired
    private SalesService sales;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long customerId;
    private Long managerId;
    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            // Не чистим: журнал счёта и платежей неизменяем — исправление
            // только новой записью. Клиент заводится свой на каждый тест,
            // и остаток считается по нему.
            Long branchId = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branchId);
            customerId = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            managerId = jdbc.queryForObject("""
                    INSERT INTO tenant_member (user_id, display_name, role)
                    VALUES ((SELECT COALESCE(max(user_id), 0) + 1 FROM tenant_member), 'Пётр', 'SELLER')
                    RETURNING id""", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Переплата попадает на счёт, и остаток её показывает")
    void overpaymentLandsOnAccount() {
        Long partId = partWithStock("Фара для переплаты", new BigDecimal("4500"));
        Deal deal = inTenant(() -> issued(partId, new BigDecimal("4500")));

        inTenant(() -> sales.takePayment(deal.getId(), new BigDecimal("5000"), null, managerId));

        assertThat(inTenant(() -> sales.accountBalance(customerId)))
                .isEqualByComparingTo("500");
    }

    /**
     * Главный инвариант: зачёт не приносит денег, он их тратит.
     *
     * <p>Приход записан при пополнении. Создав платёж ещё и на зачёте, мы
     * получили бы в кассе сумму, которую никто не приносил, — и разошлись бы
     * с реальными деньгами ровно на неё.
     */
    @Test
    @DisplayName("Зачёт со счёта не создаёт платежа: выручка не задваивается")
    void payingFromAccountCreatesNoPayment() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("5000"), null, managerId));
        int paymentsAfterTopUp = payments();

        Long partId = partWithStock("Бампер за счёт аванса", new BigDecimal("3000"));
        Deal deal = inTenant(() -> issued(partId, new BigDecimal("3000")));

        Deal paid = inTenant(() -> sales.payFromAccount(
                deal.getId(), new BigDecimal("3000"), managerId));

        assertThat(paid.debt()).isEqualByComparingTo("0");
        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("2000");
        assertThat(payments())
                .as("зачёт создал платёж — в кассе появились деньги, которых не приносили")
                .isEqualTo(paymentsAfterTopUp);
    }

    // Счёт — это обязательство перед клиентом. Уйдя в минус, оно превращается
    // в долг клиента, о котором он не договаривался.
    @Test
    @DisplayName("Больше остатка со счёта не зачесть, и отказ называет числа")
    void cannotSpendMoreThanBalance() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("1000"), null, managerId));
        Long partId = partWithStock("Дверь дороже аванса", new BigDecimal("9000"));
        Deal deal = inTenant(() -> issued(partId, new BigDecimal("9000")));

        assertThatThrownBy(() -> inTenant(() -> sales.payFromAccount(
                deal.getId(), new BigDecimal("5000"), managerId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1000")
                .hasMessageContaining("5000");

        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("1000");
    }

    // Зачесть больше долга значило бы переплату поверх уже оплаченной сделки:
    // деньги ушли бы со счёта и вернулись на него же, оставив в журнале две
    // записи, которые ничего не объясняют.
    @Test
    @DisplayName("Больше долга по сделке не зачесть")
    void cannotSpendMoreThanDebt() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("10000"), null, managerId));
        Long partId = partWithStock("Фара дешевле аванса", new BigDecimal("2000"));
        Deal deal = inTenant(() -> issued(partId, new BigDecimal("2000")));

        assertThatThrownBy(() -> inTenant(() -> sales.payFromAccount(
                deal.getId(), new BigDecimal("5000"), managerId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2000");
    }

    @Test
    @DisplayName("Журнал счёта читается со знаком: приход плюсом, зачёт минусом")
    void entriesCarrySign() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("4000"), null, managerId));
        Long partId = partWithStock("Стартер", new BigDecimal("1500"));
        Deal deal = inTenant(() -> issued(partId, new BigDecimal("1500")));
        inTenant(() -> sales.payFromAccount(deal.getId(), new BigDecimal("1500"), managerId));

        var entries = inTenant(() -> sales.accountEntries(customerId));

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).signedAmount())
                .as("оплата со счёта обязана вычитать, а не прибавлять")
                .isEqualByComparingTo("-1500");
        assertThat(entries.get(1).signedAmount()).isEqualByComparingTo("4000");
        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("2500");
    }

    /**
     * У выдачи и зачёта разная природа, и видно это по кассе.
     *
     * <p>Зачёт денег не двигает — они уже у нас и просто меняют назначение.
     * Выдача уносит их из кассы клиенту, и расход, которого в кассе нет,
     * к вечеру не сойдётся с ящиком.
     */
    @Test
    @DisplayName("Выдача со счёта создаёт расход в кассе, в отличие от зачёта")
    void withdrawalCreatesPayment() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("3000"), null, managerId));
        int before = payments();

        inTenant(() -> sales.withdrawFromAccount(
                customerId, new BigDecimal("1200"), null, managerId));

        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("1800");
        assertThat(payments())
                .as("выдача не отразилась в кассе — деньги ушли, а расхода нет")
                .isEqualTo(before + 1);
        assertThat(outgoing())
                .as("выдача записана приходом: касса вырастет на сумму, которую отдали")
                .isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("Больше остатка со счёта не выдать")
    void cannotWithdrawMoreThanBalance() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("700"), null, managerId));

        assertThatThrownBy(() -> inTenant(() -> sales.withdrawFromAccount(
                customerId, new BigDecimal("1000"), null, managerId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("700");

        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("700");
    }

    private BigDecimal outgoing() {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(amount), 0) FROM payment
                 WHERE customer_id = ? AND direction = 'OUT'""",
                BigDecimal.class, customerId));
    }

    /**
     * Отмена оплаченной сделки возвращает деньги, а не теряет их.
     *
     * <p>Клиент оставил аванс, продавец зачёл его в отложенную сделку, клиент
     * передумал. До этой правки полторы тысячи не числились ни за сделкой
     * (она закрыта), ни на счёте (их оттуда списали) — и всплывало это,
     * когда клиент приезжал за своими деньгами.
     */
    @Test
    @DisplayName("Отмена оплаченной сделки возвращает деньги на счёт")
    void cancellingPaidDealReturnsMoney() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("2000"), null, managerId));
        Long partId = partWithStock("Фара, от которой отказались", new BigDecimal("1500"));
        Deal deal = inTenant(() -> sales.createReserved(customerId, managerId,
                Instant.now().plus(1, ChronoUnit.DAYS), null,
                List.of(new SalesService.ItemRequest(
                        partId, BigDecimal.ONE, new BigDecimal("1500"), warehouseId)),
                List.of()));
        inTenant(() -> sales.payFromAccount(deal.getId(), new BigDecimal("1500"), managerId));
        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("500");

        inTenant(() -> sales.cancel(deal.getId(), managerId, "клиент передумал"));

        assertThat(inTenant(() -> sales.accountBalance(customerId)))
                .as("деньги пропали: сделка закрыта, а на счёт они не вернулись")
                .isEqualByComparingTo("2000");
    }

    // Оплата наличными возвращается туда же — на счёт: отмена это решение
    // в системе, а не открытая касса. Захочет забрать — выдадут, и расход
    // появится тогда же.
    @Test
    @DisplayName("Наличная оплата при отмене тоже возвращается на счёт")
    void cancellingCashPaidDealReturnsMoney() {
        Long partId = partWithStock("Бампер, от которого отказались", new BigDecimal("3000"));
        Deal deal = inTenant(() -> sales.createReserved(customerId, managerId,
                Instant.now().plus(1, ChronoUnit.DAYS), null,
                List.of(new SalesService.ItemRequest(
                        partId, BigDecimal.ONE, new BigDecimal("3000"), warehouseId)),
                List.of()));
        inTenant(() -> sales.takePayment(deal.getId(), new BigDecimal("3000"), null, managerId));

        inTenant(() -> sales.cancel(deal.getId(), managerId, null));

        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("3000");
    }

    // Неоплаченную отменяют как раньше: записи о возврате быть не должно,
    // иначе журнал счёта заполнится нулями, среди которых не найти настоящих.
    @Test
    @DisplayName("Отмена неоплаченной сделки счёта не касается")
    void cancellingUnpaidDealLeavesAccountAlone() {
        Long partId = partWithStock("Стартер без оплаты", new BigDecimal("900"));
        Deal deal = inTenant(() -> sales.createReserved(customerId, managerId,
                Instant.now().plus(1, ChronoUnit.DAYS), null,
                List.of(new SalesService.ItemRequest(
                        partId, BigDecimal.ONE, new BigDecimal("900"), warehouseId)),
                List.of()));

        inTenant(() -> sales.cancel(deal.getId(), managerId, null));

        assertThat(inTenant(() -> sales.accountEntries(customerId))).isEmpty();
    }

    /**
     * Правка — для того, что случилось вне системы: деньги приняли мимо кассы,
     * старый долг простили, при переезде остаток приехал не тем. Всё, что
     * растёт из самой системы, чинится в ней.
     */
    @Test
    @DisplayName("Правка меняет остаток в обе стороны и хранит причину")
    void correctionMovesBalanceBothWays() {
        inTenant(() -> sales.correctAccount(customerId, new BigDecimal("1500"),
                "принято наличными мимо кассы 30 июля", managerId));
        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("1500");

        inTenant(() -> sales.correctAccount(customerId, new BigDecimal("-500"),
                "ошиблись при переносе из прежней системы", managerId));

        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("1000");
        assertThat(inTenant(() -> sales.accountEntries(customerId)).get(0).getComment())
                .isEqualTo("ошиблись при переносе из прежней системы");
    }

    // Без «почему» через месяц правку не отличить от ошибки, а спорить о ней
    // придётся с клиентом.
    @Test
    @DisplayName("Правка без причины не принимается")
    void correctionRequiresReason() {
        assertThatThrownBy(() -> inTenant(() -> sales.correctAccount(
                customerId, new BigDecimal("100"), "  ", managerId)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inTenant(() -> sales.accountEntries(customerId))).isEmpty();
    }

    // Отрицательный остаток — это долг клиента, а такого договора нет.
    @Test
    @DisplayName("Правка не уводит остаток в минус")
    void correctionKeepsBalanceNonNegative() {
        inTenant(() -> sales.topUpAccount(customerId, new BigDecimal("300"), null, managerId));

        assertThatThrownBy(() -> inTenant(() -> sales.correctAccount(
                customerId, new BigDecimal("-1000"), "списываем лишнее", managerId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("300");

        assertThat(inTenant(() -> sales.accountBalance(customerId))).isEqualByComparingTo("300");
    }

    /**
     * У заказа с площадки клиент необязателен, а назначить его задним числом
     * нечем. Отказ в отмене запер бы продавца в сделке, которую не отменить
     * и не выдать, поэтому деньги уходят расходом из кассы — так же,
     * как пришли.
     */
    @Test
    @DisplayName("Оплаченная сделка без клиента отменяется, деньги уходят из кассы")
    void cancellingPaidDealWithoutCustomerRefundsCash() {
        Long partId = partWithStock("Фара с площадки", new BigDecimal("2500"));
        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-000-77", null, null, null, null, null, null,
                List.of(new SalesService.ItemRequest(
                        partId, BigDecimal.ONE, new BigDecimal("2500"), warehouseId)),
                List.of()));
        Long dealId = accepted.deal().getId();
        inTenant(() -> sales.takePayment(dealId, new BigDecimal("2500"), null, managerId));

        inTenant(() -> sales.cancel(dealId, managerId, "покупатель отказался"));

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(amount), 0) FROM payment
                 WHERE deal_id = ? AND direction = 'OUT'""", BigDecimal.class, dealId)))
                .as("деньги остались у нас: отмена прошла, а расхода нет")
                .isEqualByComparingTo("2500");
    }

    private int payments() {
        Integer found = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE customer_id = ?", Integer.class, customerId));
        return found == null ? 0 : found;
    }

    private Deal issued(Long partId, BigDecimal price) {
        Deal created = sales.createReserved(customerId, managerId,
                Instant.now().plus(1, ChronoUnit.DAYS), null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, price, warehouseId)),
                List.of());
        return sales.issue(created.getId(), managerId);
    }

    private Long partWithStock(String title, BigDecimal price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, status)
                    VALUES (1, ?, ?, ?, 'IN_STOCK') RETURNING id""",
                    Long.class, title, price, price.multiply(new BigDecimal("0.4")));
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', 1, ?)""", partId, warehouseId);
            return partId;
        });
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
