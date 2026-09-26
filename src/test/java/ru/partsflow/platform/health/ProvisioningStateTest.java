package ru.partsflow.platform.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.platform.tenant.ProvisioningController;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Признак «управляющий контур включён» — то, чего не было нигде.
 *
 * <p>На боевой ячейке секрет провижининга простоял включённым трое суток,
 * и узнать об этом было нечем: готовность про контур не спрашивает, сторож
 * настроек смотрит монтирования, тревог про провижининг нет ни одной
 * (задача 0197).
 *
 * <p><b>Свойства подобраны под кэш контекстов {@code TenantProvisioningTest}</b>
 * (тот же секрет, тот же {@code @AutoConfigureMockMvc}): своим набором тест
 * поднял бы ещё один Spring и ещё один пул соединений. Заодно это ровно тот
 * контекст, который нужен, — контур в нём <b>включён</b>, то есть проверяется
 * опасное состояние, а не безопасное.
 *
 * <p>Схему этот тест не занимает вовсе — арендатор ему не нужен, он спрашивает
 * состояние ячейки.
 */
@SpringBootTest(properties = "app.provisioning-token=секрет-провижининга")
@AutoConfigureMockMvc
class ProvisioningStateTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProvisioningStateEndpoint endpoint;

    /**
     * Тело ответа в UTF-8.
     *
     * <p>{@code getContentAsString()} кириллицу портит — читает не в UTF-8, —
     * и проверка на русское слово падала бы при исправном ответе. Записано
     * в корневом {@code CLAUDE.md}, здесь это важно: весь разбор ответа
     * состоит из русских слов.
     */
    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Включённый контур виден по HTTP: «да», с какого момента и чем отзывается")
    void enabledCircuitIsVisibleOverHttp() throws Exception {
        // По HTTP, а не вызовом бина: ответ наружу — record, и обычный класс
        // Jackson не сериализует вовсе («No acceptable representation»).
        // Тест, зовущий метод напрямую, этого не видит.
        MvcResult result = mvc.perform(get("/actuator/provisioning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();

        String body = bodyOf(result);

        // «Включён» без «с какого момента» не отличает минуту подключения
        // клиента от третьих суток — ровно того случая, который наблюдали.
        assertThat(body).contains("\"since\"").contains("\"forSeconds\"");
        assertThat(endpoint.state().since()).isNotBlank();
        assertThat(endpoint.state().forSeconds()).isGreaterThanOrEqualTo(0);

        // Состояние названо словами, и названо то, чем секрет отзывается:
        // человек на ячейке не догадался, что restart окружение не читает,
        // и догадываться не должен.
        assertThat(body).contains("ВКЛЮЧЁН");
        assertThat(body).contains("up -d");
        assertThat(body).contains("restart");
    }

    @Test
    @DisplayName("Секрет не печатается ни в каком виде — ни целиком, ни частями")
    void secretNeverLeaves() throws Exception {
        String secret = "секрет-провижининга";
        String body = bodyOf(mvc.perform(get("/actuator/provisioning")).andReturn());

        assertThat(body).doesNotContain(secret);
        // И частями тоже: признак — это «да» или «нет», а не подсказка к подбору.
        //
        // Проверяется ХВОСТ значения, а не начало, и это не придирка к букве:
        // первые шесть символов здешнего секрета — слово «секрет», которое
        // объяснение законно употребляет («и секрет остаётся живым»). Проверка
        // на начало краснела бы на полностью исправном ответе, а тест,
        // краснеющий на исправном коде, выключают первым — вместе с защитой.
        // Поймано прогоном: именно так эта строка и упала.
        assertThat(body).doesNotContain(secret.substring(secret.length() - 10));
    }

    @Test
    @DisplayName("Выключенный контур отвечает тем же написанием, что и сам отказ контура")
    void disabledCircuitSaysWhatTheCircuitSays() {
        // Прямой сборкой, а не вторым контекстом Spring: пустой секрет — это
        // другой набор свойств, то есть ещё один поднятый Spring и ещё один
        // пул соединений (записано в корневом CLAUDE.md). Проверяется здесь
        // именно написание, а сериализацию по HTTP доказывает случай выше.
        ProvisioningStateEndpoint off = new ProvisioningStateEndpoint(
                new ProvisioningController(null, null, null, ""));

        ProvisioningStateEndpoint.State state = off.state();

        assertThat(state.enabled()).isFalse();
        // Дословно то же, что отвечает контур на запрос: два написания одного
        // смысла разъехались бы молча, и оператор читал бы «выключен» там,
        // где отказ говорит другое.
        assertThat(state.detail()).startsWith(ProvisioningController.DISABLED);
    }

    @Test
    @DisplayName("Пустой и незаданный секрет — одно и то же выключено")
    void blankAndMissingAreBothOff() {
        // Пустая строка приезжает из `APP_PROVISIONING_TOKEN=` в .env,
        // null — из незаданной переменной вовсе. Пробелы — из строки,
        // которую правили руками.
        for (String token : new String[]{null, "", "   "}) {
            ProvisioningStateEndpoint off = new ProvisioningStateEndpoint(
                    new ProvisioningController(null, null, null, token));
            assertThat(off.state().enabled())
                    .as("секрет %s обязан означать «выключено»", token == null ? "null" : "«" + token + "»")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Длительность читается словами, а не числом секунд")
    void durationIsHumanReadable() {
        // «259200» и «3 дня» отвечают на вопрос по-разному, а признак заводился
        // затем, чтобы человек заметил разницу между минутой и третьими сутками.
        assertThat(ProvisioningStateEndpoint.human(30)).isEqualTo("меньше минуты");
        assertThat(ProvisioningStateEndpoint.human(60)).isEqualTo("1 минута");
        assertThat(ProvisioningStateEndpoint.human(120)).isEqualTo("2 минуты");
        assertThat(ProvisioningStateEndpoint.human(60 * 11)).isEqualTo("11 минут");
        assertThat(ProvisioningStateEndpoint.human(3600)).isEqualTo("1 час");
        assertThat(ProvisioningStateEndpoint.human(3600 * 5)).isEqualTo("5 часов");
        // Ровно тот случай, который наблюдали на ячейке.
        assertThat(ProvisioningStateEndpoint.human(3600 * 24 * 3)).isEqualTo("3 дня");
        assertThat(ProvisioningStateEndpoint.human(3600 * 24 * 21)).isEqualTo("21 день");
    }
}
