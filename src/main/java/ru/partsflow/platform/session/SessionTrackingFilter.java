package ru.partsflow.platform.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.TenantPrincipal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Что происходит с живой сессией на каждом запросе: отметка активности.
 *
 * <p>Сессия существует ровно постольку, поскольку с ней приходят, и наблюдать
 * за ней можно только здесь.
 *
 * <p><b>Проверки отзыва здесь больше нет, и это не потеря.</b> До задачи 0080
 * отзыв был отметкой в памяти экземпляра, и сверять её приходилось на каждом
 * запросе. Теперь состояние сессии лежит в общей схеме ячейки, а отзыв —
 * это удаление строки ({@link SessionRevocations}): отозванную сессию
 * не находит уже {@code SessionRepositoryFilter}, и до этого места запрос
 * доходит невошедшим — то есть получает тот же 401 с пустым телом, только
 * на любом экземпляре, а не на том, где отзыв сделали.
 *
 * <p><b>Отметка активности прорежена, и это главное требование задачи.</b>
 * «Отметку активности нельзя обновлять на каждый запрос — это запись в базу
 * на каждый клик. Обновлять не чаще раза в 1–5 минут» (ответы владельца
 * продукта от 9 сентября 2026, docs/sessions.md §5). Прореживание держится
 * пометкой в самой сессии, а не запросом «а когда была прошлая отметка»:
 * второе стоило бы ровно того же, чего мы избегаем.
 *
 * <p><b>Точность здесь — минуты, и она никому не нужна точнее.</b> Длительность
 * смены считается по последней активности, и ошибка в пару минут на границе
 * не меняет ни одного ответа, ради которого журнал открывают.
 *
 * <p><b>Пометка о прореживании живёт в памяти экземпляра, а не в сессии, —
 * и после переезда сессий в базу (0080) иначе нельзя.</b> Атрибут сессии
 * теперь строка в общей схеме, то есть каждая пометка — запись в базу
 * (ровно то, чего прореживание и избегает), а первая её запись ещё
 * и не выдерживает одновременных запросов: шесть параллельных отправок
 * офлайн-очереди с одной cookie вставляют один и тот же атрибут разом
 * и получают нарушение первичного ключа — то есть <b>ошибку на успешную
 * приёмку</b>. Поймано полным прогоном на {@code IntakeRetryHttpTest}
 * и {@code MarketplaceOrderHttpTest}: оба проверяют ровно этот сценарий.
 *
 * <p>Состоянием сессии эта пометка не является: потеряв её при перезапуске,
 * мы заплатим одной лишней записью в журнал на сессию, а не выходом
 * человека. Гонки на ней нет — отмечается тот, кто выиграл
 * {@code merge}.
 *
 * <p><b>Фильтр стоит после цепочки Spring Security</b> (у неё порядок −100,
 * у обычного бина-фильтра — последний): до неё вошедшего ещё нет, и отмечать
 * было бы нечего.
 */
@Component
public class SessionTrackingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionTrackingFilter.class);

    /** Суррогат сессии для журнала. Кладётся при входе, читается здесь. */
    public static final String KEY = "partsflow.session.key";

    /**
     * Ключ сессии, из которой пришёл запрос.
     *
     * <p>Нужен отзыву: сотрудник, сменивший свой пароль, остаётся работать,
     * а всех остальных выкидывает.
     *
     * @return {@code null} — запрос без сессии либо сессия не от входа
     */
    public static String keyOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(KEY) instanceof String key ? key : null;
    }

    private final LoginSessions sessions;
    private final Duration markEvery;

    /**
     * Когда по каждой сессии в последний раз писали отметку активности.
     *
     * <p>Своя у каждого экземпляра приложения, и это верно: прореживание —
     * про то, как часто ходить в базу, а не про то, кто вошёл. Записей
     * в ней не больше, чем сессий, которые работали за последние
     * {@code markEvery}, — остальные выметает {@link #purge}.
     */
    private final Map<String, Instant> marked = new ConcurrentHashMap<>();

    /** Когда в последний раз выметали устаревшие пометки. */
    private final AtomicReference<Instant> purgedAt = new AtomicReference<>(Instant.EPOCH);

    public SessionTrackingFilter(LoginSessions sessions,
                                 @Value("${app.sessions.activity-interval:2m}") Duration markEvery) {
        this.sessions = sessions;
        this.markEvery = markEvery;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        TenantPrincipal principal = CurrentUser.get().orElse(null);
        if (session == null || principal == null) {
            chain.doFilter(request, response);
            return;
        }

        // Сессия, заведённая не входом (CSRF-токен до формы входа), журнала
        // не имеет — и это не ошибка, а её нормальное состояние.
        if (!(session.getAttribute(KEY) instanceof String sessionKey)) {
            chain.doFilter(request, response);
            return;
        }

        markSeen(principal, sessionKey);
        chain.doFilter(request, response);
    }

    private void markSeen(TenantPrincipal principal, String sessionKey) {
        Instant now = Instant.now();
        purge(now);

        // Пометка ставится до запроса, а не после: отказ базы не должен
        // означать поход в неё на каждом следующем запросе. И ставится она
        // одним действием: из двух одновременных запросов отмечается тот,
        // чьё значение легло в карту, — сравнение по ссылке здесь точное,
        // а «прочитали, сравнили, записали» пропустило бы обоих.
        Instant winner = marked.merge(sessionKey, now,
                (previous, candidate) -> previous.plus(markEvery).isAfter(candidate)
                        ? previous : candidate);
        if (winner != now) {
            return;
        }
        try {
            sessions.markSeen(principal.tenantSchema(), sessionKey);
        } catch (RuntimeException e) {
            // Журнал не мешает работать: пусть строка истории потеряется,
            // а продавец продаёт.
            log.debug("Отметка активности не записана", e);
        }
    }

    /**
     * Выметает пометки, которые уже ничего не прореживают.
     *
     * <p>Пометка старше {@code markEvery} не влияет ни на что: следующий
     * запрос этой сессии всё равно пойдёт в базу. Проход идёт не чаще раза
     * в тот же интервал — иначе обход карты стоил бы дороже самой записи,
     * которой мы избегаем.
     */
    private void purge(Instant now) {
        Instant previous = purgedAt.get();
        if (previous.plus(markEvery).isAfter(now) || !purgedAt.compareAndSet(previous, now)) {
            return;
        }
        marked.values().removeIf(at -> at.plus(markEvery).isBefore(now));
    }
}
