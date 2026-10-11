package ru.partsflow.platform.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Преграда перед формой регистрации вместо капчи (задача 0184).
 *
 * <p><b>Что это.</b> Сервер выдаёт задание, браузер подбирает число так, чтобы
 * {@code SHA-256(задание + ":" + число)} начиналось с заданного числа нулевых
 * битов, и присылает его вместе с формой. На телефоне это секунды, человеку
 * они не видны: форма всё равно заполняется дольше. Скрипту, шлющему запросы
 * без браузера, каждая регистрация стоит вычисления — а не просто запроса.
 *
 * <p><b>Почему не капча.</b> Настоящая капча — это чужой сервис с ключом и
 * договором (hCaptcha, Cloudflare Turnstile, Яндекс SmartCaptcha), а ключей у
 * нас нет и заводить зависимость от чужой доступности перед первой строкой
 * входа в продукт — тот самый вид хрупкости, который проект уже трижды
 * оплатил с реестрами образов. Назвать равной капче эту преграду нельзя, и
 * это сказано прямо: <b>решающий противник считает хеши быстрее браузера</b>,
 * и для него она почти бесплатна. Достаточной она оказывается не сама по себе,
 * а в связке: предел с одного адреса ({@link RegistrationLimits}), потолок
 * числа компаний в ячейке и уникальность почты — вред от обойдённой преграды
 * ограничен потолком, а не диском. Остаточный риск при этом остаётся и назван:
 * потолок можно занять мусорными регистрациями с разных адресов, и тогда
 * настоящим клиентам форма скажет «временно закрыта».
 *
 * <p><b>Задание подписано, а не запомнено.</b> Состояния на сервере нет:
 * {@code v1.срок.случайное.подпись}, подпись — HMAC от случайного ключа
 * процесса. Две сборки в ячейке (синяя и зелёная) ключей друг друга не знают,
 * и задание, взятое у одной и предъявленное другой во время переключения
 * трафика, будет отвергнуто — форма просто возьмёт новое.
 *
 * <p><b>Решение расходуется один раз — но на один ключ повтора.</b> Иначе
 * одно решение, подобранное один раз, раздавали бы на сотню регистраций до
 * истечения срока. А двойное нажатие шлёт одно и то же тело дважды, и второй
 * запрос не должен падать на «решение уже использовано»: он тот же, он
 * получает результат первого. Поэтому занятое решение помнит, с каким ключом
 * его предъявили; тот же ключ проходит, чужой — нет.
 *
 * <p><b>Память — одного экземпляра.</b> Занятые решения помнит процесс, и у
 * каждой из двух сборок своя карта. Одно решение можно предъявить обеим
 * сборкам, но срок его жизни — минуты, а каждая регистрация всё равно
 * упирается в предел адреса и потолок ячейки.
 */
@Component
public class RegistrationChallenge {

    /** Сколько живёт задание: форму заполняют минуты, но не часы. */
    static final Duration TTL = Duration.ofMinutes(20);

    /** Сколько решений держим: защита нашей же памяти, а не предел для человека. */
    private static final int MAX_REMEMBERED = 20_000;

    private static final String VERSION = "v1";

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret = new byte[32];
    private final int bits;

    /** Занятые решения: случайная часть задания → ключ повтора и срок. */
    private final Map<String, Used> used = new ConcurrentHashMap<>();

    public RegistrationChallenge(@Value("${app.registration-pow-bits:16}") int bits) {
        if (bits < 0 || bits > 30) {
            throw new IllegalArgumentException(
                    "app.registration-pow-bits — от 0 до 30, получено " + bits);
        }
        this.bits = bits;
        random.nextBytes(secret);
    }

    /** Задание для браузера. */
    public Issued issue() {
        String nonce = HexFormat.of().formatHex(randomBytes(8));
        long expires = System.currentTimeMillis() / 1000 + TTL.toSeconds();
        String body = VERSION + "." + expires + "." + nonce;
        return new Issued(body + "." + sign(body), bits);
    }

    /**
     * Проверяет решение и занимает его за ключом повтора.
     *
     * @throws IllegalArgumentException задание чужое, просроченное, решение
     *         не подходит или уже использовано другой регистрацией — 400, и
     *         текст говорит, что делать
     * @throws ProvisioningLimits.TooManyRequests помнить новые решения
     *         больше нечем
     */
    public void verifyAndUse(String challenge, String solution, String registrationKey) {
        String[] parts = challenge == null ? new String[0] : challenge.split("\\.");
        if (parts.length != 4 || !VERSION.equals(parts[0])
                || solution == null || solution.isBlank() || solution.length() > 20) {
            throw stale();
        }
        String body = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8))) {
            throw stale();
        }
        long expires;
        try {
            expires = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw stale();
        }
        if (expires < System.currentTimeMillis() / 1000) {
            throw stale();
        }
        if (!meetsDifficulty(challenge, solution)) {
            throw new IllegalArgumentException(
                    "Проверка «вы не робот» не пройдена. Обновите страницу и отправьте форму ещё раз");
        }

        forgetExpired();
        if (used.size() >= MAX_REMEMBERED && !used.containsKey(parts[2])) {
            throw new ProvisioningLimits.TooManyRequests(
                    "Сейчас слишком много регистраций. Повторите через несколько минут");
        }
        Used previous = used.putIfAbsent(parts[2], new Used(registrationKey, expires));
        if (previous != null && !previous.registrationKey().equals(registrationKey)) {
            throw new IllegalArgumentException(
                    "Эта проверка уже использована. Обновите страницу и отправьте форму ещё раз");
        }
    }

    private boolean meetsDifficulty(String challenge, String solution) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256")
                    .digest((challenge + ":" + solution).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
        return leadingZeroBits(hash) >= bits;
    }

    static int leadingZeroBits(byte[] hash) {
        int count = 0;
        for (byte b : hash) {
            if (b == 0) {
                count += 8;
                continue;
            }
            count += Integer.numberOfLeadingZeros(b & 0xff) - 24;
            break;
        }
        return count;
    }

    private void forgetExpired() {
        long now = System.currentTimeMillis() / 1000;
        used.values().removeIf(entry -> entry.expires() < now);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC недоступен", e);
        }
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }

    private static IllegalArgumentException stale() {
        return new IllegalArgumentException(
                "Проверка устарела. Обновите страницу и отправьте форму ещё раз");
    }

    /**
     * Задание браузеру.
     *
     * @param bits сколько нулевых битов должно быть в начале хеша
     */
    public record Issued(String challenge, int bits) {
    }

    private record Used(String registrationKey, long expires) {
    }
}
