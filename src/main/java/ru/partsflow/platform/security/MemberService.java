package ru.partsflow.platform.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
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
        // Филиал обязан существовать, и сказать об этом надо словами: роль
        // проверяется белым списком и отбивается внятно, а чужой филиал
        // доезжал до внешнего ключа и возвращался как «Операция нарушает
        // целостность данных». Пусто законно: филиал у сотрудника
        // необязателен.
        if (branchId != null) {
            Integer branch = jdbc.queryForObject(
                    "SELECT count(*) FROM branch WHERE id = ?", Integer.class, branchId);
            if (branch == null || branch == 0) {
                throw new IllegalArgumentException("Филиал не найден: " + branchId);
            }
        }

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
        if (!active) {
            requireNotLastOwner(memberId);
        }
        int updated = jdbc.update("UPDATE tenant_member SET is_active = ? WHERE id = ?",
                active, memberId);
        if (updated == 0) {
            throw new IllegalArgumentException("Сотрудник не найден: " + memberId);
        }
    }

    /**
     * Последнего владельца выключить нельзя.
     *
     * <p>Иначе компания запирается снаружи: сотрудников, выгрузки, ключи
     * кабинетов и списание видит только владелец, а включить его обратно
     * может тоже только владелец. Выход остаётся один — провижининг, то есть
     * разработчик с секретом управляющего контура.
     *
     * <p>Правило стоит здесь, а не на экране: экран прячет кнопку у строки
     * с ролью «владелец», но это одна из двух дверей — запрос к API открыт
     * так же. Пока проверки не было, компания могла запереть себя одним
     * нажатием, и обнаружилось бы это при следующем входе.
     */
    private void requireNotLastOwner(Long memberId) {
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM tenant_member
                 WHERE role = 'OWNER' AND is_active AND login IS NOT NULL AND id <> ?""",
                Integer.class, memberId);
        String role = jdbc.query("SELECT role FROM tenant_member WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, memberId);

        if ("OWNER".equals(role) && (others == null || others == 0)) {
            throw new IllegalStateException(
                    "Это единственный владелец: выключив его, в компанию нельзя будет войти "
                            + "как владелец. Сначала заведите второго");
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

    /**
     * Имена сотрудников по идентификаторам — одним запросом на всю выдачу.
     *
     * <p>История документа — это десятки строк, и запрос на каждую превратил
     * бы её открытие в столько же обращений к базе. Без имени же строка
     * читается как «автор 3»: разбирают историю через недели, когда
     * по номеру никто никого не вспомнит.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> namesOf(Collection<Long> memberIds) {
        List<Long> ids = memberIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new java.util.HashMap<>();
        jdbc.query("SELECT id, display_name FROM tenant_member WHERE id = ANY (?)",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("display_name"));
                },
                (Object) ids.toArray(Long[]::new));
        return names;
    }

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
