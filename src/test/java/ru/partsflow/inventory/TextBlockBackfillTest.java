package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Перенос {@code part.text_block} в именованные блоки (задача 0376, {@code tenant/095}).
 *
 * <p>Changeset раздаёт значения лежащим строкам, и чистая база его не судит:
 * на пустой схеме схлопывать нечего, {@code db/verify.sh} пропустит любой
 * перенос. Поэтому позиции заводятся в схеме, затем всё, что завёл changeset,
 * снимается вместе с отметкой о накате — и он проигрывается заново
 * (приём {@code PartNumberTest}, {@code WheelWearPercentTest}).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class TextBlockBackfillTest extends PostgresTestBase {

    private static final String TENANT = "t_000561";

    private static final String PAYMENT = "ОПЛАТА В МАГАЗИНЕ ТОЛЬКО НАЛИЧНЫМИ!";
    private static final String LONG_HEAD =
            "Деталь проверена на машине до снятия, гарантия на установку четырнадцать дней";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Одинаковые тексты становятся одним блоком, имя — по тексту, пустое блоком не становится")
    void sameTextsCollapseIntoOneNamedBlock() {
        long first = part(PAYMENT);
        long second = part(PAYMENT);
        long other = part("Доставка\nпо городу   бесплатно");
        long upper = part("ДОСТАВКА ПО ГОРОДУ БЕСПЛАТНО");
        long longA = part(LONG_HEAD + ", вариант первый");
        long longB = part(LONG_HEAD + ", вариант второй");
        long blank = part("  \n ");
        long none = part(null);

        replayChangeset();

        Map<Long, Long> link = inTenant(() -> {
            Map<Long, Long> m = new java.util.HashMap<>();
            jdbc.query("SELECT id, text_block_id FROM part", rs -> {
                m.put(rs.getLong("id"), (Long) rs.getObject("text_block_id"));
            });
            return m;
        });

        assertThat(link.get(first)).as("первая позиция с текстом оплаты").isNotNull();
        assertThat(link.get(second)).as("две позиции с одним текстом — один блок")
                .isEqualTo(link.get(first));
        assertThat(link.get(other)).isNotNull().isNotEqualTo(link.get(first));
        assertThat(link.get(upper)).as("другой текст — другой блок, даже если имя совпало бы")
                .isNotNull().isNotEqualTo(link.get(other));
        assertThat(link.get(blank)).as("текст из пробелов блоком не становится").isNull();
        assertThat(link.get(none)).isNull();

        assertThat(block(link.get(first)))
                .containsExactly(PAYMENT, PAYMENT);
        assertThat(block(link.get(other)))
                .as("имя в одну строку, текст как лежал")
                .containsExactly("Доставка по городу бесплатно", "Доставка\nпо городу   бесплатно");
        assertThat(block(link.get(upper))[0])
                .as("совпадение имени без учёта регистра — номер по порядку первой позиции")
                .isEqualTo("ДОСТАВКА ПО ГОРОДУ БЕСПЛАТНО (2)");

        String head = LONG_HEAD.substring(0, 59).strip() + "…";
        assertThat(block(link.get(longA))[0]).isEqualTo(head);
        assertThat(block(link.get(longB))[0]).isEqualTo(head + " (2)");
        assertThat(block(link.get(longB))[1]).isEqualTo(LONG_HEAD + ", вариант второй");

        Integer blocks = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM text_block", Integer.class));
        assertThat(blocks).as("пять разных непустых текстов — пять блоков").isEqualTo(5);

        Integer kept = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE text_block = ?", Integer.class, PAYMENT));
        assertThat(kept).as("part.text_block не снят: его читает нынешний код").isEqualTo(2);
    }

    @Test
    @DisplayName("Блок «подходит» из файлов Bazon и применимость карточки Дрома — разные поля")
    void bazonFitsIsNotDromApplicability() {
        long partId = part(null);
        inTenant(() -> jdbc.update("""
                INSERT INTO part_drom_card (part_id, applicability, bazon_fits)
                VALUES (?, 'Текст карточки Дрома', 'Фара подходит:\nМарка Модель 2 поколение')""",
                partId));

        Map<String, Object> row = inTenant(() -> jdbc.queryForMap(
                "SELECT applicability, bazon_fits FROM part_drom_card WHERE part_id = ?", partId));
        assertThat(row.get("applicability")).isEqualTo("Текст карточки Дрома");
        assertThat(row.get("bazon_fits")).isEqualTo("Фара подходит:\nМарка Модель 2 поколение");
    }

    private long part(String textBlock) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, condition, text_block)
                VALUES (1, 'Позиция блока', 1000, 'USED', ?) RETURNING id""",
                Long.class, textBlock));
    }

    private String[] block(Long id) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT name, body FROM text_block WHERE id = ?",
                (rs, n) -> new String[] {rs.getString("name"), rs.getString("body")}, id));
    }

    private void replayChangeset() {
        inTenant(() -> {
            jdbc.execute("ALTER TABLE part DROP COLUMN text_block_id");
            jdbc.execute("DROP TABLE text_block");
            jdbc.execute("ALTER TABLE part_drom_card DROP COLUMN bazon_fits");
            jdbc.update("DELETE FROM " + TENANT + ".databasechangelog WHERE id = ?",
                    "tenant-095-bazon-fits-and-named-text-blocks");
            return null;
        });
        provisionTenants(TENANT);
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
