package ru.partsflow.platform.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Отзыв сессий сотрудника: смена пароля, выключение учётной записи.
 *
 * <p><b>Зачем.</b> «Смена пароля, смена прав и блокировка сотрудника обязаны
 * убивать сессии — иначе понижённый в правах доработает смену со старыми»
 * (docs/sessions.md, §6; ответы владельца продукта от 9 сентября 2026).
 * До этого выключенный сотрудник продолжал работать до конца дня: {@code
 * TenantPrincipal} лежит в сессии снимком, и выключение его не касалось.
 *
 * <p><b>Отзыв — это удаление сессии из общего хранилища, а не отметка
 * в памяти.</b> До задачи 0080 здесь лежал {@code ConcurrentHashMap}
 * «кого перестали пускать и с какого момента», и работал он ровно до второго
 * экземпляра приложения: отметка оставалась там, где её поставили, а сосед
 * пускал по той же cookie до истечения. То есть обещание «отозвали —
 * и сессия умерла сразу» становилось ложью в тот день, когда экземпляров
 * стало два, и узнать об этом было неоткуда. Теперь состояние сессии живёт
 * в общей схеме ячейки ({@link SharedSessionStore}), и удалённую строку
 * не находит ни один экземпляр — включая тот, который её и не видел.
 *
 * <p><b>«Немедленно» по-прежнему означает «на ближайшем запросе»</b>, и сильнее
 * быть не может: cookie-сессия наблюдаема только тогда, когда с ней приходят.
 * Разница в том, что теперь это верно для всех экземпляров, а не для одного.
 *
 * <p><b>Сравнения времени входа больше нет — и его отсутствие важно.</b>
 * Отметка в памяти жила дольше сессий, поэтому её приходилось сравнивать
 * с временем входа: иначе смена пароля заперла бы сотрудника навсегда, убивая
 * и те сессии, которыми он вошёл уже после неё. Удаление такого вопроса
 * не ставит: убиваются ровно те сессии, что существуют сейчас, а заведённая
 * следующей секундой законна по построению.
 */
@Component
public class SessionRevocations {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocations.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionRevocations(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /**
     * Отозвать сессии сотрудника.
     *
     * <p>Ищутся они по составному имени «схема и номер сотрудника»
     * ({@link SharedSessionStore#principalIndex}), а не по логину: логин
     * уникален только внутри арендатора, и отзыв по нему выключал бы
     * однофамильца в чужой компании.
     *
     * @param spareSessionKey сессия, из которой отзыв сделан: сотрудник,
     *                        сменивший свой пароль, остаётся работать —
     *                        выкинуть того, кто как раз всё сделал правильно,
     *                        верный способ отучить менять пароли.
     *                        {@code null} — отозвать все
     * @return сколько сессий закрыто
     */
    public int revoke(String schema, long memberId, String spareSessionKey) {
        Map<String, ? extends Session> found = sessions.findByPrincipalName(
                SharedSessionStore.principalIndex(schema, memberId));

        int revoked = 0;
        for (Map.Entry<String, ? extends Session> entry : found.entrySet()) {
            if (spared(entry.getValue(), spareSessionKey)) {
                continue;
            }
            sessions.deleteById(entry.getKey());
            revoked++;
        }
        log.debug("Арендатор {}, сотрудник {}: отозвано сессий {}", schema, memberId, revoked);
        return revoked;
    }

    /**
     * Своя ли это сессия того, кто отзывает.
     *
     * <p>Сравнивается суррогат из самой сессии, а не её идентификатор: именно
     * его знает вызывающий — идентификатор наружу не отдаётся вовсе, чтобы
     * журнал не становился складом действующих ключей.
     */
    private static boolean spared(Session session, String spareSessionKey) {
        return spareSessionKey != null
                && Objects.equals(session.getAttribute(SessionTrackingFilter.KEY),
                                  spareSessionKey);
    }
}
