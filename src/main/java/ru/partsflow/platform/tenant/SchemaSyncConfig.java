package ru.partsflow.platform.tenant;

import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import ru.partsflow.platform.config.SchedulerConfig;

import javax.sql.DataSource;

/**
 * Контекст режима «привести схему и выйти»: ровно то, что нужно миграциям.
 *
 * <p><b>Почему не весь {@code PartsPlatformApplication} с выключенным
 * веб-слоем.</b> Веб-слой в нём выключается свойством, а вот
 * {@code @EnableWebSecurity} не выключается ничем — он не условный, и контекст
 * без сервлета на нём и встаёт. Но дело не только в этом: полный контекст
 * поднимает планировщики, и одноразовый контейнер миграций начал бы переливать
 * outbox и слать дельты на площадку. Миграция, отправившая объявление, — это
 * не «лишняя работа», это правка чужого кабинета шагом выкладки.
 *
 * <p>Поэтому здесь перечислены бины, а не просканирован пакет: список,
 * который надо дописывать руками, — это ровно то свойство, которого мы тут
 * и хотим. Подключение берётся из того же {@code application.yml} и тех же
 * переменных окружения, что у приложения: два места, знающие адрес базы,
 * разъедутся на первой же правке.
 *
 * <p><b>{@code @Configuration} здесь нет намеренно.</b> Приложение сканирует
 * весь {@code ru.partsflow}, и помеченный класс уехал бы и в его контекст —
 * со своим бином замка поверх того, что заводит {@code SchedulerConfig}.
 * Контекст на этом не поднимался вовсе ({@code BeanDefinitionOverrideException}),
 * а поднявшись, дал бы два {@code LockProvider}'а и спор о том, какой
 * подставлять. Без аннотации класс не кандидат для сканирования, а
 * {@code SpringApplicationBuilder} принимает его источником и разбирает
 * {@code @Bean}-методы сам.
 */
@EnableConfigurationProperties(DataSourceProperties.class)
@ImportAutoConfiguration(PropertyPlaceholderAutoConfiguration.class)
@Import({SchemaOwnerDataSource.class, TenantSchemaMigrator.class, CatalogSchemaMigrator.class,
        SchemaGrants.class, SchemaSync.class})
public class SchemaSyncConfig {

    /**
     * Тот же замок ячейки, что у планировщиков приложения.
     *
     * <p>{@link SchedulerConfig} сюда не импортирован целиком: вместе с ним
     * приехало бы {@code @EnableSchedulerLock} со своим аспектом, а расписаний
     * в этом контексте нет вовсе. Настройка замка при этом одна на оба места —
     * иначе контейнер миграций и работающее приложение брали бы <b>разные</b>
     * замки и спокойно пошли бы по одной схеме вдвоём.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return SchedulerConfig.cellLockProvider(dataSource);
    }
}
