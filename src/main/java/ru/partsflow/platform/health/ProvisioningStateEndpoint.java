package ru.partsflow.platform.health;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.ProvisioningController;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Включён ли управляющий контур — и с какого момента.
 *
 * <p><b>Зачем он есть.</b> 26 сентября 2026 на боевой ячейке у сборки,
 * запущенной трое суток назад, в окружении лежал непустой
 * {@code APP_PROVISIONING_TOKEN}. Утечки не было — снаружи контур закрыт
 * по адресам, и {@code POST /api/provisioning/tenants} через терминатор
 * отвечает 404, — но правило «секрет выключается сразу после подключения»
 * ({@code docs/onboarding.md}, раздел 2) не выполнялось трое суток, и
 * <b>узнать об этом было нечем</b>: готовность задавала пять вопросов и ни
 * одного про контур, сторож настроек смотрит только монтирования, у тревог
 * про провижининг нет ни одного правила. Правило, выполняемое руками
 * и проверяемое памятью, не выполняют — и это не видно.
 *
 * <p>Цена невыполнения не абстрактная: секрет — <b>единственная</b> преграда
 * перед провижинингом (ограничений частоты и числа схем нет вовсе, задача
 * 0183), а кончившийся диск ячейки останавливает Postgres, а с ним архив WAL
 * и точку возврата <b>всех</b> арендаторов.
 *
 * <p><b>Спрашивается работающее приложение, а не файл.</b> Это главное
 * свойство признака, и оно взято прямо из наблюдавшегося случая: окружение
 * читается при создании контейнера, поэтому вычищенный {@code .env} и живой
 * контейнер с прежним секретом — состояние совершенно обычное. Признак,
 * читающий {@code .env}, соврал бы ровно там, где он нужен. Отвечает здесь
 * сам {@link ProvisioningController#enabled()} — тот же метод, которым контур
 * решает, пускать ли запрос: посчитанное дважды разъезжается.
 *
 * <p><b>«С какого момента» обязательно.</b> Без него «включён» не отличает
 * минуту подключения клиента (законно) от третьих суток (то, что наблюдали).
 * Момент — это запуск процесса: окружение контейнера при жизни процесса
 * не меняется, значит «сколько контур включён» и «сколько работает эта
 * сборка» — одно и то же число.
 *
 * <p><b>Секрет не печатается ни в каком виде</b> — ни целиком, ни длиной,
 * ни началом: наружу идёт «да» или «нет». Стережёт это
 * {@code ProvisioningStateTest}, сверяя всё тело ответа.
 *
 * <p><b>Красным это ничего не красит, и решение не исполнителя.</b> Во время
 * подключения клиента контур включён законно, и «не готов» остановило бы ровно
 * ту работу, ради которой он включён. Поэтому признак — сообщение: он говорит
 * состояние и называет, чем секрет отзывается. Обязан ли включённый контур
 * красить выкладку или готовность — вопрос владельцу продукта, названный
 * в задаче 0197 и в PR.
 *
 * <p><b>Своим эндпоинтом, а не проверкой готовности.</b> Попади он
 * в {@code /actuator/readiness}, ответ «не готов» получила бы сборка, которая
 * обслуживает людей и обязана обслуживать, — и выкладка гасила бы её сама.
 * По той же причине это не {@code HealthIndicator}: любой такой бин попадает
 * в {@code /actuator/health}, которым проверяет себя образ.
 *
 * <p>Учётной записи не спрашивает — как готовность и метрики: у шага выкладки
 * и у оператора со скриптом её нет. Наружу адрес закрыт теми же двумя
 * способами: порт приложения не публикуется, а терминатор отдаёт
 * на {@code /actuator} 404.
 */
@Component
@Endpoint(id = "provisioning")
public class ProvisioningStateEndpoint {

    private final ProvisioningController controlPlane;

    /**
     * Когда процесс начал работать с этим окружением.
     *
     * <p>Берётся у самого процесса, а не у бина: бин создаётся в середине
     * подъёма контекста, и на подъёме приложения это расходится с правдой
     * на секунды. У процесса ответ точный, а спросить его больше негде —
     * окружение контейнера не менялось с его создания.
     */
    private final Instant startedAt;

    public ProvisioningStateEndpoint(ProvisioningController controlPlane) {
        this.controlPlane = controlPlane;
        this.startedAt = ProcessHandle.current().info().startInstant()
                // Пустым это приходит на JVM, не отдающих сведения о процессе.
                // Момент создания бина ошибается на время подъёма контекста —
                // в разы меньше, чем длится любое «забыли выключить».
                .orElseGet(Instant::now);
    }

    @ReadOperation
    public State state() {
        Instant now = Instant.now();
        Duration since = Duration.between(startedAt, now);
        // Отрицательным это станет только при переводе часов назад. «Ноль»
        // честнее, чем «включён минус два часа».
        long seconds = Math.max(0, since.getSeconds());
        String moment = DateTimeFormatter.ISO_INSTANT.format(startedAt.atOffset(ZoneOffset.UTC));

        if (!controlPlane.enabled()) {
            return new State(false, moment, seconds, human(seconds),
                    // Дословно то же, что отвечает сам контур на запрос:
                    // одно написание на обе поверхности.
                    ProvisioningController.DISABLED
                            + " — и не задан с запуска этой сборки (" + human(seconds) + " назад). "
                            + "Схему завести нельзя: сперва задайте APP_PROVISIONING_TOKEN в .env "
                            + "и ПЕРЕСОЗДАЙТЕ сборку (docker compose up -d <сборка>) — "
                            + "docker compose restart окружение контейнера не перечитывает");
        }
        return new State(true, moment, seconds, human(seconds),
                "Управляющий контур ВКЛЮЧЁН — с запуска этой сборки, " + human(seconds) + ". "
                        + "Пока он включён, POST /api/provisioning/tenants заводит схемы всякому, "
                        + "кто дошёл до контура; ограничений частоты и числа схем нет. "
                        + "Во время подключения клиента это законно, после — нет. "
                        + "Отзывается пустым APP_PROVISIONING_TOKEN в .env и ПЕРЕСОЗДАНИЕМ сборки "
                        + "(docker compose up -d <сборка>): docker compose restart окружение "
                        + "контейнера не перечитывает, и секрет остаётся живым");
    }

    /**
     * Сколько это длится — словами, а не числом секунд.
     *
     * <p>«259200» и «3 дня» отвечают на вопрос по-разному: второе читается
     * за секунду, а признак и заводился затем, чтобы человек заметил разницу
     * между минутой подключения и третьими сутками.
     */
    static String human(long seconds) {
        if (seconds < 60) {
            return "меньше минуты";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " " + plural(minutes, "минута", "минуты", "минут");
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " " + plural(hours, "час", "часа", "часов");
        }
        long days = hours / 24;
        return days + " " + plural(days, "день", "дня", "дней");
    }

    private static String plural(long n, String one, String few, String many) {
        long mod100 = n % 100;
        long mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return few;
        }
        return many;
    }

    /**
     * @param enabled    одно поле, по которому отвечают машине
     * @param since      момент запуска сборки, ISO-8601 в UTC: с него контур
     *                   находится в этом состоянии и раньше измениться
     *                   не мог — окружение читается при создании контейнера
     * @param forSeconds сколько он в этом состоянии, секундами — для тревоги
     *                   и для скрипта
     * @param forHuman   то же словами — для человека
     * @param detail     что это значит и чем отзывается секрет; догадываться
     *                   об этом человек не должен — он и не догадался
     */
    public record State(boolean enabled, String since, long forSeconds,
                        String forHuman, String detail) {
    }
}
