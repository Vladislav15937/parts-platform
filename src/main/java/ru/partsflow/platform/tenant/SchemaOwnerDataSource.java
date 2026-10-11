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
 * <p>Владельцем ходят накат общей схемы, провижининг арендатора, миграции его
 * схемы, оркестратор миграций, выдача прав и приведение схем
 * ({@code --schema-sync}), а ещё два вопроса готовности — «принята ли общая
 * схема» и «не отстали ли схемы арендаторов»: историю наката рабочей роли
 * не читают. Всё остальное — рантайм-ролью.
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

    /**
     * Потолок пула владельца — самая большая названная законная пачка заведений,
     * десять разборок подряд при переносе клиентов (задача 0068).
     *
     * <p><b>Ускорения это почти не даёт, и число выбрано не за него.</b>
     * Замер задачи 0070: одно заведение — около секунды, пачка из десяти —
     * от 2 до 13 секунд при пуле в 4, 8 и 10, и разница между пулами меньше
     * разброса прогонов (в парном сравнении 4 против 10 — от нуля до секунды,
     * пока машина не нагружена). Упирается пачка в процессор базы, а не в пул:
     * одна роль с пулом в 20 даёт те же числа.
     *
     * <p>Выбрано за то, что пачка не больше пула <b>не встаёт в очередь
     * за соединением вовсе</b>. Каждое заведение держит не больше одного
     * соединения разом, а ждущего соединение Hikari через 30 секунд отпускает
     * отказом — 500 либо запись, брошенная в {@code PROVISIONING}. При четырёх
     * хвост пачки ждёт, пока накатятся чужие, и время пачки становится
     * временем ожидания: на машине под чужой нагрузкой десять при четырёх шли
     * 25–38 секунд (при десяти в той же паре — 11), а сорок разом на одном
     * ядре за предел ушли — двенадцать отказов из сорока.
     *
     * <p>Пачка больше пула по-прежнему ждёт и на медленной базе срывается:
     * те же сорок при десяти дали семь отказов. Это уже не про число
     * соединений, а про то, что заведение ждёт соединения столько же, сколько
     * запрос продавца.
     */
    static final int MAX_POOL_SIZE = 10;

    /**
     * Сколько соединений владельца держать открытыми без дела.
     *
     * <p>Одно — под опрос готовности (раз в 15 секунд у healthcheck). Умолчание
     * Hikari держит открытыми все {@link #MAX_POOL_SIZE}, а заведение пачкой
     * бывает раз в подключение пилота: остальное время это места
     * в {@code max_connections} (умолчание Postgres, 100), занятые ничем.
     * Сборок в ячейке две, у каждой рабочий пул в 20: при прежних «четыре
     * открыты всегда» владелец держал в простое 2 × 4 = 8 мест, теперь 2 × 1,
     * а пачка на одной сборке поднимает это до 1 + 10 = 11 — на три места
     * больше прежнего и только на время пачки. Лишние соединения пул
     * закрывает сам через {@code idleTimeout} (10 минут).
     */
    static final int MIN_IDLE = 1;

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
        // Было 4 «под редкие и последовательные операции», но заведение
        // пачкой (0068) и открытая регистрация (0184) последовательными
        // их не оставили. Доводы и замер — у констант.
        owner.setMaximumPoolSize(MAX_POOL_SIZE);
        owner.setMinimumIdle(MIN_IDLE);
        owner.setPoolName("SchemaOwnerPool");
        return owner;
    }
}
