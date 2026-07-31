package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Экран разбора нераспознанных наименований.
 *
 * <p>Главное здесь — не сам список, а то, что происходит после нажатия.
 * Сопоставление, не тронувшее ни одной карточки, чинит будущее и оставляет
 * склад как был: справочник разгребают после импорта, когда все позиции уже
 * заведены, и ради них экран и существует.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartNameControllerTest extends PostgresTestBase {

    private static final String TENANT = "t_000072";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long headlightKindId;
    private Long headlightCategoryId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 72");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (72, ?, 'Разборка', 'namesco')""", TENANT);

        // Эталон берём из поставляемого справочника: своя копия дала бы два
        // эталона на один синоним, то есть сопоставление, зависящее от порядка.
        headlightKindId = jdbc.queryForObject(
                "SELECT id FROM catalog.part_kind WHERE name = 'Фара'", Long.class);
        headlightCategoryId = jdbc.queryForObject(
                "SELECT category_id FROM catalog.part_kind WHERE id = ?",
                Long.class, headlightKindId);

        inTenant(() -> {
            jdbc.update("DELETE FROM part WHERE part_name_id IS NOT NULL");
            jdbc.update("DELETE FROM part_name");
            member("vladelec", "Владелец", "OWNER");
            member("priyomshik", "Приёмщик", "STOREKEEPER");
            return null;
        });
    }

    @Test
    @DisplayName("Сопоставление доводит карточки: категория и эталонный заголовок")
    void matchFixesExistingCards() throws Exception {
        Long nameId = unmatchedName("фара лев.");
        Long partId = partUnder(nameId, "фара лев. Toyota Camry 2006 (б/у)");

        mvc.perform(post("/api/part-names/" + nameId + "/match").with(csrf())
                        .session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partKindId\":%d}".formatted(headlightKindId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.partName.matchStatus").value("MANUAL"));

        // Ради этих двух строк экран и заведён: без них сопоставление
        // не меняет ничего из того, что видит владелец.
        assertThat(titleOf(partId))
                .as("заголовок остался в написании приёмщика — склад так и будет неоднородным")
                .isEqualTo("Фара Toyota Camry 2006 (б/у)");
        assertThat(categoryOf(partId))
                .as("категория не проставлена — карточка не попадёт ни в один разрез склада")
                .isEqualTo(headlightCategoryId);
    }

    @Test
    @DisplayName("Заголовок из чужой таблицы не укорачивается до эталона")
    void importedTitleKeepsItsWords() throws Exception {
        Long nameId = unmatchedName("Фара левая");
        // У позиции из чужой таблицы заголовок и есть написание, целиком.
        // Подмена на эталон «Фара» стёрла бы сторону, и левая с правой стали бы
        // одной деталью — колонки side_lr у импорта нет, восстановить неоткуда.
        Long partId = partUnder(nameId, "Фара левая");

        mvc.perform(post("/api/part-names/" + nameId + "/match").with(csrf())
                        .session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partKindId\":%d}".formatted(headlightKindId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        assertThat(titleOf(partId))
                .as("сторона стёрта: левая фара стала неотличима от правой")
                .isEqualTo("Фара левая");
        assertThat(categoryOf(partId))
                .as("категорию получают и такие карточки: она от заголовка не зависит")
                .isEqualTo(headlightCategoryId);
    }

    @Test
    @DisplayName("Чужой заголовок не трогается: подменять в нём нечего")
    void foreignTitleIsLeftAlone() throws Exception {
        Long nameId = unmatchedName("фара лев.");
        // Позиция из чужой системы: заголовок собран не нами и с написания
        // не начинается. Слепая подмена первого слова испортила бы его.
        Long partId = partUnder(nameId, "Toyota Camry фара левая, б/у");

        mvc.perform(post("/api/part-names/" + nameId + "/match").with(csrf())
                        .session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partKindId\":%d}".formatted(headlightKindId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        assertThat(titleOf(partId)).isEqualTo("Toyota Camry фара левая, б/у");
        assertThat(categoryOf(partId))
                .as("категорию получают и такие карточки: она от заголовка не зависит")
                .isEqualTo(headlightCategoryId);
    }

    @Test
    @DisplayName("Сопоставленное из списка уходит")
    void matchedLeavesTheList() throws Exception {
        Long nameId = unmatchedName("фара лев.");
        MockHttpSession session = login("vladelec");

        mvc.perform(get("/api/part-names/unmatched").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("фара лев."));

        mvc.perform(post("/api/part-names/" + nameId + "/match").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partKindId\":%d}".formatted(headlightKindId)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/part-names/unmatched").session(session))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("Ходовое написание идёт первым, а не свежее")
    void busiestComesFirst() throws Exception {
        Long rare = unmatchedName("фара лев.");
        Long busy = unmatchedName("бампер пер.");
        // Импорт заводит все написания одной секундой: сортировка по времени
        // внутри неё случайна, и разгребать список в таком порядке бесполезно.
        inTenant(() -> jdbc.update("UPDATE part_name SET usage_count = 200 WHERE id = ?", busy));
        inTenant(() -> jdbc.update("UPDATE part_name SET usage_count = 1 WHERE id = ?", rare));

        mvc.perform(get("/api/part-names/unmatched").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("бампер пер."))
                .andExpect(jsonPath("$.items[0].usageCount").value(200));
    }

    @Test
    @DisplayName("Эталон ищется по части названия, когда подсказки мимо")
    void kindsAreSearchable() throws Exception {
        // «запаска» не похожа на «Запасное колесо» ничем — подсказки по похожести
        // такое не находят, и без поиска разбор встанет.
        mvc.perform(get("/api/part-names/kinds?q=запасное").session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Запасное колесо"));
    }

    @Test
    @DisplayName("Приёмщик справочник не правит")
    void storekeeperCannotMatch() throws Exception {
        Long nameId = unmatchedName("фара лев.");
        Long partId = partUnder(nameId, "фара лев. Toyota Camry 2006 (б/у)");

        // Сопоставление меняет заголовки всех позиций под написанием разом.
        mvc.perform(post("/api/part-names/" + nameId + "/match").with(csrf())
                        .session(login("priyomshik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partKindId\":%d}".formatted(headlightKindId)))
                .andExpect(status().isForbidden());

        assertThat(titleOf(partId)).isEqualTo("фара лев. Toyota Camry 2006 (б/у)");
    }

    @Test
    @DisplayName("Снятое сопоставление возвращает наименование в список")
    void unmatchReturnsToList() throws Exception {
        Long nameId = unmatchedName("фара лев.");
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/part-names/" + nameId + "/match").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partKindId\":%d}".formatted(headlightKindId)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/part-names/" + nameId + "/unmatch").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchStatus").value("UNMATCHED"));

        mvc.perform(get("/api/part-names/unmatched").session(session))
                .andExpect(jsonPath("$.total").value(1));
    }

    private Long unmatchedName(String name) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part_name (name, match_status) VALUES (?, 'UNMATCHED')
                RETURNING id""", Long.class, name));
    }

    private Long partUnder(Long nameId, String title) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (part_name_id, title, price) VALUES (?, ?, 5000)
                RETURNING id""", Long.class, nameId, title));
    }

    private String titleOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, partId));
    }

    private Long categoryOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT category_id FROM part WHERE id = ?", Long.class, partId));
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"namesco","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
