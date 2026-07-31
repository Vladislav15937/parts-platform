package ru.partsflow.platform.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Шифрование секретов площадок.
 *
 * <p>Проверяется не столько «расшифровывается обратно» — это очевидно, —
 * сколько то, ради чего шифрование и заводилось: в дампе базы ключа кабинета
 * не видно, подменённый шифротекст не превращается в мусор, который уедет
 * площадке как ключ, и чужой ключ шифрования не открывает секрет.
 */
class SecretCipherTest {

    private static final String KEY = base64Key((byte) 7);
    private static final String OTHER_KEY = base64Key((byte) 9);

    private final SecretCipher cipher = new SecretCipher(KEY);

    @Test
    @DisplayName("Зашифрованное расшифровывается обратно")
    void roundTrip() {
        byte[] encrypted = cipher.encrypt("ключ-кабинета-дрома");

        assertThat(cipher.decrypt(encrypted)).isEqualTo("ключ-кабинета-дрома");
    }

    @Test
    @DisplayName("Секрета не видно в том, что лежит в базе")
    void plaintextIsNotStored() {
        byte[] encrypted = cipher.encrypt("секрет-кабинета");

        // Ровно та беда, от которой защищаемся: дамп базы не должен давать
        // доступ к кабинету клиента на площадке.
        assertThat(new String(encrypted, StandardCharsets.UTF_8))
                .doesNotContain("секрет-кабинета");
    }

    @Test
    @DisplayName("Одно и то же шифруется по-разному")
    void ivIsRandom() {
        byte[] first = cipher.encrypt("один и тот же ключ");
        byte[] second = cipher.encrypt("один и тот же ключ");

        // С постоянным вектором инициализации GCM ломается полностью,
        // а одинаковые шифротексты выдают, что у двух клиентов один ключ.
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
    }

    @Test
    @DisplayName("Испорченный шифротекст не расшифровывается, а не даёт мусор")
    void tamperedIsRejected() {
        byte[] encrypted = cipher.encrypt("ключ-кабинета");
        encrypted[encrypted.length - 1] ^= 0x01;

        // Без проверки целостности сюда приехала бы строка случайных байт,
        // и она уехала бы площадке как ключ.
        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не расшифровывается");
    }

    @Test
    @DisplayName("Чужой ключ шифрования секрет не открывает")
    void otherKeyCannotDecrypt() {
        byte[] encrypted = cipher.encrypt("ключ-кабинета");

        assertThatThrownBy(() -> new SecretCipher(OTHER_KEY).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Записанное до шифрования читается, но помечается как открытое")
    void legacyPlaintextIsReadable() {
        byte[] legacy = "старый-ключ".getBytes(StandardCharsets.UTF_8);

        // Отказ читать сломал бы выгрузку у тех, у кого ключ уже заведён.
        assertThat(cipher.decrypt(legacy)).isEqualTo("старый-ключ");
        assertThat(SecretCipher.isEncrypted(legacy)).isFalse();
    }

    @Test
    @DisplayName("Пустой секрет — это отсутствие секрета, а не ошибка")
    void emptyIsNull() {
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt(new byte[0])).isNull();
    }

    @Test
    @DisplayName("Без ключа шифрования механизм выключен, а не пишет открытым текстом")
    void withoutKeyOperationsFail() {
        SecretCipher disabled = new SecretCipher("");

        assertThat(disabled.isConfigured()).isFalse();
        // Молча записать открытым текстом было бы худшим из вариантов:
        // схема обещает шифрование, и никто бы не заметил.
        assertThatThrownBy(() -> disabled.encrypt("ключ"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.crypto.key");
    }

    @Test
    @DisplayName("Ключ неверной длины отвергается при запуске")
    void shortKeyIsRejected() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretCipher(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 байта");
    }

    private static String base64Key(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        return Base64.getEncoder().encodeToString(key);
    }
}
