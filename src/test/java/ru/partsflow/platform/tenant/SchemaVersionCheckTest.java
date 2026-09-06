package ru.partsflow.platform.tenant;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Отставшие схемы называются при старте, а не молчат до первой пятисотки.
 *
 * <p>Поймано живьём 6 сентября 2026: схемы девяти арендаторов отстали
 * на миграцию, и прайс Дрома уехал на площадку пустым файлом — то есть
 * командой «снять все объявления».
 *
 * <p>Свойства те же, что у {@link TenantMigrationsTest}: набор свойств —
 * это ключ кэша контекстов Spring, и своим набором тест поднял бы ещё один
 * контекст с ещё одним пулом соединений.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class SchemaVersionCheckTest extends PostgresTestBase {

    /** Схемы за этой записью нет и не нужно: быстрая проверка идёт по реестру. */
    private static final long TENANT_ID = 198;
    private static final String SCHEMA = "t_000198";

    @Autowired
    private SchemaVersionCheck check;

    @Autowired
    private TenantSchemaMigrator migrator;

    @Autowired
    private JdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> appender;

    /** Чужие отметки: реестр общий у всех контекстов, и их надо вернуть. */
    private final Map<Long, String> saved = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        jdbc.query("""
                SELECT tenant_id, schema_version FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        saved.put(rs.getLong("tenant_id"), rs.getString("schema_version")));
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", TENANT_ID);

        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", TENANT_ID);
        saved.forEach((tenantId, version) -> jdbc.update(
                "UPDATE public.tenant_registry SET schema_version = ? WHERE tenant_id = ?",
                version, tenantId));
    }

    @Test
    @DisplayName("Отставшая схема названа при старте: числа, имена и ожидаемая версия")
    void behindTenantIsAnnounced() {
        register("старьё");

        check.report();

        assertThat(warnings())
                .as("предупреждения об отставших схемах нет — приложение поднялось "
                        + "молча, и первым про отставание узнает площадка, забравшая "
                        + "пустой прайс")
                .anySatisfy(message -> assertThat(message)
                        .containsPattern("Схемы арендаторов отстали от кода: \\d+ из \\d+")
                        .contains(SCHEMA)
                        .contains(migrator.expectedVersion())
                        .contains("ops/migrate-tenants.sh"));
    }

    @Test
    @DisplayName("Совпадающие версии — ни одной записи")
    void matchingVersionsAreSilent() {
        String expected = migrator.expectedVersion();
        // Отметки всех, кого считает быстрая проверка: реестр общий, и чужой
        // отставший арендатор ответил бы за нас. Возвращаются в tearDown.
        jdbc.update("""
                UPDATE public.tenant_registry SET schema_version = ?
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""", expected);
        register(expected);

        check.report();

        assertThat(warnings())
                .as("предупреждение при совпадающих версиях: проверка, зеленеющая "
                        + "на всём подряд, приучает не читать строку рядом")
                .isEmpty();
    }

    private void register(String version) {
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status, schema_version)
                VALUES (?, ?, 'Отставшая', 'behindco', 'ACTIVE', ?)""",
                TENANT_ID, SCHEMA, version);
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SchemaVersionCheck.class);
    }
}
