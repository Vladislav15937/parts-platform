package ru.partsflow.sales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Клиенты: найти позвонившего и завести нового.
 *
 * <p><b>Транзакция здесь обязательна, хотя запросы читающие.</b> Схему
 * арендатора выставляет провайдер соединений Hibernate, и делает это только
 * внутри транзакции. {@code JdbcTemplate}, взявший соединение прямо из пула,
 * смотрит в {@code public}, где никакой {@code customer} нет.
 */
@Service
public class CustomerService {

    private final JdbcTemplate jdbc;

    public CustomerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Поиск по имени и телефону одной строкой.
     *
     * <p>Режим продавец не выбирает — он набирает то, что услышал. Телефон
     * сравнивается по цифрам: один и тот же номер записывают и с +7, и с 8,
     * и со скобками, и точное сравнение не найдёт ничего.
     */
    @Transactional(readOnly = true)
    public List<Customer> search(String query, int limit) {
        String term = query == null ? "" : query.strip();
        if (term.isEmpty()) {
            return jdbc.query("""
                    SELECT id, name, phone, email, customer_type
                      FROM customer ORDER BY id DESC LIMIT ?""", CustomerService::map, limit);
        }
        String digits = term.replaceAll("\\D", "");

        return jdbc.query("""
                SELECT id, name, phone, email, customer_type
                  FROM customer
                 WHERE name ILIKE '%' || ? || '%'
                    OR (? <> '' AND regexp_replace(COALESCE(phone, ''), '\\D', '', 'g')
                                    LIKE '%' || ? || '%')
                 ORDER BY id DESC
                 LIMIT ?""", CustomerService::map, term, digits, digits, limit);
    }

    @Transactional
    public Customer create(String name, String phone, String email, String customerType) {
        return jdbc.queryForObject("""
                INSERT INTO customer (name, phone, email, customer_type)
                VALUES (?, ?, ?, ?)
                RETURNING id, name, phone, email, customer_type""",
                CustomerService::map,
                name.strip(), blankToNull(phone), blankToNull(email),
                customerType == null || customerType.isBlank() ? "PERSON" : customerType);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Customer map(ResultSet rs, int row) throws SQLException {
        return new Customer(rs.getLong("id"), rs.getString("name"), rs.getString("phone"),
                rs.getString("email"), rs.getString("customer_type"));
    }

    public record Customer(Long id, String name, String phone, String email, String customerType) {
    }
}
