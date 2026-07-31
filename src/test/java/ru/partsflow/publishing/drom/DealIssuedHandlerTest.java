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
import ru.partsflow.platform.outbox.DomainEvent;
import ru.partsflow.platform.outbox.DomainEventPublisher;
import ru.partsflow.platform.outbox.OutboxRelay;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной путь события: продажа доезжает до Дрома.
 *
 * <p>Раньше он обрывался: {@code deal.issued.v1} доходил до транспорта
 * и заканчивался там — потребителей в проекте не было вовсе, а
 * {@code DromDeltaSender.onDealIssued} никто не вызывал. Проверяется вся цепочка
 * целиком: запись в outbox, релей, диспетчер, обработчик, запрос на площадку.
 *
 * <p>Живой Дром подменён: важно не что уехало, а что уехало вообще и ровно
 * один раз.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Import(DealIssuedHandlerTest.StubSyncConfig.class)
class DealIssuedHandlerTest extends PostgresTestBase {

    private static final String TENANT = "t_000063";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private StubSyncClient syncClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;
    private Long customer;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        syncClient.reset();

        jdbc.update("""
                        INSERT INTO public.tenant_registry
                            (tenant_id, schema_name, company_name, code)
                        VALUES (?, ?, 'Дром', ?)
                        ON CONFLICT (tenant_id) DO NOTHING""",
                63L, TENANT, "drom-handler-test");

        inTenant(() -> {
            jdbc.update("DELETE FROM outbox");
            jdbc.update("DELETE FROM processed_event");
            jdbc.update("DELETE FROM event_dead_letter");
            jdbc.update("DELETE FROM publication_log");
            jdbc.update("DELETE FROM marketplace_account");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            customer = jdbc.queryForObject(
                    "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
            account();
            return null;
        });
    }

    @Test
    @DisplayName("Выданная сделка доезжает до площадки через событие")
    void issuedDealReachesMarketplace() {
        long dealId = issuedDeal(part("Фара левая"));
        publishIssued(dealId);

        relay.relay();

        // Ради этого всё и делалось: между продажей и площадкой не осталось
        // разрыва, и вызывать отправителя руками из кода сделки не нужно.
        assertThat(syncClient.calls())
                .as("событие снова никуда не доехало")
                .hasSize(1);
    }

    @Test
    @DisplayName("Второй заход релея не отправляет дельту повторно")
    void deltaIsNotSentTwice() {
        long dealId = issuedDeal(part("Бампер"));
        publishIssued(dealId);

        relay.relay();
        relay.relay();

        assertThat(syncClient.calls()).hasSize(1);
    }

    @Test
    @DisplayName("Повторная доставка того же события площадку не беспокоит")
    void redeliveryIsIdempotent() {
        long dealId = issuedDeal(part("Крыло"));
        publishIssued(dealId);
        relay.relay();

        // Транспорт at-least-once: то же событие приходит снова после сбоя.
        inTenant(() -> jdbc.update("UPDATE outbox SET published_at = NULL"));
        relay.relay();

        assertThat(syncClient.calls())
                .as("повтор доставки дошёл до площадки вторым запросом")
                .hasSize(1);
    }

    @Test
    @DisplayName("Отказ площадки виден в разборе, а не теряется")
    void marketplaceFailureIsVisible() {
        syncClient.failWith(503, "сервис недоступен");
        long dealId = issuedDeal(part("Дверь"));
        publishIssued(dealId);

        relay.relay();

        // Отправитель исключений не бросает — сорванная выгрузка не повод
        // откатывать выдачу товара. Но потеряться она тоже не должна: иначе
        // площадка неделю показывает проданную деталь, и никто не знает.
        assertThat(deadLetters())
                .as("непрошедшая дельта пропала бесследно")
                .isOne();
    }

    @Test
    @DisplayName("После отказа событие можно доставить снова")
    void failedEventCanBeRedelivered() {
        syncClient.failWith(503, "сервис недоступен");
        long dealId = issuedDeal(part("Капот"));
        publishIssued(dealId);
        relay.relay();

        syncClient.reset();
        inTenant(() -> jdbc.update("UPDATE outbox SET published_at = NULL"));
        relay.relay();

        assertThat(syncClient.calls())
                .as("после отказа событие осталось помеченным обработанным")
                .hasSize(1);
    }

    private void publishIssued(long dealId) {
        inTenant(() -> {
            publisher.publish(DomainEvent.of("deal", dealId, "deal.issued.v1",
                    "{}".getBytes(StandardCharsets.UTF_8)));
            return null;
        });
    }

    private long deadLetters() {
        return inTenantGet(() -> jdbc.queryForObject(
                "SELECT count(*) FROM event_dead_letter WHERE resolved_at IS NULL", Long.class));
    }

    private void account() {
        jdbc.update("""
                INSERT INTO marketplace_account (marketplace, title, status, settings, credentials)
                VALUES ('DROM', 'Кабинет', 'ACTIVE', '{"packetId":"777"}'::jsonb, ?)""",
                "secret".getBytes(StandardCharsets.UTF_8));
    }

    private Long part(String title) {
        return inTenantGet(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, is_published)
                    VALUES (1, ?, 5000, 2000, true) RETURNING id""", Long.class, title);
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta, to_warehouse_id)
                    VALUES (?, 'INTAKE', 1, ?)""", partId, warehouse);
            return partId;
        });
    }

    private long issuedDeal(Long partId) {
        return inTenantGet(() -> {
            Long dealId = jdbc.queryForObject("""
                    INSERT INTO deal (customer_id, status, total_amount, issued_at, closed_at)
                    VALUES (?, 'ISSUED', 5000, now(), now()) RETURNING id""",
                    Long.class, customer);
            jdbc.update("""
                    INSERT INTO deal_item (deal_id, part_id, quantity, price, warehouse_id, status)
                    VALUES (?, ?, 1, 5000, ?, 'ISSUED')""", dealId, partId, warehouse);
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                from_warehouse_id, ref_type, ref_id)
                    VALUES (?, 'SALE', -1, ?, 'DEAL', ?)""", partId, warehouse, dealId);
            return dealId;
        });
    }

    private <T> T inTenant(Supplier<T> body) {
        return inTenantGet(body);
    }

    private <T> T inTenantGet(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    /** Заглушка вместо Mockito: на этой JDK ByteBuddy не инструментирует классы. */
    static class StubSyncClient extends DromSyncClient {

        private final List<String> calls = new ArrayList<>();
        private Result answer = new Result(true, 200, "");

        @Override
        public Result sync(String packetId, String auth, byte[] deltaXml, String fileName) {
            calls.add(fileName);
            return answer;
        }

        void failWith(int httpStatus, String body) {
            answer = new Result(false, httpStatus, body);
        }

        void reset() {
            calls.clear();
            answer = new Result(true, 200, "");
        }

        List<String> calls() {
            return List.copyOf(calls);
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
