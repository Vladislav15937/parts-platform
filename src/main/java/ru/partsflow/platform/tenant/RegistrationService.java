package ru.partsflow.platform.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Самостоятельная регистрация компании: клиент заводит себя сам (задача 0184).
 *
 * <p>Это открывает наружу путь, закрытый намеренно: до этого схему заводил
 * только оператор под секретом. Вся сложность здесь, а не в форме, и порядок
 * шагов не случаен.
 *
 * <ol>
 *   <li><b>Поля</b> — до всего остального, словами и без траты чего бы то ни
 *       было: плохой код компании не должен стоить ни вычисления, ни места
 *       в пределе.</li>
 *   <li><b>Вычислительная преграда</b> ({@link RegistrationChallenge}).</li>
 *   <li><b>Повтор</b> — по ключу клиента. Известный ключ возвращает результат
 *       первого раза и не тратит ни место в окне, ни потолок: двойное нажатие
 *       и «ответ не дошёл, нажал снова» не должны наказываться отказом
 *       «слишком часто» или «закрыто».</li>
 *   <li><b>Пределы</b> ({@link RegistrationLimits}).</li>
 *   <li><b>Заведение</b> — {@link TenantProvisioning}; почту и ключ занимает
 *       та же вставка, что номер и код, арбитром служат уникальные индексы.</li>
 * </ol>
 *
 * <p><b>Одновременный повтор</b> проходит шаг 3 вхолостую (первого ещё нет в
 * реестре) и разводится на вставке: проигравший получает
 * {@link TenantProvisioning.ReplayedRegistration}, возвращает занятое место и
 * <b>ждёт результата первого</b>. «Ждёт» потому, что накат схемы занимает
 * секунды, а запись первого ещё в {@code PROVISIONING}; вернуть здесь ошибку
 * значило бы показать человеку красное на регистрации, которая удалась.
 *
 * <p><b>Подтверждения почты нет</b> — решение владельца от 11 октября 2026:
 * компания работает сразу, а подтверждение подключат отдельной задачей, когда
 * появится ключ оператора писем. Почта хранится так, чтобы оно встало без
 * переноса данных ({@code tenant_registry.owner_email}); письма отсюда не
 * уходят вовсе.
 */
@Service
public class RegistrationService {

    /** Достаточно для адреса из людей и серверов, а не для проверки доставки. */
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+");

    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}");

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** Сколько ждать результата первого запроса при одновременном повторе. */
    static final Duration WAIT = Duration.ofSeconds(90);

    private static final long POLL_MILLIS = 250;

    private final TenantProvisioning provisioning;
    private final RegistrationLimits limits;
    private final RegistrationChallenge challenge;
    private final JdbcTemplate jdbc;

    public RegistrationService(TenantProvisioning provisioning, RegistrationLimits limits,
                               RegistrationChallenge challenge, JdbcTemplate jdbc) {
        this.provisioning = provisioning;
        this.limits = limits;
        this.challenge = challenge;
        this.jdbc = jdbc;
    }

    public Registered register(Form form, String address) {
        Checked checked = check(form);
        String key = checked.registrationKey();

        challenge.verifyAndUse(form.challenge(), form.solution(), key);

        Existing first = existing(key);
        if (first != null) {
            return awaitFirst(key, first);
        }

        limits.beforeRegister(address);

        TenantProvisioning.Result created;
        try {
            created = provisioning.provision(
                    new TenantProvisioning.Request(checked.code(), checked.companyName(),
                            checked.login(), form.ownerPassword(), checked.ownerName()),
                    new TenantProvisioning.Registration(
                            checked.email(), checked.emailKey(), key));
        } catch (TenantProvisioning.ReplayedRegistration replay) {
            limits.replayed(address);
            return awaitFirst(key, existing(key));
        }

        limits.registered(address);
        return new Registered(created.companyCode(), checked.companyName(), false);
    }

    /**
     * Результат первого запроса с этим ключом.
     *
     * <p>Если запись ещё в {@code PROVISIONING}, первый идёт прямо сейчас —
     * ждём. Если ключа не стало, первый сорвался и отпустил его: сказать об
     * этом надо словами, повторять за него мы не вправе — у нас нет его пароля
     * в этом запросе, а нажать «зарегистрировать» ещё раз человеку дёшево.
     */
    private Registered awaitFirst(String key, Existing current) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        Existing row = current;
        while (true) {
            if (row == null) {
                throw new IllegalStateException(
                        "Прошлая попытка зарегистрировать эту компанию не удалась. "
                                + "Нажмите «Зарегистрировать» ещё раз");
            }
            if (!row.provisioning()) {
                return new Registered(row.code(), row.companyName(), true);
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Регистрация этой компании ещё идёт. Подождите минуту и нажмите "
                                + "«Зарегистрировать» ещё раз — повтор безопасен");
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание регистрации прервано", e);
            }
            row = existing(key);
        }
    }

    private Existing existing(String key) {
        List<Existing> found = jdbc.query("""
                SELECT code, company_name, status
                  FROM public.tenant_registry WHERE registration_key = ?""",
                (rs, n) -> new Existing(rs.getString("code"), rs.getString("company_name"),
                        "PROVISIONING".equals(rs.getString("status"))),
                key);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Поля формы: словами, до траты чего бы то ни было. */
    private Checked check(Form form) {
        if (form == null) {
            throw new IllegalArgumentException("Форма пустая");
        }
        String key = form.requestId() == null ? "" : form.requestId().strip();
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Не передан ключ запроса. Обновите страницу и отправьте форму ещё раз");
        }

        String companyName = strip(form.companyName());
        if (companyName.isEmpty()) {
            throw new IllegalArgumentException("Укажите название компании");
        }
        if (companyName.length() > 120) {
            throw new IllegalArgumentException("Название компании — не длиннее 120 символов");
        }

        String code = form.companyCode() == null
                ? "" : form.companyCode().strip().toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Код компании — от 2 до 31 символа: латиница, цифры и дефис, "
                            + "начинается с буквы или цифры");
        }

        String email = strip(form.email());
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException(
                    "Укажите почту так, как она пишется: имя@домен.ру");
        }
        // Приведение — здесь, а не в базе: триггеров и генерируемых колонок
        // в схеме нет. Нижний регистр по правилам Unicode, а не локали
        // машины, — ключ обязан быть одним и тем же на любой ячейке.
        String emailKey = email.toLowerCase(Locale.ROOT);

        String login = strip(form.ownerLogin());
        if (login.isEmpty()) {
            throw new IllegalArgumentException("Укажите логин владельца");
        }
        if (login.length() > 64) {
            throw new IllegalArgumentException("Логин — не длиннее 64 символов");
        }

        String password = form.ownerPassword() == null ? "" : form.ownerPassword();
        if (password.length() < 8) {
            throw new IllegalArgumentException("Пароль — минимум 8 символов");
        }
        // Хеш считается по первым 72 байтам, и более длинный пароль либо
        // отбивается шифратором ошибкой, либо молча обрезается — человек
        // не узнает, что хвост не работает. Лучше сказать сразу.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException(
                    "Пароль слишком длинный: не больше 72 байт (русские буквы занимают по две — "
                            + "около 36 букв)");
        }

        String ownerName = strip(form.ownerName());
        if (ownerName.length() > 120) {
            throw new IllegalArgumentException("Имя — не длиннее 120 символов");
        }

        return new Checked(key, companyName, code, email, emailKey, login, ownerName);
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** Что пришло с формы. Пароль наружу не идёт и в лог не пишется. */
    public record Form(String requestId, String challenge, String solution,
                       String companyName, String companyCode, String email,
                       String ownerLogin, String ownerPassword, String ownerName) {
        @Override
        public String toString() {
            return "Form[requestId=%s, companyCode=%s]".formatted(requestId, companyCode);
        }
    }

    private record Checked(String registrationKey, String companyName, String code,
                           String email, String emailKey, String login, String ownerName) {
    }

    /**
     * Ответ человеку: код компании нужен ему для входа, имя — чтобы увидеть,
     * что заведено то, что он вводил. Ни номера схемы, ни номера в реестре:
     * внутреннее представление человеку не показывают.
     *
     * @param replayed это результат прежнего нажатия, а не новая компания
     */
    public record Registered(String companyCode, String companyName, boolean replayed) {
    }

    /** Запись реестра, найденная по ключу повтора. */
    private record Existing(String code, String companyName, boolean provisioning) {
    }
}
