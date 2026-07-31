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
        mvc.perform(post("/api/parts/opechatka").with(csrf()).with(user("priyomshchik"))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Неизвестный путь вне /api — тоже 404")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/net-takogo-adresa").with(user("priyomshchik")))
                .andExpect(status().isNotFound());
    }
}
