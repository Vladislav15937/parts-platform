package ru.partsflow.oneshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.partsflow.inventory.OemRecount;
import ru.partsflow.platform.tenant.TenantMigrations;
import ru.partsflow.platform.tenant.TenantSweep;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Вход в режим «разовая правка данных по реестру арендаторов и выйти».
 *
 * <pre>
 *   java -jar app.jar --one-shot=oem-recount                     # проверка: ничего не пишет, печатает отчёт
 *   java -jar app.jar --one-shot=oem-recount --apply             # пересчитать
 *   java -jar app.jar --one-shot=oem-recount --tenant=t_000042   # одна схема
 *   java -jar app.jar --one-shot=oem-recount --apply --feed-marks   # и отметки для площадки
 * </pre>
 *
 * <p><b>Проверка — умолчание, правка — явным {@code --apply}</b> (задача 0317).
 * Правка необратима иначе как отчётом или бэкапом, и запуск без ключа обязан
 * быть безопасным: человек, набравший команду не до конца, получает отчёт,
 * а не изменённый склад.
 *
 * <p>Тем же устройством, что {@code SchemaSyncCommand}: развилка в {@code main},
 * свой маленький контекст ({@link OneShotConfig}) без веб-слоя и без
 * планировщиков — контейнер разовой правки не должен ни занимать порт
 * работающего приложения, ни переливать outbox и слать дельты.
 *
 * <p>Работы перечислены здесь списком, а не найдены сканированием: вторая
 * (0316) дописывается строкой в {@link #JOBS}.
 */
public final class OneShotCommand {

    private static final Logger log = LoggerFactory.getLogger(OneShotCommand.class);

    static final String FLAG = "--one-shot=";
    private static final String APPLY = "--apply";
    private static final String DRY_RUN = "--dry-run";
    private static final String TENANT = "--tenant=";
    private static final String FEED_MARKS = "--feed-marks";

    /** Разобранная команда. */
    record Plan(String job, boolean apply, String tenant, boolean feedMarks) {
    }

    /** Работа → как её выполнить в поднятом контексте; ответ — код возврата. */
    private static final Map<String, BiFunction<ConfigurableApplicationContext, Plan, Integer>> JOBS = Map.of(
            OemRecount.JOB, (context, plan) -> exitCode(context.getBean(OemRecount.class)
                    .run(new OemRecount.Options(plan.apply(), plan.tenant(), plan.feedMarks()))));

    private OneShotCommand() {
    }

    public static boolean requested(String[] args) {
        for (String arg : args) {
            if (arg.startsWith(FLAG)) {
                return true;
            }
        }
        return false;
    }

    /** @return код возврата процесса */
    public static int run(String[] args) {
        Plan plan;
        try {
            plan = planOf(args);
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            return 2;
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(OneShotConfig.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args)) {
            return JOBS.get(plan.job()).apply(context, plan);
        } catch (RuntimeException e) {
            log.error("Разовый запуск {} не выполнен: {}", plan.job(), TenantMigrations.rootMessage(e), e);
            return 1;
        }
    }

    static Plan planOf(String[] args) {
        String job = null;
        boolean apply = false;
        boolean dryRun = false;
        String tenant = null;
        boolean feedMarks = false;
        for (String arg : args) {
            if (arg.startsWith(FLAG)) {
                job = arg.substring(FLAG.length()).strip();
            } else if (APPLY.equals(arg)) {
                apply = true;
            } else if (DRY_RUN.equals(arg)) {
                dryRun = true;
            } else if (arg.startsWith(TENANT)) {
                tenant = arg.substring(TENANT.length()).strip();
            } else if (FEED_MARKS.equals(arg)) {
                // Отметки для площадки — только явно: тысячи дельт Дрому разом
                // не уходят, пока человек не попросил (решение до слова владельца).
                feedMarks = true;
            } else if (arg.startsWith("--") && !arg.contains("=")) {
                // Опечатка в «--aply» молча означала бы проверку там, где просили
                // правку, — безопасно, но человек ушёл бы, считая склад пересчитанным.
                // С «=» пропускаем: это настройка Spring (адрес базы и т. п.).
                throw new IllegalArgumentException("Непонятный аргумент " + arg + ". Годятся "
                        + String.join(", ", List.of(APPLY, DRY_RUN, TENANT + "<схема>", FEED_MARKS)));
            }
        }
        if (job == null || !JOBS.containsKey(job)) {
            throw new IllegalArgumentException("Неизвестная разовая работа «" + job + "». Есть: "
                    + String.join(", ", JOBS.keySet()));
        }
        if (apply && dryRun) {
            throw new IllegalArgumentException("Названы и " + APPLY + ", и " + DRY_RUN + " — выберите одно");
        }
        if (tenant != null && tenant.isEmpty()) {
            throw new IllegalArgumentException(TENANT + " без схемы");
        }
        return new Plan(job, apply, tenant, feedMarks);
    }

    /** Ноль — у всех арендаторов работа сделана (или нечего было делать). */
    static int exitCode(List<TenantSweep.Outcome<OemRecount.Tally>> outcomes) {
        boolean failed = outcomes.stream().anyMatch(o -> o.problem() != null
                || o.result().busy > 0 || o.result().cycles > 0);
        return failed ? 1 : 0;
    }
}
