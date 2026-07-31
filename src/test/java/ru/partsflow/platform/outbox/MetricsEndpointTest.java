package ru.partsflow.platform.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.support.PostgresTestBase;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отдача метрик наружу.
 *
 * <p>Тесты самих значений берут {@code SimpleMeterRegistry} и до отдачи
 * не доходят — а живой прогон показал, что отдавать было нечем: реестра
 * Prometheus не было в зависимостях вовсе. В списке
 * {@code management.endpoints} он при этом перечислен, то есть конфигурация
 * выглядела рабочей, а {@code /actuator/prometheus} отвечал 401 — как любой
 * несуществующий адрес за аутентификацией.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "management.endpoints.web.exposure.include=health,prometheus"})
@AutoConfigureMockMvc
// В тестах Spring Boot выключает сбор метрик по умолчанию, и без этого
// адрес не отображён вовсе — то есть тест проверял бы не то, что в бою.
@AutoConfigureObservability(tracing = false)
class MetricsEndpointTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Метрики очереди отдаются сборщику без учётной записи")
    void queueMetricsAreExposed() throws Exception {
        // Без учётной записи намеренно: у сборщика метрик её нет и быть
        // не может. Наружу адрес при этом не смотрит — терминатор отдаёт
        // на /actuator 404, а порт приложения не опубликован.
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                // Именно эти четыре: на них вешается тревога, и молча
                // исчезнувшая метрика неотличима от исправной системы.
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("partsflow_outbox_pending"),
                        org.hamcrest.Matchers.containsString("partsflow_outbox_oldest_seconds"),
                        org.hamcrest.Matchers.containsString("partsflow_deadletter_unresolved"),
                        org.hamcrest.Matchers.containsString("partsflow_deadletter_attention"))));
    }
}
