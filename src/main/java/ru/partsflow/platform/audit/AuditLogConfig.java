package ru.partsflow.platform.audit;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.context.annotation.Configuration;

/**
 * Подключает {@link AuditLogListener} к Hibernate.
 *
 * <p>Регистрация в рантайме, а не через {@code hibernate.ejb.event.*}
 * в настройках: слушателю нужен бин Spring, а строчка в properties создаёт
 * его сама и о контексте не знает.
 *
 * <p>{@code append} — чтобы не вытеснить встроенных слушателей: на
 * {@code POST_UPDATE} у Hibernate свои дела, и замена списка их отключает.
 */
@Configuration
public class AuditLogConfig {

    private final EntityManagerFactory entityManagerFactory;

    public AuditLogConfig(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @PostConstruct
    void register() {
        SessionFactoryImplementor factory =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry listeners = factory.getServiceRegistry()
                .getService(EventListenerRegistry.class);

        AuditLogListener listener = new AuditLogListener();
        listeners.appendListeners(EventType.POST_INSERT, listener);
        listeners.appendListeners(EventType.POST_UPDATE, listener);
        listeners.appendListeners(EventType.POST_DELETE, listener);
    }
}
