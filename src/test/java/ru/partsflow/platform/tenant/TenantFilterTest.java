package ru.partsflow.platform.tenant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.partsflow.platform.security.TenantAuthenticationToken;
import ru.partsflow.platform.security.TenantPrincipal;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Фильтр — единственное место, где вошедший пользователь превращается в схему,
 * поэтому его ошибки означают либо отказ в обслуживании, либо (хуже) работу
 * не с тем клиентом.
 */
class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Схема берётся из вошедшего пользователя")
    void setsTenantFromAuthenticatedUser() throws Exception {
        login("t_000042");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");

        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.getOrNull());

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("t_000042");
    }

    @Test
    @DisplayName("Заголовок X-Tenant-Id больше не действует")
    void headerIsIgnored() throws Exception {
        // Сторожевой тест. Пока арендатор приходил заголовком, любой читал чужой
        // склад, подставив другой номер, — а первые десять клиентов конкурируют
        // в одном городе. Если заголовок вернут «для удобства отладки», здесь
        // станет красно.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        request.addHeader("X-Tenant-Id", "42");

        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.getOrNull());

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).as("заголовок снова определяет арендатора").isNull();
    }

    @Test
    @DisplayName("Чужой заголовок не перебивает схему вошедшего пользователя")
    void headerDoesNotOverrideSession() throws Exception {
        login("t_000042");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        request.addHeader("X-Tenant-Id", "43");

        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.getOrNull());

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("t_000042");
    }

    @Test
    @DisplayName("Без вошедшего пользователя арендатор не выставляется")
    void anonymousRequestHasNoTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");

        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.getOrNull());

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("Контекст освобождается после запроса: иначе арендатор утечёт в следующий")
    void clearsContextAfterRequest() throws Exception {
        login("t_000042");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    @DisplayName("Контекст освобождается и когда обработка упала")
    void clearsContextWhenChainThrows() {
        login("t_000042");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("сломалось ниже по стеку");
        };

        assertThatThrownBy(() ->
                filter.doFilterInternal(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);

        // Поток вернётся в пул, и оставленный арендатор достанется другому клиенту.
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    @DisplayName("Вход и проверки живости фильтр не трогает")
    void skipsAuthAndActuator() {
        assertThat(filter.shouldNotFilter(
                new MockHttpServletRequest("POST", "/api/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(
                new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(
                new MockHttpServletRequest("GET", "/api/parts"))).isFalse();
    }

    private void login(String schema) {
        TenantPrincipal principal = new TenantPrincipal(
                schema, 42L, 1L, "ivan", "Иван", "STOREKEEPER", true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new TenantAuthenticationToken(principal, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }
}
