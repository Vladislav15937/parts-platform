package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p><b>Порядок витрины по умолчанию.</b> Он обязан быть порядком заведения:
 * «сортировать витрину по порядковому номеру 1. 2. 3. и тд» — решение
 * владельца продукта от 11 сентября 2026.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class PartNumberTest extends PostgresTestBase {

    private static final String TENANT = "t_000148";

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
     * Порядок витрины по умолчанию — порядок заведения.
     *
     * <p>Сортировка по коду валит этот тест словами о том, чем порядок
     * отличается от ожидаемого: он был проверен откатом и до починки
     * не проходит.
     */
    @Test
    @DisplayName("Витрина по умолчанию открывается порядком заведения, а не случайным")
    void defaultOrderIsCreationOrder() {
        List<String> titles = List.of(
                "Порядок первая", "Порядок вторая", "Порядок третья", "Порядок четвёртая");
        // Коды убывают вместе с ростом id: сортировка по коду даст ровно
        // обратный порядок, и её видно по сообщению.
        List<String> codes = List.of(
                "FFFF00000001", "CCCC00000002", "888800000003", "111100000004");
        for (int at = 0; at < titles.size(); at++) {
            part(titles.get(at), codes.get(at));
        }

        // Сортировка не названа вовсе — ровно так экран и открывается
        // до первого нажатия на заголовок колонки.
        List<CatalogService.Row> rows = inTenant(() -> catalog.list("Порядок", true, true,
                List.of(), null, Map.of(), Map.of(), null, false, 0, 50)).rows();

        assertThat(rows.stream().map(CatalogService.Row::title).toList())
                .as("витрина отдала порядок, отличный от порядка заведения: "
                        + "первой стоит не первая принятая позиция")
                .containsExactlyElementsOf(titles);

        List<Long> numbers = rows.stream().map(CatalogService.Row::number).toList();
        assertThat(numbers)
                .as("номера позиций идут не по возрастанию: %s — "
                        + "значит порядок на экране не тот, что обещает колонка «№ позиции»",
                        numbers)
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

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
