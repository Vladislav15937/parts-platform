package ru.partsflow.platform.tenant;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Насколько заполнена ячейка — для предупреждения владельцу (задача 0184).
 *
 * <p>Два числа: сколько компаний в реестре и каким стоит потолок регистрации.
 * Тревога {@code ЯчейкаЗаполняется} ({@code ops/alerts.yml}) сравнивает их,
 * а не держит число 160 у себя: потолок — настройка, и правило с зашитым
 * числом разошлось бы с ней молча в день, когда её поднимут.
 *
 * <p>Считается в момент опроса, а не по расписанию: это один {@code count}
 * по таблице в сотни строк раз в полминуты, и расписание ради него — лишний
 * механизм. Не посчиталось — {@code NaN}: ноль означал бы «компаний нет», то
 * есть выдал бы незнание за здоровье.
 */
@Component
public class RegistrationMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(RegistrationMetrics.class);

    private final RegistrationLimits limits;

    public RegistrationMetrics(RegistrationLimits limits) {
        this.limits = limits;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("partsflow.registration.tenants", this, RegistrationMetrics::tenants)
                .description("Компаний в реестре ячейки, включая брошенные")
                .register(registry);
        Gauge.builder("partsflow.registration.limit", limits, l -> (double) l.maxTenants())
                .description("Потолок регистрации компаний в ячейке")
                .register(registry);
    }

    private double tenants() {
        try {
            return limits.tenantCount();
        } catch (RuntimeException e) {
            log.warn("Число компаний в ячейке не посчиталось для метрики", e);
            return Double.NaN;
        }
    }
}
