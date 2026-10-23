package ru.partsflow.platform.security;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Проходит ли вошедший путь кабинета — тем же решением, что приложение:
 * правило пути из живой цепочки Spring Security ({@link AuthorizationFilter})
 * и {@code @PreAuthorize} на методе тем же {@link PreAuthorizeAuthorizationManager},
 * что ставит {@code @EnableMethodSecurity}.
 *
 * <p><b>Этот файл — общий у эталона и проверки, и копируется в прежний код
 * без единой правки.</b> Эталон прежних проходимостей
 * ({@code src/test/resources/access/legacy-access-b07449e9.tsv}) снят им же
 * на коммите {@code b07449e9} (первый родитель слияния {@code 8dfaccde}, то есть
 * {@code main} до PR 506), а {@link LegacyAccessAfterDeployTest} спрашивает его
 * о нынешнем приложении. Разное у двух сторон — только код приложения
 * и вошедший; способ спросить один, и расхождение в таблице поэтому значит
 * расхождение в правилах, а не в способе их читать. Как снят эталон —
 * {@code src/test/resources/access/README.md}.
 *
 * <p><b>Почему решение, а не запрос.</b> {@code @PreAuthorize} проверяется после
 * разбора аргументов: запрос с неразобравшимся телом отвечает 400 раньше,
 * чем дойдёт до права, и 403 на нём не увидеть. Решение не исполняет тело
 * метода (аргументы пустые) и потому одинаково честно на всех путях сразу;
 * что перехватчик и цепочка действительно стоят — проверяют настоящие
 * запросы в том же тесте.
 *
 * <p>Ответ — {@code allow}, {@code deny} или {@code error}: выражение, которое
 * не вычисляется с пустыми аргументами, — это ответ, а не повод молча
 * пропустить путь.
 */
public final class AccessMatrix {

    private final RequestMappingHandlerMapping handlers;
    private final AuthorizationManager<HttpServletRequest> paths;
    private final PreAuthorizeAuthorizationManager methods = new PreAuthorizeAuthorizationManager();

    @SuppressWarnings("unchecked")
    public AccessMatrix(ApplicationContext context) {
        this.handlers = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        AuthorizationManager<HttpServletRequest> found = null;
        FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
        for (SecurityFilterChain chain : proxy.getFilterChains()) {
            for (Filter filter : chain.getFilters()) {
                if (filter instanceof AuthorizationFilter authorization) {
                    found = authorization.getAuthorizationManager();
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException("В цепочке Spring Security нет AuthorizationFilter");
        }
        this.paths = found;
        DefaultMethodSecurityExpressionHandler expressions = new DefaultMethodSecurityExpressionHandler();
        expressions.setApplicationContext(context);
        methods.setExpressionHandler(expressions);
    }

    /** Пути кабинета ({@code /api/…}) — «МЕТОД шаблон» → обработчик. */
    public Map<String, HandlerMethod> routes() {
        Map<String, HandlerMethod> routes = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers.getHandlerMethods().entrySet()) {
            Set<RequestMethod> verbs = entry.getKey().getMethodsCondition().getMethods();
            for (String pattern : entry.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod verb : verbs.isEmpty() ? EnumSet.allOf(RequestMethod.class) : verbs) {
                    if (verb == RequestMethod.HEAD || verb == RequestMethod.OPTIONS
                            || verb == RequestMethod.TRACE) {
                        continue;
                    }
                    routes.put(verb.name() + " " + pattern, entry.getValue());
                }
            }
        }
        return routes;
    }

    /** Пропускает ли приложение вошедшего на путь «МЕТОД шаблон». */
    public String decide(String route, HandlerMethod handler, Authentication who) {
        String verb = route.substring(0, route.indexOf(' '));
        String pattern = route.substring(route.indexOf(' ') + 1);
        String uri = pattern.replaceAll("\\{[^}]*}", "1");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest(verb, uri);
            request.setServletPath(uri);
            AuthorizationDecision byPath = paths.check(() -> who, request);
            if (byPath != null && !byPath.isGranted()) {
                return "deny";
            }
            Method method = handler.getMethod();
            Object bean = handler.createWithResolvedBean().getBean();
            MethodInvocation invocation = new SimpleMethodInvocation(
                    bean, method, new Object[method.getParameterCount()]);
            AuthorizationDecision byMethod = methods.check(() -> who, invocation);
            return byMethod == null || byMethod.isGranted() ? "allow" : "deny";
        } catch (RuntimeException e) {
            return "error";
        }
    }
}
