package ru.partsflow.platform.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Запись журнала сессий: вход, отметка активности, конец сессии.
 *
 * <p><b>Зачем он есть.</b> До 9 сентября 2026 о входах не оставалось ничего,
 * кроме {@code tenant_member.last_login_at}, а та перезаписывается: предыдущий
 * вход не хранился нигде. Владелец при этом спрашивает «кто заходил в кабинет»
 * ровно тогда же, когда «кто уронил цену» (задача 0043, решение владельца
 * продукта от 8 сентября 2026), а {@code audit_log} входов не видит по природе:
 * его пишет слушатель Hibernate, а вход — не изменение строки в базе.
 *
 * <p><b>Схема подставляется в SQL руками, и это не небрежность.</b> Три из пяти
 * путей сюда идут <b>вне</b> транзакции JPA и вне контекста арендатора: вход
 * происходит до того, как арендатор известен (на то он и вход), отметка
 * активности и выход живут в фильтре сервлета, уборка зависших — в фоновом
 * обходе по всем схемам ячейки. {@code JdbcTemplate} без транзакции уходит
 * в {@code public} — та самая ловушка, на которую в этом проекте наступали
 * пять раз, — и лечится она здесь тем же приёмом, что в
 * {@code MemberAuthenticationProvider}: имя схемы пишется в запрос явно.
 * Безопасно это потому, что имя приходит из реестра либо из личности
 * вошедшего, и проверяется регуляркой до склейки.
 *
 * <p><b>Отказы во входе лежат здесь же, а не в своей таблице.</b> «Пять отказов
 * подряд ценнее ста успешных входов» (docs/sessions.md, §3), и спрашивают о них
 * тем же вопросом и в том же списке. У отказа нет сессии: ключ, активность
 * и конец у него пусты, и уборка зависших его не трогает.
 */
@Component
public class LoginSessions {

    private static final Logger log = LoggerFactory.getLogger(LoginSessions.class);

    private final JdbcTemplate jdbc;

    public LoginSessions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Суррогат сессии для журнала.
     *
     * <p>Хеш, а не сам идентификатор: «журнал не должен становиться складом
     * действующих ключей» (docs/sessions.md, §3). Найти по нему свою запись
     * можно, войти — нет, и утёкший дамп журнала не даёт чужих сессий.
     */
    public static String key(String sessionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 обязателен для любой JVM; если его нет, дело не в журнале.
            throw new IllegalStateException("Нет SHA-256", impossible);
        }
    }

    /**
     * Удачный вход.
     *
     * <p>Роль пишется снимком — тем же решением, что у
     * {@code audit_log.changed_by_role}: она живёт одной колонкой
     * в {@code tenant_member} и переписывается на месте, поэтому подтянутая
     * при чтении соврала бы задним числом про переведённого человека.
     */
    public void recordLogin(String schema, Long memberId, String login, String role,
                            String sessionKey, String ip, String userAgent) {
        update(schema, """
                        INSERT INTO %s.login_session
                            (member_id, login_attempted, member_role, outcome, session_key,
                             started_at, last_seen_at, ip, user_agent)
                        VALUES (?, ?, ?, 'SUCCESS', ?, now(), now(), ?, ?)""",
                "вход", memberId, login, role, sessionKey, ip, userAgent);
    }

    /**
     * Неудачная попытка входа.
     *
     * <p>Пишется только тогда, когда известна компания: пароль, поданный
     * с несуществующим кодом компании, не принадлежит ни одной организации,
     * и показать его некому. Сотрудник при этом может быть неизвестен —
     * тогда остаётся введённый логин, и по нему как раз и видно подбор.
     */
    public void recordFailure(String schema, String login, Long memberId, String role,
                              String reason, String ip, String userAgent) {
        update(schema, """
                        INSERT INTO %s.login_session
                            (member_id, login_attempted, member_role, outcome, failure_reason,
                             started_at, ip, user_agent)
                        VALUES (?, ?, ?, 'FAILED', ?, now(), ?, ?)""",
                "отказ во входе", memberId, login, role, reason, ip, userAgent);
    }

    /**
     * Отметка активности.
     *
     * <p>Зовётся не на каждый запрос — прореживание стоит в
     * {@link SessionTrackingFilter}. Здесь только запись, и она намеренно
     * без чтения: «есть ли уже свежая отметка» решает фильтр по своей же
     * пометке в сессии, а не поход в базу.
     */
    public void markSeen(String schema, String sessionKey) {
        update(schema, """
                        UPDATE %s.login_session SET last_seen_at = now()
                         WHERE session_key = ? AND ended_at IS NULL""",
                "отметка активности", sessionKey);
    }

    /**
     * Выход по кнопке.
     *
     * <p>Активность двигается вместе с концом: нажатие «Выйти» — это и есть
     * последнее действие человека, и оставить прежнюю отметку значит укоротить
     * его смену на время до неё.
     */
    public void recordLogout(String schema, String sessionKey) {
        update(schema, """
                        UPDATE %s.login_session
                           SET ended_at = now(), last_seen_at = now(), end_reason = 'LOGOUT'
                         WHERE session_key = ? AND ended_at IS NULL""",
                "выход", sessionKey);
    }

    /**
     * Отзыв сессий сотрудника: смена пароля, выключение учётной записи.
     *
     * <p>{@code spareSessionKey} — сессия, из которой отзыв и сделан. Сотрудник,
     * сменивший свой пароль, остаётся работать: выкинуть его вместе с угонщиком
     * значит наказать того, кто как раз всё сделал правильно. Чужие сессии
     * закрываются все.
     *
     * <p><b>Единственный метод, который не проглатывает отказ базы.</b>
     * Остальные пишут журнал: не записалось — потеряна строка истории. Здесь
     * же отзыв, и молча не состоявшийся отзыв — это ложь о безопасности:
     * владелец, выключивший сотрудника, будет считать, что тот выведен,
     * а на экране останется открытая сессия. Пусть лучше не пройдёт сама
     * смена пароля.
     *
     * @return сколько записей закрыто
     */
    public int revoke(String schema, long memberId, String detail, String spareSessionKey) {
        return jdbc.update("""
                        UPDATE %s.login_session
                           SET ended_at = now(), end_reason = 'REVOKED', end_detail = ?
                         WHERE member_id = ? AND outcome = 'SUCCESS' AND ended_at IS NULL
                           AND session_key IS DISTINCT FROM ?"""
                        .formatted(requireSchema(schema)),
                detail, memberId, spareSessionKey);
    }

    /**
     * Уборка зависших: приложение упало или перезапустилось, запись осталась
     * открытой.
     *
     * <p><b>Конец ставится задним числом</b> — моментом, когда сессия перестала
     * быть действительной, то есть последняя активность плюс срок простоя.
     * Писать сюда «сейчас» значит утверждать, что человек работал до этой
     * минуты: закрыл ноутбук в 18:00, уборка прошла в 6:00 — и в журнале
     * «работал двенадцать часов». Длительность работы при этом считается
     * по {@code last_seen_at}, а не по концу: между отметками активности
     * человек мог и не работать, но до последней — работал.
     *
     * @param idle срок простоя, после которого сессия недействительна
     * @return сколько записей закрыто
     */
    public int closeStale(String schema, Duration idle) {
        long seconds = idle.toSeconds();
        return update(schema, """
                        UPDATE %s.login_session
                           SET ended_at = coalesce(last_seen_at, started_at)
                                            + (? * interval '1 second'),
                               end_reason = 'EXPIRED'
                         WHERE outcome = 'SUCCESS' AND ended_at IS NULL
                           AND coalesce(last_seen_at, started_at) < now() - (? * interval '1 second')""",
                "уборка зависших сессий", seconds, seconds);
    }

    /**
     * Запись журнала не роняет то, что она записывает.
     *
     * <p>Схема арендатора, отставшая на эту миграцию, — обычное дело
     * в ячейке между накатами ({@code SchemaVersionCheck} про это и пишет
     * при старте). Бросив исключение наружу, журнал сессий отнял бы у такого
     * клиента <b>вход в систему целиком</b>: не «журнал пуст», а «войти
     * нельзя». Отсюда {@code catch} с {@code ERROR} в логе — тот же довод,
     * по которому не валят старт {@code JournalProtectionCheck}
     * и {@code SchemaVersionCheck}.
     */
    private int update(String schema, String sql, String what, Object... args) {
        try {
            return jdbc.update(sql.formatted(requireSchema(schema)), args);
        } catch (RuntimeException e) {
            log.error("Журнал сессий, {}: не записано в схеме {}", what, schema, e);
            return 0;
        }
    }

    /**
     * Имя схемы подставляется в текст запроса — параметром нельзя.
     *
     * <p>Приходит оно из реестра арендаторов либо из личности вошедшего,
     * то есть не от пользователя; и всё же проверяется, потому что цена
     * ошибки здесь не «запрос не выполнится», а выполненный чужой SQL.
     */
    private static String requireSchema(String schema) {
        if (schema == null || !schema.matches("t_\\d{6,}")) {
            throw new IllegalArgumentException("Недопустимое имя схемы арендатора: " + schema);
        }
        return schema;
    }
}
