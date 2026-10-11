package ru.partsflow.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Глубокие адреса приложения отдаются его оболочкой.
 *
 * <p>Приложение одностраничное: маршрут разбирает браузер, а сервер обязан
 * на любой его адрес отдать {@code index.html}. Без этого ссылка на сделку,
 * открытая клиентом из переписки, приезжает четырёхсоткой — а это единственный
 * способ, которым он её и откроет.
 *
 * <p>Правило узкое намеренно: только известные адреса приложения. Отдавать
 * оболочку на всё подряд значит вернуть опечатке в пути двухсотый ответ,
 * а офлайн-очередь приёмки разбирает такой ответ как успех и удаляет запись.
 */
@Configuration
public class SpaRoutingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/s/{company}/{token}").setViewName("forward:/index.html");
        // Страница регистрации компании (задача 0184): её открывают по ссылке
        // с сайта, и адрес здесь единственный способ сказать, что показать.
        registry.addViewController("/register").setViewName("forward:/index.html");
        // Общая страница входа и регистрации (задача 0265): туда приложение
        // уводит невошедшего с любого своего адреса, и обновивший её человек
        // обязан получить оболочку, а не белый экран.
        registry.addViewController("/login").setViewName("forward:/index.html");
        // Система администрирования сервиса (задача 0042): своя страница
        // со своим входом, вне всех кабинетов.
        registry.addViewController("/admin").setViewName("forward:/index.html");
    }
}
