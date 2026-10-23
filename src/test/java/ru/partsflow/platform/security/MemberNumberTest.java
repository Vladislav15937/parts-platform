package ru.partsflow.platform.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Видимый номер сотрудника (задача 0373, {@code tenant/097}).
 *
 * <p>Ответ владельца 23.10.2026 (tasks/0373, «Ответ владельца — 23 октября
 * 2026», пункт 10): номер порядковый внутри компании, неизменный, показан
 * на экране «Сотрудники».
 *
 * <p><b>Раздача уже заведённым проверяется проигрыванием по непустой схеме</b>
 * (приём {@code PartNumberTest}, {@code TextBlockBackfillTest}): на пустой
 * схеме раздавать нечего, и {@code db/verify.sh} проходит при любом
 * {@code ORDER BY}. Поэтому сотрудники заводятся, всё, что завёл changeset,
 * снимается вместе с отметкой о накате, — и он проигрывается заново.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemberNumberTest extends PostgresTestBase {

    /** Схема проигрывания: в ней changeset накатывается второй раз. */
    private static final String REPLAY = "t_000577";

    /** Вторая компания: своя нумерация и вход через HTTP. */
    private static final String OTHER = "t_000578";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MemberService members;

    @BeforeAll
    static void migrate() {
        provisionTenants(REPLAY, OTHER);
    }

    @Test
    @DisplayName("Уже заведённым номера раздаются в порядке заведения, новый продолжает с розданного")
    void backfillFollowsCreationOrderAndSequenceContinues() {
        // Время заведения нарочно обратное порядку id, имена — обратные
        // алфавиту: раздача по created_at или по имени дала бы другой порядок,
        // и проверка это различает.
        List<Long> ids = inTenant(REPLAY, () -> {
            jdbc.update("DELETE FROM tenant_member");
            return List.of(
                    member("Яков Первый", "yakov", "2026-10-03T10:00:00Z"),
                    member("Борис Второй", "boris", "2026-10-02T10:00:00Z"),
                    member("Анна Третья", "anna", "2026-10-01T10:00:00Z"));
        });

        replayChangeset();

        List<Long> numbers = inTenant(REPLAY, () -> jdbc.queryForList(
                "SELECT number FROM tenant_member WHERE id = ANY (?) ORDER BY id",
                Long.class, (Object) ids.toArray(Long[]::new)));
        assertThat(numbers).as("номера по порядку заведения (id), с единицы")
                .containsExactly(1L, 2L, 3L);

        // Последовательность подвинута: следующий получает 4, а не занятую 1.
        MemberService.Member created = inTenant(REPLAY, () -> members.create(
                "novichok", "пароль-подлиннее", "Новичок", "SELLER", null));
        assertThat(created.number()).isEqualTo(4L);
        assertThat(created.id()).as("номер — свой, а не id строки")
                .isNotEqualTo(created.number());
    }

    @Test
    @DisplayName("Номер уникален в компании, у другой компании нумерация своя")
    void numberIsUniqueWithinCompanyAndCountedPerCompany() {
        long here = inTenant(REPLAY, () -> plainMember("Сотрудник здесь"));

        // Две строки с одним номером означали бы, что по номеру нашли
        // не того человека: отбивает индекс, а не проверка в коде.
        assertThatThrownBy(() -> inTenant(REPLAY, () -> jdbc.update("""
                INSERT INTO tenant_member (display_name, role, number) VALUES ('Двойник', 'VIEWER', ?)""",
                here))).isInstanceOf(DuplicateKeyException.class);

        // Заведение во второй компании нумерацию первой не двигает:
        // последовательность у каждой схемы своя.
        inTenant(OTHER, () -> plainMember("Сотрудник там"));
        inTenant(OTHER, () -> plainMember("Ещё один там"));
        long next = inTenant(REPLAY, () -> plainMember("Следующий здесь"));
        assertThat(next).as("номер внутри компании, а не на всю ячейку").isEqualTo(here + 1);
    }

    @Test
    @DisplayName("API сотрудников отдаёт номер: в списке и у заведённого")
    void apiShowsNumber() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 578");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (578, ?, 'Разборка с номерами', 'nomera')""", OTHER);
        long ownerNumber = inTenant(OTHER, () -> {
            jdbc.update("DELETE FROM tenant_member");
            return jdbc.queryForObject("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Владелец Номеров', 'OWNER', 'vladelec', ?) RETURNING number""",
                    Long.class, passwordEncoder.encode("пароль-владельца"));
        });

        MockHttpSession session = login();
        mvc.perform(get("/api/members").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].login").value("vladelec"))
                .andExpect(jsonPath("$[0].number").value(ownerNumber));

        mvc.perform(post("/api/members").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login":"kladovshik","password":"пароль-кладовщика",
                                 "displayName":"Кладовщик","role":"STOREKEEPER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(ownerNumber + 1));
    }

    // ---------- вспомогательное ----------

    private long member(String name, String login, String createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash, created_at)
                VALUES (?, 'SELLER', ?, 'не-пароль', ?::timestamptz) RETURNING id""",
                Long.class, name, login, createdAt);
    }

    private long plainMember(String name) {
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role) VALUES (?, 'VIEWER')
                RETURNING number""", Long.class, name);
    }

    private void replayChangeset() {
        inTenant(REPLAY, () -> {
            jdbc.execute("DROP INDEX tenant_member_number_uk");
            jdbc.execute("ALTER TABLE tenant_member DROP COLUMN number");
            jdbc.execute("DROP SEQUENCE tenant_member_number_seq");
            jdbc.update("DELETE FROM " + REPLAY + ".databasechangelog WHERE id = ?",
                    "tenant-097-member-number");
            return null;
        });
        provisionTenants(REPLAY);
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"nomera","login":"vladelec","password":"пароль-владельца"}"""))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(String schema, Supplier<T> body) {
        TenantContext.set(schema);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
