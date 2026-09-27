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
 * Числа пределов провижининга записаны в трёх местах и обязаны сходиться.
 *
 * <p><b>Зачем.</b> До 27 сентября 2026 раздел 2 {@code docs/onboarding.md}
 * утверждал, что «ограничений частоты и числа схем нет вовсе» — при работающих
 * пределах задачи 0183. Фраза дожила до слияния потому, что <b>документы
 * не читает ни один тест</b>: код и документ связывало только то, что их
 * писали в разные дни. Цена не в неточности текста: инструкцию открывают
 * в день подключения клиента, и оператор, упёршийся в 429 на шаге, про который
 * написано «ограничивать нечему», читает предел как поломку сервера.
 *
 * <p><b>Что именно проверяется — и почему именно это.</b> Тест связывает
 * <i>значения</i>: умолчание в {@code application.yml}, запасное значение
 * в {@code @Value} у {@link ProvisioningLimits} и число, напечатанное
 * в инструкции. Это сверка на равенство, без толкования смысла: разошлось
 * число — красное, и сказано, какие два места разошлись.
 *
 * <p><b>Чего он НЕ проверяет, и это названо намеренно.</b> Он не ищет
 * запрещённых оборотов вида «ограничений нет вовсе». Такой чёрный список
 * ловил бы одну формулировку из многих (перефразированное утверждение
 * прошло бы молча) и стал бы вторым местом, решающим общую задачу про
 * расхождение отрицаний с кодом ({@code tasks/0188}) — а сторож, краснеющий
 * на каждом отрицании, по её же доводу будет отключён в первый день.
 * Здесь проверяется <b>присутствие</b> того, что обязано быть сказано,
 * а не отсутствие того, чего говорить нельзя. Этого хватает на возврат
 * дефекта: в прежней редакции раздела не было ни одного из этих чисел
 * и ни одного имени настройки, то есть возврат текста к версии {@code main}
 * валит тест.
 *
 * <p>Тест намеренно без Spring: он читает файлы и не поднимает контекст,
 * не занимает схему арендатора и не нуждается в базе. Прецедент чтения
 * файлов тестом — {@code WordingConsistencyTest}.
 */
class ProvisioningDocTest {

    /** Пути от корня репозитория: тесты Maven запускает из него. */
    private static final Path YAML = Path.of("src/main/resources/application.yml");
    private static final Path LIMITS =
            Path.of("src/main/java/ru/partsflow/platform/tenant/ProvisioningLimits.java");
    private static final Path ONBOARDING = Path.of("docs/onboarding.md");

    @Test
    @DisplayName("Умолчание частоты: application.yml, @Value и инструкция — одно число")
    void maxPerHourAgrees() throws IOException {
        String expected = yamlDefault("provisioning-max-per-hour", "APP_PROVISIONING_MAX_PER_HOUR");

        assertThat(valueFallback("app.provisioning-max-per-hour"))
                .as("умолчание @Value в ProvisioningLimits разошлось с application.yml")
                .isEqualTo(expected);

        String doc = read(ONBOARDING);
        assertThat(doc)
                .as("предел частоты (%s в час) в инструкции подключения не назван "
                        + "числом — оператор, получивший 429, узнаёт предел только "
                        + "из текста отказа", expected)
                .contains("**" + expected + "**");
        assertThat(doc)
                .as("инструкция не называет настройку, которой поднимается предел "
                        + "частоты, — а текст отказа советует её поднять")
                .contains("APP_PROVISIONING_MAX_PER_HOUR");
    }

    @Test
    @DisplayName("Умолчание потолка схем: application.yml, @Value и инструкция — одно число")
    void maxTenantsAgrees() throws IOException {
        String expected = yamlDefault("provisioning-max-tenants", "APP_PROVISIONING_MAX_TENANTS");

        assertThat(valueFallback("app.provisioning-max-tenants"))
                .as("умолчание @Value в ProvisioningLimits разошлось с application.yml")
                .isEqualTo(expected);

        String doc = read(ONBOARDING);
        assertThat(doc)
                .as("потолок числа схем (%s на ячейку) в инструкции подключения "
                        + "не назван числом — по нему решают, заводить здесь "
                        + "или поднимать следующую ячейку", expected)
                .contains("**" + expected + "**");
        assertThat(doc)
                .as("инструкция не называет настройку, которой задаётся потолок схем")
                .contains("APP_PROVISIONING_MAX_TENANTS");
    }

    @Test
    @DisplayName("Инструкция называет оба кода отказа и Retry-After из длины окна")
    void refusalsAreNamed() throws IOException {
        String doc = read(ONBOARDING);

        assertThat(doc)
                .as("инструкция не называет 429 — отказ по частоте оператор "
                        + "прочитает как поломку сервера")
                .contains("429");
        assertThat(doc)
                .as("инструкция не называет 409 — отказ заполненной ячейки")
                .contains("409");

        // Retry-After берётся из ProvisioningLimits.WINDOW, и число в инструкции
        // обязано считаться из той же длины окна: разойдясь, документ советовал бы
        // ждать не столько, сколько предел держит.
        Matcher window = Pattern.compile("WINDOW\\s*=\\s*Duration\\.ofHours\\((\\d+)\\)")
                .matcher(read(LIMITS));
        assertThat(window.find())
                .as("в ProvisioningLimits не найдено WINDOW = Duration.ofHours(N)")
                .isTrue();
        String retryAfter = String.valueOf(Integer.parseInt(window.group(1)) * 3600);

        assertThat(doc)
                .as("инструкция не называет Retry-After (%s секунд) — оператор "
                        + "не поймёт, сколько ждать после 429", retryAfter)
                .contains(retryAfter);
    }

    /** Умолчание из application.yml вида {@code ключ: ${ПЕРЕМЕННАЯ:20}}. */
    private static String yamlDefault(String key, String env) throws IOException {
        Matcher m = Pattern.compile(Pattern.quote(key) + ":\\s*\\$\\{" + Pattern.quote(env)
                        + ":(\\d+)}")
                .matcher(read(YAML));
        assertThat(m.find())
                .as("в application.yml не найдено умолчание «%s: ${%s:N}»", key, env)
                .isTrue();
        return m.group(1);
    }

    /** Запасное значение из {@code @Value("${ключ:20}")}. */
    private static String valueFallback(String key) throws IOException {
        Matcher m = Pattern.compile("\\$\\{" + Pattern.quote(key) + ":(\\d+)}")
                .matcher(read(LIMITS));
        assertThat(m.find())
                .as("в ProvisioningLimits не найдено ${%s:N}", key)
                .isTrue();
        return m.group(1);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
