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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Приём заказа, оформленного покупателем на площадке.
 *
 * <p>Главное отличие от обычной продажи: заказ уже существует, когда мы о нём
 * узнаём. Покупатель на Дроме нажал «купить» и заплатил, деньги держит
 * площадка, а у продавца трое рабочих суток на ответ — не ответил, и деньги
 * вернулись покупателю.
 *
 * <p>Отсюда два инварианта, которые тут и проверяются: заказ резервирует товар
 * в тот же момент (иначе деталь продадут с прилавка) и не заводится дважды
 * по одному номеру (иначе одна деталь обещана двум покупателям).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class MarketplaceOrderTest extends PostgresTestBase {

    private static final String TENANT = "t_000085";

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
        inTenant(() -> {
            jdbc.update("DELETE FROM deal_item");
            jdbc.update("DELETE FROM deal");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customerId = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Дром: защищённая сделка') RETURNING id",
                    Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Заказ резервирует товар в момент приёма")
    void orderReservesImmediately() {
        Long partId = partWithStock("Фара заказанная", 2);

        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-516-98", Instant.now().plus(Duration.ofDays(3)),
                customerId, null, null, "ТК СДЭК, Надым", null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)), List.of()));

        assertThat(accepted.missing()).isEmpty();
        assertThat(accepted.deal().getStatus()).isEqualTo(DealStatus.RESERVED);

        // Покупатель уже заплатил. Незарезервированную деталь через час
        // продадут с прилавка, и возвращать придётся деньги и репутацию
        // у площадки.
        assertThat(reserved(partId))
                .as("заказ не придержал товар — его продадут другому покупателю")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Повтор по тому же номеру возвращает прежнюю сделку, а не вторую")
    void repeatedOrderIsIdempotent() {
        Long partId = partWithStock("Бампер заказанный", 5);

        var first = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-777-01", null, customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)), List.of()));

        var second = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-777-01", null, customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)), List.of()));

        assertThat(second.replayed()).isTrue();
        assertThat(second.deal().getId()).isEqualTo(first.deal().getId());

        // Вторая сделка означала бы второй резерв на тот же товар, то есть
        // одну деталь, обещанную двум покупателям.
        assertThat(reserved(partId))
                .as("повтор зарезервировал товар второй раз")
                .isEqualByComparingTo("1");
        assertThat(inTenant(() -> deals.findAll().size())).isEqualTo(1);
    }

    @Test
    @DisplayName("Заказ, который нечем закрыть, записывается и ничего не резервирует")
    void unfulfillableOrderIsRecordedWithoutReservation() {
        Long partId = partWithStock("Стартер последний", 1);

        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-000-55", null, customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(
                        partId, new BigDecimal("3"), null, warehouseId)), List.of()));

        // Отказать в записи нельзя: заказ уже существует у площадки, и заказ,
        // о котором знает Дром и не знает разборка, — это потерянные деньги.
        assertThat(accepted.deal().getId()).isNotNull();
        assertThat(accepted.missing())
                .as("продавцу не сказали, чего именно не хватает")
                .hasSize(1);
        assertThat(accepted.missing().get(0)).contains("Стартер последний");

        // Частичный резерв тут хуже никакого: заказ всё равно придётся
        // отклонить целиком, а до отклонения он держал бы товар.
        assertThat(accepted.deal().getStatus()).isEqualTo(DealStatus.DRAFT);
        assertThat(reserved(partId))
                .as("необеспеченный заказ держит товар, который можно продать")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Заказы, ждущие ответа, идут по сроку площадки")
    void awaitingReplyIsOrderedByDeadline() {
        Long urgent = partWithStock("Дверь срочная", 1);
        Long later = partWithStock("Капот несрочный", 1);

        inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-100-01", Instant.now().plus(Duration.ofDays(2)),
                customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(later, BigDecimal.ONE, null, warehouseId)), List.of()));
        inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-100-02", Instant.now().plus(Duration.ofHours(2)),
                customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(urgent, BigDecimal.ONE, null, warehouseId)), List.of()));

        List<Deal> awaiting = inTenant(() -> sales.ordersAwaitingReply());

        // По сроку ответа, а не по дате заказа: пропущенный срок у Дрома —
        // это возврат денег покупателю, и заказ, до которого осталось два часа,
        // важнее вчерашнего, у которого их сутки.
        assertThat(awaiting).hasSize(2);
        assertThat(awaiting.get(0).getExternalOrderNo())
                .as("очередь отсортирована не по сроку — горящий заказ ушёл вниз")
                .isEqualTo("301-100-02");
    }

    @Test
    @DisplayName("Подтверждённый заказ уходит из очереди, но остаётся сделкой")
    void acceptedOrderLeavesTheQueue() {
        Long partId = partWithStock("Крыло подтверждённое", 1);

        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-200-07", Instant.now().plus(Duration.ofDays(1)),
                customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)), List.of()));

        Deal confirmed = inTenant(() -> sales.acceptOrder(accepted.deal().getId(), null));

        assertThat(confirmed.getOrderAcceptedAt()).isNotNull();
        assertThat(inTenant(() -> sales.ordersAwaitingReply())).isEmpty();

        // Подтверждение — это ответ площадке, а не движение склада: товар
        // зарезервирован с момента приёма и остаётся зарезервированным.
        assertThat(confirmed.getStatus()).isEqualTo(DealStatus.RESERVED);
        assertThat(reserved(partId)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Отменённый заказ освобождает товар и не ждёт ответа")
    void cancelledOrderReleasesStock() {
        Long partId = partWithStock("Решётка отменённая", 1);

        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-300-09", Instant.now().plus(Duration.ofDays(1)),
                customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)), List.of()));

        inTenant(() -> sales.cancel(accepted.deal().getId(), null, "покупатель отказался"));

        assertThat(reserved(partId))
                .as("отклонённый заказ навсегда заблокировал деталь")
                .isEqualByComparingTo("0");
        assertThat(inTenant(() -> sales.ordersAwaitingReply()))
                .as("отклонённый заказ всё ещё ждёт ответа")
                .isEmpty();
    }

    @Test
    @DisplayName("Доставка входит в деньги сделки, а не в примечание")
    void deliveryIsMoneyNotANote() {
        Long partId = partWithStock("Зеркало с доставкой", 1);
        Long delivery = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM service WHERE name = 'Доставка'", Long.class));

        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-500-11", null, customerId, null, null,
                "ТК СДЭК, Надым", null,
                List.of(new SalesService.ItemRequest(
                        partId, BigDecimal.ONE, new BigDecimal("4500"), warehouseId)),
                List.of(new SalesService.ServiceRequest(
                        delivery, BigDecimal.ONE, new BigDecimal("300")))));

        // Площадка переводит деньги за деталь вместе с доставкой. Сумма
        // документа без неё не сойдётся с переводом, и разбирать это
        // придётся вручную по каждой сделке.
        assertThat(accepted.deal().getTotalAmount())
                .as("доставка не попала в сумму сделки — оплата не сойдётся с переводом")
                .isEqualByComparingTo("4800");
        assertThat(accepted.deal().getServices()).hasSize(1);
    }

    @Test
    @DisplayName("Услуга не двигает склад")
    void serviceDoesNotTouchStock() {
        Long partId = partWithStock("Фара с упаковкой", 1);
        Long packing = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM service WHERE name = 'Упаковка'", Long.class));

        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-500-12", null, customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)),
                List.of(new SalesService.ServiceRequest(packing, BigDecimal.ONE, null))));

        // Услуга живёт отдельной таблицей ровно затем, чтобы движение склада
        // по ней было невозможно, а не запрещено на словах: у выдачи нет
        // способа списать деталь, которой нет.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM deal_item WHERE deal_id = ?", Long.class,
                accepted.deal().getId())))
                .isEqualTo(1);
        assertThat(reserved(partId)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Без указанного срока ответа заказ получает трое рабочих суток")
    void replyDeadlineDefaultsToThreeWorkingDays() {
        Long partId = partWithStock("Радиатор без срока", 1);

        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-600-01", null, customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)),
                List.of()));

        // Пока заказ заводят руками, срок никто не вводит. Без него очередь
        // «ждут ответа» сортировать нечем, и она перестаёт отвечать на свой
        // единственный вопрос — что горит.
        assertThat(accepted.deal().getReplyDeadline())
                .as("срок ответа пуст: очередь не отличит горящий заказ от вчерашнего")
                .isNotNull();

        // Трое рабочих суток — это минимум трое календарных, а с выходными
        // и больше. Верхняя граница держит от ошибки в другую сторону.
        long hours = java.time.Duration.between(
                Instant.now(), accepted.deal().getReplyDeadline()).toHours();
        assertThat(hours).isBetween(70L, 24L * 6);
    }

    @Test
    @DisplayName("Повторная отмена отклоняется, а не пишет в историю вторую")
    void cancellingTwiceIsRejected() {
        Long partId = partWithStock("Крышка отменяемая", 1);
        var accepted = inTenant(() -> sales.registerMarketplaceOrder(
                "DROM", "301-400-11", null, customerId, null, null, null, null,
                List.of(new SalesService.ItemRequest(partId, BigDecimal.ONE, null, warehouseId)),
                List.of()));

        inTenant(() -> sales.cancel(accepted.deal().getId(), null, "передумал"));

        // Молчаливое согласие тут хуже отказа: история документа получала
        // вторую отмену, то есть говорила о действии, которого не было.
        // Разбирают её через недели, когда вспомнить некому.
        assertThatThrownBy(() -> inTenant(() ->
                sales.cancel(accepted.deal().getId(), null, "ещё раз")))
                .hasMessageContaining("уже закрыта");

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM document_event
                 WHERE document_type = 'DEAL' AND document_id = ? AND event_type = 'CANCELLED'""",
                Long.class, accepted.deal().getId())))
                .as("в истории документа две отмены вместо одной")
                .isEqualTo(1);
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

    private BigDecimal reserved(Long partId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT COALESCE(sum(qty_reserved), 0) FROM part_stock WHERE part_id = ?""",
                BigDecimal.class, partId));
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
