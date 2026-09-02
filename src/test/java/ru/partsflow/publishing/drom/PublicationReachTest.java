package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.platform.tenant.TenantProvisioning;
import ru.partsflow.support.PostgresTestBase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Принятая деталь доезжает до прайса площадки.
 *
 * <p>Раньше — нет. Прайс фильтрует по {@code part.is_published}, а выставлял
 * этот флаг только импорт из Bazon: ни приёмка, ни импорт из таблицы, ни один
 * эндпоинт его не трогали. Прайс любого нового клиента оставался пустым,
 * то есть вся цепочка выгрузки была недостижима.
 *
 * <p>Тестами это не ловилось: генератор прайса проверялся на позициях, которым
 * флаг проставляли в фикстуре. Нашлось сквозным прогоном.
 */
@SpringBootTest(properties = "app.provisioning-token=секрет-публикации")
@AutoConfigureMockMvc
class PublicationReachTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantProvisioning provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Принятая деталь попадает в прайс без единого запроса в базу")
    void receivedPartReachesTheFeed() throws Exception {
        Tenant tenant = tenant();
        MockHttpSession session = tenant.session();

        receive(session, tenant.warehouseId(), "фара");

        String feed = feedOf(session);
        String price = mvc.perform(get(feed))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Ровно то, что показал прогон пустым: <offers></offers>.
        // Заголовок собран из эталона справочника, поэтому «Фара», а не «фара».
        assertThat(price)
                .as("прайс снова пуст — деталь не доезжает до площадки")
                .contains("<offer>")
                .containsIgnoringCase("фара");
    }

    @Test
    @DisplayName("Снятая с выгрузки позиция уходит из прайса")
    void unpublishedPartLeavesTheFeed() throws Exception {
        Tenant tenant = tenant();
        MockHttpSession session = tenant.session();

        long partId = receive(session, tenant.warehouseId(), "бампер");

        mvc.perform(post("/api/parts/publication").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partIds\":[%d],\"published\":false}".formatted(partId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1));

        // Отметка руками — для битого и отложенного под заказ. Умолчание
        // обратное: деталь на разборке снимают, чтобы продать.
        assertThat(mvc.perform(get(feedOf(session))).andReturn().getResponse().getContentAsString())
                .doesNotContainIgnoringCase("бампер");
    }

    @Test
    @DisplayName("Позиция без цены в прайс не идёт")
    void partWithoutPriceIsNotOffered() throws Exception {
        Tenant tenant = tenant();
        MockHttpSession session = tenant.session();

        mvc.perform(post("/api/intake/receipts").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"requestId":"%s",
                                 "items":[{"rawName":"молдинг","quantity":1}]}"""
                                .formatted(tenant.warehouseId(), UUID.randomUUID())))
                .andExpect(status().isCreated());

        // Объявление без цены площадка не примет, а выставить её забыли —
        // это работа для экрана, а не повод слать пустое предложение.
        assertThat(mvc.perform(get(feedOf(session))).andReturn().getResponse().getContentAsString())
                .doesNotContainIgnoringCase("молдинг");
    }

    private long receive(MockHttpSession session, long warehouseId, String name) throws Exception {
        String body = mvc.perform(post("/api/intake/receipts").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"requestId":"%s",
                                 "items":[{"rawName":"%s","quantity":1,"price":9500}]}"""
                                .formatted(warehouseId, UUID.randomUUID(), name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return Long.parseLong(body.replaceAll(".*\"parts\":\\[\\{\"id\":(\\d+).*", "$1"));
    }

    private String feedOf(MockHttpSession session) throws Exception {
        Long accountId = jdbc.queryForObject("""
                SELECT id FROM %s.marketplace_account ORDER BY id LIMIT 1"""
                .formatted(currentSchema(session)), Long.class);

        String body = mvc.perform(post("/api/marketplace-accounts/" + accountId + "/feed-url")
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Разбором поля, а не «всё между кавычками»: рядом с путём едет
        // полный адрес, и наивное выдирание захватывало его вместе
        // с закрывающей скобкой.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"path\":\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private String currentSchema(MockHttpSession session) throws Exception {
        String me = mvc.perform(get("/api/auth/me").session(session))
                .andReturn().getResponse().getContentAsString();
        return me.replaceAll(".*\"companySchema\":\"([^\"]+)\".*", "$1");
    }

    private Tenant tenant() throws Exception {
        String code = "pub" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result created = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));

        // Кабинет площадки заводится в базе: API его создания ещё нет,
        // и это отдельная задача — здесь проверяется путь детали до прайса.
        jdbc.update("""
                INSERT INTO %s.marketplace_account (marketplace, title, settings)
                VALUES ('DROM', 'Кабинет', '{"packetId":"777"}'::jsonb)"""
                .formatted(created.schemaName()));

        MockHttpSession session = login(code);
        Long warehouseId = jdbc.queryForObject(
                "SELECT id FROM %s.warehouse ORDER BY id LIMIT 1".formatted(created.schemaName()),
                Long.class);

        return new Tenant(session, warehouseId);
    }

    private MockHttpSession login(String code) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"vladelec","password":"пароль-8симв"}"""
                                .formatted(code)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private record Tenant(MockHttpSession session, long warehouseId) {
    }
}
