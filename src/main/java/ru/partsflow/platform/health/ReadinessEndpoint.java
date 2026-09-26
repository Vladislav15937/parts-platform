package ru.partsflow.platform.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.CatalogSchemaMigrator;
import ru.partsflow.platform.tenant.JournalProtection;
import ru.partsflow.platform.tenant.TenantMigrations;

import java.util.List;

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
 * <p><b>Отдельной проверки «контекст поднялся» здесь нет, и это решение,
 * а не упущение.</b> Отвечает этот адрес только после обновления контекста:
 * Tomcat поднимается в самом его конце, когда все бины уже созданы, —
 * то есть сам ответ и есть доказательство. Первая редакция держала рядом
 * флаг «все {@code ApplicationRunner}'ы отработали», выставляемый
 * по {@code ApplicationReadyEvent}; разбор PR #176 показал полным прогоном,
 * что в части прогонов флаг остаётся {@code false} на весь тестовый класс.
 * Причину установить не удалось — ни второй копии бина, ни другого
 * слушателя того же события в коде нет, — поэтому конструкция снята:
 * <b>проверка, которая краснеет на исправном приложении, будет выключена
 * первой</b>, а выкладка, которая ждёт её вечно, хуже отсутствующей.
 *
 * <p>Заодно выяснилось, что обещание было сильнее правды: порядок
 * {@code ApplicationRunner}'ов между собой не задан, и «все отработали»
 * флаг не гарантировал.
 *
 * <p><b>А окно, ради которого флаг стоял, закрыто спрашиванием состояния,
 * а не факта.</b> Первая редакция полагалась на то, что без реестра
 * арендаторов проверка схем отвечает «версии не проверены»; при перезапуске
 * на базе, где реестр уже есть, а новому changeset'у общей схемы накатиться
 * ещё нужно, готовность отвечала «готов» на несколько секунд раньше правды —
 * то есть трафик мог переключиться на сборку, у которой {@code catalog}
 * ещё догоняется. Теперь про общую схему спрашивают её саму
 * ({@link CatalogSchemaMigrator#pending()}), и правило из 0078 выполнено
 * буквально: спрашивать надо то, что стартовые шаги делают, а не то, что
 * они закончились (задача 0086).
 *
 * <p>Транзакция не нужна: все запросы идут в {@code public} — к реестру
 * и к правам, — а не в схему арендатора.
 */
@Component
@Endpoint(id = "readiness")
public class ReadinessEndpoint {

    private final JdbcTemplate jdbc;
    private final TenantMigrations migrations;
    private final CatalogSchemaMigrator catalog;
    private final JournalProtection journals;
    private final DatabaseWriteProbe writeProbe;

    /**
     * Лениво: список веб-эндпоинтов собирает тот же разборщик, который видит
     * и этот класс. Внедрённый напрямую, он дал бы круг в зависимостях.
     */
    private final ObjectProvider<WebEndpointsSupplier> webEndpoints;

    public ReadinessEndpoint(JdbcTemplate jdbc, TenantMigrations migrations,
                             CatalogSchemaMigrator catalog,
                             JournalProtection journals,
                             DatabaseWriteProbe writeProbe,
                             ObjectProvider<WebEndpointsSupplier> webEndpoints) {
        this.jdbc = jdbc;
        this.migrations = migrations;
        this.catalog = catalog;
        this.journals = journals;
        this.writeProbe = writeProbe;
        this.webEndpoints = webEndpoints;
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
        List<Check> checks = List.of(database(), catalog(), schemas(), journals(),
                writes(), metrics());
        boolean ready = checks.stream().allMatch(Check::ok);
        return new WebEndpointResponse<>(new Readiness(ready, checks),
                ready ? WebEndpointResponse.STATUS_OK
                      : WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE);
    }

    private Check database() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return new Check("database", true, "База отвечает");
        } catch (RuntimeException e) {
            return new Check("database", false,
                    "База не отвечает: " + TenantMigrations.rootMessage(e));
        }
    }

    /**
     * Принята ли общая схема ячейки.
     *
     * <p>Спрашивается схема, а не накат: {@code CatalogMigrations} —
     * {@code ApplicationRunner}, то есть он работает уже после того, как
     * Tomcat начал отвечать, и «порт открылся» тут не значит ничего.
     * До задачи 0086 эту дыру прикрывало совпадение: на новой ячейке реестра
     * арендаторов ещё нет, и проверка схем краснела сама. При перезапуске
     * реестр есть — и готовность отвечала «да», пока общая схема догонялась.
     *
     * <p>Стоит перед проверкой схем арендаторов намеренно: реестр лежит
     * в {@code public} и заводится этим же changelog'ом, так что «версии
     * не проверены» ниже — следствие, а причина называется здесь.
     */
    private Check catalog() {
        CatalogSchemaMigrator.Pending pending;
        try {
            pending = catalog.pending();
        } catch (RuntimeException e) {
            // Истории наката нет вовсе — ячейку поднимают впервые, и общая
            // схема ещё не создана. Причина — из самого глубокого исключения:
            // обёртка несёт в себе весь текст запроса.
            return new Check("catalog", false,
                    "Общая схема catalog не проверена: " + TenantMigrations.rootMessage(e));
        }

        if (pending.ok()) {
            return new Check("catalog", true,
                    "Общая схема catalog принята целиком: changeset'ов %d"
                            .formatted(pending.total()));
        }
        return new Check("catalog", false,
                "Общая схема catalog принята не целиком: не хватает %d из %d (%s). "
                        .formatted(pending.missing().size(), pending.total(), pending.names())
                        + "Накат идёт при старте приложения — дождитесь его; если "
                        + "не заканчивается, смотрите лог CatalogMigrations");
    }

    private Check schemas() {
        TenantMigrations.Lag lag;
        try {
            lag = migrations.lag();
        } catch (RuntimeException e) {
            // Причина берётся из самого глубокого исключения: обёртка Spring
            // несёт в сообщении весь текст запроса, и ответ готовности
            // превращался в простыню SQL вместо «реестра нет». Состояние
            // достижимое — ячейка до наката общей схемы, проверено живьём.
            return new Check("schemas", false, "Версии схем арендаторов не проверены: "
                    + TenantMigrations.rootMessage(e));
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
                                TenantMigrations.namesOf(lag.behind()))
                        + "ops/migrate-tenants.sh");
    }

    private Check journals() {
        JournalProtection.Status status;
        try {
            status = journals.status();
        } catch (RuntimeException e) {
            return new Check("journals", false,
                    "Защита журналов не проверена: " + TenantMigrations.rootMessage(e));
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
     * Пройдёт ли в этой базе запись — и почему зелёный свет здесь не гасится.
     *
     * <p><b>Зачем вопрос появился.</b> Сборка на замороженной базе отвечала
     * пятью зелёными строками и {@code ready:true}, пока релей в это же время
     * сыпал в лог {@code cannot execute UPDATE in a read-only transaction}.
     * Ни одна проверка не врала — вопроса о записи просто не было ни у одной:
     * {@code database} это {@code SELECT 1}, а {@code journals} спрашивает
     * права, которых заморозка не меняет. Брошенная на слепке сборка поэтому
     * выглядела здоровой сколько угодно долго (задача 0196).
     *
     * <p><b>Ответ этой проверки никогда не делает готовность красной, и это
     * решение исполнителя с доводом.</b> Заморозка законна как минимум
     * в трёх состояниях, и все три проверены живыми прогонами: копия во время
     * выкладки (её морозят до конца самопроверок, а трафик переводят уже
     * после), старая сборка, обязанная подниматься на чтение (задача 0122,
     * решение владельца от 18 сентября 2026), и прежняя база, оставшаяся
     * рядом слепком. Ни одно из трёх не означает «эта сборка не может
     * обслуживать людей»: читают на ней как раньше.
     *
     * <p>Цена красного здесь не теоретическая, а немедленная и двойная.
     * {@code ops/switch-build.sh} ждёт в теле {@code "ready":true} и без него
     * трафик не переводит — то есть <b>исправная выкладка не прошла бы
     * никогда</b>, потому что копию морозят как раз перед переключением.
     * И healthcheck боевого compose ищет в теле ту же строку, значит docker
     * объявил бы контейнер нездоровым, а выкладка не дождалась бы готовности.
     * Проверка, которая краснеет на исправной системе, будет выключена
     * первой — это правило здесь уже оплачено снятым флагом «старт закончен».
     *
     * <p><b>Признак, по которому шаг выкладки переводит трафик, поэтому
     * не тронут.</b> Заморозку на переключении сторожит один
     * {@code ops/switch-build.sh} (задача 0112), и второго места для этого
     * решения не появилось: задача называет смену признака вопросом владельца
     * продукта — признак не меняется.
     *
     * <p>Что проверка делает вместо этого — <b>называет состояние словами</b>,
     * тремя разными. Числом то же самое отдаёт
     * {@link DatabaseWriteProbe#FROZEN_GAUGE}, и по нему брошенную сборку можно
     * назвать по имени: метку {@code build} ставит сбор метрик.
     */
    private Check writes() {
        try {
            return new Check("writes", true, writeProbe.probe().detail());
        } catch (RuntimeException e) {
            // Проба ответ собирает сама и наружу не бросает; это последний
            // рубеж, чтобы неожиданность в ней не уронила весь ответ
            // готовности — тогда выкладка встала бы на пустом месте.
            return new Check("writes", true,
                    "Запись не проверена: " + TenantMigrations.rootMessage(e));
        }
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
