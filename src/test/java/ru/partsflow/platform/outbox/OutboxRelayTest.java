package ru.partsflow.platform.outbox;

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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Релей outbox.
 *
 * <p>Проверяется одно свойство, без которого весь событийный контур врёт:
 * отправленное событие помечается опубликованным. Не пометится — релей будет
 * слать его каждую секунду вечно, и потребители получат тысячи копий вместо
 * одной. Идемпотентность потребителя это переживёт, но нагрузка и журналы —
 * нет.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class OutboxRelayTest extends PostgresTestBase {

    private static final String TENANT = "t_000061";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InMemoryEventTransport transport;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void register() {
        // Релей идёт по реестру арендаторов, а не по существующим схемам:
        // незарегистрированную схему он не увидит вовсе. Настоящий провижининг
        // запись сюда сделает — его в проекте пока нет.
        jdbc.update("""
                        INSERT INTO public.tenant_registry
                            (tenant_id, schema_name, company_name, code)
                        VALUES (?, ?, 'Релей', ?)
                        ON CONFLICT (tenant_id) DO NOTHING""",
                61L, TENANT, "relay-test");
    }

    @BeforeEach
    void clean() {
        inTenant(() -> jdbc.update("DELETE FROM outbox"));
        transport.clear();
    }

    @Test
    @DisplayName("Отправленное событие помечается опубликованным")
    void publishedEventIsMarked() {
        inTenant(() -> publisher.publish(new DomainEvent(
                "deal", 1L, "deal.issued.v1", TENANT + ":deal:1",
                "{}".getBytes(StandardCharsets.UTF_8))));

        relay.relay();

        assertThat(unpublished())
                .as("событие осталось неопубликованным — релей будет слать его вечно")
                .isZero();
    }

    @Test
    @DisplayName("Второй заход не отправляет то же событие снова")
    void secondPassSendsNothing() {
        inTenant(() -> publisher.publish(new DomainEvent(
                "deal", 2L, "deal.issued.v1", TENANT + ":deal:2",
                "{}".getBytes(StandardCharsets.UTF_8))));

        relay.relay();
        assertThat(transport.delivered()).hasSize(1);

        relay.relay();

        assertThat(transport.delivered())
                .as("событие ушло в транспорт повторно")
                .hasSize(1);
    }

    private long unpublished() {
        return inTenantGet(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class));
    }

    private void inTenant(Runnable body) {
        TenantContext.set(TENANT);
        try {
            transactionTemplate.executeWithoutResult(status -> body.run());
        } finally {
            TenantContext.clear();
        }
    }

    private <T> T inTenantGet(java.util.function.Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
