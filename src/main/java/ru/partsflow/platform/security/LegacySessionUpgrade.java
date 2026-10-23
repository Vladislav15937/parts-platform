package ru.partsflow.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Дочитывает права в сессию, заведённую сборкой до задачи 0044.
 *
 * <p><b>Зачем.</b> Сессии живут в общей схеме ячейки и переживают выкладку
 * (задача 0080), а принципал в них — снимок того вида, который был при входе.
 * У входа до 0044 набора прав в принципале нет вовсе ({@code permissions}
 * приезжает {@code null}), и без этого места каждый, кто работал во время
 * выкладки, получил бы 403 на первом же нажатии — а офлайн-очередь телефона
 * читает 403 как отказ по существу и уводит запись в «требует внимания».
 * Условие владельца при переносе — «доступ при выкладке никто не теряет».
 *
 * <p>Читается один раз на сессию: дочитанный принципал кладётся обратно
 * в сессию, и следующий запрос сюда уже не заходит. Выход по истечению
 * и новый вход не нужны.
 *
 * <p><b>Стоит после цепочки Spring Security</b> (обычный бин-фильтр, как
 * {@code SessionTrackingFilter}), то есть раньше проверок на методах —
 * {@code @PreAuthorize} решает уже по дочитанным правам. Правила путей
 * в {@code SecurityConfig} прав не спрашивают (только «вошёл ли сотрудник
 * кабинета» и «не администратор ли»), и для них дочитывать нечего.
 */
@Component
public class LegacySessionUpgrade extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LegacySessionUpgrade.class);

    private final MemberAccess access;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public LegacySessionUpgrade(MemberAccess access) {
        this.access = access;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TenantPrincipal principal = CurrentUser.get().orElse(null);
        if (principal != null && principal.permissions() == null && !principal.isServiceAdmin()) {
            MemberAccess.Access rights = access.resolve(principal.tenantSchema(), principal.memberId());
            if (rights != null) {
                TenantPrincipal upgraded = principal.withAccess(rights.owner(), rights.permissions());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        new TenantAuthenticationToken(upgraded, upgraded.getAuthorities()));
                SecurityContextHolder.setContext(context);
                contextRepository.saveContext(context, request, response);
                log.debug("Арендатор {}, сотрудник {}: права дочитаны в прежнюю сессию",
                        principal.tenantSchema(), principal.memberId());
            }
        }
        chain.doFilter(request, response);
    }
}
