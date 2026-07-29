package ru.partsflow.platform.tenant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Фильтр — единственное место, где номер арендатора превращается в схему,
 * поэтому его ошибки означают либо отказ в обслуживании, либо (хуже) работу
 * не с тем клиентом.
 */
class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Номер из заголовка становится схемой арендатора")
    void setsTenantFromHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        request.addHeader("X-Tenant-Id", "42");

        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.getOrNull());

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("t_000042");
    }

    @Test
    @DisplayName("Контекст освобождается после запроса: иначе арендатор утечёт в следующий")
    void clearsContextAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        request.addHeader("X-Tenant-Id", "42");

        filter.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    @DisplayName("Контекст освобождается и когда обработка упала")
    void clearsContextWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        request.addHeader("X-Tenant-Id", "42");

        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("сломалось ниже по цепочке");
        };

        assertThatThrownBy(() -> filter.doFilterInternal(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    @DisplayName("Нечисловой заголовок — 400, запрос дальше не идёт")
    void rejectsMalformedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        request.addHeader("X-Tenant-Id", "не-число");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> reached.set(true);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(reached.get()).isFalse();
    }

    @Test
    @DisplayName("Отрицательный номер отбивается: он даёт имя схемы, не проходящее проверку")
    void rejectsNegativeTenantId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");
        request.addHeader("X-Tenant-Id", "-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("IllegalArgumentException из контроллера не выдаётся за ошибку заголовка")
    void doesNotDisguiseDownstreamFailureAsBadHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/parts");
        request.addHeader("X-Tenant-Id", "42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain failing = (req, res) -> {
            throw new IllegalArgumentException("цена не может быть отрицательной");
        };

        // Пока catch накрывал chain.doFilter, это возвращало клиенту 400
        // «Некорректный X-Tenant-Id» на любую ошибку валидации ниже по стеку.
        assertThatThrownBy(() -> filter.doFilterInternal(request, response, failing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("цена");

        assertThat(response.getStatus()).isNotEqualTo(400);
    }

    @Test
    @DisplayName("Actuator фильтр не трогает: там нет арендатора и быть не должно")
    void skipsActuator() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("Без заголовка запрос проходит, контекст остаётся пустым")
    void passesThroughWithoutHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/parts");

        AtomicReference<String> seen = new AtomicReference<>("не вызывалось");
        FilterChain chain = (req, res) -> seen.set(TenantContext.getOrNull());

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNull();
    }
}
