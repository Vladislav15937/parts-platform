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
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Шины и диски» и поиск продавца открываются новыми сверху (задача 0379).
 *
 * <p>Решение владельца продукта 26.10.2026 (`tasks/0372`, раздел «Вопрос
 * владельцу — „Шины и диски“ и поиск продавца», ответ «новые сверху»): склад
 * после 0372 открывается номером по убыванию, а два соседних места оставались
 * «1, 2, 3…» — переходящий с Bazon клиент читает разный порядок рядом как
 * недоделку.
 *
 * <p>Через HTTP, а не вызовом сервиса: умолчание живёт в {@code @RequestParam}
 * контроллера (вкладка) и в порядке, который сервис подставляет, когда продавец
 * сортировку не выбрал, — и первое вызов сервиса не видит вовсе.
 *
 * <p>Своя схема ({@code t_000379}): порядок выдачи — ровно то, что ломается
 * чужими строками в общей схеме (корневой {@code CLAUDE.md}, «Тесты»).
 */
@SpringBootTest
@AutoConfigureMockMvc
class NewestFirstDefaultsTest extends PostgresTestBase {

    private static final String TENANT = "t_000379";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private StockLedger ledger;

    private MockHttpSession owner;
    private long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    /** Пишет то, что называет: найденного не принимает молча (корневой CLAUDE.md, «Тесты»). */
    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 379");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (379, ?, 'Разборка новых сверху', 'novye379')""", TENANT);
        warehouse = inTenant(() -> {
            // Не удаляется между тестами: на него ссылаются заведённые колёса.
            String hash = passwordEncoder.encode("пароль");
            if (jdbc.update("""
                    UPDATE tenant_member SET display_name = 'Владелец 0379', role = 'OWNER',
                           password_hash = ? WHERE login = 'vladelec0379'""", hash) == 0) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец 0379', 'OWNER', 'vladelec0379', ?)""", hash);
            }
            if (jdbc.queryForList("SELECT id FROM warehouse WHERE name = 'Склад 0379'").isEmpty()) {
                Long branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал 0379') RETURNING id", Long.class);
                jdbc.update("INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад 0379')", branch);
            }
            return jdbc.queryForObject("SELECT id FROM warehouse WHERE name = 'Склад 0379'", Long.class);
        });
        owner = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"novye379","login":"vladelec0379","password":"пароль"}"""))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    // ---- «Шины и диски» ----

    /**
     * Вкладка без {@code sort} и {@code desc} — последнее заведённое колесо первым;
     * выгрузка тем же порядком.
     *
     * <p>Марки подобраны против номера: старшее колесо «Bridgestone», новое
     * «Yokohama». Тогда порядок по номеру по убыванию и порядок по марке от «А»
     * различаются, и проверка с {@code sort=brand} ниже ловит простой
     * {@code defaultValue = "true"} — ловушку из 0372, перевернувшую бы и её.
     */
    @Test
    @DisplayName("«Шины и диски» без названной сортировки — новые сверху, и выгрузка тем же порядком")
    void wheelsOpenNewestFirst() throws Exception {
        long older = wheel("Bridgestone", 205, 55);
        long newer = wheel("Yokohama", 205, 55);

        List<Long> page = wheelNumbers(get("/api/wheels").param("q", "205/55").session(owner));
        assertThat(page)
                .as("вкладка «Шины и диски» открылась с позиции № 1, а не с последней заведённой")
                .containsExactly(newer, older);

        List<Long> file = exportNumbers(get("/api/wheels/export").param("q", "205/55").session(owner));
        assertThat(file)
                .as("выгрузка без названной сортировки разошлась с вкладкой")
                .containsExactly(newer, older);
    }

    @Test
    @DisplayName("«Шины и диски»: названная без направления сортировка идёт по возрастанию — и в файле тоже")
    void namedWheelSortStaysAscending() throws Exception {
        long older = wheel("Bridgestone", 215, 60);
        long newer = wheel("Yokohama", 215, 60);

        assertThat(wheelNumbers(get("/api/wheels").param("q", "215/60")
                        .param("sort", "tyreBrand").session(owner)))
                .as("sort=tyreBrand без desc начался с «Я»: направление умолчания перевернуло "
                        + "названную сортировку")
                .containsExactly(older, newer);
        assertThat(exportNumbers(get("/api/wheels/export").param("q", "215/60")
                        .param("sort", "tyreBrand").session(owner)))
                .as("выгрузка с sort=tyreBrand без desc идёт не от «А»")
                .containsExactly(older, newer);
        // Явное направление по-прежнему главнее умолчания.
        assertThat(wheelNumbers(get("/api/wheels").param("q", "215/60")
                        .param("sort", "number").param("desc", "false").session(owner)))
                .containsExactly(older, newer);
    }

    // ---- Поиск продавца ----

    /**
     * Две одинаково подходящие позиции (то же наименование, то же наличие) —
     * более новая выше. До 0379 последним ключом стоял {@code r.id} по
     * возрастанию, и первой шла заведённая раньше.
     */
    @Test
    @DisplayName("Поиск продавца: из одинаково подходящих более новая выше")
    void sellerTiesGoNewestFirst() throws Exception {
        long older = stocked("Плафон салона", null, 1000);
        long newer = stocked("Плафон салона", null, 1000);

        assertThat(stockIds(get("/api/parts/stock").param("q", "плафон").session(owner)))
                .as("из двух одинаковых позиций продавцу первой показана заведённая раньше")
                .containsExactly(newer, older);
    }

    /**
     * «Новее» — это номер позиции, а не порядок заведения строки.
     *
     * <p>У переехавшего с Bazon номер — их «Номер товара» (задача 0334), и с
     * порядком заведения у нас он не совпадает. Позиции, заведённой раньше,
     * здесь ставится номер больше: {@code r.id DESC} вместо номера дал бы
     * обратный порядок.
     */
    @Test
    @DisplayName("Поиск продавца: «новее» значит больший номер позиции, а не позже заведённая строка")
    void sellerTiesFollowTheNumberNotTheRow() throws Exception {
        long renumbered = stocked("Подсветка номера", null, 1000);
        long other = stocked("Подсветка номера", null, 1000);
        inTenant(() -> jdbc.update("UPDATE part SET number = 900379 WHERE id = ?", renumbered));

        assertThat(stockIds(get("/api/parts/stock").param("q", "подсветка").session(owner)))
                .as("порядок продавца идёт по строке базы, а не по номеру позиции")
                .containsExactly(renumbered, other);
    }

    /**
     * Ранжирование по совпадению остаётся главнее новизны: «кронштейн» обязан
     * показать лучшее совпадение выше менее подходящей, хоть и более новой.
     */
    @Test
    @DisplayName("Поиск продавца: лучшее совпадение выше более новой, но менее подходящей")
    void sellerRankStillBeatsNovelty() throws Exception {
        long better = stocked("Кронштейн бампера", "кронштейн кронштейн кронштейн", 1000);
        long newer = stocked("Кронштейн бампера", null, 1000);

        assertThat(stockIds(get("/api/parts/stock").param("q", "кронштейн").session(owner)))
                .as("новизна встала поверх совпадения: поиск продавца стал хуже")
                .containsExactly(better, newer);
    }

    /** Точное совпадение по номеру позиции — по-прежнему первым, впереди более новых. */
    @Test
    @DisplayName("Поиск продавца: точное совпадение по номеру по-прежнему первое")
    void sellerExactNumberStillFirst() throws Exception {
        long named = stocked("Ручка двери", null, 1000);
        long number = numberOf(named);
        // Более новая позиция, у которой цифры названного номера стоят
        // в маркировке: найдётся тем же запросом, но ответом не является.
        long newer = stocked("Ручка двери", null, 1000);
        inTenant(() -> jdbc.update("UPDATE part SET marking = ? WHERE id = ?", "M" + number, newer));

        List<Long> found = stockIds(get("/api/parts/stock").param("q", "№ " + number).session(owner));
        assertThat(found).as("названная вслух позиция не первая").startsWith(named);
    }

    /** Выбранные продавцом «цена» и «поступление» работают как раньше. */
    @Test
    @DisplayName("Поиск продавца: выбранные «цена» и «поступление» не тронуты")
    void sellerChosenSortsUnchanged() throws Exception {
        long cheapOlder = stocked("Молдинг двери", null, 1000);
        long dearNewer = stocked("Молдинг двери", null, 3000);

        assertThat(stockIds(get("/api/parts/stock").param("q", "молдинг")
                        .param("sort", "price").session(owner)))
                .containsExactly(cheapOlder, dearNewer);
        assertThat(stockIds(get("/api/parts/stock").param("q", "молдинг")
                        .param("sort", "price").param("desc", "true").session(owner)))
                .containsExactly(dearNewer, cheapOlder);
        assertThat(stockIds(get("/api/parts/stock").param("q", "молдинг")
                        .param("sort", "intake").session(owner)))
                .containsExactly(cheapOlder, dearNewer);
    }

    // ---- помощники ----

    private long wheel(String brand, int width, int height) throws Exception {
        String body = mvc.perform(post("/api/wheels/sets").with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"TYRE","warehouseId":%d,"quantity":1,"diameter":16,
                                 "tyreWidth":%d,"tyreHeight":%d,"season":"SUMMER",
                                 "brand":"%s","price":3000}""".formatted(warehouse, width, height, brand)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode created = json.readTree(body);
        long partId = created.path("partIds").path(0).asLong();
        assertThat(partId).as("ответ заведения колеса: %s", body).isPositive();
        return numberOf(partId);
    }

    private List<Long> wheelNumbers(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        JsonNode page = json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<Long> numbers = new ArrayList<>();
        page.path("rows").forEach(row -> numbers.add(row.path("wheel").path("number").asLong()));
        return numbers;
    }

    /** Первая колонка файла — «№ позиции»; разбор по заголовку, а не по месту. */
    private List<Long> exportNumbers(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        String file = new String(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = file.replace("﻿", "").split("\r?\n");
        List<String> header = cells(lines[0]);
        int at = header.indexOf("№ позиции");
        assertThat(at).as("в выгрузке нет колонки «№ позиции»: %s", header).isNotNegative();
        List<Long> numbers = new ArrayList<>();
        for (int line = 1; line < lines.length; line++) {
            if (!lines[line].isBlank()) {
                numbers.add(Long.parseLong(cells(lines[line]).get(at)));
            }
        }
        return numbers;
    }

    private static List<String> cells(String line) {
        List<String> found = new ArrayList<>();
        for (String cell : line.split(";", -1)) {
            found.add(cell.replaceAll("^\"|\"$", ""));
        }
        return found;
    }

    private long stocked(String title, String description, int price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, description, price)
                    VALUES (1, ?, ?, ?) RETURNING id""", Long.class, title, description, price);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            return partId;
        });
    }

    private List<Long> stockIds(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        JsonNode found = json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<Long> ids = new ArrayList<>();
        found.path("rows").forEach(row -> ids.add(row.path("partId").asLong()));
        return ids;
    }

    private long numberOf(long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, partId));
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
