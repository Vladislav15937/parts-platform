package ru.partsflow.platform.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Журнал сессий для показа: кто заходил, откуда, сколько работал и чем всё
 * кончилось.
 *
 * <p>Вторая половина задачи 0043. Первая (журнал изменений) отвечает
 * на вопрос «кто уронил цену», эта — на вопрос «кто вообще заходил в кабинет»,
 * и он задаётся тогда же: когда подозревают чужой вход или разбираются,
 * кто работал в смену.
 *
 * <p><b>Три исхода сессии различаются, а не сводятся к «закрыта».</b> Решение
 * владельца продукта от 9 сентября 2026: «Вышел в 18:03», «Истекла в 18:47»,
 * «Завершена владельцем в 14:20» — это три разных ответа на вопрос «почему
 * его больше не было». Наружу уезжает код причины, слово называет экран:
 * словарь на сервере стал бы второй копией.
 *
 * <p><b>Длительность считается по последней активности.</b> Здесь отдаются обе
 * метки — вход и последняя активность, — и считает по ним экран. Разница
 * не косметическая: «закрыл ноутбук в 18:00, сессия истекла в 6:00» с расчётом
 * по концу дало бы «работал двенадцать часов» — то есть ложь о человеке
 * в документе, который заводят как раз для разбирательств.
 *
 * <p><b>Города по адресу здесь нет.</b> Владелец назвал его («IP и город
 * из него с пометкой „примерно“»), но город берётся из базы соответствий
 * адресов и регионов, которую надо где-то взять, куда-то положить в ячейке
 * и раз в месяц обновлять. Это работа про ячейку, а не про журнал; IP
 * отдаётся, место под город есть, выдумывать город из ниоткуда нельзя.
 */
@Service
public class SessionJournalService {

    /**
     * Сколько чужих логинов предлагать отбором.
     *
     * <p>Сто — это уже «кто-то ломится», и разбирать их отбором по одному
     * никто не станет: смотрят списком. Потолок нужен не ради этих ста,
     * а ради того, чтобы экран владельца не скачивал десять тысяч строк,
     * набранных чужим перебором паролей.
     */
    private static final int ATTEMPTED_CAP = 100;

    private final JdbcTemplate jdbc;

    public SessionJournalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Страница журнала.
     *
     * <p>Курсора здесь нет намеренно, и это не забывчивость: записей о входах
     * на порядки меньше, чем правок — у десяти сотрудников это десяток строк
     * в день против тысяч. Глубокие страницы, ради которых заводят курсор,
     * тут просто не набираются, а «показать ещё» с растущим размером
     * отвечает на настоящий вопрос: «а раньше?».
     *
     * @param member  имя сотрудника либо введённый логин у неудачных попыток
     * @param outcome {@code SUCCESS} — входы, {@code FAILED} — отказы,
     *                пусто — и то и другое
     */
    @Transactional(readOnly = true)
    public Journal page(String member, String outcome, Instant from, Instant to, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE true");

        if (member != null && !member.isBlank()) {
            where.append(" AND coalesce(m.display_name, s.login_attempted) = ?");
            args.add(member.strip());
        }
        if ("SUCCESS".equals(outcome) || "FAILED".equals(outcome)) {
            where.append(" AND s.outcome = ?");
            args.add(outcome);
        }
        if (from != null) {
            where.append(" AND s.started_at >= ?");
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND s.started_at <= ?");
            args.add(java.sql.Timestamp.from(to));
        }

        // Разделители пишутся явными строками, а не отступом текстового блока:
        // блок срезает пробелы по краям, и склейка даёт «ANDCASE» — отказ
        // Postgres на грамматике при молчащем компиляторе. В этом проекте так
        // ломались история карточки и выгрузка колёс, оба раза на живом запросе.
        String source = "  FROM login_session s"
                + " LEFT JOIN tenant_member m ON m.id = s.member_id";

        Long total = jdbc.queryForObject(
                "SELECT count(*)" + source + where, Long.class, args.toArray());

        List<Object> paged = new ArrayList<>(args);
        paged.add(size + 1);

        List<Entry> found = jdbc.query("""
                        SELECT s.id, s.started_at, s.last_seen_at, s.ended_at, s.end_reason,
                               s.end_detail, s.outcome, s.failure_reason, s.login_attempted,
                               s.member_role, s.ip, s.user_agent,
                               m.display_name"""
                        + source + where
                        + " ORDER BY s.started_at DESC, s.id DESC LIMIT ?",
                (rs, i) -> new Entry(
                        rs.getLong("id"),
                        // Имя сотрудника, а у неудачной попытки с неизвестным
                        // логином — то, что ввели: это и есть ответ на «кто
                        // ломился». Показывать вместо него прочерк значит
                        // прятать единственное, что о попытке известно.
                        rs.getString("display_name") != null
                                ? rs.getString("display_name")
                                : rs.getString("login_attempted"),
                        rs.getString("display_name") == null,
                        rs.getString("member_role"),
                        rs.getTimestamp("started_at").toInstant(),
                        instantOrNull(rs.getTimestamp("last_seen_at")),
                        instantOrNull(rs.getTimestamp("ended_at")),
                        rs.getString("end_reason"),
                        rs.getString("end_detail"),
                        "SUCCESS".equals(rs.getString("outcome")),
                        rs.getString("failure_reason"),
                        UserAgents.describe(rs.getString("user_agent")),
                        rs.getString("user_agent"),
                        rs.getString("ip")),
                paged.toArray());

        boolean more = found.size() > size;
        return new Journal(more ? found.subList(0, size) : found, total == null ? 0 : total, more);
    }

    /**
     * Значения для отбора.
     *
     * <p>С сервера, а не списком во фронтенде: перечисленный там, он разошёлся
     * бы с тем, что сервер ищет, и отбор перестал бы находить. Тот же довод,
     * что у меню колонки на витрине склада.
     *
     * <p><b>Половина этого списка приходит снаружи, и потому она ограничена.</b>
     * У журнала изменений «кто» берётся из {@code tenant_member} — их десятки,
     * и число их задаёт владелец. Здесь рядом стоят логины, <b>введённые
     * в форме входа</b>: сколько их будет, решает не владелец, а тот, кто
     * подбирает пароль, — тысяча попыток даёт тысячу разных строк, и список
     * «всех» превращается в список подбиравшего. Поэтому сотрудники берутся
     * все, а чужие логины — только последние {@value #ATTEMPTED_CAP}.
     * Из самого журнала при этом не пропадает ни одна строка: отбор — это
     * удобство, а список отказов целиком показывает «Только отказы».
     */
    @Transactional(readOnly = true)
    public List<String> values(String column) {
        return switch (column) {
            // Один список на сотрудников и на введённые при отказе логины:
            // спрашивают «кто заходил», а не «кто из заведённых заходил».
            case "member" -> jdbc.queryForList("""
                    SELECT DISTINCT m.display_name
                      FROM login_session s
                      JOIN tenant_member m ON m.id = s.member_id
                     WHERE m.display_name IS NOT NULL
                     UNION
                    SELECT login_attempted FROM (
                        SELECT login_attempted, max(started_at) AS last_at
                          FROM login_session
                         WHERE member_id IS NULL
                         GROUP BY login_attempted
                         ORDER BY last_at DESC
                         LIMIT ?) recent
                     ORDER BY 1""", String.class, ATTEMPTED_CAP);
            default -> List.of();
        };
    }

    private static Instant instantOrNull(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    // ----------------------------------------------------------------- ответы

    /**
     * @param who        имя сотрудника либо введённый логин у отказа
     * @param unknown    такого сотрудника нет: {@code who} — то, что ввели
     *                   в форме входа. Экран обязан назвать это иначе, чем
     *                   имя своего человека
     * @param role       роль <b>на момент входа</b>, снимком. {@code null}
     *                   у отказов и у входов до 9 сентября 2026
     * @param lastSeenAt последняя активность: по ней считается длительность,
     *                   а не по {@code endedAt}
     * @param endReason  {@code LOGOUT}, {@code EXPIRED}, {@code REVOKED}
     *                   либо {@code null} — сессия ещё действует
     * @param device     разобранная строка браузера либо {@code null} —
     *                   не разобрали; сырая строка рядом, в {@code userAgent}
     */
    public record Entry(long id, String who, boolean unknown, String role,
                        Instant at, Instant lastSeenAt, Instant endedAt,
                        String endReason, String endDetail,
                        boolean success, String failureReason,
                        String device, String userAgent, String ip) {
    }

    /**
     * @param more есть что показать дальше по кнопке. Считается по строке
     *             сверх запрошенных, а не по «страница набралась полной»:
     *             последняя страница набирается полной ровно так же,
     *             и кнопка вернула бы те же строки
     */
    public record Journal(List<Entry> items, long total, boolean more) {
    }
}
