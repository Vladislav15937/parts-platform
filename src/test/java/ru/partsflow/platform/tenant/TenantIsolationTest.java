package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сторожевой тест изоляции арендаторов.
 *
 * <p>Проверяет самую опасную ошибку архитектуры: утечку {@code search_path}
 * между арендаторами через переиспользуемое соединение. Если
 * {@code TenantConnectionProvider} перестанет сбрасывать {@code search_path}
 * при возврате соединения в пул, один клиент увидит склад другого — а все
 * первые десять клиентов конкурируют между собой в одном городе.
 *
 * <p>Пул намеренно сужен до одного соединения: так каждая следующая транзакция
 * гарантированно получает то же физическое соединение, что и предыдущая, —
 * то есть ровно тот сценарий, который создаёт PgBouncer в transaction mode.
 *
 * <p><b>Не отключай этот тест.</b> Если он покраснел — это не флейк.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=none"
})
// Пробник импортируется явно: Spring Boot намеренно исключает вложенные
// в тестовые классы компоненты из сканирования, чтобы они не протекали
// в контексты других тестов.
@Import(TenantIsolationTest.PartRepositoryProbe.class)
class TenantIsolationTest extends PostgresTestBase {

    private static final String TENANT_A = "t_000042";
    private static final String TENANT_B = "t_000043";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PartRepositoryProbe parts;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT_A, TENANT_B);
    }

    @Test
    @DisplayName("Данные арендаторов не пересекаются при работе через один пул")
    void tenantsDoNotSeeEachOther() {
        String titleOfA = "Изоляция: фара левая Camry V50";
        String titleOfB = "Изоляция: бампер передний X-Trail";

        inTenant(TENANT_A, () -> parts.insert(titleOfA, new BigDecimal("8500")));
        inTenant(TENANT_B, () -> parts.insert(titleOfB, new BigDecimal("12000")));

        assertThat(inTenant(TENANT_A, () -> parts.countByTitle(titleOfA))).isEqualTo(1);
        assertThat(inTenant(TENANT_A, () -> parts.countByTitle(titleOfB)))
                .as("арендатор A увидел деталь арендатора B").isZero();

        assertThat(inTenant(TENANT_B, () -> parts.countByTitle(titleOfB))).isEqualTo(1);
        assertThat(inTenant(TENANT_B, () -> parts.countByTitle(titleOfA)))
                .as("арендатор B увидел деталь арендатора A").isZero();
    }

    @Test
    @DisplayName("search_path не протекает между чередующимися транзакциями")
    void searchPathDoesNotLeakBetweenTransactions() {
        // Считаем только свою деталь, а не все строки схемы: тесты делят один
        // контейнер и одну схему арендатора, и общий count зависел бы от
        // порядка запуска.
        String title = "Утечка: деталь A";
        inTenant(TENANT_A, () -> parts.insert(title, BigDecimal.TEN));

        // Чередование: A, B, A, B через одно и то же физическое соединение.
        for (int i = 0; i < 10; i++) {
            assertThat(inTenant(TENANT_A, () -> parts.countByTitle(title)))
                    .as("итерация %d: арендатор A", i).isEqualTo(1);
            assertThat(inTenant(TENANT_B, () -> parts.countByTitle(title)))
                    .as("итерация %d: арендатор B", i).isZero();
        }
    }

    @Test
    @DisplayName("После освобождения соединения search_path сброшен на public")
    void searchPathIsResetOnRelease() {
        inTenant(TENANT_A, parts::count);

        String actual = jdbcTemplate.queryForObject("SHOW search_path", String.class);
        assertThat(actual)
                .as("соединение вернулось в пул с чужой схемой в search_path")
                .doesNotContain(TENANT_A);
    }

    @Test
    @DisplayName("Параллельная работа арендаторов не путает данные")
    void concurrentTenantsStayIsolated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 20; i++) {
                pool.submit(() -> inTenant(TENANT_A, () -> parts.insert("A-деталь", BigDecimal.ONE)));
                pool.submit(() -> inTenant(TENANT_B, () -> parts.insert("B-деталь", BigDecimal.ONE)));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(inTenant(TENANT_A, () -> parts.countByTitle("B-деталь"))).isZero();
        assertThat(inTenant(TENANT_B, () -> parts.countByTitle("A-деталь"))).isZero();
    }

    private <T> T inTenant(String schema, java.util.function.Supplier<T> action) {
        try {
            TenantContext.set(schema);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    private void inTenant(String schema, Runnable action) {
        inTenant(schema, () -> {
            action.run();
            return null;
        });
    }

    /** Минимальный доступ к данным, чтобы тест не зависел от доменного слоя. */
    @org.springframework.stereotype.Component
    static class PartRepositoryProbe {

        private final JdbcTemplate jdbc;

        PartRepositoryProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        void insert(String title, BigDecimal price) {
            jdbc.update("INSERT INTO part (category_id, title, price, status) VALUES (1, ?, ?, 'IN_STOCK')",
                    title, price);
        }

        @Transactional(readOnly = true)
        Integer count() {
            return jdbc.queryForObject("SELECT count(*) FROM part", Integer.class);
        }

        @Transactional(readOnly = true)
        Integer countByTitle(String title) {
            return jdbc.queryForObject("SELECT count(*) FROM part WHERE title = ?", Integer.class, title);
        }
    }
}
