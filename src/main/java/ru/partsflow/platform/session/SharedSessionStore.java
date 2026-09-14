package ru.partsflow.platform.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.security.TenantPrincipal;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;

/**
 * Где живёт состояние серверной сессии: в общей схеме ячейки, а не в памяти
 * процесса.
 *
 * <p><b>Зачем (задача 0080).</b> Сессии в памяти делали неправдой два уже
 * данных обещания. Первое — выкладка без простоя (0079): у новой сборки своя
 * память, и переключение выкидывало всех вошедших; наблюдали живьём
 * на офлайн-прогоне приёмки. Второе важнее: решение владельца от 8 сентября
 * 2026 требует, чтобы отзыв доступа убивал уже открытую сессию
 * <b>немедленно</b>. На одном экземпляре это работало, на двух — нет: отзыв
 * действовал там, где его сделали, а сосед пускал по той же cookie
 * до истечения.
 *
 * <p><b>Способ не менялся, менялось место.</b> Сессия по-прежнему серверная:
 * у клиента только идентификатор в cookie. Переход на JWT здесь прямо запрещён
 * (docs/sessions.md, §1) — отозвать токен нельзя в принципе.
 *
 * <p><b>Почему общая схема, а не схема арендатора.</b> Сессию надо найти
 * раньше, чем известен арендатор: в cookie только идентификатор, а схема —
 * внутри самой сессии. Положив сессии к клиенту, мы бы искали их обходом
 * всех схем ячейки на каждый запрос. Изоляция держится не правами (роль
 * у приложения одна на всех арендаторов и так), а тем, что идентификатор
 * сессии — случайный секрет, а арендатор лежит внутри неё: подставить чужую
 * схему запросом нельзя, {@code TenantFilter} берёт её оттуда.
 *
 * <p><b>Почему настроено здесь, а не свойствами {@code spring.session.*}.</b>
 * Из-за транзакций, и это не вкусовщина. Автонастройка Spring Boot отдаёт
 * репозиторию сессий <b>общий</b> {@code PlatformTransactionManager}, то есть
 * JPA: каждое чтение сессии открывало бы транзакцию Hibernate, а та берёт
 * соединение через {@link ru.partsflow.platform.tenant.TenantConnectionProvider}
 * — который спрашивает вошедшего, чтобы выставить {@code app.user_id}. Вошедший
 * же в Spring Security 6 загружается <b>отложенно</b>, и загрузка эта читает
 * сессию. Получается круг: чтение сессии → транзакция → вошедший → чтение
 * сессии. Поймано первым же прогоном после переезда: «Connection is not
 * available, request timed out» на пустом месте, пул из пяти соединений
 * выедался четырьмя вложенными транзакциями, ни одна из которых
 * не завершалась. Поэтому у хранилища сессий свой, простой
 * {@code DataSourceTransactionManager}: таблица лежит в {@code public},
 * арендатор ей не нужен, Hibernate — тем более.
 */
@Configuration
@EnableSpringHttpSession
public class SharedSessionStore {

    /**
     * Схема в имени таблицы обязательна.
     *
     * <p>{@code JdbcTemplate} ходит сюда вне контекста арендатора, а
     * {@code search_path} у соединения из пула — то, что оставил предыдущий
     * пользователь соединения (провайдер соединений его сбрасывает, но
     * полагаться на это здесь значит зависеть от чужого {@code finally}).
     * Имя таблицы атрибутов Spring Session выводит отсюда же:
     * {@code public.spring_session_attributes}.
     */
    private static final String TABLE = "public.spring_session";

    /** Умолчание Spring Session: раз в минуту убирать истёкшие строки. */
    private static final String CLEANUP_CRON = "0 * * * * *";

    /** Ключ, под которым Spring Security кладёт контекст в сессию. */
    private static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";

    @Bean
    public JdbcIndexedSessionRepository sessionRepository(
            DataSource dataSource,
            @Value("${server.servlet.session.timeout:30m}") Duration timeout) {

        // REQUIRES_NEW — как в автонастройке Spring Session: чтение и запись
        // сессии не должны ни присоединяться к транзакции запроса, ни падать
        // вместе с ней.
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.afterPropertiesSet();

        JdbcIndexedSessionRepository repository =
                new JdbcIndexedSessionRepository(new JdbcTemplate(dataSource), transactions);
        repository.setTableName(TABLE);
        // Срок простоя берётся у сервлет-контейнера, как и раньше: сколько
        // живёт сессия — решение владельца продукта, которого он ещё
        // не принимал (docs/sessions.md, §10). Переезд в базу его не меняет,
        // иначе под видом места хранения поменялось бы поведение входа.
        repository.setDefaultMaxInactiveInterval(timeout);
        repository.setCleanupCron(CLEANUP_CRON);
        repository.setIndexResolver(SharedSessionStore::indexesOf);
        return repository;
    }

    /**
     * То, чем пользуются фильтр сессий и отзыв доступа: то же хранилище,
     * переживающее заморозку записи на время выкладки
     * ({@link FreezeTolerantSessions}, задача 0112).
     *
     * <p>Обёртка — отдельным бином поверх, а не вместо: у хранилища свой
     * жизненный цикл (уборка истёкших по расписанию заводится в
     * {@code afterPropertiesSet}), и спрятанное внутрь обёртки, оно осталось
     * бы без уборки.
     */
    @Bean
    @Primary
    public FindByIndexNameSessionRepository<Session> freezeTolerantSessions(
            JdbcIndexedSessionRepository sessionRepository) {
        return new FreezeTolerantSessions(sessionRepository);
    }

    /**
     * Под каким именем сессии сотрудника находит отзыв.
     *
     * <p><b>Логина тут мало, и это не мелочь.</b> Готовый резолвер Spring
     * Session кладёт в индекс {@code Authentication.getName()}, то есть логин
     * — а логин уникален только внутри арендатора: «ivanov» есть у каждой
     * второй разборки. Отзыв, идущий по такому индексу, выключил бы
     * однофамильца в <b>чужой</b> компании, то есть действие безопасности
     * перетекло бы между клиентами. Поэтому имя составное: схема и номер
     * сотрудника.
     */
    static String principalIndex(String tenantSchema, long memberId) {
        return tenantSchema + '#' + memberId;
    }

    private static Map<String, String> indexesOf(Session session) {
        Object context = session.getAttribute(SECURITY_CONTEXT);
        if (!(context instanceof SecurityContext securityContext)) {
            return Map.of();
        }
        Authentication authentication = securityContext.getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof TenantPrincipal principal)
                || principal.memberId() == null) {
            // Сессия без вошедшего — обычное дело: её заводит запрос
            // CSRF-токена до формы входа. Индексировать нечем, и это не отказ.
            return Map.of();
        }
        return Map.of(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                principalIndex(principal.tenantSchema(), principal.memberId()));
    }
}
