package ru.partsflow.platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.partsflow.platform.tenant.TenantSchemaMigrator;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Готовность приложения: ответ «готово / не готово» машине, а не человеку,
 * вглядывающемуся в хвост лога.
 *
 * <p>Порядок выкладки владельца («…само приложение встало штатно, всё
 * в порядке и только тогда можно убирать старую сборку») был невыполним
 * третьим шагом: устанавливать «встало штатно» было нечем. Приложение
 * отвечает на порту раньше, чем пригодно к работе.
 *
 * <p><b>Главное здесь — несимметричность.</b> Схема впереди моей версии
 * означает «готов» (новая схема обслуживает старый код, миграции
 * расширяющие), схема позади — «не готов». Симметричная проверка «версии
 * совпадают» погасила бы старую сборку в ту минуту, когда она обязана
 * работать: порядок выкладки ставит накат схем до подъёма новой сборки.
 *
 * <p><b>Журналы здесь честно красные.</b> В тестах база одна и роль одна —
 * это законно, и проверка обязана про это сказать, а не промолчать. Зелёный
 * ответ с разделением ролей проверяет {@link AppReadinessRoleSplitTest},
 * у которого рабочая роль настоящая.
 *
 * <p>Свойства подобраны под существующий кэш контекстов: своим набором
 * тест поднял бы ещё один Spring и ещё один пул соединений. Наблюдаемость
 * включена намеренно — в тестах Spring Boot выключает отдачу метрик,
 * и без неё проверка отвечала бы не про бой.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
class AppReadinessTest extends PostgresTestBase {

    /**
     * Номер маленький намеренно: защиту журналов проверяют на первом
     * работающем арендаторе реестра (по номеру), а реестр общий у всех
     * контекстов прогона. С низким номером проверяется именно наша схема,
     * а не чужая, попавшая в реестр раньше.
     */
    private static final String TENANT = "t_000003";
    private static final long TENANT_ID = 3;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TenantSchemaMigrator migrator;

    private final ObjectMapper json = new ObjectMapper();

    /** Чужие отметки и статусы: реестр общий, и их надо вернуть как было. */
    private final Map<Long, String[]> saved = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        provisionTenants(TENANT);

        jdbc.query("""
                SELECT tenant_id, schema_version, status FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""",
                (RowCallbackHandler) rs -> saved.put(rs.getLong("tenant_id"),
                        new String[] {rs.getString("schema_version"), rs.getString("status")}));

        // Арендатор чужого теста без отметки версии — отставший, и тогда
        // за нашу проверку отвечал бы он. Возвращается в tearDown.
        jdbc.update("""
                UPDATE public.tenant_registry SET schema_version = ?
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""", migrator.expectedVersion());
        register(migrator.expectedVersion());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", TENANT_ID);
        saved.forEach((tenantId, versionAndStatus) -> jdbc.update("""
                UPDATE public.tenant_registry SET schema_version = ?, status = ?
                 WHERE tenant_id = ?""",
                versionAndStatus[0], versionAndStatus[1], tenantId));
    }

    @Test
    @DisplayName("Схема позади поставляемой версии — не готов, и сказано кто")
    void schemaBehindIsNotReady() throws Exception {
        register("старьё");

        JsonNode body = readiness(503);

        assertThat(body.get("ready").asBoolean())
                .as("приложение объявило себя готовым при схеме, которой не хватает "
                        + "миграций: выкладка погасит старую сборку, а площадка "
                        + "заберёт пустой прайс — то есть снимет объявления")
                .isFalse();
        assertThat(detail(body, "schemas"))
                .as("не сказано, какая схема отстала и что делать: разбираться "
                        + "по такому ответу можно только запросами в базу")
                .contains(TENANT)
                .contains("ops/migrate-tenants.sh");
    }

    @Test
    @DisplayName("Схема впереди поставляемой версии — готов, и это названо вслух")
    void schemaAheadStaysReady() throws Exception {
        // Ровно то состояние, в котором старая сборка обслуживает людей
        // между накатом схем и подъёмом новой: идентификатор последнего
        // changeset'а ей незнаком, а число их больше.
        register(ahead());

        // 503 здесь из-за одной роли, а не из-за схемы: в этом контексте
        // журналы честно красные. Зелёный ответ целиком — в
        // AppReadinessRoleSplitTest, там же и схема впереди.
        JsonNode body = readiness(503);

        assertThat(ok(body, "schemas"))
                .as("схема впереди сочтена отставанием — при таком ответе выкладка "
                        + "гасит работающую старую сборку сразу после наката схем, "
                        + "то есть ломает ровно тот порядок, который выполняет")
                .isTrue();
        assertThat(detail(body, "schemas"))
                .as("«впереди» проглочено молча: такой ответ не отличить "
                        + "от «версии совпали», а это разные состояния выкладки")
                .contains("впереди 1");
    }

    @Test
    @DisplayName("Одна роль на всё — не готов: журналы правятся прямым SQL")
    void oneRoleIsNotReady() throws Exception {
        JsonNode body = readiness(503);

        assertThat(ok(body, "journals"))
                .as("готовность не заметила, что приложение работает владельцем схем: "
                        + "журналы снова изменяемы, и узнают об этом в день, когда их "
                        + "предъявляют как доказательство")
                .isFalse();
        assertThat(detail(body, "journals"))
                .as("не названы ни роль, ни журналы, ни способ включить разделение")
                .contains("stock_movement")
                .contains("audit_log")
                .contains("customer_account_entry")
                .contains("ops/create-roles.sh");
        assertThat(body.get("ready").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Остальные проверки при исправной настройке зелёные")
    void otherChecksAreGreen() throws Exception {
        JsonNode body = readiness(503);

        assertThat(ok(body, "application"))
                .as("старт считается незаконченным у поднятого приложения: "
                        + "готовность не наступит никогда")
                .isTrue();
        assertThat(ok(body, "database"))
                .as("база объявлена недоступной при работающей базе")
                .isTrue();
        assertThat(ok(body, "schemas"))
                .as("схемы объявлены отставшими при отметке ровно той версии, "
                        + "которую несёт сборка")
                .isTrue();
        assertThat(ok(body, "metrics"))
                .as("метрики объявлены неотдаваемыми при отображённом "
                        + "/actuator/prometheus: готовность красная там, где всё в порядке, "
                        + "а такую проверку выключат в первый же день")
                .isTrue();
    }

    @Test
    @DisplayName("Ячейка без работающих арендаторов готова — и ответ зелёный целиком")
    void cellWithoutTenantsIsReady() throws Exception {
        // Такую ячейку и поднимают перед подключением первого клиента:
        // проверять защиту журналов не на чём, и красить выкладку в красное
        // не за что. Здесь же — единственное место, где в прогоне сходятся
        // все пять проверок разом: «готов» обязан быть достижимым, иначе
        // выкладка не дождётся его никогда.
        jdbc.update("""
                UPDATE public.tenant_registry SET status = 'SUSPENDED'
                 WHERE status = 'ACTIVE'""");

        JsonNode body = readiness(200);

        assertThat(body.get("ready").asBoolean())
                .as("зелёного ответа не существует: при всех исправных проверках "
                        + "готовность всё равно «нет» — %s", body)
                .isTrue();
        assertThat(detail(body, "journals"))
                .as("сказано не то, почему журналы сочтены в порядке: на пустой "
                        + "ячейке проверять их не на чем, и это надо назвать")
                .contains("Арендаторов");
    }

    @Test
    @DisplayName("Готовность отвечает без учётной записи — её спрашивает docker")
    void answersWithoutSession() throws Exception {
        // У docker'а и у шага выкладки учётной записи нет и быть не может.
        // Закрыт адрес иначе: порт приложения не публикуется, а терминатор
        // отдаёт на /actuator 404.
        readiness(503);
    }

    /** Версия на один changeset вперёд: число больше, идентификатор незнаком. */
    private String ahead() {
        return (TenantSchemaMigrator.changeSetsIn(migrator.expectedVersion()) + 1)
                + "/tenant-из-будущей-сборки";
    }

    private void register(String version) {
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status, schema_version)
                VALUES (?, ?, 'Готовность', 'readiness', 'ACTIVE', ?)
                ON CONFLICT (tenant_id) DO UPDATE
                    SET status = 'ACTIVE', schema_version = excluded.schema_version""",
                TENANT_ID, TENANT, version);
    }

    /**
     * Тело читается как UTF-8 явно: у типа ответа actuator'а нет параметра
     * charset, и MockMvc разбирает байты как ISO-8859-1 — русские причины
     * превращаются в мусор, а с ними и сообщение упавшей проверки. JSON
     * по своему стандарту UTF-8, и настоящий клиент читает его верно.
     */
    private JsonNode readiness(int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get("/actuator/readiness")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("код ответа готовности: по нему шаг выкладки и ждёт, "
                        + "а тело разбирает уже человек. Тело: %s", body)
                .isEqualTo(expectedStatus);
        return json.readTree(body);
    }

    private boolean ok(JsonNode body, String check) {
        return check(body, check).get("ok").asBoolean();
    }

    private String detail(JsonNode body, String check) {
        return check(body, check).get("detail").asText();
    }

    private JsonNode check(JsonNode body, String name) {
        for (JsonNode check : body.get("checks")) {
            if (name.equals(check.get("check").asText())) {
                return check;
            }
        }
        throw new AssertionError("Проверки «" + name + "» нет в ответе: " + body);
    }
}
