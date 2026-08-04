package ru.partsflow.platform.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Два подключения: рабочее и то, под которым делается DDL.
 *
 * <p><b>Зачем разделять.</b> Пока приложение ходит в базу одной ролью, оно
 * владелец всех таблиц, а владелец возвращает себе любое право одной командой:
 * журнал правится прямым SQL, и {@code REVOKE} этого не отбивает. Это
 * единственная дыра в доказуемости журналов, и закрывается она только так —
 * схемы создаёт и мигрирует одна роль, работает другая, и вторая журналами
 * не владеет.
 *
 * <p>Владельцем ходят ровно четверо: накат общей схемы, провижининг арендатора,
 * миграции его схемы и оркестратор миграций. Всё остальное — рантайм-ролью.
 *
 * <p><b>Умолчание — одна роль на всё,</b> как было. Пустой {@code app.ddl.url}
 * означает «отдельной роли нет», и оба бина указывают на один источник:
 * в разработке и в тестах поднимать две роли ради одной проверки незачем,
 * а забытая настройка не должна валить запуск.
 *
 * <p>Оба источника объявлены здесь явно, и это вынужденно: как только
 * в контексте появляется свой бин типа {@code DataSource}, автоконфигурация
 * Spring Boot отключается целиком — вместе с основным источником, который
 * она и создавала. Поймано сборкой контекста, а не выведено.
 */
@Configuration
public class SchemaOwnerDataSource {

    private static final Logger log = LoggerFactory.getLogger(SchemaOwnerDataSource.class);

    /** Подключение владельца схем: DDL, миграции, провижининг. */
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier("schemaOwner")
    public @interface SchemaOwner {
    }

    /**
     * Рабочее подключение: JPA, репозитории, всё, что обслуживает запросы.
     *
     * <p>Свойства берутся по имени бина автоконфигурации — своего
     * {@code DataSourceProperties} тут заводить нельзя: их станет два,
     * и Spring не выберет между ними.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(
            @Qualifier("spring.datasource-org.springframework.boot.autoconfigure.jdbc"
                    + ".DataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @SchemaOwner
    public DataSource ownerDataSource(
            // По имени бина, а не по типу: иначе Spring подставляет сюда
            // создаваемый бин и упирается в круговую ссылку.
            @Qualifier("dataSource") DataSource runtime,
            @Value("${app.ddl.url:}") String url,
            @Value("${app.ddl.username:}") String username,
            @Value("${app.ddl.password:}") String password) {

        if (url.isBlank() || username.isBlank()) {
            log.info("Отдельной роли владельца схем нет: DDL и работа идут одной ролью");
            return runtime;
        }

        log.info("Владелец схем — роль {}; рабочая роль журналами не владеет", username);
        HikariDataSource owner = new HikariDataSource();
        owner.setJdbcUrl(url);
        owner.setUsername(username);
        owner.setPassword(password);
        // Пул маленький намеренно: этой ролью ходят только миграции
        // и провижининг — операции редкие и последовательные. Большой пул
        // тут означал бы соединения, которые никогда не используются,
        // но занимают места в max_connections.
        owner.setMaximumPoolSize(4);
        owner.setPoolName("SchemaOwnerPool");
        return owner;
    }
}
