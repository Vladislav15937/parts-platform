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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правка карточки товара.
 *
 * <p>{@code PartService.changePrice} существовал с самого начала и даже
 * публиковал событие для площадок, но снаружи его не звал никто: ни эндпоинта,
 * ни экрана. То есть цену принятой детали владелец изменить не мог вовсе —
 * а на разборке это ежедневная работа. Ровно тот случай, который в проекте
 * уже записан правилом: эндпоинт без экрана — отсутствующая возможность,
 * а сервис без эндпоинта тем более.
 *
 * <p>Через HTTP: роль проверяется аннотацией на методе, и вызов сервиса
 * напрямую её не касается.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartUpdateTest extends PostgresTestBase {

    private static final String TENANT = "t_000089";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long partId;
    private Long ownerId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 89");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (89, ?, 'Разборка', 'editco')""", TENANT);

        inTenant(() -> {
            ownerId = member("vladelec", "Владелец", "OWNER");
            member("prodavec", "Продавец", "SELLER");

            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, note, is_published)
                    VALUES (1, 'Фара Toyota Camry 2006 перед. лев. (б/у)', 4500,
                            'скол на креплении', true)
                    RETURNING id""", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Правка карточки меняет цену и отмечает, кто это сделал")
    void priceChangeIsAttributed() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":3900,"note":"скол на креплении","published":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(3900));

        assertThat(price()).isEqualByComparingTo("3900");
        // Цена без автора и времени — это ровно та ситуация, в которой потом
        // нельзя разобрать, почему деталь ушла за бесценок.
        assertThat(priceChangedBy()).isEqualTo(ownerId);
        assertThat(priceChangedAt()).isNotNull();
        assertThat(updatedBy()).isEqualTo(ownerId);
    }

    /**
     * Отметка о смене цены обязана означать «цену меняли»: иначе по ней нельзя
     * искать подешевевшее, а площадка получает дельту на правку заметки.
     */
    @Test
    @DisplayName("Правка соседнего поля не выдаёт себя за смену цены")
    void editingOtherFieldsIsNotAPriceChange() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":4500,"note":"скол и потёртость","published":true}"""))
                .andExpect(status().isOk());

        assertThat(note()).isEqualTo("скол и потёртость");
        assertThat(priceChangedAt()).isNull();
        // А автор правки записан: карточку трогали.
        assertThat(updatedBy()).isEqualTo(ownerId);
    }

    /**
     * Пустое поле формы — это «очищено», а не «не трогать». Иначе стереть
     * заметку с экрана невозможно вовсе.
     */
    @Test
    @DisplayName("Незаполненное поле формы стирает прежнее значение")
    void emptyFieldClearsTheValue() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":4500,"published":true}"""))
                .andExpect(status().isOk());

        assertThat(note()).isNull();
    }

    // Здесь цена, минимальная цена и себестоимость: продавец, торгующийся
    // с покупателем, не должен уметь подвинуть себе нижнюю границу.
    @Test
    @DisplayName("Продавец карточку править не может")
    void sellerCannotEdit() throws Exception {
        MockHttpSession session = login("prodavec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":100,"published":true}"""))
                .andExpect(status().isForbidden());

        assertThat(price()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("Отрицательная цена не принимается")
    void negativePriceIsRejected() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":-1,"published":true}"""))
                .andExpect(status().isBadRequest());

        assertThat(price()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("Правка несуществующей карточки — 400, а не пятисотка")
    void unknownPartIsRejected() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/999999").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":100,"published":true}"""))
                .andExpect(status().isBadRequest());
    }

    /**
     * Габариты и упаковка — те самые поля, которые доехали до карточки только
     * сейчас. Пока их некому было писать, они означали пустую строку у каждой
     * позиции каждого клиента навсегда.
     */
    @Test
    @DisplayName("Габариты, упаковка и текстовый блок сохраняются")
    void dimensionsAndTextAreSaved() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":4500,"weightKg":3.5,"lengthMm":120,"widthMm":80,
                                 "heightMm":45,"packageLengthMm":130,"packageWidthMm":90,
                                 "packageHeightMm":50,"packageWeightKg":4,
                                 "textBlock":"Проверена на стенде","published":false}"""))
                .andExpect(status().isOk());

        var card = inTenant(() -> jdbc.queryForMap("""
                SELECT weight_kg, length_mm, package_height_mm, text_block, is_published
                  FROM part WHERE id = ?""", partId));

        assertThat((BigDecimal) card.get("weight_kg")).isEqualByComparingTo("3.5");
        assertThat(card.get("length_mm")).isEqualTo(120);
        assertThat(card.get("package_height_mm")).isEqualTo(50);
        assertThat(card.get("text_block")).isEqualTo("Проверена на стенде");
        assertThat(card.get("is_published")).isEqualTo(false);
    }

    private BigDecimal price() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT price FROM part WHERE id = ?", BigDecimal.class, partId));
    }

    private String note() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT note FROM part WHERE id = ?", String.class, partId));
    }

    private Long priceChangedBy() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT price_changed_by FROM part WHERE id = ?", Long.class, partId));
    }

    private Instant priceChangedAt() {
        return inTenant(() -> {
            var value = jdbc.queryForObject(
                    "SELECT price_changed_at FROM part WHERE id = ?",
                    java.sql.Timestamp.class, partId);
            return value == null ? null : value.toInstant();
        });
    }

    private Long updatedBy() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT updated_by FROM part WHERE id = ?", Long.class, partId));
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
                                {"company":"editco","login":"%s","password":"пароль"}"""
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
