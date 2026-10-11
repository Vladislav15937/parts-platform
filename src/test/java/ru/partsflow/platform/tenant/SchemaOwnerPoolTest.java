package ru.partsflow.platform.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пул владельца схем (задача 0070).
 *
 * <p>Заведение держит не больше одного соединения владельца разом, а Hikari
 * отпускает ждущего соединение отказом. Значит пачка заведений, которая
 * не помещается в пул, встаёт в очередь, и на медленной базе её хвост
 * получает 500 или запись, брошенную в {@code PROVISIONING}. Здесь
 * проверяется ровно эта граница: десять держателей разом — самая большая
 * названная законная пачка — получают соединение без очереди.
 *
 * <p>Контекста Spring нет намеренно: пул собирает сам
 * {@link SchemaOwnerDataSource#ownerDataSource}, и поднимать ради него
 * ещё один кэшированный контекст со своим пулом незачем.
 */
class SchemaOwnerPoolTest extends PostgresTestBase {

    /** Самая большая названная законная пачка — десять разборок (задача 0068). */
    private static final int LARGEST_BATCH = 10;

    @Test
    @DisplayName("Десять заведений разом не ждут соединения владельца, а приложению остаются свои")
    void batchOfTenDoesNotQueueAndLeavesRuntimeAlone() throws Exception {
        try (HikariDataSource runtime = pool("runtime-under-test", 2);
             HikariDataSource owner = ownerPool(runtime)) {

            // Ожидание сокращено только здесь: на прежнем пуле пятый держатель
            // ждал бы боевые 30 секунд, а утверждение то же самое — дали
            // соединение без очереди или нет.
            owner.setConnectionTimeout(2_000);

            List<Connection> held = new ArrayList<>();
            try {
                for (int i = 1; i <= LARGEST_BATCH; i++) {
                    try {
                        held.add(owner.getConnection());
                    } catch (Exception e) {
                        throw new AssertionError("Заведение " + i + " из " + LARGEST_BATCH
                                + " встало в очередь за соединением владельца: " + e.getMessage(), e);
                    }
                }

                // Другой край: пока накат держит все соединения владельца,
                // приложение отвечает своим пулом.
                try (Connection app = runtime.getConnection()) {
                    assertThat(app.isValid(2))
                            .as("рабочий пул доступен, пока пачка держит пул владельца")
                            .isTrue();
                }
            } finally {
                for (Connection c : held) {
                    c.close();
                }
            }
        }
    }

    @Test
    @DisplayName("Без дела пул владельца держит одно соединение, а не все")
    void idleOwnerPoolHoldsOneConnection() throws Exception {
        try (HikariDataSource runtime = pool("runtime-idle", 1);
             HikariDataSource owner = ownerPool(runtime)) {
            // Умолчание Hikari («держать все») раскрывается только при запуске
            // пула: до первого соединения настройка отвечает -1 и прошла бы
            // проверку на любом коде.
            try (Connection first = owner.getConnection()) {
                assertThat(first.isValid(2)).isTrue();
            }
            assertThat(owner.getMinimumIdle())
                    .as("в простое пул владельца не занимает места в max_connections")
                    .isLessThanOrEqualTo(1);
        }
    }

    private static HikariDataSource ownerPool(DataSource runtime) {
        DataSource built = new SchemaOwnerDataSource().ownerDataSource(runtime,
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertThat(built)
                .as("при заданной роли владельца пул свой, а не рабочий")
                .isNotSameAs(runtime)
                .isInstanceOf(HikariDataSource.class);
        return (HikariDataSource) built;
    }

    private static HikariDataSource pool(String name, int size) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setMaximumPoolSize(size);
        ds.setPoolName(name);
        return ds;
    }
}
