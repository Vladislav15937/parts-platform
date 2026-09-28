package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Скрипт ячейки советует человеку тот путь наката, который выполним ТАМ,
 * где совет напечатан.
 *
 * <p><b>Зачем.</b> {@code ops/migrate-tenants.sh} ходит в управляющий контур
 * по HTTP и требует {@code APP_PROVISIONING_TOKEN} безусловно — падает
 * на пустом секрете раньше, чем успевает что-нибудь спросить, — а правило
 * подключения ({@code docs/onboarding.md}, раздел 2) велит секрет выключать
 * сразу после заведения клиента. Значит на правильно настроенной ячейке такой
 * совет невыполним. У готовности и у сообщений старта это закрыто задачей 0204,
 * у двух ops-скриптов — задачей 0210, и оба места читает человек в тот день,
 * когда ему не до разбирательств: {@code create-roles.sh} — на шаге 4 «Как
 * поднять», где рабочая роль ещё не дотягивается до схем арендаторов,
 * {@code restore-cell.sh} — последними строками восстановления ячейки, которое
 * проходят в аварию и с одной попытки.
 *
 * <p><b>Почему сторож здесь, а не в самом шелле.</b> Напечатанное
 * {@code create-roles.sh} проверяет и его собственная самопроверка
 * (случай 10) — это сильнее, потому что смотрит на живой вывод. А у
 * {@code restore-cell.sh} самопроверки нет: до строки «Дальше:» он
 * разворачивает дампы через docker, то есть в прогоне её не получить.
 * Поэтому текст его советов сверяется статически, и заодно оба скрипта
 * привязываются к {@link TenantMigrations#MIGRATE_COMMAND} — единственному
 * месту, знающему команду. Шелл прочитать её оттуда не может, значит копия
 * словами неизбежна; неизбежной копии нужен сторож, иначе третий раз тот же
 * совет уедет в новые файлы (первые два — 0202 и 0204).
 *
 * <p>Тест на стороне сервера по тому же доводу, что у
 * {@code WordingConsistencyTest}: проверка нужна на каждой правке команды,
 * с какой бы стороны её ни сделали.
 */
class MigrateCommandAdviceTest {

    private static final Path OPS = Path.of("ops");

    /**
     * Скрипты, чей совет читает оператор ячейки с выключенным секретом.
     * Не все ops-скрипты подряд: {@code ops/schema-sync.sh} и
     * {@code ops/deploy-checks.sh} говорят про управляющий контур
     * в прошедшем времени, разбирая ровно эту ловушку, — сторож, красный
     * на историческом разборе, был бы выключен первым.
     */
    private static final List<String> ADVISING = List.of("create-roles.sh", "restore-cell.sh");

    @Test
    @DisplayName("Скрипт советует ту же команду, что готовность и старт")
    void adviceMatchesTheSinglePlace() throws IOException {
        for (String name : ADVISING) {
            assertThat(printedLines(OPS.resolve(name)))
                    .as("ops/%s не называет человеку команду «%s» — а приводить схемы "
                            + "к версии образа и выдавать права рабочей роли больше нечем: "
                            + "путь через управляющий контур требует включённого секрета "
                            + "провижининга, который правило подключения велит выключать",
                            name, TenantMigrations.MIGRATE_COMMAND)
                    .anySatisfy(line -> assertThat(line).contains(TenantMigrations.MIGRATE_COMMAND));
        }
    }

    @Test
    @DisplayName("Путь через управляющий контур этим скриптам не советуется")
    void controlPlanePathIsNotAdvised() throws IOException {
        for (String name : ADVISING) {
            assertThat(printedLines(OPS.resolve(name)))
                    .as("ops/%s снова печатает человеку ops/migrate-tenants.sh: тот требует "
                            + "APP_PROVISIONING_TOKEN безусловно и падает до первого запроса, "
                            + "то есть совет невыполним ровно на ячейке, выполнившей правило "
                            + "«секрет выключить сразу после подключения»", name)
                    .noneMatch(line -> line.contains("migrate-tenants"));
        }
    }

    /**
     * Строки, которые скрипт <b>печатает</b> человеку.
     *
     * <p>Комментарии не считаются, и это не упрощение: объяснение «почему
     * здесь не {@code ops/migrate-tenants.sh}» законно называет тот скрипт
     * по имени и стоит в этих же файлах рядом с советом — сторож, читающий
     * весь текст, краснел бы на собственном обосновании. Ровно та же граница,
     * что у сторожей задачи 0204: они смотрят на сообщение ответа, а не
     * на исходник.
     */
    private static List<String> printedLines(Path script) throws IOException {
        List<String> printed = new ArrayList<>();
        for (String line : Files.readAllLines(script)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("echo ") || trimmed.startsWith("printf ")) {
                printed.add(trimmed);
            }
        }
        assertThat(printed)
                .as("в %s не нашлось ни одной печатающей строки — сторож смотрит "
                        + "не туда и молчал бы на любом совете", script)
                .isNotEmpty();
        return printed;
    }
}
