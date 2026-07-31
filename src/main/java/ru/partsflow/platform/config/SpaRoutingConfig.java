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
    }
}
