package ru.partsflow.oneshot;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import ru.partsflow.inventory.OemRecount;
import ru.partsflow.inventory.TitleRecount;
import ru.partsflow.platform.tenant.SchemaOwnerDataSource;
import ru.partsflow.platform.tenant.TenantSweep;

/**
 * Контекст разовой правки данных ({@link OneShotCommand}): источники подключения,
 * обход реестра и сами работы — и ничего больше.
 *
 * <p>Без {@code @Configuration} по той же причине, что {@code SchemaSyncConfig}:
 * приложение сканирует весь {@code ru.partsflow}, и помеченный класс уехал бы
 * в его контекст. Без веб-слоя и планировщиков: разовая правка, переливающая
 * outbox или шлющая дельты, правила бы чужой кабинет шагом обслуживания.
 */
@EnableConfigurationProperties(DataSourceProperties.class)
@ImportAutoConfiguration(PropertyPlaceholderAutoConfiguration.class)
@Import({SchemaOwnerDataSource.class, TenantSweep.class, OemRecount.class, TitleRecount.class})
public class OneShotConfig {
}
