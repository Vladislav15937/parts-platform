package ru.partsflow.platform.admin;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.partsflow.platform.tenant.SchemaOwnerDataSource;

import javax.sql.DataSource;

/**
 * Заведение учётной записи администратора сервиса.
 *
 * <p><b>Через управляющий контур, а не экраном.</b> Администратор сервиса
 * сегодня один — «сейчас это я» (решение владельца продукта от 8 сентября
 * 2026, tasks/0042), — и заводит его тот же, кто заводит ячейку и клиентов:
 * оператор с секретом {@code app.provisioning-token}. Экран, на котором
 * администраторы заводят администраторов, — это вопрос о том, кто вправе
 * раздавать доступ ко всем клиентам сразу, и в задаче ответа на него нет.
 *
 * <p>Пишет владелец схем, как и реестр арендаторов: рабочей роли таблица
 * учётных записей отдана только на чтение (вход), и запрос из приложения
 * не заведёт себе администратора, даже найдя путь мимо контура.
 */
@Service
public class ServiceAdmins {

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final JdbcTemplate owner;
    private final PasswordEncoder passwordEncoder;

    public ServiceAdmins(@SchemaOwnerDataSource.SchemaOwner DataSource ownerDataSource,
                         PasswordEncoder passwordEncoder) {
        this.owner = new JdbcTemplate(ownerDataSource);
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Двенадцать символов, а не восемь, как у сотрудника: эта учётная запись
     * открывает кабинеты всех клиентов ячейки, у которых открыт доступ.
     */
    public Created create(String login, String password, String displayName) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("Логин обязателен");
        }
        if (password == null || password.strip().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль администратора короче " + MIN_PASSWORD_LENGTH + " символов");
        }
        String name = displayName == null || displayName.isBlank()
                ? login.strip() : displayName.strip();
        try {
            owner.update("""
                    INSERT INTO public.service_admin (login, password_hash, display_name)
                    VALUES (?, ?, ?)""", login.strip(), passwordEncoder.encode(password), name);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    "Администратор с логином «%s» уже есть".formatted(login.strip()));
        }
        return new Created(login.strip(), name);
    }

    public record Created(String login, String displayName) {
    }
}
