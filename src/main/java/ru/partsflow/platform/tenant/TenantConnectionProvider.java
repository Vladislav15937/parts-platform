package ru.partsflow.platform.tenant;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.security.CurrentUser;

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
            // Обе настройки одним обменом с базой: соединение берётся
            // на каждую транзакцию, и лишний round-trip здесь — это лишний
            // round-trip на каждый запрос приложения.
            st.execute("SET search_path TO " + quote(tenantSchema) + ", catalog, public;"
                    + " SET app.user_id TO " + userId());
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantSchema, Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            // Сброс обязателен: соединение уходит в пул и достанется другому
            // арендатору. Вошедшего сбрасываем тем же обменом — иначе следующая
            // транзакция подпишет чужие правки предыдущим сотрудником, и это
            // та же утечка, что и со схемой, только заметить её можно лишь
            // по журналу задним числом.
            st.execute("SET search_path TO public; SET app.user_id TO ''");
        } finally {
            connection.close();
        }
    }

    /**
     * Вошедший сотрудник для триггера аудита.
     *
     * <p>{@code audit_trigger} читает его из {@code current_setting('app.user_id')}
     * и пишет в {@code audit_log.changed_by}. Выставлять это было некому,
     * поэтому у переехавшего клиента все 141 955 записей аудита без автора:
     * журнал есть, а «кто уронил цену» по нему не спросить.
     *
     * <p>Здесь, а не в бизнес-коде, по той же причине, по которой аудит сидит
     * на триггере: правка мимо сервиса не должна остаться неподписанной.
     * Пусто — это фоновые задачи: релей, миграции, забор прайса площадкой.
     * У них вошедшего нет и быть не может.
     */
    private static String userId() {
        Long memberId = CurrentUser.memberId();
        return memberId == null ? "''" : String.valueOf(memberId);
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
