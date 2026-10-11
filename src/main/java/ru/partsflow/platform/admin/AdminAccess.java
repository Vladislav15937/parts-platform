package ru.partsflow.platform.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.session.LoginSessions;
import ru.partsflow.platform.session.SessionRevocations;
import ru.partsflow.platform.tenant.TenantContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Разрешение владельца разборки на вход администратора сервиса — и запрос его.
 *
 * <p><b>Разрешение — это состояние, а не действие.</b> Решения владельца
 * продукта от 8 сентября 2026 (tasks/0042): вход «только с разрешения
 * владельца»; срок выбирает клиент — «навсегда, до отзыва, на определённое
 * количество часов»; «отозвать пока администратор внутри можно»; доступ
 * «просит кнопкой» администратор, а у клиента «отдельная кнопка дать доступ
 * администратору». Отсюда строка с моментом окончания (пусто — до отзыва)
 * и моментом закрытия, а не флаг «пускать».
 *
 * <p><b>Живёт в общей схеме ячейки, а не у арендатора.</b> Список клиентов
 * с состоянием доступа администратор видит одним запросом, вне всех кабинетов
 * (пункт 5 решения), — обходом пятисот схем он стоил бы пятисот запросов.
 * И восстановление одной схемы из бэкапа разрешений не возвращает: потерянное
 * разрешение — это закрытый доступ, то есть отказ в безопасную сторону.
 *
 * <p><b>Открыто одно разрешение на арендатора, и держит это индекс</b>
 * ({@code admin_access_grant_open_uk}), а выдача нового идёт под
 * транзакционной рекомендательной блокировкой по арендатору — «закрыть
 * прежнее и открыть новое» иначе разъехалось бы у двух владельцев, нажавших
 * одновременно, и проигравший получил бы «нарушение целостности» на успешной
 * выдаче. Блокировка транзакционная, а не сессионная: сессионная протекла бы
 * через PgBouncer, как {@code SET search_path}.
 */
@Service
public class AdminAccess {

    /**
     * Сколько часов можно выбрать. Нижняя граница — час: меньше владелец
     * и не выберет, а ноль означал бы разрешение, закрытое в момент выдачи.
     * Верхняя — год: для «надолго» есть «до отзыва», и большее число часов
     * было бы тем же самым, только выглядящим иначе.
     */
    static final int MAX_HOURS = 24 * 365;

    /** Почему закрылась сессия администратора — видно владельцу во «Входах». */
    static final String REVOKED_DETAIL = "доступ администратора отозван";

    private static final String OPEN = "closed_at IS NULL AND (expires_at IS NULL OR expires_at > now())";

    private final JdbcTemplate jdbc;
    private final LoginSessions sessions;
    private final SessionRevocations revocations;

    public AdminAccess(JdbcTemplate jdbc, LoginSessions sessions, SessionRevocations revocations) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.revocations = revocations;
    }

    /**
     * Действует ли сейчас разрешение.
     *
     * <p>Спрашивается на каждом запросе администратора в кабинете
     * ({@link AdminVisitGuard}): истёкший срок обязан закрывать доступ сам,
     * без чьего-либо нажатия, а у сессии своего знания о сроке нет.
     * Таблица в {@code public}, имя квалифицировано — транзакция и арендатор
     * не нужны.
     */
    public boolean isOpen(long tenantId) {
        Boolean open = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.admin_access_grant WHERE tenant_id = ? AND "
                        + OPEN + ")",
                Boolean.class, tenantId);
        return Boolean.TRUE.equals(open);
    }

    /** Что видит владелец: открыт ли доступ, до какого часа и не просят ли его. */
    @Transactional(readOnly = true)
    public State state(long tenantId) {
        List<Grant> grants = jdbc.query(
                "SELECT granted_at, expires_at FROM public.admin_access_grant"
                        + " WHERE tenant_id = ? AND " + OPEN,
                (rs, i) -> new Grant(instant(rs, "granted_at"), instant(rs, "expires_at")),
                tenantId);
        List<Request> requests = jdbc.query("""
                SELECT q.requested_at, a.display_name
                  FROM public.admin_access_request q
                  JOIN public.service_admin a ON a.id = q.admin_id
                 WHERE q.tenant_id = ? AND q.closed_at IS NULL""",
                (rs, i) -> new Request(instant(rs, "requested_at"), rs.getString("display_name")),
                tenantId);
        return new State(grants.isEmpty() ? null : grants.get(0),
                requests.isEmpty() ? null : requests.get(0));
    }

    /**
     * Дать доступ: своей кнопкой владельца или в ответ на запрос — путь один.
     *
     * <p>Два пути к одному состоянию названы владельцем продукта прямо:
     * «позвонил в поддержку, дал доступ» обязан работать без того, чтобы
     * с той стороны сначала нажали «прошу». Поэтому выдача не требует
     * запроса, а открытый запрос она закрывает как исполненный.
     *
     * <p>Прежнее разрешение закрывается новым: владелец, открывший доступ
     * на два часа и передумавший на «до отзыва», меняет срок, а не копит
     * два разрешения.
     *
     * @param hours {@code null} — до отзыва
     */
    @Transactional
    public State grant(long tenantId, long ownerMemberId, Integer hours) {
        if (hours != null && (hours < 1 || hours > MAX_HOURS)) {
            throw new IllegalArgumentException(
                    "Срок доступа — от 1 до %d часов либо «до отзыва»".formatted(MAX_HOURS));
        }
        lock(tenantId);
        jdbc.update("""
                UPDATE public.admin_access_grant SET closed_at = now()
                 WHERE tenant_id = ? AND closed_at IS NULL""", tenantId);
        jdbc.update("""
                INSERT INTO public.admin_access_grant (tenant_id, granted_by, expires_at)
                VALUES (?, ?, CASE WHEN ?::int IS NULL THEN NULL
                                   ELSE now() + make_interval(hours => ?::int) END)""",
                tenantId, ownerMemberId, hours, hours);
        jdbc.update("""
                UPDATE public.admin_access_request SET closed_at = now(), outcome = 'GRANTED'
                 WHERE tenant_id = ? AND closed_at IS NULL""", tenantId);
        return state(tenantId);
    }

    /**
     * Отозвать доступ — и выгнать администратора, если он внутри.
     *
     * <p><b>Отзыв обязан убивать уже открытую сессию, а не переставать пускать
     * со следующего входа</b> (tasks/0042, критерий 6): иначе владелец видит
     * закрытый доступ там, где он открыт. Действуют здесь три вещи, и каждая
     * своё: закрытое разрешение отбивает следующий же запрос через
     * {@link AdminVisitGuard} на любом экземпляре; удалённая сессия — то же
     * самое тем же механизмом, что смена пароля сотрудника; запись в журнале
     * входов отвечает владельцу через неделю, почему сессия кончилась.
     * Порядок тот же, что у {@code MemberService}: сначала журнал, потом сама
     * сессия.
     *
     * <p>Повторный отзыв ничего не ломает: закрывать уже нечего.
     */
    @Transactional
    public State revoke(long tenantId, long ownerMemberId) {
        lock(tenantId);
        jdbc.update("""
                UPDATE public.admin_access_grant SET closed_at = now(), closed_by = ?
                 WHERE tenant_id = ? AND closed_at IS NULL""", ownerMemberId, tenantId);

        String schema = TenantContext.require();
        List<Long> admins = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE role = 'ADMIN'", Long.class);
        for (Long admin : admins) {
            sessions.revoke(schema, admin, REVOKED_DETAIL, null);
            revocations.revoke(schema, admin, null);
        }
        return state(tenantId);
    }

    /** Отклонить запрос, не давая доступа. Разрешение, если оно есть, не трогается. */
    @Transactional
    public State decline(long tenantId) {
        jdbc.update("""
                UPDATE public.admin_access_request SET closed_at = now(), outcome = 'DECLINED'
                 WHERE tenant_id = ? AND closed_at IS NULL""", tenantId);
        return state(tenantId);
    }

    /**
     * Попросить доступ из системы администрирования.
     *
     * <p>Повтор и одновременное двойное нажатие дают тот же один запрос:
     * открытый запрос на арендатора один, держит это индекс, а вставка
     * проигравшего тихо ничего не делает — ответ тот же, что у первого.
     */
    @Transactional
    public void request(long tenantId, long adminId) {
        jdbc.update("""
                INSERT INTO public.admin_access_request (tenant_id, admin_id)
                VALUES (?, ?)
                ON CONFLICT (tenant_id) WHERE closed_at IS NULL DO NOTHING""", tenantId, adminId);
    }

    /**
     * Все действующие разборки ячейки с состоянием доступа — для системы
     * администрирования. Компаний на ячейке не больше потолка (500), и список
     * отдаётся целиком.
     */
    @Transactional(readOnly = true)
    public List<TenantAccess> tenants() {
        return jdbc.query("""
                SELECT r.code, r.company_name, g.granted_at, g.expires_at, q.requested_at
                  FROM public.tenant_registry r
                  LEFT JOIN public.admin_access_grant g
                         ON g.tenant_id = r.tenant_id AND g.closed_at IS NULL
                        AND (g.expires_at IS NULL OR g.expires_at > now())
                  LEFT JOIN public.admin_access_request q
                         ON q.tenant_id = r.tenant_id AND q.closed_at IS NULL
                 WHERE r.status = 'ACTIVE' AND r.code IS NOT NULL
                 ORDER BY r.company_name, r.code""",
                (rs, i) -> new TenantAccess(rs.getString("code"), rs.getString("company_name"),
                        rs.getTimestamp("granted_at") == null ? null
                                : new Grant(instant(rs, "granted_at"), instant(rs, "expires_at")),
                        instant(rs, "requested_at")));
    }

    private void lock(long tenantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('admin_access_grant'), ?::int)",
                (ResultSetExtractor<Void>) rs -> null, tenantId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    /** @param expiresAt {@code null} — до отзыва */
    public record Grant(Instant grantedAt, Instant expiresAt) {
    }

    public record Request(Instant requestedAt, String adminName) {
    }

    /**
     * @param grant   действующее разрешение либо {@code null} — доступа нет
     * @param request открытый запрос администратора либо {@code null}
     */
    public record State(Grant grant, Request request) {
    }

    public record TenantAccess(String code, String companyName, Grant grant, Instant requestedAt) {
    }
}
