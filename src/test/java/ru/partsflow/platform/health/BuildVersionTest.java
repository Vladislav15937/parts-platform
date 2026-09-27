package ru.partsflow.platform.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор сведений о сборке: оба состояния и оба честно (задача 0130).
 *
 * <p>Без Spring намеренно: здесь проверяется, что именно приложение
 * <b>говорит</b> про свою версию, а не как оно её отдаёт. Отдачу по HTTP
 * (ответ наружу — record, обычный класс Jackson не сериализует вовсе)
 * проверяет {@link BuildVersionHttpTest}.
 *
 * <p>Состояний два, и второе — не экзотика: местная сборка
 * ({@code ./mvnw package}) образом не является, SHA ей взять негде.
 * Ровно то же состояние даёт образ, собранный без
 * {@code --build-arg APP_VERSION}, и на нём шаг выкладки обязан краснеть:
 * сверить «подняли то, что выкладывали» нечем.
 */
class BuildVersionTest {

    private static final String SHA = "3f45f4d757ba39bd1ec10b8adcfb891f63e1448f";

    private static BuildProperties with(String sha, Instant time) {
        Properties p = new Properties();
        p.setProperty("group", "ru.partsflow");
        p.setProperty("artifact", "parts-platform");
        p.setProperty("version", "0.1.0-SNAPSHOT");
        if (sha != null) {
            p.setProperty("sha", sha);
        }
        if (time != null) {
            p.setProperty("time", String.valueOf(time.toEpochMilli()));
        }
        return new BuildProperties(p);
    }

    @Test
    @DisplayName("Собранный с SHA образ называет версию целиком и коротко")
    void namedVersion() {
        BuildVersion.Version version = BuildVersion.read(
                with(SHA, Instant.parse("2026-09-27T10:00:00Z")));

        assertThat(version.named())
                .as("образ с переданным SHA объявлен безымянным: шаг выкладки "
                        + "прочитает это как дефект сборки и покраснеет "
                        + "на исправном образе")
                .isTrue();
        assertThat(version.sha())
                .as("SHA целиком — то, с чем сверяют тег выкладки")
                .isEqualTo(SHA);
        assertThat(version.shortSha())
                .as("короткий SHA той же длины, что в имени копии базы "
                        + "у выкладки (parts_green_68a6d90b2720): человек "
                        + "сверяет эти строки рядом")
                .isEqualTo("3f45f4d757ba")
                .hasSize(BuildVersion.SHORT);
        assertThat(version.builtAt())
                .as("время сборки не названо: «что стоит» и «когда собрано» — "
                        + "два разных вопроса в разборе аварии")
                .isEqualTo("2026-09-27T10:00:00Z");
        assertThat(version.detail()).contains(SHA).contains("2026-09-27T10:00:00Z");
    }

    @Test
    @DisplayName("Пустой и незаданный SHA — одно и то же «версия не названа»")
    void blankAndMissingAreBothUnnamed() {
        // Пустая строка приезжает от `docker build` без --build-arg
        // (умолчание ARG в Dockerfile), отсутствие свойства — от сборки,
        // которая build-info не гоняла вовсе. Пробелы — от правки руками.
        for (String sha : new String[]{null, "", "   "}) {
            BuildVersion.Version version = BuildVersion.read(with(sha, Instant.now()));

            assertThat(version.named())
                    .as("SHA %s объявлен версией: приложение выдаёт за версию то, "
                            + "чего у него нет", sha == null ? "null" : "«" + sha + "»")
                    .isFalse();
            assertThat(version.sha()).isEmpty();
            assertThat(version.detail())
                    .as("не сказано ни что версии нет, ни почему: человек "
                            + "в разборе аварии прочитает пустое поле как поломку "
                            + "признака, а не как местную сборку")
                    .contains("НЕ НАЗВАНА")
                    .contains("APP_VERSION");
        }
    }

    @Test
    @DisplayName("Не-SHA версией не считается, и пришедшее названо дословно")
    void anythingButAShaIsNotAVersion() {
        // Заглушка из pom (сборка без --build-arg), имя ветки, незаменённая
        // подстановка — проверяется ФОРМА, потому что иначе заглушку
        // пришлось бы знать и pom, и Java, а два места с одним значением
        // расходятся молча.
        for (String sha : new String[]{"no-build-arg", "main", "${APP_VERSION}", "0.1.0"}) {
            BuildVersion.Version version = BuildVersion.read(with(sha, null));

            assertThat(version.named())
                    .as("«%s» принято за версию: приложение назовёт версией то, "
                            + "по чему коммит не найти, а шаг выкладки сверит "
                            + "мусор с мусором", sha)
                    .isFalse();
            assertThat(version.detail())
                    .as("пришедшее не названо дословно: «версии нет» и «версия "
                            + "названа невесть чем» чинят по-разному")
                    .contains(sha);
        }
    }

    @Test
    @DisplayName("Сведений о сборке нет вовсе — ответ тот же и без стека")
    void noBuildPropertiesAtAll() {
        // Бина BuildProperties нет, если артефакт собран мимо build-info:
        // запуск из IDE по target/classes, чужая сборка. Падать здесь
        // нельзя — это ответ готовности, его спрашивает docker.
        BuildVersion.Version version = BuildVersion.read(null);

        assertThat(version.named()).isFalse();
        assertThat(version.builtAt())
                .as("время сборки выдумано при отсутствующих сведениях о сборке")
                .isNull();
        assertThat(version.detail()).contains("НЕ НАЗВАНА");
    }

    @Test
    @DisplayName("Короткий SHA не длиннее самого SHA")
    void shortShaNeverExceedsTheSha() {
        // Тег правят руками (и на ячейке, и в .env), поэтому строка короче
        // двенадцати знаков достижима, а substring на ней бросил бы
        // исключение из ответа готовности.
        BuildVersion.Version version = BuildVersion.read(with("3f45f4d", null));

        assertThat(version.named()).isTrue();
        assertThat(version.shortSha()).isEqualTo("3f45f4d");
    }
}
