package ru.partsflow.sales;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
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
 * По закрытой сделке денег не берут.
 *
 * <p><b>Что было.</b> Долг считался как «сумма минус оплачено» без оглядки
 * на состояние, и у отменённой сделки, как и у возвращённой, оставался
 * ненулевым. Продавец видел «Долг 1000 ₽» у детали, которую клиент сам принёс
 * обратно, и шёл звонить за деньгами. Рядом с этим числом на экране стоит
 * кнопка «Зачесть со счёта» — показывается она ровно при {@code debt > 0}.
 *
 * <p><b>Чем это кончалось.</b> Нажатие забирало деньги <b>со счёта клиента</b>
 * в счёт возвращённого товара: на живом прогоне 150 ₽ ушли в ноль. Обычная
 * оплата отменённой сделки тоже принималась — приход в кассу, которого вечером
 * не сойдётся с ящиком. Первое не ловила даже сверка: {@code
 * v_account_discrepancy} знает про отменённую с невозвращённой оплатой,
 * а про возвращённую — нет.
 *
 * <p>Поймано живым прогоном по цепочке «выдал без оплаты → клиент вернул»,
 * а не тестом: тесты платили по сделкам, которые для этого и заводили.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class ClosedDealMoneyTest extends PostgresTestBase {

    private static final String TENANT = "t_000104";

    @Autowired
    private SalesService sales;

    @Autowired
    private StockLedger ledger;

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
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад') RETURNING id",
                    Long.class, branch);
            // Клиент свой на каждый тест: остаток лицевого счёта считается
            // по журналу, а журнал неизменяем — чистить его в фикстуре нельзя.
            customerId = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Возвращающийся') RETURNING id",
                    Long.class);
            managerId = jdbc.queryForObject("""
                    INSERT INTO tenant_member (user_id, display_name, role)
                    VALUES ((SELECT COALESCE(max(user_id), 0) + 1 FROM tenant_member),
                            'Пётр', 'SELLER') RETURNING id""", Long.class);
            return null;
        });
    }

    /**
     * Главный случай: товар отдали, денег не взяли, клиент принёс обратно.
     * Долг обязан обнулиться — иначе продавец звонит за деньгами, которых
     * никто не должен.
     */
    @Test
    @DisplayName("У возвращённой сделки долга нет")
    void returnedDealOwesNothing() {
        Long partId = partWithStock("Фара", new BigDecimal("1000"));

        Deal deal = inTenant(() -> {
            Deal issued = issued(partId, new BigDecimal("1000"));
            sales.registerReturn(issued.getId(), warehouseId,
                    List.of(SalesService.ReturnRequest.whole(issued.getItems().get(0).getId())),
                    "Не подошла", false, null, managerId);
            return sales.require(issued.getId());
        });

        assertThat(deal.getStatus()).isEqualTo(DealStatus.RETURNED);
        assertThat(deal.debt())
                .as("клиент вернул товар, а с экрана продавца ему выставлен долг")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Отменённая не состоялась вовсе — тем более платить не за что. */
    @Test
    @DisplayName("У отменённой сделки долга нет")
    void cancelledDealOwesNothing() {
        Long partId = partWithStock("Бампер", new BigDecimal("500"));

        Deal deal = inTenant(() -> {
            Deal created = reserved(partId, new BigDecimal("500"));
            sales.cancel(created.getId(), managerId, "Передумал");
            return sales.require(created.getId());
        });

        assertThat(deal.debt()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Самое дорогое из трёх: деньги клиента уходят в счёт товара, который
     * он вернул. Отменить это можно только ручной правкой остатка.
     */
    @Test
    @DisplayName("Зачёт со счёта в возвращённую сделку отбивается")
    void offsetIntoReturnedDealIsRefused() {
        Long partId = partWithStock("Крыло", new BigDecimal("1000"));

        Long dealId = inTenant(() -> {
            Deal issued = issued(partId, new BigDecimal("1000"));
            sales.registerReturn(issued.getId(), warehouseId,
                    List.of(SalesService.ReturnRequest.whole(issued.getItems().get(0).getId())),
                    "Не подошла", false, null, managerId);
            sales.topUpAccount(customerId, new BigDecimal("150"), null, managerId);
            return issued.getId();
        });

        assertThatThrownBy(() -> inTenant(() ->
                sales.payFromAccount(dealId, new BigDecimal("150"), managerId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("закрыта");

        assertThat(balance())
                .as("деньги клиента ушли в счёт товара, который он вернул")
                .isEqualByComparingTo(new BigDecimal("150"));
    }

    /**
     * Приход в кассу по отменённой сделке — это деньги, которых вечером
     * не сойдётся с ящиком. Сверка его ловит, но задним числом.
     */
    @Test
    @DisplayName("Оплата отменённой сделки отбивается")
    void paymentIntoCancelledDealIsRefused() {
        Long partId = partWithStock("Дверь", new BigDecimal("500"));

        Long dealId = inTenant(() -> {
            Deal created = reserved(partId, new BigDecimal("500"));
            sales.cancel(created.getId(), managerId, "Передумал");
            return created.getId();
        });

        assertThatThrownBy(() -> inTenant(() ->
                sales.takePayment(dealId, new BigDecimal("500"), null, managerId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("закрыта");

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE deal_id = ?", Integer.class, dealId)))
                .as("в кассу попал приход по несостоявшейся сделке")
                .isZero();
    }

    /**
     * Запрет обязан различать закрытое и живое: предоплата по отложенной
     * сделке — ежедневная работа продавца.
     */
    @Test
    @DisplayName("По живой сделке деньги принимаются как раньше")
    void openDealStillTakesMoney() {
        Long partId = partWithStock("Капот", new BigDecimal("700"));

        Deal paid = inTenant(() -> {
            Deal created = reserved(partId, new BigDecimal("700"));
            sales.takePayment(created.getId(), new BigDecimal("700"), null, managerId);
            return sales.require(created.getId());
        });

        assertThat(paid.getPaidAmount()).isEqualByComparingTo(new BigDecimal("700"));
        assertThat(paid.debt()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private Deal reserved(Long partId, BigDecimal price) {
        return sales.createReserved(customerId, managerId,
                Instant.now().plus(1, ChronoUnit.DAYS), null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, price, warehouseId)),
                List.of());
    }

    private Deal issued(Long partId, BigDecimal price) {
        return sales.issue(reserved(partId, price).getId(), managerId);
    }

    private BigDecimal balance() {
        BigDecimal value = inTenant(() -> jdbc.queryForObject("""
                SELECT coalesce(sum(CASE WHEN entry_type IN ('TOP_UP', 'CORRECTION')
                                         THEN amount ELSE -amount END), 0)
                  FROM customer_account_entry WHERE customer_id = ?""",
                BigDecimal.class, customerId));
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Остаток ставится движением: писать в кэш напрямую нельзя. */
    private Long partWithStock(String title, BigDecimal price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, status)
                    VALUES (1, ?, ?, ?, 'IN_STOCK') RETURNING id""",
                    Long.class, title, price, price.multiply(new BigDecimal("0.4")));
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouseId, null));
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
