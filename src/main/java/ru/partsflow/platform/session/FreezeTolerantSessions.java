package ru.partsflow.platform.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import ru.partsflow.platform.config.WriteFreeze;

import java.util.Map;

/**
 * Хранилище сессий, переживающее заморозку записи на время выкладки.
 *
 * <p><b>Зачем (задача 0112).</b> Выкладка замораживает запись в базу, пока
 * снимает копию и поднимает на ней новую сборку, — минуты. Чтение в это время
 * обязано работать: продавец ищет деталь, владелец смотрит склад. А Spring
 * Session пишет в {@code public.spring_session} на <b>каждом</b> запросе
 * вошедшего — отметку последнего обращения, — и отказ этой записи всплывал
 * в фильтре уже после ответа контроллера: каждый запрос вошедшего, даже
 * чистое чтение, отвечал пятисоткой. Заморозка записи превращалась бы
 * в остановку ячейки.
 *
 * <p><b>Что глотается и что нет.</b> Глотается только сохранение, и только
 * отказ замороженной базы. Потерянная отметка обращения стоит пары минут
 * точности срока простоя; новая сессия, не записавшаяся при входе, означает
 * вход, которого не было (сам вход в это время отвечает 503 — он пишет
 * журнал входов). Удаление сессии не глотается никогда: выход и отзыв доступа
 * не имеют права молча не случиться — это действие безопасности, и человек
 * должен увидеть, что оно не прошло.
 *
 * <p>Сырые типы — не небрежность: тип сессии у {@code JdbcIndexedSessionRepository}
 * закрыт пакетом, назвать его отсюда нельзя.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class FreezeTolerantSessions implements FindByIndexNameSessionRepository<Session> {

    private static final Logger log = LoggerFactory.getLogger(FreezeTolerantSessions.class);

    private final FindByIndexNameSessionRepository delegate;

    FreezeTolerantSessions(FindByIndexNameSessionRepository<? extends Session> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Session createSession() {
        return delegate.createSession();
    }

    @Override
    public void save(Session session) {
        try {
            delegate.save(session);
        } catch (RuntimeException e) {
            if (!WriteFreeze.isFrozen(e)) {
                throw e;
            }
            // Отладочным уровнем: во время заморозки это происходит на каждом
            // запросе каждого вошедшего, а сама заморозка видна в логе выкладки.
            log.debug("Сессия {} не сохранена: запись заморожена на время выкладки",
                    session.getId());
        }
    }

    @Override
    public Session findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public void deleteById(String id) {
        delegate.deleteById(id);
    }

    @Override
    public Map<String, Session> findByIndexNameAndIndexValue(String indexName, String indexValue) {
        return delegate.findByIndexNameAndIndexValue(indexName, indexValue);
    }
}
