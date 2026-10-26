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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Номер позиции: тот, который называют вслух (задача 0060).
 *
 * <p><b>Что было для человека.</b> Витрина открывалась сортировкой
 * по {@code public_code}, а он — шесть случайных байт
 * ({@code upper(encode(gen_random_bytes(6),'hex'))}). Владелец, открывший
 * главный свой экран, видел первые пятьдесят строк из тридцати пяти тысяч
 * <b>в порядке случайных чисел</b>, и завтра другие: позиция, стоявшая вчера
 * второй сверху, сегодня не видна вовсе — и это читается как «пропала».
 *
 * <p>Проверяются здесь две вещи, и первая опаснее второй.
 *
 * <p><b>Раздача номеров существующим позициям.</b> У переехавшего клиента
 * 35 841 позиция, и номера им раздаются накатом. Разойдись раздача с порядком
 * сортировки — «позиция 347» окажется не там, где её ищут, а названный
 * человеку номер менять уже нельзя. Проверяется это не на пустой схеме
 * (там раздавать нечего), а повторным накатом на схему с данными.
 *
 * <p><b>Порядок витрины по умолчанию.</b> Номер по убыванию, новые сверху —
 * решение владельца продукта 19.10.2026 «всё как у bazon» (задача 0372);
 * прежнее «1. 2. 3. и тд» от 11 сентября 2026 (задача 0060) отменено.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartNumberTest extends PostgresTestBase {

    private static final String TENANT = "t_000148";

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            jdbc.update("INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая')", branch);
            return null;
        });
    }

    /**
     * Номера раздаются в порядке заведения — по {@code id}, а не по коду.
     *
     * <p>Устроено это единственным способом, каким такую раздачу вообще можно
     * проверить: схема заводится, в неё кладутся позиции, затем колонка,
     * последовательность и отметка о накате снимаются — и накат проигрывается
     * заново, уже по непустой таблице. Так он и пройдёт у переехавшего
     * клиента, у которого к этому дню лежит 35 841 позиция; на чистой базе
     * (а другой у {@code db/verify.sh} и у провижининга нет) раздавать нечего,
     * и ошибка в ней невидима по устройству.
     *
     * <p>Коды у позиций нарочно убывают вместе с ростом {@code id}: раздача
     * по коду дала бы обратный порядок, и тест назвал бы, чем именно он
     * отличается от ожидаемого.
     */
    @Test
    @DisplayName("Существующим позициям номера раздаются по порядку заведения")
    void backfillFollowsCreationOrder() {
        List<String> titles = List.of("Первая принятая", "Вторая принятая", "Третья принятая");
        // Код убывает: у настоящих позиций он случаен, и «случайный» —
        // это в том числе «ровно наоборот».
        List<String> codes = List.of("FFFFFF000003", "AAAAAA000002", "000000000001");
        List<Long> ids = new java.util.ArrayList<>();
        for (int at = 0; at < titles.size(); at++) {
            ids.add(part(titles.get(at), codes.get(at)));
        }

        replayNumbering();

        Map<Long, Long> numbers = numbersOf(ids);
        // Первый заведённый — первый номер. Иначе «позиция 347» окажется
        // не там, где её ищут.
        assertThat(numbers.get(ids.get(0)))
                .as("номер первой заведённой позиции (%s) не меньше номера второй (%s): "
                                + "номера розданы не в порядке заведения",
                        numbers.get(ids.get(0)), numbers.get(ids.get(1)))
                .isLessThan(numbers.get(ids.get(1)));
        assertThat(numbers.get(ids.get(1)))
                .as("номер второй заведённой позиции (%s) не меньше номера третьей (%s): "
                                + "номера розданы не в порядке заведения",
                        numbers.get(ids.get(1)), numbers.get(ids.get(2)))
                .isLessThan(numbers.get(ids.get(2)));

        // И без дыр: «1. 2. 3. и тд» — это подряд, а не «1, 7, 12».
        assertThat(numbers.get(ids.get(1)) - numbers.get(ids.get(0)))
                .as("между соседними по заведению позициями пропущен номер")
                .isEqualTo(1);

        // Последовательность продолжает с розданного: иначе первая же
        // заведённая после наката позиция получила бы занятый номер,
        // и уникальность отбила бы приёмку.
        Long next = part("Заведённая после наката", "BBBBBB000004");
        assertThat(numbersOf(List.of(next)).get(next))
                .as("номер новой позиции не продолжает розданные — "
                        + "последовательность не подвинута накатом")
                .isGreaterThan(numbers.get(ids.get(2)));
    }

    /**
     * Порядок витрины по умолчанию — номер позиции по убыванию, новые сверху (задача 0372).
     *
     * <p>Решение владельца продукта 19.10.2026 «всё как у bazon»: у Bazon списки
     * открываются «Номером товара» по убыванию, и прежнее «1. 2. 3. и тд» (0060,
     * 11.09.2026) отменено. У клиента с 35 тысячами позиций первая страница — это всё,
     * что человек видит, не трогая сортировку, и при возрастании там лежали бы позиции
     * трёхлетней давности.
     *
     * <p>Через HTTP, а не вызовом сервиса: умолчание живёт в {@code @RequestParam}
     * контроллера, и вызов сервиса с явным направлением его не видит. Страница
     * и выгрузка проверяются обе — файл качают ради сверки с экраном.
     *
     * <p>Коды убывают вместе с ростом номера: сортировка по коду дала бы порядок
     * заведения, и это видно по сообщению.
     */
    @Test
    @DisplayName("Витрина и выгрузка по умолчанию открываются номером по убыванию — новые сверху, как у Bazon")
    void defaultOrderIsNewestFirst() throws Exception {
        List<String> titles = List.of(
                "Порядок первая", "Порядок вторая", "Порядок третья", "Порядок четвёртая");
        List<String> codes = List.of(
                "111100000001", "888800000002", "CCCC00000003", "FFFF00000004");
        for (int at = 0; at < titles.size(); at++) {
            part(titles.get(at), codes.get(at));
        }
        List<String> newestFirst = new java.util.ArrayList<>(titles);
        java.util.Collections.reverse(newestFirst);
        MockHttpSession owner = login();

        // Сортировка не названа вовсе — так экран спрашивает до первого нажатия
        // на заголовок колонки, если клиент умолчание не прислал.
        JsonNode page = json.readTree(mvc.perform(get("/api/parts/catalog")
                        .param("q", "Порядок").param("missing", "true").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        List<String> shown = titlesOf(page);
        assertThat(shown)
                .as("витрина без названной сортировки открылась не новыми сверху: первой стоит "
                        + "не последняя заведённая позиция (решение 19.10.2026, «как у Bazon»)")
                .containsExactlyElementsOf(newestFirst);
        List<Long> numbers = new java.util.ArrayList<>();
        page.path("rows").forEach(row -> numbers.add(row.path("number").asLong()));
        assertThat(numbers)
                .as("номера позиций идут не по убыванию: %s", numbers)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // Прежний порядок остаётся выбираемым: запомненная настройка человека
        // приходит с явным направлением и обязана исполниться как есть.
        JsonNode ascending = json.readTree(mvc.perform(get("/api/parts/catalog")
                        .param("q", "Порядок").param("missing", "true")
                        .param("sort", "number").param("desc", "false").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(titlesOf(ascending))
                .as("явное «по возрастанию» не исполнилось")
                .containsExactlyElementsOf(titles);
        // Названная без направления сортировка идёт по возрастанию, как нажатие
        // на заголовок: «по убыванию» — направление умолчания, а не любой колонки.
        assertThat(titlesOf(json.readTree(mvc.perform(get("/api/parts/catalog")
                        .param("q", "Порядок").param("missing", "true")
                        .param("sort", "number").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8))))
                .as("сортировка, названная без направления, перевернулась умолчанием")
                .containsExactlyElementsOf(titles);

        String file = mvc.perform(get("/api/parts/catalog/export")
                        .param("q", "Порядок").param("missing", "true").session(owner))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        List<Integer> at = newestFirst.stream().map(file::indexOf).toList();
        assertThat(at).as("в выгрузке нет позиций прогона").doesNotContain(-1);
        assertThat(at)
                .as("выгрузка без названной сортировки разошлась с экраном: строки не новыми сверху")
                .isSorted();
    }

    /**
     * Снимает номера и проигрывает накат заново — так он пройдёт у клиента,
     * у которого позиции уже лежат.
     *
     * <p>Отметка о накате удаляется вместе с объектами: Liquibase считает
     * changeset выполненным по строке в {@code DATABASECHANGELOG}, и без
     * удаления он просто пропустит его.
     */
    private void replayNumbering() {
        inTenant(() -> {
            jdbc.execute("ALTER TABLE part DROP COLUMN number");
            jdbc.execute("DROP SEQUENCE part_number_seq");
            jdbc.update("DELETE FROM " + TENANT + ".databasechangelog WHERE id = ?",
                    "tenant-064-part-number");
            return null;
        });
        provisionTenants(TENANT);
    }

    private Map<Long, Long> numbersOf(List<Long> ids) {
        return inTenant(() -> {
            Map<Long, Long> found = new java.util.HashMap<>();
            for (Long id : ids) {
                found.put(id, jdbc.queryForObject(
                        "SELECT number FROM part WHERE id = ?", Long.class, id));
            }
            return found;
        });
    }

    /** Позиция с заданным кодом: настоящий код случаен, а тесту нужен известный. */
    private Long part(String title, String code) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, ?, 1000)
                    RETURNING id""", Long.class, title);
            jdbc.update("UPDATE part SET public_code = ? WHERE id = ?", code, id);
            return id;
        });
    }

    private static List<String> titlesOf(JsonNode page) {
        List<String> titles = new java.util.ArrayList<>();
        page.path("rows").forEach(row -> titles.add(row.path("title").asText()));
        return titles;
    }

    /** Пишет то, что называет: найденного не принимает молча (корневой CLAUDE.md, «Тесты»). */
    private MockHttpSession login() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 148");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (148, ?, 'Разборка номеров', 'nomer148')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login = 'vladelec0372'");
            jdbc.update("""
                    INSERT INTO tenant_member (display_name, role, login, password_hash)
                    VALUES ('Владелец 0372', 'OWNER', 'vladelec0372', ?)""",
                    passwordEncoder.encode("пароль"));
            return null;
        });
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"nomer148","login":"vladelec0372","password":"пароль"}"""))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
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
