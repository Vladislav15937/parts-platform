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

    /**
     * Схем за этими записями нет и не нужно: быстрая проверка идёт по отметке
     * в реестре. Номера подряд — список отставших упорядочен по ним, и от этого
     * зависит, кого проверка назовёт по имени.
     */
    private static final long FIRST_ID = 198;

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
        clean();

        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
        clean();
        saved.forEach((tenantId, version) -> jdbc.update(
                "UPDATE public.tenant_registry SET schema_version = ? WHERE tenant_id = ?",
                version, tenantId));
    }

    @Test
    @DisplayName("Отставшая схема названа при старте: числа, имя и ожидаемая версия")
    void behindTenantIsAnnounced() {
        catchUpEveryoneElse();
        register(FIRST_ID, "старьё");

        check.report();

        assertThat(warnings())
                .as("предупреждения об отставших схемах нет — приложение поднялось "
                        + "молча, и первым про отставание узнает площадка, забравшая "
                        + "пустой прайс")
                .anySatisfy(message -> assertThat(message)
                        .containsPattern("Схемы арендаторов отстали от кода: 1 из \\d+")
                        .contains(schema(FIRST_ID))
                        .contains(migrator.expectedVersion())
                        .contains("ops/migrate-tenants.sh"));
    }

    @Test
    @DisplayName("Имён названо три, остальные — числом")
    void extraSchemasAreCounted() {
        // Девять схем в строку — это строка, которую не читают. Первых трёх
        // хватает, чтобы понять, кого смотреть, числа остальных — чтобы понять
        // масштаб; сюда смотрят как раз тогда, когда прайс уехал пустым.
        catchUpEveryoneElse();
        for (long id = FIRST_ID; id < FIRST_ID + 4; id++) {
            register(id, "старьё");
        }

        check.report();

        assertThat(warnings())
                .anySatisfy(message -> assertThat(message)
                        .contains("4 из")
                        .contains("(t_000198, t_000199, t_000200 и ещё 1)"));
    }

    @Test
    @DisplayName("Совпадающие версии — ни одной записи")
    void matchingVersionsAreSilent() {
        catchUpEveryoneElse();
        register(FIRST_ID, migrator.expectedVersion());

        check.report();

        assertThat(warnings())
                .as("предупреждение при совпадающих версиях: проверка, зеленеющая "
                        + "на всём подряд, приучает не читать строку рядом")
                .isEmpty();
    }

    /**
     * Отметки всех, кого считает быстрая проверка. Реестр общий у контекстов,
     * и отставший арендатор чужого теста ответил бы за наш — на CI их два
     * десятка, и проверка называла по имени не тех. Возвращаются в tearDown.
     */
    private void catchUpEveryoneElse() {
        jdbc.update("""
                UPDATE public.tenant_registry SET schema_version = ?
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""", migrator.expectedVersion());
    }

    private void register(long tenantId, String version) {
        jdbc.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status, schema_version)
                VALUES (?, ?, 'Отставшая', ?, 'ACTIVE', ?)""",
                tenantId, schema(tenantId), "behind" + tenantId, version);
    }

    private void clean() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id BETWEEN ? AND ?",
                FIRST_ID, FIRST_ID + 9);
    }

    private static String schema(long tenantId) {
        return "t_%06d".formatted(tenantId);
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
