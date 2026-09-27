package ru.partsflow.platform.tenant;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Вторая преграда перед провижинингом: частота и потолок числа схем
 * (задача 0183).
 *
 * <p>До этого преграда была одна — секрет, — и тот, кто его узнал, заводил
 * схемы, пока не кончится диск. Цена не в мусорных компаниях: кончившийся диск
 * ячейки останавливает Postgres, а с ним архив WAL и точку возврата
 * <b>всех</b> арендаторов.
 *
 * <p><b>Предел здесь свой, маленький ({@code max-per-hour=1}), и это не
 * подгонка.</b> Проверяется само правило «после предела отказ», а не боевое
 * число: чтобы упереться в двадцать, тест завёл бы двадцать схем, то есть
 * двадцать накатов Liquibase — минуты прогона на утверждение, которое
 * доказывается двумя.
 *
 * <p>Своя схема классу не нужна вовсе: схемы он заводит провижинингом, а тот
 * сам берёт следующий свободный номер.
 */
@SpringBootTest(properties = {
        "app.provisioning-token=секрет-предела",
        "app.provisioning-max-per-hour=1"})
@AutoConfigureMockMvc
class ProvisioningLimitsTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("После предела — 429 со словами, и схема не создаётся; у другого адреса свой счёт")
    void beyondTheLimitTheAnswerIs429AndNothingIsCreated() throws Exception {
        String first = "10.77.0.1";

        // Первая компания с этого адреса проходит: предел не должен задевать
        // путь, которым заводят каждого клиента.
        mvc.perform(create(uniqueCode()).with(from(first)))
                .andExpect(status().isCreated());

        long before = tenantCount();

        // Вторая с того же адреса — отказ. Именно 429: по нему видно, что это
        // предел, а не поломка и не «секрет не тот». 403 отправил бы оператора
        // искать опечатку в секрете, 500 — искать сломанный сервер.
        MvcResult refused = mvc.perform(create(uniqueCode()).with(from(first)))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        String body = bodyOf(refused);
        // Отказ обязан называть сам предел и то, чем он поднимается, — иначе
        // следующий шаг непонятен.
        assertThat(body).contains("предел");
        assertThat(body).contains("app.provisioning-max-per-hour");
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotBlank();

        // Главное: отбитый запрос не создал схему. Предел, срабатывающий
        // после CREATE SCHEMA и наката changeset'ов, защищал бы только реестр,
        // а диск занимался бы как раньше.
        assertThat(tenantCount())
                .as("отбитая по пределу попытка не должна заводить арендатора")
                .isEqualTo(before);

        // А у другого адреса счёт свой: предел назван «с одного адреса»,
        // и общий на всех он остановил бы заведение соседям.
        mvc.perform(create(uniqueCode()).with(from("10.77.0.2")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Потолок числа схем в ячейке — 409 со словами, а не молчаливое заведение")
    void cellFullIsRefusedWithWords() {
        // Прямой сборкой, а не вторым контекстом Spring: иной потолок — это
        // другой набор свойств, то есть ещё один поднятый Spring и ещё один
        // пул соединений (записано в корневом CLAUDE.md). Потолок ноль —
        // это «ячейка уже полна» при любом числе схем в базе.
        ProvisioningLimits full = new ProvisioningLimits(
                new JdbcTemplate(new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())),
                new SimpleMeterRegistry(), 100, 0);

        assertThatThrownBy(() -> full.beforeCreate("10.77.0.9"))
                .isInstanceOf(ProvisioningLimits.CellFull.class)
                // Потолок в 500 взят как проверенный пробой (план — 200–300):
                // дальше поднимают следующую ячейку. Молча завести
                // пятитысячную нельзя, и отказ говорит, что делать.
                .hasMessageContaining("Ячейка заполнена")
                .hasMessageContaining("app.provisioning-max-tenants");
    }

    @Test
    @DisplayName("Неверный секрет оставляет след: тревога «ломятся в контур» стала возможной")
    void wrongSecretLeavesATrace() throws Exception {
        // До задачи 0183 о неудачной попытке не оставалось ничего — ни записи,
        // ни метрики, — то есть тревога была невозможна по построению.
        double before = rejectedWithReason(ProvisioningLimits.REASON_SECRET);

        mvc.perform(post("/api/provisioning/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"не тот","companyCode":"%s","companyName":"К",
                                 "ownerLogin":"o","ownerPassword":"пароль-8симв"}"""
                                .formatted(uniqueCode()))
                        .with(from("10.77.0.3")))
                .andExpect(status().isForbidden());

        assertThat(rejectedWithReason(ProvisioningLimits.REASON_SECRET))
                .as("стук в управляющий контур обязан быть посчитан")
                .isEqualTo(before + 1);
    }

    /** Счётчик отбитых попыток с этой причиной; до первой — ноль. */
    private double rejectedWithReason(String reason) {
        var counter = metrics.find("partsflow.provisioning.rejected")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private long tenantCount() {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_registry", Long.class);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
            String code) {
        // CSRF здесь не нужен — авторизует секрет в теле, cookie в запросе нет.
        return post("/api/provisioning/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"секрет-предела","companyCode":"%s",
                         "companyName":"Предел","ownerLogin":"vladelec",
                         "ownerPassword":"пароль-8симв"}""".formatted(code));
    }

    /** Запрос как бы с этого адреса: предел считается по адресу. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    /**
     * Тело ответа в UTF-8.
     *
     * <p>{@code getContentAsString()} кириллицу портит — читает не в UTF-8, —
     * и проверка на русское слово падала бы при исправном ответе.
     */
    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Код свой у каждого вызова: реестр общий на весь прогон и не чистится. */
    private static String uniqueCode() {
        return "lim" + UUID.randomUUID().toString().substring(0, 8);
    }
}
