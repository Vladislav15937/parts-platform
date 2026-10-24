package ru.partsflow.platform.security;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.partsflow.support.PostgresTestBase;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Права на путях кабинета — перебором путей самого приложения (задача 0044).
 *
 * <p><b>Зачем перебор.</b> До 0044 изменяющие пути закрывал от «Просмотра»
 * запрет по методам в {@code SecurityConfig}: забытая на новом эндпоинте
 * проверка роли оставляла его закрытым для роли «только смотреть». Ролей
 * с таким свойством больше нет, и забытая аннотация теперь открыла бы путь
 * <b>всем вошедшим</b> — направление отказа обратное. Поэтому изменяющий
 * путь без права валит прогон здесь, а не всплывает у клиента.
 *
 * <p>Пути берутся у {@link RequestMappingHandlerMapping}, а не списком в тесте:
 * путь, добавленный завтра, проверяется сам.
 *
 * <p><b>Матрица прав считается выражениями аннотаций, а не вызовом
 * контроллера.</b> Для каждого пути с правом: сотрудник со всеми правами,
 * кроме этого, — отказ; с одним этим — пропуск; владелец без единого права
 * в наборе — пропуск. Тело метода при этом не исполняется (ни базы, ни
 * хранилища, ни сети), а проверяется ровно то выражение, которое увидит
 * перехватчик. Что перехватчик действительно стоит, проверяют HTTP-тесты
 * ({@code PermissionHttpTest} и тесты ролей по модулям).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PermissionGuardTest extends PostgresTestBase {

    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    /**
     * Изменяющие пути кабинета без права на методе — каждый с причиной.
     * Пометка без причины не принимается: список — это решение по каждому
     * пути, а не способ замолчать перебор.
     */
    private static final Map<String, String> NO_PERMISSION = Map.of(
            "POST /api/auth/login", "вход: за ним ещё нет никого",
            "PUT /api/me/settings/{screen}",
            "свой экран сотрудника: меняет не данные предприятия (SecurityConfig)",
            "DELETE /api/stock/documents/{id}",
            "право stock_document.delete_done проверяет сервис — одно место "
                    + "PostedDocumentDeletion, его же читают кнопки ленты и перевозок");

    /** Пути вне кабинета: свои входы и свои секреты, прав сотрудника у них нет. */
    private static final List<String> OUTSIDE_CABINET = List.of(
            "/api/admin/", "/api/provisioning/", "/api/registration/", "/api/mail/events/");

    /**
     * Права, которые решают не путь, а содержимое ответа или признак кнопки, —
     * их спрашивает код ({@link TenantPrincipal#can}), а не аннотация.
     */
    private static final Set<Permission> IN_CODE =
            EnumSet.of(Permission.PART_MONEY_VIEW, Permission.STOCK_DOCUMENT_DELETE_DONE);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final DefaultMethodSecurityExpressionHandler expressions =
            new DefaultMethodSecurityExpressionHandler();

    @Test
    @DisplayName("Изменяющий путь кабинета без права — только из названных с причиной")
    void everyChangingPathHasPermission() {
        List<String> unguarded = new ArrayList<>();
        int changing = 0;
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String route : routes(entry.getKey())) {
                if (route.startsWith("GET ") || outside(route)) {
                    continue;
                }
                changing++;
                String expression = preAuthorize(entry.getValue());
                if ((expression == null || !AUTHORITY.matcher(expression).find())
                        && !NO_PERMISSION.containsKey(route)) {
                    unguarded.add(route + " (" + entry.getValue().getShortLogMessage() + ")");
                }
            }
        }
        assertThat(changing).as("перебор нашёл изменяющие пути").isGreaterThan(100);
        assertThat(unguarded)
                .as("изменяющий путь без права открыт любому вошедшему: поставьте "
                        + "@PreAuthorize(\"hasAuthority('…')\") или назовите причину в NO_PERMISSION")
                .isEmpty();
        assertThat(NO_PERMISSION.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("На путях нет жёстких ролей, кроме администратора сервиса, и каждый ключ — из справочника")
    void onlyKnownPermissionsInAnnotations() {
        List<String> roles = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            String expression = preAuthorize(handler);
            if (expression == null) {
                continue;
            }
            String withoutAdmin = expression.replace("hasRole('ADMIN')", "");
            if (withoutAdmin.contains("hasRole") || withoutAdmin.contains("hasAnyRole")) {
                roles.add(handler.getShortLogMessage() + ": " + expression);
            }
            Matcher key = AUTHORITY.matcher(expression);
            while (key.find()) {
                if (Permission.byKey(key.group(1)).isEmpty()) {
                    unknown.add(handler.getShortLogMessage() + ": " + key.group(1));
                }
            }
        }
        assertThat(roles).as("права решает набор роли клиента, а не строка жёсткой роли").isEmpty();
        assertThat(unknown).as("ключ, которого нет в Permission, не откроет путь никому, кроме владельца")
                .isEmpty();
    }

    /**
     * Отказ пути словами (`PathRightRefusal`) понимает только права через `or`, «себе»
     * и хвост `and !hasRole('ADMIN')`. Путь с иной формой — `and` между правами,
     * отрицание права — он назвал бы неверно («нет ни одного из прав» там, где нужны
     * оба), поэтому новая форма заводится вместе с правкой разбора, а не молча
     * (разбор PR 518). Разбор проверяется и сам: на заведомо чужих формах — «не понимаю».
     */
    @Test
    @DisplayName("Каждое выражение пути с правом — той формы, которую отказ словами называет верно")
    void everyExpressionIsUnderstoodByRefusalWords() {
        List<String> foreign = new ArrayList<>();
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            String expression = preAuthorize(handler);
            if (expression != null && AUTHORITY.matcher(expression).find()
                    && !PathRightRefusal.understood(expression)) {
                foreign.add(handler.getShortLogMessage() + ": " + expression);
            }
        }
        assertThat(foreign).as("форма, которую PathRightRefusal назовёт неверно: научите его или смените форму")
                .isEmpty();
        assertThat(PathRightRefusal.understood("hasAuthority('a.b') and hasAuthority('c.d')")).isFalse();
        assertThat(PathRightRefusal.understood("!hasAuthority('a.b')")).isFalse();
        assertThat(PathRightRefusal.understood("hasAuthority('a.b') or hasRole('OWNER')")).isFalse();
        assertThat(PathRightRefusal.understood("hasAuthority('a.b') and !hasRole('ADMIN')")).isTrue();
        assertThat(PathRightRefusal.understood(
                "hasAuthority('a.b') or #id == authentication.principal.memberId")).isTrue();
    }

    @Test
    @DisplayName("Каждое право справочника что-то открывает")
    void everyPermissionGuardsSomething() {
        Set<String> used = new TreeSet<>();
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            String expression = preAuthorize(handler);
            if (expression != null) {
                Matcher key = AUTHORITY.matcher(expression);
                while (key.find()) {
                    used.add(key.group(1));
                }
            }
        }
        IN_CODE.forEach(permission -> used.add(permission.key()));
        Set<String> idle = Arrays.stream(Permission.values()).map(Permission::key)
                .filter(key -> !used.contains(key)).collect(Collectors.toCollection(TreeSet::new));
        assertThat(idle).as("галочка на экране ролей, которая ничего не меняет, — ложь владельцу")
                .isEmpty();
    }

    @Test
    @DisplayName("Матрица: без права — отказ, с одним этим правом — пропуск, владельцу — всё")
    void permissionMatrix() {
        Map<String, String> wrong = new LinkedHashMap<>();
        int checked = 0;
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            String expression = preAuthorize(handler);
            if (expression == null) {
                continue;
            }
            // Путь может открываться любым из нескольких прав («hasAuthority(a) or
            // hasAuthority(b)» — списки сотрудников и ролей при четырёх правах
            // на раздачу): тогда без всех них — отказ, с каждым одним — пропуск.
            Matcher key = AUTHORITY.matcher(expression);
            Set<String> named = new TreeSet<>();
            while (key.find()) {
                named.add(key.group(1));
            }
            if (named.isEmpty()) {
                continue;
            }
            Set<String> allButThese = Arrays.stream(Permission.values()).map(Permission::key)
                    .filter(k -> !named.contains(k)).collect(Collectors.toSet());
            String where = handler.getShortLogMessage();

            if (allows(handler, expression, member(false, allButThese))) {
                wrong.put(where + " без " + named, "пропустил");
            }
            for (String permission : named) {
                if (!allows(handler, expression, member(false, Set.of(permission)))) {
                    wrong.put(where + " с одним «" + permission + "»", "отказал");
                }
            }
            if (!allows(handler, expression, member(true, Set.of()))) {
                wrong.put(where + " владельцу", "отказал");
            }
            checked++;
        }
        assertThat(checked).as("перебор нашёл пути с правом").isGreaterThan(100);
        assertThat(wrong).as("путь решает не тем правом, которое на нём написано").isEmpty();
    }

    // ---------------------------------------------------------- вспомогательное

    private boolean allows(HandlerMethod handler, String expression, TenantPrincipal principal) {
        Method method = handler.getMethod();
        MethodInvocation invocation = new SimpleMethodInvocation(
                handler.getBean(), method, new Object[method.getParameterCount()]);
        Authentication authentication = new TenantAuthenticationToken(principal,
                principal.getAuthorities());
        EvaluationContext context = expressions.createEvaluationContext(
                () -> authentication, invocation);
        Expression parsed = expressions.getExpressionParser().parseExpression(expression);
        return ExpressionUtils.evaluateAsBoolean(parsed, context);
    }

    private static TenantPrincipal member(boolean owner, Set<String> permissions) {
        // Номер сотрудника, которого заведомо нет: «сменить пароль себе»
        // (#id == principal.memberId) при пустом аргументе не сработает.
        return new TenantPrincipal("t_000441", 441L, -1L, "perebor", "Перебор",
                owner ? "OWNER" : "VIEWER", true, owner, permissions);
    }

    private static String preAuthorize(HandlerMethod handler) {
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(
                handler.getMethod(), PreAuthorize.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(
                handler.getBeanType(), PreAuthorize.class);
        return onClass == null ? null : onClass.value();
    }

    private static List<String> routes(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        List<String> routes = new ArrayList<>();
        for (String pattern : info.getPatternValues()) {
            if (!pattern.startsWith("/api/")) {
                continue;
            }
            for (RequestMethod method : methods.isEmpty() ? EnumSet.allOf(RequestMethod.class) : methods) {
                routes.add(method.name() + " " + pattern);
            }
        }
        return routes;
    }

    private static boolean outside(String route) {
        String path = route.substring(route.indexOf(' ') + 1);
        return OUTSIDE_CABINET.stream().anyMatch(path::startsWith);
    }
}
