package ru.partsflow.platform.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Сотрудники арендатора: создание, пароли, отключение.
 *
 * <p>Работает через {@code JdbcTemplate}, а не через сущность: таблица
 * административная, из шести полей, и второй способ доступа к ней уже есть —
 * {@link MemberAuthenticationProvider} читает её сырым SQL, потому что вход
 * происходит до маршрутизации арендатора. Заводить сущность ради одного сервиса
 * значит получить три пути к одной таблице вместо двух.
 */
@Service
public class MemberService {

    /**
     * Восемь символов — не про стойкость к подбору, а про то, что короче люди
     * ставят «12345». Стойкость даёт BCrypt и отсутствие публичного перебора.
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final Set<String> ROLES =
            Set.of("OWNER", "MANAGER", "STOREKEEPER", "SELLER", "VIEWER");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public MemberService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Member create(String login, String password, String displayName, String role,
                         Long branchId) {
        validate(login, password, role);

        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash, branch_id)
                    VALUES (?, ?, ?, ?, ?) RETURNING id""",
                    Long.class,
                    displayName == null || displayName.isBlank() ? login.strip() : displayName.strip(),
                    role, login.strip(), passwordEncoder.encode(password), branchId);

            return byId(id);
        } catch (DuplicateKeyException e) {
            // Уникальность по нормализованному логину стоит в БД: «Ivan» и «ivan »
            // это один человек, и второй такой должен получить отказ.
            throw new IllegalArgumentException("Сотрудник с логином «%s» уже есть".formatted(login));
        }
    }

    @Transactional(readOnly = true)
    public List<Member> all() {
        return jdbc.query("""
                SELECT id, login, display_name, role, is_active, last_login_at
                  FROM tenant_member
                 WHERE login IS NOT NULL
                 ORDER BY id""", MemberService::map);
    }

    /**
     * Меняет пароль.
     *
     * <p>Старый пароль не спрашивается: менять может владелец (тогда старого
     * он не знает) либо сам сотрудник в уже подтверждённой сессии. Требовать
     * старый имеет смысл против угона сессии, но это отдельный разговор
     * и отдельный эндпоинт «сменить свой пароль».
     */
    @Transactional
    public void changePassword(Long memberId, String newPassword) {
        requirePassword(newPassword);

        int updated = jdbc.update("""
                UPDATE tenant_member SET password_hash = ?
                 WHERE id = ? AND login IS NOT NULL""",
                passwordEncoder.encode(newPassword), memberId);

        if (updated == 0) {
            throw new IllegalArgumentException("Сотрудник не найден: " + memberId);
        }
    }

    /**
     * Включает и отключает сотрудника.
     *
     * <p>Удаления нет намеренно: сотрудник стоит в {@code created_by} у сделок,
     * движений и документов. Удалить его значит либо порвать историю, либо
     * оставить в ней пустоту вместо имени — а по этой истории считают зарплаты.
     */
    @Transactional
    public void setActive(Long memberId, boolean active) {
        int updated = jdbc.update("UPDATE tenant_member SET is_active = ? WHERE id = ?",
                active, memberId);
        if (updated == 0) {
            throw new IllegalArgumentException("Сотрудник не найден: " + memberId);
        }
    }

    /** Есть ли у арендатора хоть один сотрудник с доступом. */
    @Transactional(readOnly = true)
    public boolean hasAnyWithCredentials() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_member WHERE login IS NOT NULL", Integer.class);
        return count != null && count > 0;
    }

    /**
     * Создаёт первого владельца, если у арендатора нет ни одной учётной записи.
     *
     * <p>Проверка и вставка — одна инструкция: раздельные «посмотреть, нет ли»
     * и «вставить» пропустят второй одновременный запрос, и у компании окажется
     * два владельца от разных людей. Тот же приём, что в {@code reserve_stock}.
     *
     * @return пусто, если учётные записи уже есть
     */
    @Transactional
    public Optional<Member> createFirstOwnerIfEmpty(String login, String password,
                                                    String displayName) {
        validate(login, password, "OWNER");

        List<Long> created = jdbc.queryForList("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                SELECT ?, 'OWNER', ?, ?
                 WHERE NOT EXISTS (SELECT 1 FROM tenant_member WHERE login IS NOT NULL)
                RETURNING id""",
                Long.class,
                displayName == null || displayName.isBlank() ? login.strip() : displayName.strip(),
                login.strip(), passwordEncoder.encode(password));

        return created.isEmpty() ? Optional.empty() : Optional.of(byId(created.get(0)));
    }

    @Transactional(readOnly = true)
    public Member byId(Long memberId) {
        List<Member> found = jdbc.query("""
                SELECT id, login, display_name, role, is_active, last_login_at
                  FROM tenant_member WHERE id = ?""", MemberService::map, memberId);

        if (found.isEmpty()) {
            throw new IllegalArgumentException("Сотрудник не найден: " + memberId);
        }
        return found.get(0);
    }

    private void validate(String login, String password, String role) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("Логин обязателен");
        }
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException(
                    "Неизвестная роль «%s», допустимы: %s".formatted(role, ROLES));
        }
        requirePassword(password);
    }

    private void requirePassword(String password) {
        if (password == null || password.strip().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль короче " + MIN_PASSWORD_LENGTH + " символов");
        }
    }

    private static Member map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Member(
                rs.getLong("id"),
                rs.getString("login"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getBoolean("is_active"),
                rs.getTimestamp("last_login_at") == null
                        ? null : rs.getTimestamp("last_login_at").toInstant());
    }

    /** Сотрудник без пароля: наружу хеш не отдаётся никогда. */
    public record Member(Long id, String login, String displayName, String role,
                         boolean active, Instant lastLoginAt) {
    }
}
