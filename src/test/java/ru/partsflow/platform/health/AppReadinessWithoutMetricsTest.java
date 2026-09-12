package ru.partsflow.platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Метрики не отдаются — значит не готов.
 *
 * <p>Реестра Prometheus однажды не оказалось в зависимостях вовсе, при том
 * что в {@code management.endpoints} он был перечислен: конфигурация выглядела
 * рабочей, а {@code /actuator/prometheus} отвечал 401, как любой
 * несуществующий адрес. Ячейка при этом работает, тревоги молчат — и молчат
 * они не потому, что всё хорошо, а потому, что собирать нечего.
 *
 * <p><b>Свойства и аннотации подобраны под уже существующий в прогоне
 * контекст</b> (тот же набор у {@code CustomerControllerTest} и соседей):
 * своим набором тест поднял бы ещё один Spring и ещё один пул соединений.
 * Отдачу метрик Spring Boot в тестах выключает по умолчанию — ровно то
 * состояние, которое здесь и проверяется, и получено оно настоящей
 * настройкой, а не подменой бина.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class AppReadinessWithoutMetricsTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("Без отдачи метрик готовности нет, и сказано почему")
    void metricsMissingMeansNotReady() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/readiness")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(result.getResponse().getStatus())
                .as("готовность при неотдаваемых метриках: тело %s", body)
                .isEqualTo(503);

        JsonNode metrics = check(json.readTree(body));
        assertThat(metrics.get("ok").asBoolean())
                .as("метрики сочтены отдаваемыми, когда отдавать нечем: ячейка "
                        + "останется без наблюдения, а выкладка покрасит себя "
                        + "зелёным — об остановившемся релее оператор узнает "
                        + "от клиента")
                .isFalse();
        assertThat(metrics.get("detail").asText())
                .as("не названо ни что проверяли, ни где это включается")
                .contains("/actuator/prometheus");
    }

    private JsonNode check(JsonNode body) {
        for (JsonNode check : body.get("checks")) {
            if ("metrics".equals(check.get("check").asText())) {
                return check;
            }
        }
        throw new AssertionError("Проверки метрик нет в ответе: " + body);
    }
}
