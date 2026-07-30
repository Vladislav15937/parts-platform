package ru.partsflow.platform.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Шифрование секретов, которые лежат в базе арендатора.
 *
 * <p>Пока такой секрет один — ключ кабинета площадки в
 * {@code marketplace_account.credentials}. Схема обещала шифрование
 * с самого начала, а кода не было: дамп базы давал доступ к кабинету клиента
 * на Дроме, то есть к его объявлениям и статистике.
 *
 * <p><b>AES-GCM, а не AES-CBC.</b> GCM проверяет целостность: подменённый
 * или обрезанный шифротекст не расшифруется, а не превратится в мусор,
 * который уедет площадке как ключ. Вектор инициализации случайный на каждое
 * шифрование — с постоянным GCM ломается полностью, это не перестраховка.
 *
 * <p><b>Формат хранения версионирован.</b> Первые четыре байта — метка
 * {@code PFC1}, дальше вектор и шифротекст с меткой аутентичности. Без версии
 * смена алгоритма означала бы разовую перешифровку всей базы под остановленным
 * приложением; с версией старое читается старым способом, новое пишется новым.
 *
 * <p><b>Пустой ключ выключает механизм.</b> Это верное значение по умолчанию:
 * приложение поднимается и работает, а попытка прочитать или записать секрет
 * отвечает внятной ошибкой вместо тихой записи открытым текстом. Тот же приём,
 * что у {@code app.provisioning-token}.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    /** Метка версии формата. Меняется вместе с алгоритмом, не вместе с ключом. */
    private static final byte[] MAGIC = "PFC1".getBytes(StandardCharsets.US_ASCII);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.crypto.key:}") String base64Key) {
        this.key = parseKey(base64Key);
        if (this.key == null) {
            log.warn("app.crypto.key не задан: секреты площадок читать и писать нельзя. "
                    + "Для рабочего окружения ключ обязателен.");
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** @return метка версии, вектор и шифротекст одним массивом */
    public byte[] encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return ByteBuffer.allocate(MAGIC.length + iv.length + encrypted.length)
                    .put(MAGIC).put(iv).put(encrypted).array();
        } catch (Exception e) {
            // Наружу не выпускаем ни ключ, ни открытый текст: сообщение уедет
            // в журнал, а журналы читают шире, чем базу.
            throw new IllegalStateException("Не удалось зашифровать секрет", e);
        }
    }

    /**
     * @return {@code null}, если секрета нет
     * @throws IllegalStateException если шифротекст испорчен или ключ не тот
     */
    public String decrypt(byte[] stored) {
        if (stored == null || stored.length == 0) {
            return null;
        }
        if (!isEncrypted(stored)) {
            // Данные, записанные до появления шифрования. Читаем, но громко:
            // такая строка в дампе — это утекший ключ кабинета.
            log.warn("Секрет лежит открытым текстом ({} байт). Перезапишите его, "
                    + "чтобы он зашифровался.", stored.length);
            return new String(stored, StandardCharsets.UTF_8);
        }
        requireKey();
        try {
            byte[] iv = Arrays.copyOfRange(stored, MAGIC.length, MAGIC.length + IV_BYTES);
            byte[] payload = Arrays.copyOfRange(stored, MAGIC.length + IV_BYTES, stored.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Секрет не расшифровывается: не тот ключ или данные испорчены", e);
        }
    }

    /** Уже зашифровано? Нужно, чтобы отличить старые записи от новых. */
    public static boolean isEncrypted(byte[] stored) {
        if (stored == null || stored.length < MAGIC.length + IV_BYTES) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(stored, MAGIC.length), MAGIC);
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "app.crypto.key не задан: работа с секретами площадок невозможна");
        }
    }

    private static SecretKeySpec parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64Key.strip());
        if (bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "app.crypto.key должен быть 32 байта в base64, а в нём " + bytes.length);
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
