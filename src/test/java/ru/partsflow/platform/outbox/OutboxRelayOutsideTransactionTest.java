package ru.partsflow.platform.outbox;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Отправка в транспорт идёт вне транзакции БД.
 *
 * <p>Свойство не косметическое. Пока {@code transport.send} стоял внутри
 * транзакции релея, ответа брокера ждало соединение из пула, а обход
 * арендаторов однопоточный: лежащая Kafka с её двухминутным
 * {@code delivery.timeout.ms} останавливала событийный контур всей ячейки,
 * и двухсотый клиент реестра ждал первого.
 *
 * <p>Проверяется настоящим транспортом-заглушкой, а не чтением кода:
 * он спрашивает {@code TransactionSynchronizationManager} в момент отправки.
 * Обычным транспортом это не поймать — в памяти всё равно проходит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@Import(OutboxRelayOutsideTransactionTest.ProbeConfig.class)
class OutboxRelayOutsideTransactionTest extends PostgresTestBase {

    private static final String TENANT = "t_000090";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private DomainEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProbeTransport transport;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void register() {
        jdbc.update("""
                        INSERT INTO public.tenant_registry
                            (tenant_id, schema_name, company_name, code)
                        VALUES (?, ?, 'Релей вне транзакции', ?)
                        ON CONFLICT (tenant_id) DO NOTHING""",
                90L, TENANT, "relay-outside-test");
        inTenant(() -> jdbc.update("DELETE FROM outbox"));
        transport.reset();
    }

    @Test
    @DisplayName("В момент отправки транзакции БД нет")
    void sendHappensOutsideTransaction() {
        publish(1L);

        relay.relay();

        assertThat(transport.sendsFor(TENANT)).hasSize(1);
        assertThat(transport.sends)
                .as("отправка идёт внутри транзакции — лежащий брокер займёт соединение "
                        + "и остановит релей всей ячейки")
                .noneMatch(ProbeTransport.Send::transactionActive);
        assertThat(unpublished()).isZero();
    }

    @Test
    @DisplayName("Сорванная отправка не теряет событие и не оставляет его заявленным")
    void failedSendReleasesClaim() {
        publish(2L);
        transport.failFor = TENANT;

        relay.relay();

        assertThat(unpublished()).as("событие пропало при отказе транспорта").isEqualTo(1);
        assertThat(claimed())
                .as("заявка не снята — событие пролежит до её истечения, "
                        + "хотя следующий заход мог бы отправить его через секунду")
                .isZero();

        relay.relay();

        assertThat(unpublished()).as("повторный заход не забрал событие").isZero();
        assertThat(transport.sendsFor(TENANT)).hasSize(2);
    }

    private void publish(long aggregateId) {
        inTenant(() -> publisher.publish(new DomainEvent(
                "deal", aggregateId, "deal.issued.v1", TENANT + ":deal:" + aggregateId,
                "{}".getBytes(StandardCharsets.UTF_8))));
    }

    private long unpublished() {
        return count("SELECT count(*) FROM outbox WHERE published_at IS NULL");
    }

    private long claimed() {
        return count("SELECT count(*) FROM outbox WHERE claimed_at IS NOT NULL"
                + " AND published_at IS NULL");
    }

    private long count(String sql) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> jdbc.queryForObject(sql, Long.class));
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(Runnable body) {
        TenantContext.set(TENANT);
        try {
            transactionTemplate.executeWithoutResult(status -> body.run());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Заглушка транспорта: Mockito на этой JDK не работает, границы подменяются
     * руками.
     *
     * <p>Отправки помнятся по арендаторам, и отказ адресный. База у тестовых
     * контекстов общая, релей идёт по всему реестру — в чужих схемах лежат
     * неотправленные события соседних тестов, и «уронить следующую отправку»
     * уронило бы чужую.
     */
    static class ProbeTransport implements EventTransport {

        record Send(String tenant, int size, boolean transactionActive) {
        }

        final List<Send> sends = new ArrayList<>();
        volatile String failFor;

        @Override
        public void send(List<OutboxRecord> batch) {
            String tenant = TenantContext.require();
            sends.add(new Send(tenant, batch.size(),
                    TransactionSynchronizationManager.isActualTransactionActive()));
            if (tenant.equals(failFor)) {
                failFor = null;
                throw new IllegalStateException("брокер недоступен");
            }
        }

        List<Send> sendsFor(String tenant) {
            return sends.stream().filter(s -> s.tenant().equals(tenant)).toList();
        }

        void reset() {
            sends.clear();
            failFor = null;
        }
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        @Primary
        ProbeTransport probeTransport() {
            return new ProbeTransport();
        }
    }
}
