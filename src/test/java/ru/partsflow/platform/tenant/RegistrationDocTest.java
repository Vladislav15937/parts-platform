package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Числа регистрации компании записаны в четырёх местах и обязаны сходиться
 * (задача 0184): умолчание в {@code application.yml}, запасное значение
 * в {@code @Value}, умолчание в {@code docker-compose.prod.yml} и таблица
 * в {@code docs/deployment.md}.
 *
 * <p><b>Зачем.</b> Ровно на этом потеряли задачу 0183: текст отказа советовал
 * поднять предел переменной, которую compose в приложение не передавал, —
 * совет был невыполним, и узнали об этом по ячейке, а не по тесту. Для
 * регистрации тот же совет стоит в отказе «временно закрыта» и в тревоге
 * {@code ЯчейкаЗаполняется}, а потолок ещё и работает выключателем (нуль).
 * Разойдись любое из четырёх чисел — оператор прочитал бы в документе одно,
 * а на ячейке работало бы другое.
 *
 * <p>Сверка на равенство без толкования смысла и без Spring: тест читает
 * файлы, как {@link ProvisioningDocTest}.
 */
class RegistrationDocTest {

    private static final Path YAML = Path.of("src/main/resources/application.yml");
    private static final Path COMPOSE = Path.of("docker-compose.prod.yml");
    private static final Path DEPLOYMENT = Path.of("docs/deployment.md");
    private static final Path LIMITS =
            Path.of("src/main/java/ru/partsflow/platform/tenant/RegistrationLimits.java");
    private static final Path CHALLENGE =
            Path.of("src/main/java/ru/partsflow/platform/tenant/RegistrationChallenge.java");

    @Test
    @DisplayName("Частота: yml, @Value, compose и документ — одно число")
    void maxPerHourAgrees() throws IOException {
        agrees("registration-max-per-hour", "app.registration-max-per-hour",
                "APP_REGISTRATION_MAX_PER_HOUR", LIMITS);
    }

    @Test
    @DisplayName("Потолок: yml, @Value, compose и документ — одно число")
    void maxTenantsAgrees() throws IOException {
        agrees("registration-max-tenants", "app.registration-max-tenants",
                "APP_REGISTRATION_MAX_TENANTS", LIMITS);
    }

    @Test
    @DisplayName("Сложность проверки: yml, @Value, compose и документ — одно число")
    void powBitsAgrees() throws IOException {
        agrees("registration-pow-bits", "app.registration-pow-bits",
                "APP_REGISTRATION_POW_BITS", CHALLENGE);
    }

    private static void agrees(String yamlKey, String property, String env, Path javaFile)
            throws IOException {
        String expected = find("\\b" + Pattern.quote(yamlKey) + ":\\s*\\$\\{" + Pattern.quote(env)
                + ":(\\d+)}", read(YAML), "application.yml: «" + yamlKey + ": ${" + env + ":N}»");

        assertThat(find("\\$\\{" + Pattern.quote(property) + ":(\\d+)}", read(javaFile),
                "@Value ${" + property + ":N} в " + javaFile.getFileName()))
                .as("запасное значение @Value разошлось с application.yml")
                .isEqualTo(expected);

        // Без этой строки заданное в .env до приложения не доезжает вовсе —
        // и совет «поднимите переменную» из текста отказа невыполним.
        assertThat(find("\\b" + Pattern.quote(env) + ":\\s*\\$\\{" + Pattern.quote(env)
                + ":-(\\d+)}", read(COMPOSE), "docker-compose.prod.yml: «" + env + ": ${" + env
                + ":-N}»"))
                .as("умолчание в docker-compose.prod.yml разошлось с application.yml")
                .isEqualTo(expected);

        assertThat(find("`" + Pattern.quote(env) + "`\\s*\\|\\s*`(\\d+)`", read(DEPLOYMENT),
                "docs/deployment.md: строка таблицы «`" + env + "` | `N`»"))
                .as("число в таблице docs/deployment.md разошлось с application.yml")
                .isEqualTo(expected);
    }

    private static String find(String regex, String text, String what) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as("не найдено — %s", what).isTrue();
        return m.group(1);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
