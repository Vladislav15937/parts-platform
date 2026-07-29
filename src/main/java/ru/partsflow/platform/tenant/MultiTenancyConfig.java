package ru.partsflow.platform.tenant;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Подключение мультиарендности к Hibernate.
 *
 * <p>В Hibernate 6 настройки {@code hibernate.multiTenancy} больше нет: режим
 * определяется самим фактом наличия {@code MultiTenantConnectionProvider}.
 * Поэтому провайдер и резолвер регистрируются здесь явно — в YAML это уже
 * не задать.
 */
@Configuration
public class MultiTenancyConfig implements HibernatePropertiesCustomizer {

    private final TenantConnectionProvider connectionProvider;
    private final TenantIdentifierResolver identifierResolver;

    public MultiTenancyConfig(TenantConnectionProvider connectionProvider,
                              TenantIdentifierResolver identifierResolver) {
        this.connectionProvider = connectionProvider;
        this.identifierResolver = identifierResolver;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, identifierResolver);
    }
}
