package ru.partsflow.platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.partsflow.platform.tenant.SchemaGrants;
import ru.partsflow.platform.tenant.SchemaOwnerDataSource;
import ru.partsflow.platform.tenant.TenantSchemaMigrator;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Готовность на правильно настроенной ячейке: «да» по HTTP — и чем это «да»
 * краснеет.
 *
 * <p><b>Зачем отдельный класс.</b> Остальные проверки готовности показывают
 * «нет»: в обычном тестовом контексте роль одна, и журналы там честно
 * красные. То есть проверялась ложная зелень и не проверялся <b>ложный
 * отказ</b> — готовность, которая не отвечает «да» никогда, остановит любую
 * выкладку, и обнаружится это в бою, когда новая сборка не переключится при
 * полностью исправной системе. Живого прогона и {@code JournalProtectionTest}
 * для этого мало: первый не повторяется, второй зовёт {@code JournalProtection}
 * напрямую и HTTP-слоя не видит — а класс, уже оплаченный в этом проекте,
 * живёт именно там (обычный класс в ответе контроллера не сериализуется вовсе,
 * и ответ уходит пятисоткой «No acceptable representation»). Задача 0088.
 *
 * <p><b>Ячейка здесь настоящая, а не подменённая.</b> Две роли Postgres, как
 * в бою: владелец схем делает DDL и читает историю наката, рабочая роль
 * обслуживает запросы и журналами не владеет — права ей выдаёт настоящий
 * {@link SchemaGrants}, а не копия его SQL. Подменены ровно два бина
 * источников данных, потому что иначе роль не подставить: имя и пароль ставит
 * {@code @DynamicPropertySource} базового класса, и он старше свойств
 * {@code @SpringBootTest}.
 *
 * <p><b>Обе стороны в одном классе — это требование задачи, а не порядок.</b>
 * Зелёный ответ, полученный один раз, через полгода превращается в тест,
 * который не краснеет ни от чего: рядом стоят три отката, и каждый ломает
 * свою составляющую — общую схему, версию схем арендатора и защиту журналов.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        // Два источника данных ниже заменяют объявленные в SchemaOwnerDataSource:
        // разделение ролей иначе не получить, а без него «да» недостижимо.
        "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
class AppReadinessHealthyCellTest extends PostgresTestBase {

    private static final String TENANT = "t_000202";
    private static final long TENANT_ID = 202;

    /** Рабочая роль ячейки: та же затея, что у {@code partsflow_app} в бою. */
    private static final String ROLE = "readiness_cell_runtime";
    private static final String PASSWORD = "readiness-cell-runtime";

    static {
        prepareCell();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantSchemaMigrator migrator;

    private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

    private final ObjectMapper json = new ObjectMapper();

    /** Чужие отметки и статусы: реестр общий у всего прогона, вернуть как было. */
    private final Map<Long, String[]> saved = new LinkedHashMap<>();

    /**
     * Ячейка, на которой выкладка обязана проходить: схемы на версии сборки,
     * арендатор один и он наш, журналы принадлежат владельцу.
     *
     * <p>Чужие записи реестра уводятся в {@code SUSPENDED}: защита журналов
     * проверяется на первом работающем арендаторе по номеру, а прав на чужие
     * схемы нашей роли никто не выдавал — ответ пришёл бы про них. Порядок
     * классов в прогоне не задан, поэтому «взять номер поменьше» тут
     * не работает.
     */
    @BeforeEach
    void setUp() {
        owner.query("""
                SELECT tenant_id, schema_version, status FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""",
                (RowCallbackHandler) rs -> saved.put(rs.getLong("tenant_id"),
                        new String[] {rs.getString("schema_version"), rs.getString("status")}));

        owner.update("""
                UPDATE public.tenant_registry SET schema_version = ?, status = 'SUSPENDED'
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""", migrator.expectedVersion());
        register(migrator.expectedVersion());
    }

    @AfterEach
    void tearDown() {
        owner.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", TENANT_ID);
        saved.forEach((tenantId, versionAndStatus) -> owner.update("""
                UPDATE public.tenant_registry SET schema_version = ?, status = ?
                 WHERE tenant_id = ?""",
                versionAndStatus[0], versionAndStatus[1], tenantId));
    }

    @Test
    @DisplayName("Исправная ячейка готова: 200 и все шесть проверок зелёные")
    void healthyCellIsReady() throws Exception {
        JsonNode body = readiness(200);

        assertThat(body.get("ready").asBoolean())
                .as("зелёного ответа не существует при полностью исправной ячейке: "
                        + "шаг выкладки не дождётся его никогда и погасит себя "
                        + "по таймауту при работающей системе — %s", body)
                .isTrue();

        assertThat(names(body))
                .as("состав проверок изменился молча: шаг выкладки и тревога "
                        + "узнают причину по имени проверки, а не по тексту")
                .containsExactly("database", "catalog", "schemas", "journals",
                        "writes", "metrics");

        assertThat(detail(body, "writes"))
                .as("про запись не сказано, что она проходит: исправная ячейка — "
                        + "единственное место в прогоне, где видно, что проба записи "
                        + "вообще работает, а не молчит одинаково при любом ответе базы")
                .contains("Запись проходит");

        assertThat(detail(body, "journals"))
                .as("журналы сочтены защищёнными не потому, что защищены: "
                        + "проверять надо на настоящей схеме и настоящей ролью")
                .contains(ROLE)
                .contains(TENANT);
        assertThat(detail(body, "catalog"))
                .as("про общую схему не сказано, что именно проверено")
                .contains("catalog");
    }

    @Test
    @DisplayName("Непринятый changeset общей схемы — не готов, хотя реестр на месте")
    void catalogBehindIsNotReady() throws Exception {
        // Ровно то окно, ради которого заведена задача 0086: приложение
        // перезапустили на базе, где реестр арендаторов уже есть, а накат
        // общей схемы ещё идёт. Tomcat при этом отвечает — ApplicationRunner
        // работает после него, — и до правки готовность говорила «да»
        // на несколько секунд раньше правды.
        String changeSet = withoutLastCatalogChangeSet();
        try {
            JsonNode body = readiness(503);

            assertThat(ok(body, "catalog"))
                    .as("общая схема объявлена принятой, когда changeset'а в истории "
                            + "нет: выкладка переключит трафик на сборку, у которой "
                            + "catalog ещё догоняется")
                    .isFalse();
            assertThat(body.get("ready").asBoolean())
                    .as("ответ «готов» при непринятой общей схеме — %s", body)
                    .isFalse();
            assertThat(detail(body, "catalog"))
                    .as("не сказано, чего именно не хватает: разбираться по такому "
                            + "ответу можно только запросами в базу")
                    .contains(changeSet);
        } finally {
            restoreCatalogChangeSet();
        }
    }

    @Test
    @DisplayName("Схема арендатора позади версии сборки — не готов")
    void schemaBehindIsNotReady() throws Exception {
        register("старьё");

        JsonNode body = readiness(503);

        assertThat(ok(body, "schemas"))
                .as("схема без части миграций сочтена годной: площадка заберёт "
                        + "пустой прайс, то есть снимет объявления")
                .isFalse();
        assertThat(body.get("ready").asBoolean()).isFalse();
        assertThat(detail(body, "schemas")).contains(TENANT);
    }

    @Test
    @DisplayName("Журналы доступны рабочей роли на UPDATE — не готов")
    void writableJournalsAreNotReady() throws Exception {
        // Права возвращает владелец — то же самое делает и человек, который
        // «временно» открыл журнал, чтобы что-то поправить.
        owner.execute("GRANT UPDATE ON " + TENANT + ".stock_movement TO " + ROLE);
        try {
            JsonNode body = readiness(503);

            assertThat(ok(body, "journals"))
                    .as("движение склада правится рабочей ролью, а готовность этого "
                            + "не заметила: журнал перестал быть доказательством, "
                            + "и узнают об этом в день, когда его предъявляют")
                    .isFalse();
            assertThat(body.get("ready").asBoolean()).isFalse();
            assertThat(detail(body, "journals")).contains("stock_movement");
        } finally {
            owner.execute("REVOKE UPDATE ON " + TENANT + ".stock_movement FROM " + ROLE);
        }
    }

    /**
     * Убирает из истории последний накатанный changeset общей схемы.
     *
     * <p>Это и есть «накат ещё не дошёл» с точки зрения того, кого спрашивает
     * готовность: состояние схемы, а не факт запуска наката. Строка целиком
     * откладывается во временную таблицу и возвращается на место — реестр
     * и история наката общие у всего прогона.
     *
     * @return идентификатор изъятого changeset'а: он обязан быть назван в ответе
     */
    private String withoutLastCatalogChangeSet() {
        owner.execute("""
                CREATE TABLE public.readiness_saved_changelog AS
                SELECT * FROM public.databasechangelog
                 WHERE orderexecuted = (SELECT max(orderexecuted) FROM public.databasechangelog)""");
        String id = owner.queryForObject(
                "SELECT id FROM public.readiness_saved_changelog", String.class);
        owner.update("DELETE FROM public.databasechangelog WHERE id = ?", id);
        return id;
    }

    private void restoreCatalogChangeSet() {
        owner.execute("""
                INSERT INTO public.databasechangelog
                SELECT * FROM public.readiness_saved_changelog""");
        owner.execute("DROP TABLE public.readiness_saved_changelog");
    }

    private void register(String version) {
        owner.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status, schema_version)
                VALUES (?, ?, 'Исправная ячейка', 'healthy-cell', 'ACTIVE', ?)
                ON CONFLICT (tenant_id) DO UPDATE
                    SET status = 'ACTIVE', schema_version = excluded.schema_version""",
                TENANT_ID, TENANT, version);
    }

    /**
     * Тело читается как UTF-8 явно: у типа ответа actuator'а нет параметра
     * charset, и MockMvc разбирает байты как ISO-8859-1 — русские причины
     * превращаются в мусор, а с ними и сообщение упавшей проверки.
     */
    private JsonNode readiness(int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get("/actuator/readiness")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus())
                .as("код ответа готовности: healthcheck docker'а смотрит на код, "
                        + "а не на тело. Тело: %s", body)
                .isEqualTo(expectedStatus);
        return json.readTree(body);
    }

    private List<String> names(JsonNode body) {
        return body.get("checks").findValuesAsText("check");
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

    /**
     * Заводит схему, роль и права до подъёма контекста: рабочей ролью Spring
     * подключится сразу, и роли к этому моменту полагается быть.
     */
    private static void prepareCell() {
        provisionTenants(TENANT);

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles
                                    WHERE rolname = 'readiness_cell_runtime') THEN
                        CREATE ROLE readiness_cell_runtime LOGIN
                            PASSWORD 'readiness-cell-runtime';
                    ELSE
                        ALTER ROLE readiness_cell_runtime LOGIN
                            PASSWORD 'readiness-cell-runtime';
                    END IF;
                END $$""");

        // Ровно то, что выдаёт общей части ячейки ops/create-roles.sh.
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT ON public.tenant_registry TO " + ROLE);
        owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.shedlock TO " + ROLE);
        owner.execute("GRANT USAGE ON SCHEMA catalog TO " + ROLE);
        owner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO " + ROLE);

        // А права на схему арендатора и на служебные таблицы ячейки —
        // настоящим SchemaGrants: копия его SQL разошлась бы с оригиналом,
        // и тест стерёг бы несуществующее поведение. Хранилище сессий тут
        // не украшение: без прав на него уборка истёкших сессий отвечает
        // «permission denied for table spring_session» каждую минуту,
        // а вход на такой ячейке невозможен вовсе.
        SchemaGrants grants = new SchemaGrants(ownerDataSource(), ROLE);
        grants.apply(TENANT);
        grants.applyCellTables();
    }

    /** Владелец схем: тот, под кем поднят контейнер. */
    private static DataSource ownerDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * Две роли вместо одной — единственная подмена в этом контексте.
     *
     * <p>Объявлены оба источника, а не один: {@code ownerDataSource} при пустом
     * {@code app.ddl.url} возвращает рабочий, и тогда историю наката общей
     * схемы читала бы рабочая роль — которой {@code SELECT}
     * на {@code public.databasechangelog} не выдают ни здесь, ни в бою.
     */
    @TestConfiguration
    static class TwoRoles {

        @Bean
        @Primary
        DataSource dataSource() {
            return pool(ROLE, PASSWORD, "ReadinessCellRuntimePool");
        }

        @Bean
        @SchemaOwnerDataSource.SchemaOwner
        DataSource ownerDataSource() {
            return pool(POSTGRES.getUsername(), POSTGRES.getPassword(),
                    "ReadinessCellOwnerPool");
        }

        /** Пулы маленькие: контекстов в прогоне полтора десятка, а база одна. */
        private static DataSource pool(String username, String password, String name) {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            dataSource.setMaximumPoolSize(2);
            dataSource.setPoolName(name);
            return dataSource;
        }
    }
}
