package ru.partsflow.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Выбор канала отправки по {@code app.mail.provider}. */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    @Bean
    public MailTransport mailTransport(MailProperties props, ObjectMapper json) {
        return props.unisender() ? new UnisenderGoTransport(props, json) : new LogMailTransport();
    }
}
