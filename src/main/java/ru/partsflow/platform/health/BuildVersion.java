package ru.partsflow.platform.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Какая это версия — ответ самого работающего приложения (задача 0130).
 *
 * <p><b>Зачем.</b> «Что у вас стоит» спрашивают раньше, чем «что сломалось»,
 * а спросить было нечем: {@code /actuator/info} пуст, сведений о сборке
 * в образе не было вовсе, а {@code <version>} в {@code pom.xml} —
 * {@code 0.1.0-SNAPSHOT} и от сборки к сборке не меняется. Единственным
 * способом оставалось {@code docker image inspect … --format
 * '{{.Created}}'} на машине, то есть ответ про <b>файл на диске</b>,
 * а не про то, что обслуживает людей. А сборок в ячейке две
 * ({@code app-blue}, {@code app-green}), и вопрос давно звучит как «какая
 * версия работает <b>под трафиком</b>».
 *
 * <p><b>Ответ запечён в артефакт, а не взят из окружения, и это главное
 * решение.</b> SHA приезжает аргументом сборки Dockerfile
 * ({@code --build-arg APP_VERSION}) в {@code META-INF/build-info.properties}
 * внутри jar. Переменная окружения образа выглядела бы проще, но её задают
 * в compose из {@code .env} — то есть ответ снова стал бы пересказом файла,
 * а задача требует обратного: «проверка, которая читает `.env`
 * и пересказывает его же, подтвердит ложь, если в файл вписали не то».
 * Подменить запечённое можно только собрав другой артефакт.
 *
 * <p><b>Пустая версия — законное состояние, и врать про неё нельзя.</b>
 * Местная сборка ({@code ./mvnw package}) образом не является, SHA ей взять
 * негде — и тогда ответ говорит «версия не названа» вместо того, чтобы
 * выдать за версию номер из {@code pom.xml}. Это же состояние получается
 * у образа, собранного без аргумента, и шаг выкладки на нём краснеет:
 * сверить «подняли то, что выкладывали» нечем.
 *
 * <p><b>Считается один раз за процесс.</b> Changelog и сведения о сборке
 * лежат внутри артефакта и при жизни процесса не меняются — тот же довод,
 * по которому {@code TenantMigrations.expectedVersion()} перестал разбирать
 * changelog на каждый вызов: готовность спрашивают раз в несколько секунд.
 *
 * <p>Наружу версия не смотрит: отдаётся она в ответе
 * {@code /actuator/readiness}, а тот закрыт двумя способами — порт
 * приложения не публикуется, терминатор отдаёт на {@code /actuator} 404.
 * По той же причине выключен contributor {@code build}
 * у {@code /actuator/info} ({@code application.yml}): номер сборки,
 * доступный каждому вошедшему, — подсказка тому, кто ищет чужие дыры.
 */
@Component
public class BuildVersion {

    /**
     * Сколько знаков SHA называют вслух.
     *
     * <p>Двенадцать — столько же, сколько в имени копии базы у выкладки
     * ({@code parts_green_68a6d90b2720}): человек сверяет эти строки глазами
     * рядом, и разная длина заставляла бы его считать символы.
     */
    static final int SHORT = 12;

    /** Имя свойства в {@code build-info.properties} (см. {@code pom.xml}). */
    private static final String SHA = "sha";

    /**
     * Как выглядит SHA коммита — семь-сорок шестнадцатеричных знаков.
     *
     * <p>Проверяется <b>форма</b>, а не совпадение с заглушкой из
     * {@code pom.xml}, и это осознанно: иначе одно значение пришлось бы знать
     * двоим и они разошлись бы молча. Заодно так ловится всё, что приехало
     * вместо SHA, — имя ветки, незаменённая подстановка, короткий тег:
     * версией это не считается, а в ответе называется дословно.
     */
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{7,40}");

    private final Version version;

    public BuildVersion(ObjectProvider<BuildProperties> build) {
        // Лениво и через ObjectProvider: бина нет вовсе, если сведения
        // о сборке в артефакт не попали (сборка чужим способом, запуск
        // из IDE по target/classes без прогона generate-resources).
        this.version = read(build.getIfAvailable());
    }

    public Version version() {
        return version;
    }

    /**
     * Разбор сведений о сборке — отдельным статическим методом, чтобы его
     * можно было проверить на обоих состояниях без подъёма Spring.
     */
    static Version read(BuildProperties build) {
        String raw = build == null ? null : build.get(SHA);
        String sha = raw == null ? "" : raw.strip();
        String builtAt = build == null || build.getTime() == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(build.getTime());

        if (!COMMIT.matcher(sha).matches()) {
            return new Version(false, "", "", builtAt,
                    "Версия НЕ НАЗВАНА: образ собран без --build-arg APP_VERSION "
                            + "(Dockerfile, задача 0130)"
                            // Пришедшее называется дословно: «не названа»
                            // и «названа невесть чем» чинят по-разному.
                            + (sha.isEmpty() ? ""
                                    : ", в сведениях о сборке стоит «" + sha
                                            + "» — это не SHA коммита")
                            + (builtAt == null ? "" : ", артефакт собран " + builtAt)
                            + ". Сверить «подняли то, что выкладывали» нечем: "
                            + "у местной сборки SHA нет вовсе, а образу его "
                            + "передаёт сборка образа");
        }

        String shortSha = sha.length() <= SHORT ? sha : sha.substring(0, SHORT);
        return new Version(true, sha, shortSha, builtAt,
                "Версия %s (%s)".formatted(shortSha, sha)
                        + (builtAt == null ? "" : ", собран " + builtAt));
    }

    /**
     * @param named    названа ли версия. Машинный признак: по нему шаг
     *                 выкладки отличает «образ не назвал себя» (сборка без
     *                 аргумента — сверять нечем, и это дефект сборки)
     *                 от несовпадения версий (подняли не то). Договор
     *                 с {@code ops/deploy-checks.sh}, как и {@code known}
     *                 у {@link ReadinessEndpoint.Check}
     * @param sha      SHA коммита целиком: с ним сверяют тег выкладки
     * @param shortSha он же короткий — для человека и для журнала
     * @param builtAt  когда собран артефакт, ISO-8601 в UTC; {@code null},
     *                 если сведений о сборке нет вовсе
     * @param detail   то же словами — человеку, пришедшему разбираться
     */
    public record Version(boolean named, String sha, String shortSha, String builtAt,
                          String detail) {
    }
}
