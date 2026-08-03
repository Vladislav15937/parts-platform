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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Машина в карточке позиции.
 *
 * <p>Через HTTP: ответ — record, а обычный класс Jackson не сериализует;
 * и коды состояния, руля и коробки должны приезжать словами, а не как
 * {@code RIGHT} и {@code AT} — раскладывать их на клиенте значит держать
 * второй словарь, который разойдётся с этим.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartDonorHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000092";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long withDonor;
    private Long contractPart;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 92");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (92, ?, 'Разборка', 'donorco')""", TENANT);

        inTenant(() -> {
            member();
            // Схема этого класса своя, и чистить её можно: номер машины
            // у клиента уникален (на нём держится идемпотентность переноса),
            // а число снятых деталей проверяется точным значением.
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM donor");
            // LIMIT 1: справочник марок общий на все контексты тестов,
            // и одноимённых записей в нём накапливается несколько —
            // уникальность имени марки схемой не обещана.
            Long brand = jdbc.queryForObject(
                    "SELECT id FROM catalog.brand WHERE name = 'Toyota' ORDER BY id LIMIT 1",
                    Long.class);
            Long donor = jdbc.queryForObject("""
                    INSERT INTO donor (brand_id, year, status, steering, drive_type,
                                       transmission_type, transmission_model, color, color_code,
                                       equipment_code, mileage_km, body_code, engine_code,
                                       legacy_code)
                    VALUES (?, 2000, 'DISMANTLING', 'RIGHT', 'FWD', 'AT', 'A244L-01A',
                            'Белый', '040', '0128644', 85364, 'EXZ10', '5EFE', 'Д-395')
                    RETURNING id""", Long.class, brand);

            withDonor = part("Колонка рулевая", donor);
            part("Стартер с той же машины", donor);
            // Контрактная деталь: машины у неё нет и быть не должно.
            contractPart = part("Фара контрактная", null);
            return null;
        });
    }

    @Test
    @DisplayName("Карточка отдаёт машину словами, а не кодами")
    void donorIsServedInWords() throws Exception {
        mvc.perform(get("/api/parts/%d/donor".formatted(withDonor)).session(login()))
                .andExpect(status().isOk())
                // Номер, которым машину зовёт клиент, а не наш внутренний код.
                .andExpect(jsonPath("$.code").value("Д-395"))
                .andExpect(jsonPath("$.status").value("В разборе"))
                .andExpect(jsonPath("$.steering").value("Правый руль"))
                // Тип и модель вместе: одна модель коробки человеку ничего
                // не говорит, а один тип не даёт подобрать деталь.
                .andExpect(jsonPath("$.transmission").value("АКПП, A244L-01A"))
                .andExpect(jsonPath("$.driveType").value("Передний"))
                .andExpect(jsonPath("$.color").value("Белый (040)"))
                .andExpect(jsonPath("$.mileageKm").value(85364))
                .andExpect(jsonPath("$.bodyCode").value("EXZ10"))
                .andExpect(jsonPath("$.engineCode").value("5EFE"))
                // Сколько деталей снято — по этому числу видно, разобрана
                // машина или с неё сняли одну эту.
                .andExpect(jsonPath("$.partsCount").value(2));
    }

    @Test
    @DisplayName("У контрактной детали машины нет, и это не пятисотка")
    void partWithoutDonorIsNotFound() throws Exception {
        // Контрактных у переехавшего клиента девять тысяч из тридцати шести:
        // ответ на «покажи машину» тут — «её нет», а не поломка.
        mvc.perform(get("/api/parts/%d/donor".formatted(contractPart)).session(login()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Без входа машину не показывают")
    void donorNeedsSession() throws Exception {
        mvc.perform(get("/api/parts/%d/donor".formatted(withDonor)))
                .andExpect(status().isUnauthorized());
    }

    private Long part(String title, Long donorId) {
        return jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, donor_id)
                VALUES (1, ?, 5000, ?) RETURNING id""", Long.class, title, donorId);
    }

    private void member() {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, "prodavec");
        if (found.isEmpty()) {
            jdbc.update("""
                            INSERT INTO tenant_member (display_name, role, login, password_hash)
                            VALUES ('Продавец', 'SELLER', 'prodavec', ?)""",
                    passwordEncoder.encode("пароль"));
        }
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"donorco","login":"prodavec","password":"пароль"}"""))
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
