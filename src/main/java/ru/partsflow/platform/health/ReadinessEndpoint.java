package ru.partsflow.platform.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.JournalProtection;
import ru.partsflow.platform.tenant.TenantMigrations;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Готово ли приложение обслуживать людей — машинно, а не по хвосту лога.
 *
 * <p>Порядок выкладки, заданный владельцем: «прогоняется миграция, база
 * поднялась без проблем, ошибок нет, само приложение встало штатно, всё
 * в порядке и только тогда можно убирать старую сборку». Третий шаг был
 * невыполним: устанавливать «встало штатно» было нечем, кроме человека,
 * вглядывающегося в лог.
 *
 * <p><b>«Порт отвечает» здесь не годится.</b> Приложение отвечает на порту
 * раньше, чем пригодно к работе, и все три состояния «поднялся, но работать
 * нельзя» в этом проекте уже случались: схемы арендаторов отстали (площадка
 * забрала пустой прайс и сняла объявления), запуск без переменных окружения
 * прошёл владельцем схем (журналы снова изменяемы), реестра Prometheus
 * не оказалось в зависимостях (метрики отдавались в никуда, а адрес отвечал
 * 401, как любой несуществующий). Выкладка, считающая такое приложение
 * здоровым, погасит старую сборку и покрасит себя зелёным.
 *
 * <p><b>Две поверхности, а не одна, и это главное решение здесь.</b>
 * {@code /actuator/health} остаётся мягким: контекст поднялся, база отвечает
 * — им проверяет живость docker, и он обязан быть зелёным в разработке, где
 * одна роль законна, а схема между накатами отстаёт. Строгий ответ живёт
 * здесь и нужен шагу выкладки. Смешать их значит либо не давать подняться
 * локально, либо выкладываться на непригодное приложение.
 *
 * <p><b>Отставшие схемы не валят запуск — и это не противоречие.</b>
 * «Не готов для выкладки» и «не запускаться» — разные вещи: разработчик
 * работает как раз между накатами, а падение на старте означало бы, что
 * локально ничего не поднять. Поэтому проверки при старте
 * ({@code SchemaVersionCheck}, {@code JournalProtectionCheck}) говорят
 * в лог, а тот же самый ответ отдаётся здесь машинно.
 *
 * <p><b>Схема впереди — норма, схема позади — нет.</b> Несимметричность
 * обязательна: между накатом и подъёмом новой сборки людей обслуживает
 * старая, и её схема к этой минуте уже впереди. Подробности —
 * в {@link TenantMigrations#lag()}.
 *
 * <p><b>Наружу не смотрит.</b> Терминатор отдаёт на {@code /actuator} 404,
 * а порт приложения не публикуется: ответ нужен docker'у и шагу выкладки
 * внутри сети. Учётной записи при этом не спрашивает — у docker'а её нет
 * и быть не может, как и у сборщика метрик.
 *
 * <p>Транзакция не нужна: все запросы идут в {@code public} — к реестру
 * и к правам, — а не в схему арендатора.
 */
@Component
@Endpoint(id = "readiness")
public class ReadinessEndpoint {

    /** Сколько отставших схем называть по имени: дальше — числом. */
    private static final int NAMED = 3;

    private final JdbcTemplate jdbc;
    private final TenantMigrations migrations;
    private final JournalProtection journals;

    /**
     * Лениво: список веб-эндпоинтов собирает тот же разборщик, который видит
     * и этот класс. Внедрённый напрямую, он дал бы круг в зависимостях.
     */
    private final ObjectProvider<WebEndpointsSupplier> webEndpoints;

    /**
     * Старт закончен: все {@code ApplicationRunner}'ы отработали.
     *
     * <p>Порт отвечает раньше этого момента — Tomcat поднимается на
     * обновлении контекста, а накат общей схемы {@code catalog} идёт
     * runner'ом после. Значит между «порт ответил» и «работать можно»
     * есть окно, и выкладка, переключившая трафик в него, застанет
     * приложение посреди миграции.
     */
    private volatile boolean started;

    public ReadinessEndpoint(JdbcTemplate jdbc, TenantMigrations migrations,
                             JournalProtection journals,
                             ObjectProvider<WebEndpointsSupplier> webEndpoints) {
        this.jdbc = jdbc;
        this.migrations = migrations;
        this.journals = journals;
        this.webEndpoints = webEndpoints;
    }

    @EventListener(ApplicationReadyEvent.class)
    void applicationReady() {
        started = true;
    }

    /**
     * Ответ «готово / не готово» и почему.
     *
     * <p>Код ответа тоже отвечает на тот же вопрос (200 против 503), чтобы
     * шагу выкладки хватило {@code curl -f}, а не разбора тела. Причины при
     * этом перечислены все, а не первая: оператор, поднимающий стенд, чинит
     * их разом, а не по одной за перезапуск.
     */
    @ReadOperation
    public WebEndpointResponse<Readiness> readiness() {
        List<Check> checks = List.of(application(), database(), schemas(), journals(), metrics());
        boolean ready = checks.stream().allMatch(Check::ok);
        return new WebEndpointResponse<>(new Readiness(ready, checks),
                ready ? WebEndpointResponse.STATUS_OK : 503);
    }

    private Check application() {
        return new Check("application", started,
                started ? "Старт завершён" : "Старт ещё идёт: стартовые шаги не закончены");
    }

    private Check database() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new Check("database", true, "База отвечает");
        } catch (RuntimeException e) {
            return new Check("database", false, "База не отвечает: " + e.getMessage());
        }
    }

    private Check schemas() {
        TenantMigrations.Lag lag;
        try {
            lag = migrations.lag();
        } catch (RuntimeException e) {
            return new Check("schemas", false,
                    "Версии схем арендаторов не проверены: " + e.getMessage());
        }

        if (lag.ok()) {
            return new Check("schemas", true, lag.ahead() == 0
                    ? "Схем позади версии %s нет (арендаторов %d)"
                            .formatted(lag.expectedVersion(), lag.tenants())
                    // Впереди говорится вслух: иначе «готов при схеме впереди»
                    // на экране неотличим от «версии совпали», а это ровно
                    // та минута выкладки, ради которой правило несимметрично.
                    : "Схем позади версии %s нет; впереди %d из %d — это норма "
                            .formatted(lag.expectedVersion(), lag.ahead(), lag.tenants())
                            + "между накатом и подъёмом новой сборки");
        }

        return new Check("schemas", false,
                "Схемы арендаторов позади версии %s: %d из %d (%s). Накатите: "
                        .formatted(lag.expectedVersion(), lag.behind().size(), lag.tenants(),
                                names(lag.behind()))
                        + "ops/migrate-tenants.sh");
    }

    private Check journals() {
        JournalProtection.Status status;
        try {
            status = journals.status();
        } catch (RuntimeException e) {
            return new Check("journals", false, "Защита журналов не проверена: " + e.getMessage());
        }

        if (status.problem() != null) {
            return new Check("journals", false,
                    "Защита журналов не проверена: " + status.problem());
        }
        if (status.schema() == null) {
            // Ячейка без арендаторов законна: её как раз и поднимают перед
            // подключением первого клиента. Проверять при этом нечего,
            // и красить выкладку в красное не за что.
            return new Check("journals", true,
                    "Арендаторов в ячейке ещё нет — проверять нечего");
        }
        if (status.locked()) {
            return new Check("journals", true,
                    "Журналы защищены: роль %s не может править %s (проверено на %s)"
                            .formatted(status.role(), JournalProtection.JOURNALS,
                                    status.schema()));
        }
        if (!status.reachable()) {
            // Роль без прав на схему журналы тоже не перепишет — и это не
            // защита, а неработающая ячейка: обычные таблицы ей недоступны
            // ровно так же. Поймано живым прогоном между заведением роли
            // и накатом, который права выдаёт.
            return new Check("journals", false,
                    "Рабочая роль %s не дотягивается до схемы %s: прав нет ни на журналы, "
                            .formatted(status.role(), status.schema())
                            + "ни на обычные таблицы. Права на схемы выдаёт накат: "
                            + "ops/migrate-tenants.sh");
        }
        return new Check("journals", false,
                "Журналы правятся прямым SQL: %s доступны на UPDATE роли %s (проверено на %s). "
                        .formatted(status.writable(), status.role(), status.schema())
                        + "Включается разделение ролей — ops/create-roles.sh и переменные "
                        + "DB_USER, APP_DDL_*, APP_RUNTIME_ROLE");
    }

    /**
     * Отдаются ли метрики.
     *
     * <p>Спрашивается не зависимость и не настройка, а сам список
     * отображённых наружу адресов: реестра Prometheus не было в зависимостях
     * вовсе, при том что в {@code management.endpoints} он был перечислен —
     * то есть конфигурация выглядела рабочей. Этот способ ловит оба случая
     * разом: нет реестра — нет и эндпоинта, убрали из списка отдачи — тоже
     * нет.
     */
    private Check metrics() {
        WebEndpointsSupplier supplier = webEndpoints.getIfAvailable();
        if (supplier == null) {
            return new Check("metrics", false,
                    "Отдача метрик не проверена: список веб-эндпоинтов недоступен");
        }
        boolean exposed = supplier.getEndpoints().stream()
                .anyMatch(endpoint -> "prometheus".equals(endpoint.getEndpointId().toString()));
        return exposed
                ? new Check("metrics", true, "Метрики отдаются: /actuator/prometheus на месте")
                : new Check("metrics", false,
                        "Метрики не отдаются: /actuator/prometheus не отображён — нет реестра "
                                + "Prometheus в зависимостях либо адрес убран из "
                                + "management.endpoints.web.exposure.include. Тревоги ячейки "
                                + "при этом молчат, а выглядит это как исправная система");
    }

    private static String names(List<TenantMigrations.TenantView> behind) {
        String first = behind.stream()
                .limit(NAMED)
                .map(TenantMigrations.TenantView::schema)
                .collect(Collectors.joining(", "));
        int rest = behind.size() - Math.min(NAMED, behind.size());
        return rest == 0 ? first : first + " и ещё " + rest;
    }

    /**
     * @param ready  одно поле, по которому отвечают машине: шаг выкладки
     *               ждёт его, а не разбирает список
     * @param checks все причины сразу, а не первая найденная
     */
    public record Readiness(boolean ready, List<Check> checks) {
    }

    /**
     * @param check  имя проверки латиницей: по нему шаг выкладки и тревога
     *               узнают, что именно не готово, а текст меняется свободно
     * @param detail что показать человеку, который пришёл разбираться
     */
    public record Check(String check, boolean ok, String detail) {
    }
}
