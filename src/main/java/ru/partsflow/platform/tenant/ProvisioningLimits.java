package ru.partsflow.platform.tenant;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Вторая преграда перед провижинингом: частота и потолок числа схем
 * (задача 0183).
 *
 * <p><b>Зачем.</b> До 27 сентября 2026 у {@code POST /api/provisioning/tenants}
 * была ровно одна преграда — секрет, — и защита была устроена как «всё или
 * ничего»: тот, кто секрет узнал, заводил схемы, пока не кончится диск.
 * Ограничений не было ни одного: ни частоты, ни счёта попыток, ни потолка
 * числа схем. Записано это было тем же, кто заводил порядок подключения
 * ({@code docs/onboarding.md}, раздел 2), то есть известно и не случайно.
 *
 * <p><b>Цена не в мусорных компаниях.</b> Кончившийся диск ячейки
 * останавливает Postgres, а с ним архив WAL, а с ним точку возврата
 * <b>всех</b> арендаторов этой ячейки. То есть чужой скрипт отнимает защиту
 * данных у живой разборки. Тот же класс уже проходили с другой стороны
 * (задача 0154, журнал расписания без ротации): растущее без предела ломает
 * ровно ту точку возврата, ради которой всё заведено. И заведение схемы
 * не дёшево само по себе — {@code CREATE SCHEMA} плюс накат всех changeset'ов
 * арендатора.
 *
 * <p><b>Два предела делают разную работу, и поэтому их два.</b>
 *
 * <p>{@link #maxPerHour} (частота, с одного адреса) — против всплеска
 * и ради заметности: он не даёт набить потолок за минуты и оставляет след
 * на первой же отбитой попытке.
 *
 * <p>{@link #maxTenants} (потолок числа схем в ячейке) — абсолютная граница
 * для диска. Именно он закрывает то, что названо в задаче словами «пока
 * не кончится диск»: сколько бы ни было времени и попыток, больше потолка
 * схем в ячейке не появится.
 *
 * <p><b>Предел стоит на пути HTTP, а не внутри {@link TenantProvisioning}.</b>
 * Ограничивать надо управляющий контур — то, до чего дотягивается чужой, —
 * а не саму операцию: провижининг зовут ещё и изнутри (фикстуры, инструменты
 * стенда), и предел в сервисе превратился бы в предел на прогон тестов
 * и на раскладку эталонного набора.
 *
 * <p><b>Потолок проверяется с допуском, и это сказано вслух.</b> Одновременные
 * запросы читают счёт схем до того, как соседний успел вставить запись,
 * поэтому потолок может быть превышен на число запросов, пришедших в один
 * момент. Так и оставлено: потолок защищает диск, а не инвариант, и «501
 * вместо 500» не стоит блокировки на весь контур. Для сравнения — номер
 * арендатора выдаётся именно под блокировкой, потому что там цена ошибки
 * другая: два клиента в одной схеме.
 */
@Component
public class ProvisioningLimits {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningLimits.class);

    /**
     * Окно счёта частоты. Час — то, в чём предел назван человеку.
     *
     * <p>Публичное: {@code ApiExceptionHandler} берёт отсюда {@code Retry-After},
     * чтобы длина окна была записана в одном месте. Разойдись они — ответ
     * советовал бы ждать не столько, сколько предел держит.
     */
    public static final Duration WINDOW = Duration.ofHours(1);

    /**
     * Сколько адресов держать под счётом.
     *
     * <p>Это защита нашей же памяти, а не предел для человека: тот, кто ходит
     * с нового адреса на каждый запрос, иначе растил бы карту без предела.
     * Заполненная карта (уже после уборки устаревшего) — сама по себе признак
     * долбёжки, и запрос в этом состоянии отбивается.
     *
     * <p>Тысяча с запасом: законных адресов у управляющего контура один-два —
     * его зовут изнутри машины.
     */
    private static final int MAX_TRACKED_ADDRESSES = 1024;

    /** Отказ по частоте. */
    public static final String REASON_RATE = "rate";

    /** Отказ по потолку числа схем. */
    public static final String REASON_CELL_FULL = "cell-full";

    /** Неверный секрет — то самое «кто-то ломится в управляющий контур». */
    public static final String REASON_SECRET = "secret";

    /** Контур выключен, а в него постучались. */
    public static final String REASON_DISABLED = "disabled";

    /** Адресов под счётом больше, чем мы готовы держать. */
    public static final String REASON_ADDRESSES = "addresses";

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final int maxPerHour;
    private final int maxTenants;

    /** Когда с этого адреса заводили схемы — в пределах окна. */
    private final Map<String, Deque<Long>> recentByAddress = new ConcurrentHashMap<>();

    public ProvisioningLimits(
            // Реестр рабочая роль читать вправе — писать в него не вправе,
            // и здесь только чтение. Имя схемы в запросе указано полностью
            // (public.tenant_registry): JdbcTemplate вне транзакции уходит
            // в public, и это ровно то, что нужно, — таблица общая, арендатора
            // у неё нет. Ловушка про search_path здесь не работает: она
            // про схемы арендаторов.
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            @Value("${app.provisioning-max-per-hour:20}") int maxPerHour,
            @Value("${app.provisioning-max-tenants:500}") int maxTenants) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.maxPerHour = maxPerHour;
        this.maxTenants = maxTenants;
    }

    /**
     * Можно ли завести ещё одну компанию с этого адреса — и если да, занимает
     * место в окне.
     *
     * <p>Зовётся <b>после</b> сверки секрета и <b>до</b> самого провижининга:
     * отбитый по пределу запрос не должен ни создавать схему, ни накатывать
     * на неё changeset'ы.
     *
     * <p><b>Место занимается на разрешённой попытке, а не на успехе.</b> Иначе
     * сотня одновременных запросов прошла бы проверку вся: каждый читал бы
     * окно раньше, чем сосед успел в него записаться. Плата за это — сорванное
     * заведение (занятый код компании) тоже расходует место в окне; при пределе
     * в {@link #maxPerHour} это заметно дешевле, чем дыра в самом пределе.
     *
     * @param address адрес, с которого пришёл запрос
     * @throws TooManyRequests частота исчерпана — 429
     * @throws CellFull        потолок числа схем в ячейке — 409
     */
    public void beforeCreate(String address) {
        long tenants = tenantCount();
        if (tenants >= maxTenants) {
            rejected(REASON_CELL_FULL, address, "схем в ячейке " + tenants);
            throw new CellFull(("Ячейка заполнена: схем %d, потолок %d. Новых компаний здесь "
                    + "не заводят — поднимают следующую ячейку. Потолок задаётся настройкой "
                    + "app.provisioning-max-tenants (переменная APP_PROVISIONING_MAX_TENANTS)")
                    .formatted(tenants, maxTenants));
        }

        int used = takeSlot(address);
        if (used > maxPerHour) {
            rejected(REASON_RATE, address, "за час попыток " + used);
            throw new TooManyRequests(("Слишком часто заводятся компании: предел — %d за час "
                    + "с одного адреса, с %s за последний час уже %d. Это предел, а не поломка: "
                    + "повторите позже либо поднимите app.provisioning-max-per-hour "
                    + "(переменная APP_PROVISIONING_MAX_PER_HOUR)")
                    .formatted(maxPerHour, address, used - 1));
        }
    }

    /**
     * Компания заведена — считаем.
     *
     * <p>Отдельно от занятого места в окне: место занимает и сорвавшаяся
     * попытка, а это число отвечает на вопрос «сколько компаний в ячейке
     * появилось».
     */
    public void created(String address) {
        metrics.counter("partsflow.provisioning.created").increment();
        log.info("Заведена компания с адреса {}", address);
    }

    /**
     * Отбитая попытка оставляет след — и до задачи 0183 не оставляла никакого.
     *
     * <p>Ни записи, ни метрики: тревога «кто-то ломится в управляющий контур»
     * была невозможна по построению. Теперь считается каждая отбитая попытка,
     * и считается <b>с причиной</b>: неверный секрет и упёршийся в предел
     * оператор — разные события, и путать их нельзя.
     *
     * <p><b>Адрес идёт в лог, а не в метку метрики.</b> Метка-адрес — это
     * неограниченное число временных рядов (их заводит тот, кто стучится,
     * а не мы), то есть хранилище метрик, которое растёт от самой атаки.
     * Причин же ровно пять, и они перечислены здесь.
     *
     * <p>Правило, по которому это читают: {@code partsflow.provisioning.rejected}
     * с причиной {@code secret} — это стук в контур; с причиной {@code rate}
     * или {@code cell-full} — это предел, сработавший как задумано.
     */
    public void rejected(String reason, String address, String detail) {
        metrics.counter("partsflow.provisioning.rejected", "reason", reason).increment();
        log.warn("Попытка провижининга отбита: причина {}, адрес {}, {}", reason, address, detail);
    }

    /** Сколько арендаторов уже есть в ячейке — с теми, что заведены наполовину. */
    private long tenantCount() {
        // Считаются все записи реестра, а не только ACTIVE: сорвавшийся
        // провижининг оставляет запись в PROVISIONING и, возможно, созданную
        // схему — место на диске она занимает так же. Потолок про диск,
        // а не про работающих клиентов.
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM public.tenant_registry", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Занимает место в окне и возвращает, сколько их стало вместе с этим.
     *
     * <p>Под замком на карте: чтение окна и запись в него не должны
     * разъезжаться — то же правило, что у остатка склада, только цена ниже.
     */
    private synchronized int takeSlot(String address) {
        long now = System.currentTimeMillis();
        long since = now - WINDOW.toMillis();

        // Уборка устаревшего на каждом заходе: иначе карта растёт адресами,
        // которые больше не придут.
        recentByAddress.values().forEach(times -> {
            while (!times.isEmpty() && times.peekFirst() < since) {
                times.pollFirst();
            }
        });
        recentByAddress.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        Deque<Long> times = recentByAddress.get(address);
        if (times == null) {
            if (recentByAddress.size() >= MAX_TRACKED_ADDRESSES) {
                rejected(REASON_ADDRESSES, address,
                        "адресов под счётом " + recentByAddress.size());
                throw new TooManyRequests(("В управляющий контур ломятся с %d разных адресов "
                        + "за час — больше, чем ячейка готова различать. Это предел, "
                        + "а не поломка").formatted(recentByAddress.size()));
            }
            times = new ArrayDeque<>();
            recentByAddress.put(address, times);
        }
        times.addLast(now);
        return times.size();
    }

    /**
     * Адрес запроса.
     *
     * <p><b>{@code X-Forwarded-For} намеренно не читается.</b> Заголовок ставит
     * тот, кто запрос послал, — значит предел «с одного адреса», считающий
     * по нему, обходится сменой строки в заголовке, то есть не существует.
     * Проксей перед управляющим контуром и нет: снаружи терминатор отдаёт
     * на {@code /api/provisioning} 404, а законный вызов идёт изнутри машины.
     *
     * <p>Отсюда и следствие, которое надо знать: у законных вызовов адрес
     * почти всегда один и тот же (изнутри контейнера), и предел с одного
     * адреса — это для них предел на всю ячейку. Так и задумано: у ячейки
     * один оператор подключения.
     */
    public static String addressOf(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "неизвестен" : address;
    }

    /** Частота исчерпана — 429. */
    public static class TooManyRequests extends RuntimeException {
        public TooManyRequests(String message) {
            super(message);
        }
    }

    /** Потолок числа схем в ячейке — 409. */
    public static class CellFull extends RuntimeException {
        public CellFull(String message) {
            super(message);
        }
    }
}
