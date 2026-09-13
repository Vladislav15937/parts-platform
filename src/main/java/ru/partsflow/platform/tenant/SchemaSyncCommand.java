package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Вход в режим «привести схемы к версии этого артефакта и выйти».
 *
 * <pre>
 *   java -jar app.jar --schema-sync              # привести к версии образа
 *   java -jar app.jar --schema-sync --check      # только сказать, кто не на версии
 *   java -jar app.jar --schema-sync --to=132     # опустить до версии 132
 * </pre>
 *
 * <p><b>Веб-слой не поднимается.</b> Режим, слушающий порт, отобрал бы его
 * у работающего приложения на той же машине — а запускают этот контейнер
 * именно рядом с работающим. Наружу режим отдаёт код возврата: ноль — все
 * схемы на целевой версии, ненулевой — нет, и тогда в логе стоит список
 * с именами схем.
 *
 * <p><b>Разбор аргументов свой, а не через {@code ApplicationArguments}.</b>
 * Решение «этот запуск не приложение» принимается до того, как поднялся
 * хоть какой-то контекст: контекстов тут два разных, и выбирает между ними
 * {@code main}.
 */
public final class SchemaSyncCommand {

    private static final Logger log = LoggerFactory.getLogger(SchemaSyncCommand.class);

    /** Флаг режима. Его отсутствие означает обычный запуск приложения. */
    static final String FLAG = "--schema-sync";

    private static final String CHECK = "--check";
    private static final String TO = "--to=";

    private SchemaSyncCommand() {
    }

    public static boolean requested(String[] args) {
        for (String arg : args) {
            if (FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** @return код возврата процесса */
    public static int run(String[] args) {
        SchemaSync.Plan plan;
        try {
            plan = planOf(args);
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            return 1;
        }

        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(SchemaSyncConfig.class)
                             .web(WebApplicationType.NONE)
                             .bannerMode(Banner.Mode.OFF)
                             .run(args)) {

            return context.getBean(SchemaSync.class).run(plan).exitCode();
        } catch (RuntimeException e) {
            // Не дать стеку стать единственным ответом: шаг выкладки читает
            // код возврата, а человек — строку. Причина лежит в самом
            // глубоком исключении, обёртка Spring несёт мимо дела.
            log.error("Привести схемы не вышло: {}", TenantMigrations.rootMessage(e), e);
            return 1;
        }
    }

    static SchemaSync.Plan planOf(String[] args) {
        SchemaSync.Mode mode = SchemaSync.Mode.APPLY;
        Integer target = null;

        for (String arg : args) {
            if (FLAG.equals(arg)) {
                continue;
            }
            if (CHECK.equals(arg)) {
                mode = SchemaSync.Mode.CHECK;
            } else if (arg.startsWith(TO)) {
                target = versionOf(arg.substring(TO.length()));
            } else if (arg.startsWith("--") && !arg.contains("=")) {
                // Молча проглоченная опечатка в «--chek» означала бы накат
                // там, где просили проверку. Всё с «=» пропускаем: это
                // настройка Spring, и запрещать её здесь значит отнять
                // у оператора единственный способ указать чужую базу.
                throw new IllegalArgumentException(
                        "Непонятный аргумент " + arg + ". Годятся " + CHECK + " и " + TO + "<число>");
            }
        }
        return new SchemaSync.Plan(mode, target);
    }

    private static int versionOf(String raw) {
        try {
            int version = Integer.parseInt(raw.strip());
            if (version < 0) {
                throw new NumberFormatException(raw);
            }
            return version;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Версия в " + TO + " — число changeset'ов набора, а не «" + raw + "»");
        }
    }
}
