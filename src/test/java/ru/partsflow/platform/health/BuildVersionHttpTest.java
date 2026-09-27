package ru.partsflow.platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Версия доезжает до спрашивающего по HTTP — и не становится проверкой
 * (задача 0130).
 *
 * <p>Через HTTP, а не вызовом бина: ответ наружу — record, и обычный класс
 * Jackson не сериализует вовсе («No acceptable representation»). Тест,
 * зовущий метод напрямую, этого не видит — правило корневого
 * {@code CLAUDE.md}.
 *
 * <p><b>Свойства подобраны под кэш контекстов {@link AppReadinessTest}</b>
 * (тот же набор, та же наблюдаемость): своим набором тест поднял бы ещё один
 * Spring и ещё один пул соединений. Схему он не занимает — арендатор ему
 * не нужен, он спрашивает сборку о себе.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
class BuildVersionHttpTest extends PostgresTestBase {

    /** Проверки ответа готовности — ровно те, что красят выкладку. */
    private static final List<String> GATING =
            List.of("database", "catalog", "schemas", "journals", "writes", "metrics");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InfoEndpoint info;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("Готовность отдаёт версию: признак «названа», SHA и время сборки")
    void readinessCarriesTheVersion() throws Exception {
        JsonNode version = readiness().get("version");

        assertThat(version)
                .as("в ответе готовности нет блока version: спросить у работающего "
                        + "приложения, какая это сборка, снова нечем — остаётся дата "
                        + "образа в docker, то есть ответ про файл на диске")
                .isNotNull();
        // Признак машинный: по нему шаг выкладки отличает «образ не назвал
        // себя» от «подняли не тот образ». Здесь он false — прогон идёт
        // на местной сборке, и это её честный ответ.
        assertThat(version.get("named"))
                .as("в блоке version нет признака «названа»: шаг выкладки не сможет "
                        + "отличить дефект сборки от несовпадения версий")
                .isNotNull();
        assertThat(version.get("sha")).isNotNull();
        assertThat(version.get("detail").asText())
                .as("версия не объяснена словами: человек, пришедший в разбор "
                        + "аварии, читает поле, а не догадывается о нём")
                .isNotBlank();
    }

    @Test
    @DisplayName("Версия — не проверка: список красящих выкладку не изменился")
    void versionIsNotACheck() throws Exception {
        JsonNode body = readiness();

        List<String> names = new ArrayList<>();
        for (JsonNode check : body.get("checks")) {
            names.add(check.get("check").asText());
        }

        assertThat(names)
                .as("версия попала в список проверок: сборка, не назвавшая себя, "
                        + "стала бы «не готова» — а ops/switch-build.sh ждёт "
                        + "«\"ready\":true» и не перевёл бы трафик, и healthcheck "
                        + "боевого compose объявил бы контейнер нездоровым. "
                        + "Красное на исправной сборке выключают первым")
                .containsExactlyElementsOf(GATING);
    }

    @Test
    @DisplayName("На /actuator/info версия не попадает — там её видел бы каждый вошедший")
    void versionDoesNotLeakToInfo() {
        // Сведения о сборке лежат внутри артефакта, и Spring по умолчанию
        // отдал бы их на /actuator/info — то есть номер сборки узнал бы
        // каждый вошедший, включая продавца и «Просмотр». Задача велит
        // отдавать версию рядом с готовностью и новых поверхностей
        // не заводить, поэтому contributor выключен в application.yml.
        assertThat(info.info())
                .as("сведения о сборке уехали на /actuator/info: версию видит "
                        + "любой вошедший, а это подсказка тому, кто ищет чужие дыры. "
                        + "Чинится management.info.build.enabled=false")
                .doesNotContainKey("build");
    }

    /**
     * Тело читается как UTF-8 явно: у типа ответа actuator'а нет параметра
     * charset, и MockMvc разбирает байты как ISO-8859-1 — русские слова
     * превращаются в мусор (записано в корневом {@code CLAUDE.md}).
     */
    private JsonNode readiness() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/readiness")).andReturn();
        return json.readTree(new String(result.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8));
    }
}
