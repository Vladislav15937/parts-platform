package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.partsflow.intake.Donor;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Комплектация» в скачанном файле склада — то же, что в клетке экрана.
 *
 * <p>Файл качают ради сверки с экраном, а у файла была своя копия выражения
 * без {@code AMT → робот}: у позиции с донором на роботе клетка экрана
 * говорила «робот», файл — пусто (мелочь из ответа владельца по 0044,
 * 24.10.2026; разбор PR 500). Перебор по всем значениям словарей донора —
 * руль, коробка, привод, — а не по одному AMT: новое значение перечисления
 * попадает в проверку в тот день, когда его дописали.
 *
 * <p>Через HTTP: страница и файл — разные запросы с разными соединениями.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogEquipmentFileTest extends PostgresTestBase {

    private static final String TENANT = "t_000121";
    private static final int REGISTRY_ID = 121;
    private static final String COMPANY = "equipmentfile";

    private static boolean seeded;
    /** Номер позиции → чем собрана её комплектация (для сообщения). */
    private static final Map<Long, String> CASES = new LinkedHashMap<>();
    /** Коробка → номер и внутренний номер позиции на доноре с этой коробкой. */
    private static final Map<String, long[]> GEARBOX = new LinkedHashMap<>();
    /** «колонка:код» → номер позиции без донора с этой стороной. */
    private static final Map<String, Long> SIDES = new LinkedHashMap<>();

    /**
     * Слова владельца продукта, 24.10.2026 (tasks/0044, «Ответ владельца —
     * 24 октября 2026 (развёрнутые вопросы)», пункты 3 и 4), — дословно,
     * а не из словаря сервера: иначе код проверял бы сам себя.
     */
    private static final Map<String, String> OWNER_GEARBOX = Map.of(
            "AT", "АКПП", "MT", "МКПП", "CVT", "Вариатор", "AMT", "Робот", "DCT", "Робот DSG");
    private static final Map<String, String> OWNER_FRONT_REAR = Map.of(
            "FRONT", "Перед.", "REAR", "Задн.", "BOTH", "Перед., Задн.");
    private static final Map<String, String> OWNER_LEFT_RIGHT = Map.of(
            "LEFT", "Лев.", "RIGHT", "Прав.", "BOTH", "Лев., Прав.");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StockLedger ledger;

    private final ObjectMapper json = new ObjectMapper();
    private MockHttpSession session;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        if (!seeded) {
            seed();
            seeded = true;
        }
        session = login();
    }

    private void seed() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка: комплектация в файле', ?)""", REGISTRY_ID, TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Основной') RETURNING id",
                    Long.class, branch);
            for (Donor.TransmissionType value : Donor.TransmissionType.values()) {
                GEARBOX.put(value.name(), part(warehouse, "коробка " + value, null, value.name(), null));
            }
            for (Donor.Steering value : Donor.Steering.values()) {
                part(warehouse, "руль " + value, value.name(), null, null);
            }
            for (Donor.DriveType value : Donor.DriveType.values()) {
                part(warehouse, "привод " + value, null, null, value.name());
            }
            for (LongitudinalSide value : LongitudinalSide.values()) {
                SIDES.put("side_fr:" + value, sided(warehouse, "side_fr", value.name()));
            }
            for (LateralSide value : LateralSide.values()) {
                SIDES.put("side_lr:" + value, sided(warehouse, "side_lr", value.name()));
            }
            return null;
        });
    }

    @Test
    @DisplayName("Комплектация в файле — та же, что в клетке экрана, у каждого значения донора")
    void fileEquipmentMatchesTheScreen() throws Exception {
        Map<Long, String> screen = new LinkedHashMap<>();
        page().path("rows").forEach(row ->
                screen.put(row.path("number").asLong(), row.path("equipment").asText("")));
        Map<Long, String> file = fileEquipment();

        assertThat(screen.keySet()).as("экран показал не все позиции").containsAll(CASES.keySet());
        for (var entry : CASES.entrySet()) {
            assertThat(file.get(entry.getKey()))
                    .as("%s: в файле «Комплектация» не та, что на экране", entry.getValue())
                    .isEqualTo(screen.get(entry.getKey()));
        }
        // Сверка с экраном сама по себе зелена и на двух одинаково пустых
        // клетках — поэтому сам случай из жалобы назван отдельно.
        long amt = CASES.entrySet().stream()
                .filter(e -> e.getValue().equals("коробка AMT")).findFirst().orElseThrow().getKey();
        assertThat(file.get(amt)).as("робот в скачанном файле").isEqualTo("Робот");
    }

    /**
     * Коробка названа словом владельца — одним и тем же в «Комплектации»
     * клетки, в скачанном файле и в карточке машины-донора.
     *
     * <p>До этого словарей было два: карточка донора — «Вариатор», «Робот»,
     * «Комплектация» — «вариатор», «робот», и {@code DCT} не знал ни один
     * (схема и перечисление его допускают — клетка и файл были пусты).
     * Перебором по перечислению: новое значение без слова владельца краснит
     * первую же проверку.
     */
    @Test
    @DisplayName("Коробка — слово владельца в клетке, в файле и в карточке донора, «Робот DSG» тоже")
    void gearboxWordIsTheOwnersEverywhere() throws Exception {
        assertThat(OWNER_GEARBOX.keySet())
                .as("у значения коробки нет слова владельца")
                .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(Donor.TransmissionType.values())
                        .map(Enum::name).toList());
        Map<Long, String> screen = new LinkedHashMap<>();
        page().path("rows").forEach(row ->
                screen.put(row.path("number").asLong(), row.path("equipment").asText("")));
        Map<Long, String> file = fileColumn("Комплектация");
        for (var entry : GEARBOX.entrySet()) {
            String word = OWNER_GEARBOX.get(entry.getKey());
            long number = entry.getValue()[0];
            assertThat(screen.get(number)).as("клетка «Комплектация», коробка %s", entry.getKey())
                    .isEqualTo(word);
            assertThat(file.get(number)).as("файл склада, «Комплектация», коробка %s", entry.getKey())
                    .isEqualTo(word);
            assertThat(donorCard(entry.getValue()[1]).path("transmission").asText())
                    .as("карточка донора, коробка %s", entry.getKey())
                    .isEqualTo(word);
        }
    }

    /**
     * Стороны в файле склада — с заглавной, и «обе» тоже: «Перед., Задн.»,
     * а не «Перед., задн.» (решение владельца 24.10.2026, пункт 4).
     * Клетку экрана рисует фронтенд по коду — его копию словаря сверяет
     * с сервером {@code ValueWordsTest}.
     */
    @Test
    @DisplayName("Стороны в файле склада — слова владельца с заглавной, «обе» тоже")
    void sideWordsInTheFileAreTheOwners() throws Exception {
        Map<Long, String> frontRear = fileColumn("Передний / Задний");
        Map<Long, String> leftRight = fileColumn("Левый / Правый");
        assertThat(SIDES).as("заведены не все стороны").hasSize(
                LongitudinalSide.values().length + LateralSide.values().length);
        for (var entry : SIDES.entrySet()) {
            String[] key = entry.getKey().split(":");
            boolean fr = key[0].equals("side_fr");
            String word = (fr ? OWNER_FRONT_REAR : OWNER_LEFT_RIGHT).get(key[1]);
            assertThat(word).as("у стороны %s нет слова владельца", entry.getKey()).isNotNull();
            assertThat((fr ? frontRear : leftRight).get(entry.getValue()))
                    .as("файл склада, %s", entry.getKey())
                    .isEqualTo(word);
        }
    }

    /**
     * Отбор, сохранённый до смены регистра, находит то же, что находил.
     *
     * <p>Выбранное из списка ищется точным равенством, а слова сменили
     * регистр: сохранённое «Перед., задн.» (настройка экрана, отбор выгрузки)
     * точным равенством не нашло бы ничего — у выгрузки это объявления, тихо
     * выпавшие из прайса. Поэтому у колонок сторон равенство без регистра.
     */
    @Test
    @DisplayName("Отбор, сохранённый прежними строчными словами, находит те же позиции")
    void filterSavedWithOldWordsStillFinds() throws Exception {
        assertThat(numbers("sideFr:Перед., задн."))
                .as("отбор «Перед., задн.», сохранённый до 24.10.2026")
                .containsExactly(SIDES.get("side_fr:BOTH"));
        assertThat(numbers("sideLr:Лев., прав."))
                .as("отбор «Лев., прав.», сохранённый до 24.10.2026")
                .containsExactly(SIDES.get("side_lr:BOTH"));
        // И точное слово по-прежнему находит своё — равенство не стало вхождением.
        assertThat(numbers("sideFr:Перед."))
                .as("«Перед.» нашло не только переднюю")
                .containsExactly(SIDES.get("side_fr:FRONT"));
    }

    // --- помощники ---

    /** Номер и внутренний номер заведённой позиции. */
    private long[] part(long warehouse, String what, String steering, String transmission,
                        String drive) {
        Long donor = jdbc.queryForObject("""
                INSERT INTO donor (brand_id, steering, transmission_type, drive_type)
                VALUES ((SELECT id FROM catalog.brand ORDER BY id LIMIT 1), ?, ?, ?)
                RETURNING id""", Long.class, steering, transmission, drive);
        Long partId = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, donor_id)
                VALUES (1, ?, 1000, ?) RETURNING id""", Long.class, "Деталь: " + what, donor);
        ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
        Long number = jdbc.queryForObject("SELECT number FROM part WHERE id = ?", Long.class, partId);
        CASES.put(number, what);
        return new long[] {number, partId};
    }

    /** Позиция без донора с одной стороной установки; возвращает её номер. */
    private long sided(long warehouse, String column, String code) {
        Long partId = jdbc.queryForObject(
                "INSERT INTO part (category_id, title, price, " + column + ")"
                        + " VALUES (1, ?, 1000, ?) RETURNING id",
                Long.class, "Деталь: " + column + " " + code, code);
        ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
        return jdbc.queryForObject("SELECT number FROM part WHERE id = ?", Long.class, partId);
    }

    private JsonNode donorCard(long partId) throws Exception {
        String body = mvc.perform(get("/api/parts/{id}/donor", partId).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    /** Номера позиций, которые витрина отдаёт по выбранному из списка значению. */
    private List<Long> numbers(String filter) throws Exception {
        String body = mvc.perform(get("/api/parts/catalog")
                        .param("missing", "true")
                        .param("size", "50")
                        .param("filter", filter)
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Long> out = new ArrayList<>();
        json.readTree(body).path("rows").forEach(row -> out.add(row.path("number").asLong()));
        return out;
    }

    private JsonNode page() throws Exception {
        String body = mvc.perform(get("/api/parts/catalog")
                        .param("missing", "true")
                        .param("size", "50")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private Map<Long, String> fileEquipment() throws Exception {
        return fileColumn("Комплектация");
    }

    /** Номер позиции → значение колонки скачанного файла, колонка ищется по заголовку. */
    private Map<Long, String> fileColumn(String title) throws Exception {
        String body = mvc.perform(get("/api/parts/catalog/export")
                        .param("missing", "true")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = body.replace("﻿", "").split("\r?\n");
        List<String> header = cells(lines[0]);
        int column = header.indexOf(title);
        assertThat(column).as("в файле нет колонки «%s»: %s", title, header).isNotNegative();
        Map<Long, String> out = new LinkedHashMap<>();
        for (int at = 1; at < lines.length; at++) {
            if (lines[at].isBlank()) {
                continue;
            }
            List<String> row = cells(lines[at]);
            out.put(Long.parseLong(row.get(0).strip()), column < row.size() ? row.get(column) : "");
        }
        return out;
    }

    /** Строка файла по «;», с кавычками и удвоенной кавычкой внутри. */
    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ';') {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"owner","password":"пароль"}"""
                                .formatted(COMPANY)))
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
