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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Раздача событий потребителям.
 *
 * <p>Главное здесь — идемпотентность. Транспорт даёт at-least-once, то есть
 * повторы гарантированы, а не возможны: перезапуск потребителя, ребалансировка
 * группы, повтор релея. Обработчик, отработавший дважды, — это второе списание
 * остатка или вторая премия менеджеру.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "app.dispatcher-test=true"
})
@Import({EventDispatcherTest.Handlers.class})
class EventDispatcherTest extends PostgresTestBase {

    private static final String TENANT = "t_000062";

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private CountingHandler counting;

    @Autowired
    private FailingHandler failing;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void clean() {
        counting.seen.clear();
        failing.broken = true;
        inTenant(() -> {
            jdbc.update("DELETE FROM processed_event");
            jdbc.update("DELETE FROM event_dead_letter");
        });
    }

    @Test
    @DisplayName("Повторная доставка не обрабатывается второй раз")
    void duplicateDeliveryIsIgnored() {
        ConsumedEvent event = event(1, "test.counted.v1");

        inTenantContext(() -> dispatcher.dispatch(event));
        inTenantContext(() -> dispatcher.dispatch(event));

        // Транспорт at-least-once доставит одно и то же при любом сбое между
        // обработкой и подтверждением смещения.
        assertThat(counting.seen).containsExactly(1L);
    }

    @Test
    @DisplayName("Разные события обрабатываются оба")
    void distinctEventsAreProcessed() {
        inTenantContext(() -> dispatcher.dispatch(event(1, "test.counted.v1")));
        inTenantContext(() -> dispatcher.dispatch(event(2, "test.counted.v1")));

        assertThat(counting.seen).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Отметка об обработке ведётся по обработчику, а не по событию")
    void marksArePerHandler() {
        // На один тип подписаны двое: то, что дельту отправил один, не значит,
        // что второй тоже отработал.
        failing.broken = false;
        inTenantContext(() -> dispatcher.dispatch(event(7, "test.shared.v1")));

        assertThat(counting.seen).containsExactly(7L);
        assertThat(handlersOf(7L)).containsExactlyInAnyOrder("counting", "failing");
    }

    @Test
    @DisplayName("Отказ одного обработчика не мешает другому")
    void failureIsIsolated() {
        inTenantContext(() -> dispatcher.dispatch(event(8, "test.shared.v1")));

        // Падающий обработчик подписан на тот же тип и падает всегда.
        assertThat(counting.seen)
                .as("отказ соседа съел успешную обработку")
                .containsExactly(8L);
    }

    @Test
    @DisplayName("Непринятое событие попадает в разбор и теряет отметку")
    void failedEventGoesToDeadLetter() {
        inTenantContext(() -> dispatcher.dispatch(event(9, "test.failing.v1")));

        assertThat(deadLetterCount("failing", 9L)).isOne();
        // Отметку снимаем, иначе повторная доставка уйдёт в «уже обработано»,
        // и второго шанса у события не будет вовсе.
        assertThat(handlersOf(9L)).doesNotContain("failing");
    }

    @Test
    @DisplayName("Починенный обработчик принимает событие с повторной доставки")
    void repairedHandlerAcceptsOnRetry() {
        inTenantContext(() -> dispatcher.dispatch(event(10, "test.failing.v1")));
        assertThat(deadLetterCount("failing", 10L)).isOne();

        failing.broken = false;
        inTenantContext(() -> dispatcher.dispatch(event(10, "test.failing.v1")));

        assertThat(handlersOf(10L)).contains("failing");
    }

    @Test
    @DisplayName("Повторный отказ считается попыткой, а не второй записью")
    void repeatedFailureCountsAttempts() {
        inTenantContext(() -> dispatcher.dispatch(event(11, "test.failing.v1")));
        inTenantContext(() -> dispatcher.dispatch(event(11, "test.failing.v1")));

        assertThat(deadLetterCount("failing", 11L))
                .as("каждый отказ завёл свою строку — разбор захлебнётся")
                .isOne();
        assertThat(attemptsOf("failing", 11L)).isEqualTo(2);
    }

    @Test
    @DisplayName("Событие без подписчиков проходит молча")
    void unhandledEventIsIgnored() {
        inTenantContext(() -> dispatcher.dispatch(event(12, "test.nobody-cares.v1")));

        assertThat(counting.seen).isEmpty();
        assertThat(inTenantGet(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_event", Long.class))).isZero();
    }

    private ConsumedEvent event(long id, String type) {
        return new ConsumedEvent(id, "test", id, type, "{}".getBytes(StandardCharsets.UTF_8));
    }

    private List<String> handlersOf(long eventId) {
        return inTenantGet(() -> jdbc.queryForList(
                "SELECT handler FROM processed_event WHERE event_id = ?", String.class, eventId));
    }

    private long deadLetterCount(String handler, long eventId) {
        return inTenantGet(() -> jdbc.queryForObject("""
                SELECT count(*) FROM event_dead_letter
                 WHERE handler = ? AND event_id = ?""", Long.class, handler, eventId));
    }

    private int attemptsOf(String handler, long eventId) {
        return inTenantGet(() -> jdbc.queryForObject("""
                SELECT attempts FROM event_dead_letter
                 WHERE handler = ? AND event_id = ?""", Integer.class, handler, eventId));
    }

    /** Диспетчер сам открывает транзакции, снаружи нужен только арендатор. */
    private void inTenantContext(Runnable body) {
        TenantContext.set(TENANT);
        try {
            body.run();
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

    private <T> T inTenantGet(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Обработчики для проверок.
     *
     * <p>Заглушки рукописные, а не Mockito: на этой JDK ByteBuddy не может
     * инструментировать классы, и мок падает при создании.
     */
    @TestConfiguration
    static class Handlers {

        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }

        @Bean
        FailingHandler failingHandler() {
            return new FailingHandler();
        }
    }

    static class CountingHandler implements EventHandler {

        final List<Long> seen = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Set<String> handles() {
            return Set.of("test.counted.v1", "test.shared.v1");
        }

        @Override
        public void handle(ConsumedEvent event) {
            seen.add(event.eventId());
        }
    }

    static class FailingHandler implements EventHandler {

        volatile boolean broken = true;

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public Set<String> handles() {
            return Set.of("test.failing.v1", "test.shared.v1");
        }

        @Override
        public void handle(ConsumedEvent event) {
            if (broken) {
                throw new IllegalStateException("площадка не ответила");
            }
        }
    }
}
