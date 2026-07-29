package ru.partsflow.platform.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * ShedLock нужен с первого дня, а не «когда появится второй инстанс».
 * Без него два экземпляра приложения будут одновременно пересобирать фиды
 * и переливать outbox — клиент получит дубли объявлений на площадке,
 * а разбираться с этим придётся вручную через модерацию.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .withTableName("public.shedlock")
                        .usingDbTime()
                        .build());
    }
}
