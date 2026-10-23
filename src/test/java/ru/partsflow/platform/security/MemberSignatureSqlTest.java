package ru.partsflow.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.partsflow.support.PostgresTestBase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL-запись подписи совпадает с Java-записью буква в букву (задача 0373).
 *
 * <p>Подпись записана дважды намеренно — {@link MemberSignature#of} для того,
 * что собирается после запроса, и {@link MemberSignature#sql} для колонок,
 * которые отбирают и сортируют в самом запросе (витрина «Кто изменил»).
 * Две записи одного правила расходятся молча на первой правке одной из них;
 * этот тест — то, что не даёт разойтись: каждая ветка Java-записи сверяется
 * с ответом настоящего Postgres на тех же данных.
 */
class MemberSignatureSqlTest extends PostgresTestBase {

    static Stream<Arguments> members() {
        return Stream.of(
                Arguments.of("Пётр Массов", "vladelec", 3L),
                Arguments.of("Администратор сервиса", null, 12L),
                Arguments.of(null, "prodavec", 5L),
                Arguments.of("   ", "prodavec", 6L),
                Arguments.of("", "kladovshik", 7L),
                Arguments.of("prodavec", "prodavec", 8L),
                Arguments.of("Сергей", "", 9L),
                Arguments.of("Сергей", "   ", 10L),
                Arguments.of(null, null, 11L),
                Arguments.of("Сергей", "sergey", null),
                Arguments.of(null, null, null));
    }

    @ParameterizedTest(name = "имя={0}, логин={1}, номер={2}")
    @MethodSource("members")
    @DisplayName("Postgres собирает ту же подпись, что Java")
    void sqlMatchesJava(String name, String login, Long number) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + MemberSignature.sql("m")
                             + " FROM (VALUES (?::text, ?::text, ?::bigint))"
                             + " AS m(display_name, login, number)")) {
            statement.setString(1, name);
            statement.setString(2, login);
            if (number == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, number);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(MemberSignature.of(name, login, number));
            }
        }
    }
}
