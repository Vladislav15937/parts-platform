package ru.partsflow.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Аутентификация: сессия в cookie.
 *
 * <p>Токены появятся с нативным клиентом; до тех пор сессия проще и не требует
 * ротации. PWA живёт на том же домене, поэтому cookie ей подходит.
 *
 * <p><b>Ответ на неаутентифицированный запрос — 401, а не редирект на форму.</b>
 * Клиент здесь один и тот же для человека и для очереди офлайн-отправки:
 * редирект она разберёт как успех и удалит запись из очереди, потеряв работу
 * приёмщика.
 */
@Configuration
@EnableWebSecurity
// Проверки роли стоят на методах (@PreAuthorize), а не путями в этом файле:
// правило «создавать сотрудников может владелец» читается там, где создают
// сотрудника, а не в настройках, куда никто не смотрит.
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AuthenticationManager authenticationManager)
            throws Exception {

        // CSRF-токен в cookie, читаемой скриптом: PWA обязана положить его
        // в заголовок сама. Отключать CSRF при сессии в cookie нельзя — это
        // и есть тот случай, для которого он придуман.
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();

        http
                .csrf(c -> c.csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authenticationManager(authenticationManager)
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                        // Первого владельца создавать некому: сессии ещё нет.
                        // Механизм защищён секретом из конфигурации и работает
                        // только пока у арендатора нет учётных записей —
                        // см. MemberBootstrap.
                        .requestMatchers("/api/members/bootstrap").permitAll()
                        // Проверки живости нужны балансировщику до всякого входа.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.NO_CONTENT.value())));

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(MemberAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    /**
     * BCrypt: медленный по замыслу, поэтому подбор украденного хеша дорог.
     * Стоимость по умолчанию (10) на 2026 год всё ещё разумна для входа
     * человека — это ~50 мс на проверку.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
