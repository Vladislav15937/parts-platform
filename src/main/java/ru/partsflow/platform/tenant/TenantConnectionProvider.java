package ru.partsflow.platform.tenant;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Маршрутизация соединений по схемам арендаторов.
 *
 * <p><b>Здесь живёт самая опасная ошибка этой архитектуры.</b> PgBouncer в
 * transaction mode возвращает физическое соединение в пул после коммита, и
 * следующая транзакция — возможно, другого арендатора — получит то же соединение.
 * Сессионный {@code SET search_path} при этом протечёт, и клиент увидит чужой
 * склад. Для десяти конкурирующих компаний из одного города это не баг, а конец
 * бизнеса.
 *
 * <p>Защита: {@code search_path} выставляется при получении соединения и
 * <b>обязательно сбрасывается</b> в {@link #releaseConnection}. Инвариант
 * стережёт {@code TenantIsolationTest} — он прогоняет транзакции двух арендаторов
 * вперемежку через один пул. Не отключай его.
 *
 * <p>Имя схемы подставляется в SQL конкатенацией, потому что идентификаторы
 * нельзя передать параметром. Безопасность обеспечивается проверкой формата в
 * {@link TenantContext#set} — произвольная строка сюда не дойдёт.
 */
@Component
public class TenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    public TenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantSchema) throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement st = connection.createStatement()) {
            st.execute("SET search_path TO " + quote(tenantSchema) + ", catalog, public");
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantSchema, Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            // Сброс обязателен: соединение уходит в пул и достанется другому арендатору.
            st.execute("SET search_path TO public");
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        // false: агрессивное освобождение вернуло бы соединение в пул посреди
        // транзакции, и search_path пришлось бы выставлять заново на каждый запрос.
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return DataSource.class.isAssignableFrom(unwrapType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return (T) dataSource;
        }
        throw new IllegalArgumentException("Не поддерживается: " + unwrapType);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
