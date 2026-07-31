package ru.partsflow.platform.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Метрики событийного контура.
 *
 * <p>До них о застрявшей очереди узнавали от клиента: «объявление висит,
 * а деталь продана». Проверяется не сам факт наличия чисел, а то, что они
 * отвечают на вопрос «плохо ли»: глубина очереди без возраста не говорит
 * ничего — сто событий, появившихся секунду назад, это обычная работа.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class EventQueueMetricsTest extends PostgresTestBase {

    private static final String TENANT = "t_000084";

    @Autowired
    private EventQueueMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 84");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (84, ?, 'Разборка', 'metrico')""", TENANT);
        jdbc.execute("DELETE FROM " + TENANT + ".outbox");
        jdbc.execute("DELETE FROM " + TENANT + ".event_dead_letter");
    }

    @Test
    @DisplayName("Глубина очереди и возраст самого старого события")
    void queueDepthAndAge() {
        // Метрика считается по всей ячейке, а в реестре живут арендаторы
        // соседних тестов со своими очередями. Поэтому сравнивается прирост,
        // а не абсолют: иначе тест проверял бы порядок запуска классов.
        metrics.refresh();
        double before = gauge("partsflow.outbox.pending");

        jdbc.update("""
                INSERT INTO %s.outbox (aggregate_type, aggregate_id, event_type,
                                       partition_key, payload, created_at)
                VALUES ('deal', 1, 'deal.issued.v1', 'deal-1', '{}'::bytea,
                        now() - interval '20 minutes')""".formatted(TENANT));

        metrics.refresh();

        assertThat(gauge("partsflow.outbox.pending") - before).isEqualTo(1);
        // Само число «в очереди сто событий» не говорит ничего: сто событий,
        // появившихся секунду назад, — обычная работа, а возрастом в час —
        // остановившийся релей.
        assertThat(gauge("partsflow.outbox.oldest.seconds"))
                .as("возраст не считается — застрявшую очередь не отличить от занятой")
                .isGreaterThan(1000);
    }

    @Test
    @DisplayName("Ждущее человека считается отдельно от того, что повторяет робот")
    void attentionIsCountedApart() {
        metrics.refresh();
        double unresolvedBefore = gauge("partsflow.deadletter.unresolved");
        double attentionBefore = gauge("partsflow.deadletter.attention");

        jdbc.update("""
                INSERT INTO %s.event_dead_letter
                    (handler, event_id, event_type, aggregate_id, error, attempts)
                VALUES ('drom-deal-delta', 1, 'deal.issued.v1', 1, 'площадка молчит', 1),
                       ('drom-deal-delta', 2, 'deal.issued.v1', 2, 'ключ не принят', %d)"""
                .formatted(TENANT, DeadLetterService.AUTO_ATTEMPTS));

        metrics.refresh();

        assertThat(gauge("partsflow.deadletter.unresolved") - unresolvedBefore).isEqualTo(2);
        // Пока робот повторяет, делать нечего. Запись, которую он бросил,
        // ждёт человека — и будить по ней должно отдельно.
        assertThat(gauge("partsflow.deadletter.attention") - attentionBefore)
                .as("повторяемое роботом посчитано наравне с брошенным — "
                        + "тревога начнёт срабатывать на обычной работе")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Метрика существует и без единого события")
    void gaugesExistWhenQueueIsEmpty() {
        metrics.refresh();

        // Отсутствующая метрика в системе наблюдения неотличима от упавшего
        // приложения, и тревога по ней не настраивается.
        assertThat(gauge("partsflow.outbox.pending")).isNotNaN().isGreaterThanOrEqualTo(0);
        assertThat(gauge("partsflow.outbox.oldest.seconds")).isNotNaN()
                .isGreaterThanOrEqualTo(0);
        assertThat(gauge("partsflow.deadletter.unresolved")).isNotNaN()
                .isGreaterThanOrEqualTo(0);
        assertThat(gauge("partsflow.deadletter.attention")).isNotNaN()
                .isGreaterThanOrEqualTo(0);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
