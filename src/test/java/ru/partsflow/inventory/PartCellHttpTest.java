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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Перестановка детали на другую полку.
 *
 * <p><b>Зачем через HTTP.</b> Здесь три вещи, которых вызов сервиса
 * не проверяет вовсе. Роль: переставляет кладовщик, а форму правки карточки
 * ему открывать нельзя — там себестоимость, и потому это отдельная точка
 * входа со своим списком ролей. Автор правки берётся из вошедшего
 * и доезжает до журнала настройкой соединения. И ответ — record: обычный
 * класс Jackson не сериализует.
 *
 * <p>Своя схема, а не общая с соседним тестом: сосед, добавивший позицию,
 * ронял бы проверки адреса, к которым отношения не имеет.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartCellHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000120";

    @Autowired
    private StockLedger ledger;

    @Autowired
    private PartRepository parts;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long partId;
    private Long nearId;
    private Long farId;
    /** «А-01-1» и «А-02-1» на ближнем складе, «Б-01-1» — на дальнем. */
    private Long first;
    private Long second;
    private Long farCell;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 120");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (120, ?, 'Разборка', 'cellco')""", TENANT);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("kladovshchik", "Кладовщик", "STOREKEEPER");
            member("prodavec", "Продавец", "SELLER");

            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            nearId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Основной') RETURNING id",
                    Long.class, branch);
            farId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            first = cell(nearId, "А-01-1");
            second = cell(nearId, "А-02-1");
            farCell = cell(farId, "Б-01-1");

            // Через JPA: журнал изменений пишет слушатель Hibernate, и вставка
            // мимо сессии в историю карточки не попадёт.
            Part part = new Part(1L, "Фара левая Toyota Camry", new BigDecimal("4500"));
            partId = parts.saveAndFlush(part).getId();
            // Приёмка кладёт деталь в первую ячейку — как на живом складе.
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, nearId, first));
            return null;
        });
    }

    /**
     * Ради чего задача и заведена.
     *
     * <p>До неё переставить деталь было нельзя вовсе: «Перевезти» требует
     * другого склада-приёмника, а у клиента с одним складом такого нет.
     */
    @Test
    @DisplayName("Кладовщик переставляет деталь на другую полку того же склада")
    void storekeeperMovesPartToAnotherShelf() throws Exception {
        MockHttpSession keeper = login("kladovshchik");

        mvc.perform(get("/api/parts/%d/cells".formatted(partId)).session(keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].warehouseId").value(nearId))
                .andExpect(jsonPath("$[0].cellCode").value("А-01-1"));

        mvc.perform(put("/api/parts/%d/cell".formatted(partId)).with(csrf()).session(keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"cellId":%d}""".formatted(nearId, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cellCode").value("А-02-1"));

        // Раскладка — то, по чему собирается лист обхода пересчёта по ячейке.
        assertThat(cellOf(nearId)).isEqualTo(second);
        // И карточка: по ней собирается колонка «Ячейка» на витрине.
        assertThat(inTenant(() -> parts.findById(partId).orElseThrow().getStorageCellId()))
                .isEqualTo(second);

        // Смена видна в истории карточки словами и с автором: «деталь
        // не нашли на полке» разбирают именно этой лентой.
        mvc.perform(get("/api/parts/%d/history".formatted(partId)).session(keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Ячейка"))
                .andExpect(jsonPath("$.changes[0].fields[0].before").value("А-01-1"))
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("А-02-1"))
                .andExpect(jsonPath("$.changes[0].author").value("Кладовщик"));
    }

    /**
     * «Без адреса» — это NULL, а не пустое значение.
     *
     * <p>Та же природа, что у снятого штрихкода: пусто означает «не заведено»,
     * а не значение. Ячейка при этом перестаёт числиться и в листе обхода
     * по этой полке.
     */
    @Test
    @DisplayName("Снятый адрес — NULL, и позиция уходит из ячейки")
    void addressCanBeCleared() throws Exception {
        mvc.perform(put("/api/parts/%d/cell".formatted(partId)).with(csrf())
                        .session(login("kladovshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"cellId":null}""".formatted(nearId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cellCode").doesNotExist());

        assertThat(cellOf(nearId)).isNull();
    }

    /**
     * Позицию на двух складах переставляют по складу.
     *
     * <p>Тихо менять адрес обоим нельзя: к дальней полке никто не подходил,
     * а кладовщик, пришедший туда по карточке, детали не найдёт.
     */
    @Test
    @DisplayName("Адрес меняется у названного склада, соседний остаётся как был")
    void otherWarehouseKeepsItsShelf() throws Exception {
        inTenant(() -> {
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, farId, farCell));
            return null;
        });

        mvc.perform(put("/api/parts/%d/cell".formatted(partId)).with(csrf())
                        .session(login("kladovshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"cellId":%d}""".formatted(nearId, second)))
                .andExpect(status().isOk());

        assertThat(cellOf(nearId)).isEqualTo(second);
        assertThat(cellOf(farId)).as("адрес соседнего склада тронут").isEqualTo(farCell);
    }

    /**
     * Ячейка чужого склада — отказ словами, а не «нарушение целостности».
     *
     * <p>Внешний ключ такое пропустит: ячейка существует, просто стоит
     * на другом складе. Кладовщик по такому ответу пошёл бы искать поломку
     * сервера, а офлайн-очередь читает пятисотку как повод повторять вечно.
     */
    @Test
    @DisplayName("Ячейка с другого склада отбивается 4xx и называет склад")
    void foreignCellIsRefusedWithWords() throws Exception {
        mvc.perform(put("/api/parts/%d/cell".formatted(partId)).with(csrf())
                        .session(login("kladovshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"cellId":%d}""".formatted(nearId, farCell)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Основной")));

        assertThat(cellOf(nearId)).as("чужая ячейка всё-таки записалась").isEqualTo(first);
    }

    /**
     * Склад, на котором позиции нет, — тоже отказ словами.
     */
    @Test
    @DisplayName("Склад без остатка отбивается 4xx и называет деталь")
    void warehouseWithoutStockIsRefused() throws Exception {
        mvc.perform(put("/api/parts/%d/cell".formatted(partId)).with(csrf())
                        .session(login("kladovshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"cellId":null}""".formatted(farId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Фара левая Toyota Camry")));
    }

    /**
     * Продавцу перестановка закрыта.
     *
     * <p>Обе стороны проверки обязательны: правка, закрывшая перестановку
     * всем, тоже «чинит» дыру — и делает это молча.
     */
    @Test
    @DisplayName("Продавец переставить не может, владелец может")
    void sellerCannotPlaceOwnerCan() throws Exception {
        String body = """
                {"warehouseId":%d,"cellId":%d}""".formatted(nearId, second);

        mvc.perform(put("/api/parts/%d/cell".formatted(partId)).with(csrf())
                        .session(login("prodavec"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/parts/%d/cell".formatted(partId)).with(csrf())
                        .session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /**
     * Витрина отдаёт код ячейки.
     *
     * <p>До этого его не было в строке вовсе: на экране виден был только
     * {@code section} — текстовое поле, которое человек пишет руками, —
     * а ячейку, которую наполняют приёмка и сканер, не показывало ничто.
     */
    @Test
    @DisplayName("Строка витрины несёт код ячейки, а не только секцию")
    void catalogRowCarriesCellCode() throws Exception {
        // По коду товара: схема живёт дольше одного метода, и «первая строка»
        // без отбора — это чужая позиция от соседней проверки.
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));

        mvc.perform(get("/api/parts/catalog").param("q", code).session(login("vladelec")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].cellCode").value("А-01-1"))
                // Секция — соседнее поле, а не то же самое: у этой позиции
                // её никто не заполнял.
                .andExpect(jsonPath("$.rows[0].section").doesNotExist());
    }

    private Long cellOf(Long warehouseId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT cell_id FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                Long.class, partId, warehouseId));
    }

    private Long cell(Long warehouseId, String code) {
        return jdbc.queryForObject(
                "INSERT INTO storage_cell (warehouse_id, code) VALUES (?, ?) RETURNING id",
                Long.class, warehouseId, code);
    }

    private void member(String login, String name, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (found.isEmpty()) {
            jdbc.update("""
                            INSERT INTO tenant_member (display_name, role, login, password_hash)
                            VALUES (?, ?, ?, ?)""",
                    name, role, login, passwordEncoder.encode("пароль"));
        }
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"cellco","login":"%s","password":"пароль"}"""
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
