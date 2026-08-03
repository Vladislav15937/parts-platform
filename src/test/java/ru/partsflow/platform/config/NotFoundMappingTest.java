package ru.partsflow.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.support.PostgresTestBase;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Несуществующий адрес отвечает 404, а не 500.
 *
 * <p>Разница не косметическая: офлайн-очередь приёмки повторяет только 5xx.
 * Пока опечатка в пути приезжала пятисоткой, такой запрос переотправлялся бы
 * вечно вместо того, чтобы честно лечь в «требует внимания» — ровно та же
 * ошибка, из-за которой раньше вечно повторялись нарушения бизнес-правил.
 *
 * <p>Запросы идут от вошедшего: неаутентифицированному любой путь отвечает
 * 401 раньше диспетчера, и по коду ответа не узнать, какие адреса существуют.
 * Очередь приёмки ходит именно вошедшей — этот случай и проверяется.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class NotFoundMappingTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Неизвестный путь под /api — 404")
    void unknownApiPathIsNotFound() throws Exception {
        mvc.perform(post("/api/parts/opechatka/net-takoy-operacii").with(csrf())
                        .with(user("priyomshchik"))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Адрес есть, а метод не тот — 405, и это не мелочь: очередь приёмки
     * повторяет только 5xx, и пятисотка на неверный метод переотправлялась бы
     * вечно. Всплыло появлением {@code PUT /api/parts/{id}} — до него этот
     * путь был неизвестен вовсе.
     */
    @Test
    @DisplayName("Верный адрес неверным методом — 405, а не пятисотка")
    void wrongMethodIsNotAServerError() throws Exception {
        mvc.perform(post("/api/parts/42").with(csrf()).with(user("priyomshchik"))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * Тело, которое не разбирается, — 400, а не пятисотка. Поймано живым
     * прогоном: объект вместо строки в настройках кабинета площадки отвечал
     * «Внутренняя ошибка», то есть очередь повторяла бы такое вечно.
     */
    @Test
    @DisplayName("Неразбираемое тело — 400, а не пятисотка")
    void unreadableBodyIsClientError() throws Exception {
        mvc.perform(post("/api/parts/publication").with(csrf()).with(user("priyomshchik"))
                        .contentType("application/json")
                        .content("{\"partIds\": \"не список\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Загрузка без файлов — 400, а не пятисотка")
    void uploadWithoutFilesIsClientError() throws Exception {
        // Перенос принимает два файла формой. Запрос телом в JSON отвечал
        // «внутренней ошибкой», и владелец, подавший файлы не тем способом,
        // шёл искать поломку сервера — при том что ошибка его.
        mvc.perform(post("/api/import/bazon").with(csrf()).with(user("priyomshchik"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Неизвестный путь вне /api — тоже 404")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/net-takogo-adresa").with(user("priyomshchik")))
                .andExpect(status().isNotFound());
    }
}
