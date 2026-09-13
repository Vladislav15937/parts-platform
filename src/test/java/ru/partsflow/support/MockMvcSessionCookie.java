package ru.partsflow.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Браузер для MockMvc: носит cookie сессии между запросами.
 *
 * <p><b>Зачем это вообще нужно.</b> С задачи 0080 состояние сессии живёт
 * в общей схеме ячейки (Spring Session JDBC), а в запросе от неё остаётся
 * ровно то, что остаётся и в бою, — cookie {@code SESSION}. MockMvc браузером
 * не является: cookie из ответа он в следующий запрос не переносит, и сорок
 * тестовых помощников вида
 *
 * <pre>{@code
 *     MvcResult result = mvc.perform(post("/api/auth/login")...).andReturn();
 *     return (MockHttpSession) result.getRequest().getSession(false);
 * }</pre>
 *
 * <p>после переезда сессии в базу получали {@code null}: сессию заводит уже
 * не контейнер, а репозиторий, и на самом {@code MockHttpServletRequest}
 * её больше нет.
 *
 * <p><b>Что делает этот кастомайзер.</b> Ровно две вещи, и обе — работа
 * браузера, а не поблажка коду:
 *
 * <ul>
 *   <li>после каждого запроса, чей ответ ставит cookie {@code SESSION},
 *       кладёт её значение в {@code MockHttpSession} запроса (заводя её,
 *       если надо) — то есть возвращает помощникам тот самый объект,
 *       который они отдают тесту;
 *   <li>перед каждым запросом, которому передали {@code .session(...)},
 *       достаёт значение обратно и подставляет cookie.
 * </ul>
 *
 * <p>То есть {@code MockHttpSession} здесь — не сессия, а <b>вкладка
 * браузера</b>: она носит cookie и больше ничего. Атрибутов сессии
 * в ней нет и быть не может — они лежат в базе, как в бою.
 *
 * <p><b>Почему поблажки нет в боевом коде.</b> Можно было научить резолвер
 * идентификатора сессии брать его у контейнерной сессии запроса, когда
 * cookie нет, — и не трогать тесты вовсе. Но в бою этот путь не исполняется
 * никогда: фильтр Spring Session стоит первым, и контейнерной сессии
 * не заводит никто. Мёртвый путь, живущий ради тестов, — это ровно то,
 * что потом принимают за работающий механизм.
 *
 * <p><b>Бин виден всем контекстам</b> — он лежит в пакете {@code ru.partsflow},
 * а сканирование компонентов идёт по classpath, куда входят и тестовые
 * классы. Отдельного контекста Spring это не создаёт: ключ кэша складывается
 * из классов конфигурации и свойств, а не из найденных сканированием бинов.
 */
@Component
public class MockMvcSessionCookie implements MockMvcBuilderCustomizer {

    /** Имя cookie сессии — умолчание Spring Session, оно же в бою. */
    private static final String COOKIE = "SESSION";

    /** Где «вкладка» держит значение cookie. Своё имя, чтобы не спутать. */
    private static final String CARRIED = "partsflow.test.sessionCookie";

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.defaultRequest(get("/").with(MockMvcSessionCookie::send));
        builder.alwaysDo(MockMvcSessionCookie::keep);
    }

    /** Подставить cookie из «вкладки», переданной тестом через {@code .session(...)}. */
    private static MockHttpServletRequest send(MockHttpServletRequest request) {
        HttpSession tab = request.getSession(false);
        if (tab == null || !(tab.getAttribute(CARRIED) instanceof String value)
                || value.isEmpty()) {
            return request;
        }
        List<Cookie> cookies = new ArrayList<>();
        if (request.getCookies() != null) {
            cookies.addAll(List.of(request.getCookies()));
        }
        cookies.removeIf(cookie -> COOKIE.equals(cookie.getName()));
        cookies.add(new Cookie(COOKIE, value));
        request.setCookies(cookies.toArray(Cookie[]::new));
        return request;
    }

    /**
     * Запомнить cookie, которую поставил ответ.
     *
     * <p>Пустое значение — это выход или уничтоженная сессия: его тоже надо
     * запомнить, иначе следующий запрос той же вкладки уедет со старой cookie
     * и окажется «всё ещё вошедшим» там, где в браузере он уже нет.
     */
    private static void keep(MvcResult result) {
        Cookie set = result.getResponse().getCookie(COOKIE);
        if (set == null) {
            return;
        }
        result.getRequest().getSession(true).setAttribute(CARRIED, set.getValue());
    }
}
