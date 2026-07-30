package ru.partsflow.publishing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.crypto.SecretCipher;

import java.util.List;
import java.util.Optional;

/**
 * Кабинеты площадок.
 *
 * <p>Единственное место, где секрет кабинета попадает в базу и достаётся
 * из неё. Заводился он до этого только руками через SQL — то есть открытым
 * текстом, потому что зашифровать его в psql нечем.
 *
 * <p><b>Секрет наружу не отдаётся никогда.</b> Ни в списке, ни поштучно:
 * его вводят один раз и потом только заменяют. Эндпоинт «показать ключ»
 * превращает права на чтение настроек в доступ к кабинету клиента.
 */
@Service
public class MarketplaceAccountService {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public MarketplaceAccountService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return jdbc.query("""
                SELECT id, marketplace, title, status, last_sync_at IS NOT NULL AS synced,
                       last_error, credentials IS NOT NULL AS has_credentials,
                       credentials
                  FROM marketplace_account
                 ORDER BY marketplace, title""",
                (rs, i) -> new Account(
                        rs.getLong("id"),
                        rs.getString("marketplace"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getBoolean("has_credentials"),
                        // Открытый текст в базе — повод показать это в интерфейсе,
                        // а не только в журнале: чинит это человек.
                        rs.getBoolean("has_credentials")
                                && !SecretCipher.isEncrypted(rs.getBytes("credentials")),
                        rs.getString("last_error")));
    }

    /** Заводит или заменяет секрет кабинета. Возврата наружу у него нет. */
    @Transactional
    public void setCredentials(long accountId, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Пустой ключ кабинета не имеет смысла");
        }
        int updated = jdbc.update("UPDATE marketplace_account SET credentials = ? WHERE id = ?",
                cipher.encrypt(secret), accountId);

        if (updated == 0) {
            throw new IllegalArgumentException("Кабинет не найден: " + accountId);
        }
    }

    /**
     * Секрет для обращения к площадке.
     *
     * <p>Только для кода выгрузки. Наружу этот метод не выходит и выходить
     * не должен.
     */
    @Transactional(readOnly = true)
    public Optional<String> secretOf(long accountId) {
        List<byte[]> found = jdbc.query(
                "SELECT credentials FROM marketplace_account WHERE id = ?",
                (rs, i) -> rs.getBytes("credentials"), accountId);

        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(cipher.decrypt(found.get(0)));
    }

    /**
     * @param plaintextSecret секрет лежит незашифрованным — его надо перезаписать
     */
    public record Account(Long id, String marketplace, String title, String status,
                          boolean hasCredentials, boolean plaintextSecret, String lastError) {
    }
}
