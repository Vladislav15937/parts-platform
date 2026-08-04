package ru.partsflow.platform.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.partsflow.platform.tenant.TenantContext;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Кто из клиентов греет ячейку.
 *
 * <p>Метрики очереди событий и глубины outbox говорят, что ячейке плохо,
 * но не говорят, из-за кого: при пятистах арендаторах на общем комплекте это
 * первый вопрос, который задаёт оператор. А узнать его неоткуда — все запросы
 * идут через одно приложение и одну базу.
 *
 * <p><b>Только верхушка, и это осознанно.</b> Временной ряд на каждого
 * арендатора — это пятьсот новых рядов на каждое число, то есть хранилище
 * метрик размером с базу. Отдаётся десятка самых тяжёлых по суммарному
 * времени за последний час, и её достаточно, чтобы понять, кого звать
 * или кого переселять в свою ячейку.
 *
 * <p>Счёт идёт в памяти экземпляра и обнуляется вместе с ним: это не учёт,
 * а наблюдение. Точное «сколько потребил клиент» считается по журналам,
 * а не по метрикам.
 */
@Component
public class TenantLoadMetrics implements HandlerInterceptor, WebMvcConfigurer {

    /** Сколько арендаторов показывать: длиннее список никто не читает. */
    private static final int TOP = 10;

    /** Окно счёта: час — то, за что успевают заметить и позвонить. */
    private static final long WINDOW_MS = 60 * 60 * 1000L;

    private static final String STARTED = "partsflow.request.started";

    private final Map<String, Load> byTenant = new ConcurrentHashMap<>();
    private volatile long windowStartedAt = System.currentTimeMillis();

    public TenantLoadMetrics(MeterRegistry registry) {
        // Гейдж отдаёт число арендаторов, попавших в верхушку: сам список
        // читается через /actuator, а в Prometheus едет только счётчик —
        // иначе метки-арендаторы размножат ряды.
        registry.gauge("partsflow.tenants.busy", Tags.empty(), this,
                metrics -> metrics.top().size());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        request.setAttribute(STARTED, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object started = request.getAttribute(STARTED);
        String schema = TenantContext.getOrNull();
        if (started == null || schema == null) {
            // Вход и провижининг идут без арендатора — считать их не за кем.
            return;
        }
        rotateIfStale();
        byTenant.computeIfAbsent(schema, key -> new Load())
                .add(System.nanoTime() - (Long) started);
    }

    /** Десятка самых тяжёлых за окно, от большего к меньшему. */
    public List<TenantLoad> top() {
        rotateIfStale();
        return byTenant.entrySet().stream()
                .map(entry -> new TenantLoad(entry.getKey(),
                        entry.getValue().requests.get(),
                        entry.getValue().nanos.get() / 1_000_000))
                .sorted(Comparator.comparingLong(TenantLoad::millis).reversed())
                .limit(TOP)
                .toList();
    }

    /**
     * Окно скользит грубо — целиком, а не по кусочкам.
     *
     * <p>Хранить поминутные корзины ради точности здесь незачем: вопрос
     * «кто греет ячейку» не требует знать, было это в 14:05 или в 14:20.
     */
    private void rotateIfStale() {
        long now = System.currentTimeMillis();
        if (now - windowStartedAt > WINDOW_MS) {
            windowStartedAt = now;
            byTenant.clear();
        }
    }

    private static final class Load {
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();

        void add(long spent) {
            requests.incrementAndGet();
            nanos.addAndGet(spent);
        }
    }

    /**
     * @param schema   схема арендатора — по ней он и ищется в реестре
     * @param requests сколько запросов обслужено за окно
     * @param millis   сколько времени на них ушло суммарно
     */
    public record TenantLoad(String schema, long requests, long millis) {
    }
}
