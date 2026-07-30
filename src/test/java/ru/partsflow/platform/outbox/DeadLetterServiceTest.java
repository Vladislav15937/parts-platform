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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор непринятых событий.
 *
 * <p>До этого запись ложилась в {@code event_dead_letter} и оставалась там
 * навсегда. На языке владельца это «сделка выдана, а объявление на площадке
 * висит доступным», и узнавал он об этом от клиента, приехавшего за проданным.
 *
 * <p>Обработчик здесь свой, управляемый: настоящий ходит в Дром, а проверять
 * надо не Дром, а то, что вокруг повтора.
 */
@SpringBootTest(properties = "app.dead-letter-test=true")
@Import(DeadLetterServiceTest.FlakyHandlerConfig.class)
class DeadLetterServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000076";

    @Autowired
    private DeadLetterService deadLetters;

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private FlakyHandler handler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        handler.reset();
        inTenant(() -> {
            jdbc.update("DELETE FROM event_dead_letter");
            jdbc.update("DELETE FROM processed_event");
            return null;
        });
    }

    @Test
    @DisplayName("Отказ обработчика ложится в разбор вместе с типом агрегата")
    void failureIsRecorded() {
        handler.failWith("площадка не отвечает");
        dispatchOnce(101);

        var letters = inTenant(() -> deadLetters.unresolved(10));
        assertThat(letters).singleElement().satisfies(letter -> {
            assertThat(letter.eventId()).isEqualTo(101);
            assertThat(letter.error()).isEqualTo("площадка не отвечает");
            // Без типа агрегата повтор отдал бы обработчику половину того,
            // что уехало в первый раз.
            assertThat(letter.aggregateType()).isEqualTo("deal");
            assertThat(letter.needsAttention()).isFalse();
        });
    }

    @Test
    @DisplayName("Повтор доставляет и закрывает запись")
    void retryDelivers() {
        handler.failWith("площадка не отвечает");
        dispatchOnce(102);
        handler.recover();

        var letter = inTenant(() -> deadLetters.unresolved(10).get(0));
        var failure = inTenant(() -> deadLetters.retry(letter.id()));

        assertThat(failure).isEmpty();
        assertThat(handler.delivered()).contains(102L);
        assertThat(inTenant(() -> deadLetters.unresolved(10))).isEmpty();
        assertThat(resolutionOf(letter.id())).isEqualTo("RETRIED");
    }

    @Test
    @DisplayName("После удачного повтора событие не обработается второй раз")
    void deliveredEventIsMarkedProcessed() {
        handler.failWith("площадка не отвечает");
        dispatchOnce(103);
        handler.recover();

        var letter = inTenant(() -> deadLetters.unresolved(10).get(0));
        inTenant(() -> deadLetters.retry(letter.id()));

        // Повторная доставка тем же транспортом обязана уйти в «уже обработано»:
        // иначе at-least-once даст вторую дельту по той же сделке.
        dispatchOnce(103);
        assertThat(handler.delivered()).containsExactly(103L);
    }

    @Test
    @DisplayName("Неудачный повтор растит счётчик и отодвигает следующую попытку")
    void failedRetryBacksOff() {
        handler.failWith("ключ кабинета не принят");
        dispatchOnce(104);

        var letter = inTenant(() -> deadLetters.unresolved(10).get(0));
        var failure = inTenant(() -> deadLetters.retry(letter.id()));

        assertThat(failure).contains("ключ кабинета не принят");
        var after = inTenant(() -> deadLetters.unresolved(10).get(0));
        assertThat(after.attempts())
                .as("счётчик не вырос — робот крутил бы эту запись до конца времён")
                .isEqualTo(2);
        assertThat(after.nextAttemptAt())
                .as("следующая попытка не отодвинута: площадку будут долбить каждую минуту")
                .isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("Робот отступается после пяти попыток и отдаёт запись человеку")
    void robotGivesUp() {
        handler.failWith("ключ кабинета не принят");
        dispatchOnce(105);

        for (int i = 0; i < 10; i++) {
            // Срок гасим, иначе выдержка не даст роботу и второй попытки.
            inTenant(() -> jdbc.update("UPDATE event_dead_letter SET next_attempt_at = now()"));
            inTenant(() -> deadLetters.retryDue());
        }

        var letter = inTenant(() -> deadLetters.unresolved(10).get(0));
        assertThat(letter.attempts())
                .as("робот не остановился: неверный ключ сам не починится, "
                        + "а повторы будут идти вечно")
                .isEqualTo(DeadLetterService.AUTO_ATTEMPTS);
        assertThat(letter.needsAttention()).isTrue();
    }

    @Test
    @DisplayName("Человек повторяет и после того, как робот сдался")
    void manualRetryIgnoresTheCap() {
        handler.failWith("ключ кабинета не принят");
        dispatchOnce(106);
        inTenant(() -> jdbc.update(
                "UPDATE event_dead_letter SET attempts = ?, next_attempt_at = now() + interval '4 hours'",
                DeadLetterService.AUTO_ATTEMPTS));

        // Человек нажимает кнопку, когда починил причину. Заставлять его ждать
        // четыре часа значит заставить лезть в базу — от чего экран и избавляет.
        handler.recover();
        var letter = inTenant(() -> deadLetters.unresolved(10).get(0));
        assertThat(inTenant(() -> deadLetters.retry(letter.id()))).isEmpty();
        assertThat(inTenant(() -> deadLetters.unresolved(10))).isEmpty();
    }

    @Test
    @DisplayName("Снятое с разбора отличимо от доставленного")
    void discardIsDistinguishable() {
        handler.failWith("площадка не отвечает");
        dispatchOnce(107);

        var letter = inTenant(() -> deadLetters.unresolved(10).get(0));
        inTenant(() -> {
            deadLetters.discard(letter.id(), null);
            return null;
        });

        assertThat(inTenant(() -> deadLetters.unresolved(10))).isEmpty();
        // Единственный след того, что событие решили не отправлять вовсе.
        assertThat(resolutionOf(letter.id())).isEqualTo("DISCARDED");
        assertThat(handler.delivered()).isEmpty();
    }

    @Test
    @DisplayName("Робот повторяет только то, чей срок подошёл")
    void robotWaitsForTheDueDate() {
        handler.failWith("площадка не отвечает");
        dispatchOnce(108);
        handler.recover();

        inTenant(() -> jdbc.update(
                "UPDATE event_dead_letter SET next_attempt_at = now() + interval '1 hour'"));

        assertThat(inTenant(() -> deadLetters.retryDue())).isZero();
        assertThat(inTenant(() -> deadLetters.unresolved(10))).hasSize(1);
    }

    private void dispatchOnce(long eventId) {
        inTenant(() -> {
            dispatcher.dispatch(new ConsumedEvent(eventId, "deal", 42, "deal.issued.v1",
                    "{}".getBytes(StandardCharsets.UTF_8)));
            return null;
        });
    }

    private String resolutionOf(long id) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT resolution FROM event_dead_letter WHERE id = ?", String.class, id));
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Обработчик, которым управляет тест.
     *
     * <p>Mockito на этой JDK не работает — ByteBuddy не может инструментировать
     * классы, — поэтому заглушка рукописная, как и у клиента Дрома.
     */
    static class FlakyHandler implements EventHandler {

        private final List<Long> delivered = new java.util.ArrayList<>();
        private volatile String failure;

        @Override
        public String name() {
            return "test-flaky";
        }

        @Override
        public Set<String> handles() {
            return Set.of("deal.issued.v1");
        }

        @Override
        public void handle(ConsumedEvent event) {
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            delivered.add(event.eventId());
        }

        void failWith(String message) {
            this.failure = message;
        }

        void recover() {
            this.failure = null;
        }

        List<Long> delivered() {
            return List.copyOf(delivered);
        }

        void reset() {
            failure = null;
            delivered.clear();
        }
    }

    @TestConfiguration
    static class FlakyHandlerConfig {

        @Bean
        FlakyHandler flakyHandler() {
            return new FlakyHandler();
        }
    }
}
