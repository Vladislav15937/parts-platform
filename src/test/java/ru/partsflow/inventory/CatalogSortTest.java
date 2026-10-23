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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сортировка у каждой колонки склада, включая колонки складов (задача 0367,
 * решение владельца продукта 23.10.2026: «сортировка по колонкам должна быть
 * на каждую колонку»).
 *
 * <p>Проверяется то, что ломается тихо. Незнакомое имя сортировки сервер
 * молча заменяет умолчанием — значит, имя с экрана, которого нет в белом
 * списке, даёт стрелку «по возрастанию» над строками в порядке номера.
 * Выражение, не разбираемое в одном из двух запросов (страница и скачанный
 * файл соединяют разные таблицы), даёт пятисотку только на этой колонке.
 * И порядок без полного ключа теряет строки между страницами.
 *
 * <p>Через HTTP, а не вызовом сервиса: ответ — record, и путь от параметра
 * {@code sort} до {@code ORDER BY} проходит контроллер.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogSortTest extends PostgresTestBase {

    private static final String TENANT = "t_000134";
    private static final int REGISTRY_ID = 134;
    private static final String COMPANY = "catalogsort";

    /** Позиции заводятся один раз: журнал движений неизменяем, чистить его нельзя. */
    private static boolean seeded;
    private static long tkatskaya;
    private static long dalniy;
    private static final Map<String, Long> PARTS = new LinkedHashMap<>();

    /**
     * Две позиции, равные друг другу во всём, что сортируется, кроме уникального:
     * номера позиции и публичного кода. Заведены одной транзакцией (одно
     * {@code now()} у дат), на одну ячейку, без штрихкода, с одним наименованием.
     * Порядок между ними задаёт только последний ключ {@code p.id}.
     */
    private static final String TIE_TITLE = "Сорт равная";
    /** Сортировки, у которых равные позиции различаются своим значением. */
    private static final Set<String> TIE_DIFFERS = Set.of("number", "code");
    private static long tieFirst;
    private static long tieSecond;

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
                VALUES (?, ?, 'Разборка: сортировка склада', ?)""", REGISTRY_ID, TENANT, COMPANY);
        inTenant(() -> {
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            tkatskaya = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            dalniy = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                    Long.class, branch);
            return null;
        });
        // Заведены в порядке A, B, C, D — то есть номера позиций растут
        // в этом порядке. Значения колонок расставлены НЕ в этом порядке:
        // сортировка, молча ушедшая в умолчание (по номеру), дала бы A, B, C, D
        // и проверку не прошла бы.
        //
        // Остаток на Ткацкой: A — 3 свободно; B — 5, из них 4 отложено
        // (свободно 1); C — на Ткацкой нет вовсе (лежит на Дальнем);
        // D — 1, и тот отложен («0 (1)»). По свободному: C=0, D=0, B=1, A=3;
        // по полному было бы C, D=1, A=3, B=5 — порядки различимы.
        part("A", "c", 300, 1200, 3, 0, tkatskaya);
        part("B", "a", 100, 130, 5, 4, tkatskaya);
        part("C", "d", 400, 900, 2, 0, dalniy);
        part("D", "b", 200, 1100, 1, 1, tkatskaya);
        tiePair();
    }

    @Test
    @DisplayName("Колонка склада сортируется по свободному остатку, а не по полному")
    void warehouseColumnSortsByFreeStock() throws Exception {
        assertThat(order("warehouse:" + tkatskaya, false))
                .as("по свободному на Ткацкой: прочерк и «0 (1)» вместе вверху, потом 1 и 3"
                        + " (по полному было бы C, D, A, B)")
                .containsExactly("C", "D", "B", "A");
        assertThat(order("warehouse:" + tkatskaya, true))
                .as("по убыванию — тот же порядок наоборот, равные — по номеру наоборот")
                .containsExactly("A", "B", "D", "C");
        assertThat(order("warehouse:" + dalniy, true))
                .as("у каждого склада свой остаток")
                .startsWith("C");
    }

    @Test
    @DisplayName("Имя колонки склада — только число: текст из запроса в SQL не попадает")
    void warehouseSortTakesOnlyANumber() throws Exception {
        // Незнакомое имя — умолчание (номер позиции), как у любой колонки.
        assertThat(order("warehouse:1 OR 1=1", false)).containsExactly("A", "B", "C", "D");
        assertThat(order("warehouse:" + tkatskaya + ";DROP TABLE part", false))
                .containsExactly("A", "B", "C", "D");
    }

    @Test
    @DisplayName("Новые колонки сортируются своим значением, а не умолчанием")
    void newColumnsSortByWhatTheyShow() throws Exception {
        // Цвет a, b, c, d стоит у B, D, A, C.
        assertThat(order("color", false)).containsExactly("B", "D", "A", "C");
        assertThat(order("color", true)).containsExactly("C", "A", "D", "B");
        // Цена установки 100, 200, 300, 400 — у B, D, A, C.
        assertThat(order("installation", false)).containsExactly("B", "D", "A", "C");
        // Номер производителя — тот же порядок букв, что у цвета.
        assertThat(order("oem", false)).containsExactly("B", "D", "A", "C");
        // Габариты — числом по длине, а не текстом клетки: «1200×…» текстом
        // встало бы перед «130×…» и «900×…».
        assertThat(order("dimensions", false))
                .as("габариты отсортированы текстом клетки, а не числом")
                .containsExactly("B", "C", "D", "A");
        // Ячейка, комментарий, штрихкод — те же буквы.
        assertThat(order("cell", false)).containsExactly("B", "D", "A", "C");
        assertThat(order("barcode", false)).containsExactly("B", "D", "A", "C");
        assertThat(order("description", false)).containsExactly("B", "D", "A", "C");
    }

    /**
     * Каждая сортировка белого списка — в обе стороны, по страницам в две
     * строки, и тем же порядком в скачанном файле.
     *
     * <p>Страницы обязаны сложиться в склад без повторов и пропусков: без
     * последнего ключа по {@code p.id} строки с равным значением база вправе
     * отдавать в разном порядке на соседних страницах (замер в памятке:
     * 744 строки при 721 уникальной). А файл — совпасть с экраном построчно:
     * его качают ради этой сверки, и выражение, которое не разбирается в его
     * запросе (там другие соединения), уронило бы файл только на этой колонке.
     */
    @Test
    @DisplayName("Любая сортировка: страницы без потерь и повторов, файл тем же порядком")
    void everySortPagesWithoutLossAndMatchesTheFile() throws Exception {
        Set<String> names = new TreeSet<>(CatalogService.sortNames());
        names.add("warehouse:" + tkatskaya);
        for (String sort : names) {
            for (boolean desc : new boolean[] {false, true}) {
                List<Long> paged = new ArrayList<>();
                long total = -1;
                for (int page = 0; page < 10; page++) {
                    JsonNode found = page(sort, desc, page, 2);
                    total = found.path("total").asLong();
                    found.path("rows").forEach(row -> paged.add(row.path("number").asLong()));
                    if (paged.size() >= total) {
                        break;
                    }
                }
                assertThat(paged).as("сортировка %s%s: страниц не хватило", sort, desc ? " ↓" : "")
                        .hasSize((int) total);
                assertThat(new HashSet<>(paged))
                        .as("сортировка %s%s: строка показана дважды", sort, desc ? " ↓" : "")
                        .hasSize(paged.size());
                assertThat(exportNumbers(sort, desc))
                        .as("сортировка %s%s: файл другим порядком, чем экран", sort, desc ? " ↓" : "")
                        .isEqualTo(paged);
                if (!TIE_DIFFERS.contains(sort)) {
                    // Равные по значению идут по p.id в ту же сторону. Без
                    // последнего ключа база оставляет их в порядке чтения
                    // таблицы — то есть по возрастанию и при убывании, — и
                    // соседние страницы вправе разойтись (744 строки при 721).
                    long earlier = desc ? tieSecond : tieFirst;
                    long later = desc ? tieFirst : tieSecond;
                    assertThat(paged.indexOf(earlier))
                            .as("сортировка %s%s: равные позиции не по p.id%s — последний ключ снят",
                                    sort, desc ? " ↓" : "", desc ? " наоборот" : "")
                            .isNotNegative()
                            .isLessThan(paged.indexOf(later));
                }
            }
        }
    }

    /**
     * Каждое имя сортировки, которое шлёт экран, сервер знает.
     *
     * <p>Незнакомое имя сервер молча заменяет порядком по умолчанию, и это
     * верно для сервера (сохранённая настройка не должна ронять витрину), но
     * на экране означает стрелку «↑» над строками в порядке номера — то есть
     * сортировку, которая выглядит работающей и не работает. Список колонок
     * живёт во фронтенде, белый список — здесь; сверка читает первый.
     */
    @Test
    @DisplayName("Каждое имя сортировки с экрана есть в белом списке сервера")
    void everyScreenSortIsKnownToTheServer() throws Exception {
        String client = Files.readString(Path.of("frontend/src/inventory/catalog.ts"),
                StandardCharsets.UTF_8);
        int start = client.indexOf("export const COLUMNS: Column[] = [");
        int end = client.indexOf("];", start);
        assertThat(start).as("список колонок витрины не найден").isPositive();
        var matcher = Pattern.compile("sort: '([A-Za-z]+)'").matcher(client.substring(start, end));
        List<String> screen = new ArrayList<>();
        while (matcher.find()) {
            screen.add(matcher.group(1));
        }
        assertThat(screen).as("сортировок на экране меньше, чем колонок").hasSizeGreaterThan(40);
        assertThat(CatalogService.sortNames())
                .as("экран шлёт сортировку, которой сервер не знает (уйдёт в умолчание молча)")
                .containsAll(screen);
    }

    // --- помощники ---

    private void part(String letter, String word, int installation, int length,
                      int qty, int reserved, long warehouse) {
        Long id = inTenant(() -> {
            Long cell = jdbc.queryForObject(
                    "INSERT INTO storage_cell (warehouse_id, code) VALUES (?, ?) RETURNING id",
                    Long.class, warehouse, "cell-" + word);
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, installation_price, color,
                                      description, barcode, length_mm, width_mm, height_mm,
                                      storage_cell_id)
                    VALUES (1, ?, 1000, ?, ?, ?, ?, ?, 10, 10, ?) RETURNING id""",
                    Long.class, "Сорт " + letter, installation, word, word, "bc-" + word,
                    length, cell);
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                    + " VALUES (?, ?, ?, true)", partId, "oem-" + word, ("OEM" + word).toUpperCase());
            ledger.record(StockMovement.intake(partId, BigDecimal.valueOf(qty), warehouse, null));
            if (reserved > 0) {
                jdbc.update("UPDATE part_stock SET qty_reserved = ? WHERE part_id = ?"
                        + " AND warehouse_id = ?", reserved, partId, warehouse);
            }
            return partId;
        });
        PARTS.put(letter, id);
    }

    /**
     * Пара равных: всё, что сортируется, у них одно, кроме номера позиции
     * и публичного кода (их раздаёт база). Одна транзакция — одна отметка
     * времени у дат заведения и правки; одна ячейка; штрихкода нет (NULL
     * с NULL в уникальном индексе не сталкиваются).
     */
    private void tiePair() {
        List<Long> ids = inTenant(() -> {
            Long cell = jdbc.queryForObject(
                    "INSERT INTO storage_cell (warehouse_id, code) VALUES (?, 'cell-tie') RETURNING id",
                    Long.class, tkatskaya);
            List<Long> made = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Long partId = jdbc.queryForObject("""
                        INSERT INTO part (category_id, title, price, installation_price, color,
                                          description, length_mm, width_mm, height_mm,
                                          storage_cell_id)
                        VALUES (1, ?, 1000, 250, 'tie', 'tie', 1000, 10, 10, ?) RETURNING id""",
                        Long.class, TIE_TITLE, cell);
                jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                        + " VALUES (?, 'oem-tie', 'OEMTIE', true)", partId);
                ledger.record(StockMovement.intake(partId, BigDecimal.valueOf(2), tkatskaya, null));
                made.add(partId);
            }
            return made;
        });
        assertThat(ids.get(0)).as("равные заведены не по возрастанию id").isLessThan(ids.get(1));
        tieFirst = numberOf(ids.get(0));
        tieSecond = numberOf(ids.get(1));
    }

    private long numberOf(long partId) {
        return inTenant(() -> jdbc.queryForObject("SELECT number FROM part WHERE id = ?",
                Long.class, partId));
    }

    /** Буквы позиций A–D в порядке выдачи — одной страницей; пара равных пропускается. */
    private List<String> order(String sort, boolean desc) throws Exception {
        List<String> letters = new ArrayList<>();
        page(sort, desc, 0, 50).path("rows").forEach(row -> {
            String title = row.path("title").asText();
            if (!title.equals(TIE_TITLE)) {
                letters.add(title.substring("Сорт ".length()));
            }
        });
        return letters;
    }

    private JsonNode page(String sort, boolean desc, int page, int size) throws Exception {
        String body = mvc.perform(get("/api/parts/catalog")
                        .param("missing", "true")
                        .param("sort", sort)
                        .param("desc", String.valueOf(desc))
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    /** Первая колонка скачанного файла — номер позиции — по порядку строк. */
    private List<Long> exportNumbers(String sort, boolean desc) throws Exception {
        String body = mvc.perform(get("/api/parts/catalog/export")
                        .param("missing", "true")
                        .param("sort", sort)
                        .param("desc", String.valueOf(desc))
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Long> numbers = new ArrayList<>();
        String[] lines = body.split("\n");
        for (int at = 1; at < lines.length; at++) {
            String first = lines[at].split(";", 2)[0].replace("\"", "");
            if (!first.isBlank()) {
                numbers.add(Long.parseLong(first.strip()));
            }
        }
        return numbers;
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
