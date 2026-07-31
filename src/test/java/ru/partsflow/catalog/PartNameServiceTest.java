package ru.partsflow.catalog;

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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Справочник наименований арендатора и его сопоставление с эталоном.
 *
 * <p>Проверяется главное свойство: приёмка не останавливается из-за
 * несопоставленного названия, но и не сопоставляет наугад. «Кронштейн
 * топливного фильтра» не должен молча стать «Фильтром топливным» — такая
 * склейка нашлась в живом справочнике Bazon и уводит деталь в чужую категорию.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class PartNameServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000048";

    @Autowired
    private PartNameService service;

    @Autowired
    private PartKindMatcher matcher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long categoryId;
    private Long wheelKindId;
    private Long testCategoryId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void seedCatalog() {
        inTenant(() -> {
            jdbc.update("DELETE FROM part_name");
            return null;
        });

        // Эталоны берём из поставляемого справочника, а не заводим свои:
        // раньше он был пуст, и тесту приходилось выдумывать «Запасное колесо»
        // с синонимом «запаска». Теперь они там есть по-настоящему, и своя
        // копия дала бы два эталона на один синоним — то есть сопоставление,
        // зависящее от порядка строк.
        wheelKindId = jdbc.queryForObject(
                "SELECT id FROM catalog.part_kind WHERE name = 'Запасное колесо'", Long.class);
        categoryId = jdbc.queryForObject(
                "SELECT category_id FROM catalog.part_kind WHERE id = ?", Long.class, wheelKindId);

        // Своя категория нужна только для пополнения каталога по ходу теста.
        jdbc.update("""
                DELETE FROM catalog.part_kind
                 WHERE category_id IN (SELECT id FROM catalog.part_category WHERE path = 'test_intake')""");
        jdbc.update("DELETE FROM catalog.part_category WHERE path = 'test_intake'");
        testCategoryId = jdbc.queryForObject("""
                INSERT INTO catalog.part_category (name, slug, path)
                VALUES ('Тест приёмки', 'test-intake', 'test_intake') RETURNING id""", Long.class);
    }

    @Test
    @DisplayName("Точное совпадение с именем эталона сопоставляется само")
    void exactNameIsMatchedAutomatically() {
        PartName resolved = inTenant(() -> service.resolve("Запасное колесо", null));

        assertThat(resolved.getMatchStatus()).isEqualTo(PartName.MatchStatus.AUTO);
        assertThat(resolved.getPartKindId()).isEqualTo(wheelKindId);
        assertThat(resolved.getCategoryId())
                .as("категория должна прийти от эталона, а не от локального написания")
                .isEqualTo(categoryId);
    }

    @Test
    @DisplayName("Синоним сопоставляется так же: «запаска» — это запасное колесо")
    void synonymIsMatched() {
        PartName resolved = inTenant(() -> service.resolve("запаска", null));

        assertThat(resolved.getMatchStatus()).isEqualTo(PartName.MatchStatus.AUTO);
        assertThat(resolved.getPartKindId()).isEqualTo(wheelKindId);
    }

    @Test
    @DisplayName("Регистр и пробелы не создают второе наименование")
    void normalizesNameOnLookup() {
        PartName first = inTenant(() -> service.resolve("Запаска", null));
        PartName second = inTenant(() -> service.resolve("  запаска  ", null));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_name", Integer.class))).isEqualTo(1);
    }

    @Test
    @DisplayName("Незнакомое написание не блокирует приёмку, а ждёт в нераспознанных")
    void unknownNameIsStillUsable() {
        // Написание, которого в справочнике нет намеренно: «бачок
        // влагоудалителя» — такой детали нет ни эталоном, ни похожим
        // (список SKIPPED в build_part_kinds_live2.py). Прежний пример,
        // «кронштейн бампера», перестал годиться: он стал синонимом
        // «Крепления бампера» во второй порции справочника.
        PartName resolved = inTenant(() -> service.resolve("бачок влагоудалителя", null));

        assertThat(resolved.getId()).as("наименование не создано — приёмка встанет").isNotNull();
        assertThat(resolved.getMatchStatus()).isEqualTo(PartName.MatchStatus.UNMATCHED);
        assertThat(resolved.getPartKindId()).isNull();
        assertThat(inTenant(() -> service.unmatchedCount())).isEqualTo(1);
    }

    @Test
    @DisplayName("Похожее не сопоставляется само: кронштейн фильтра — не фильтр")
    void similarNameIsNotMatchedAutomatically() {
        PartName resolved = inTenant(() -> service.resolve("Кронштейн топливного фильтра", null));

        // Именно такая склейка нашлась в живом справочнике Bazon. Одна такая
        // ошибка уводит деталь в чужую категорию и в отказ модерации.
        assertThat(resolved.getMatchStatus())
                .as("похожесть ушла в автосопоставление")
                .isEqualTo(PartName.MatchStatus.UNMATCHED);
    }

    @Test
    @DisplayName("Похожие эталоны предлагаются человеку подсказками")
    void suggestsSimilarKindsToHuman() {
        // Написание, которого нет ни в именах, ни в синонимах, но похожее
        // на эталон. Само оно не сопоставится — и не должно: именно такая
        // склейка уводит деталь в чужую категорию.
        PartName resolved = inTenant(() -> service.resolve("фильтр топливный грубой очистки", null));
        assertThat(resolved.getMatchStatus()).isEqualTo(PartName.MatchStatus.UNMATCHED);

        List<PartKindMatcher.PartKind> suggestions =
                inTenant(() -> service.suggestionsFor(resolved.getId()));

        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).extracting(PartKindMatcher.PartKind::name)
                .contains("Топливный фильтр");
    }

    @Test
    @DisplayName("Ручное сопоставление помечается вручную и пересчёту не подлежит")
    void manualMatchSurvivesRematch() {
        // Написание, которого в справочнике нет намеренно (см. соседний тест).
        PartName resolved = inTenant(() -> service.resolve("бачок влагоудалителя", null));
        inTenant(() -> service.matchManually(resolved.getId(), wheelKindId));

        assertThat(inTenant(() -> statusOf(resolved.getId()))).isEqualTo("MANUAL");

        // Пересчёт идёт только по нераспознанным, ручное он не видит вовсе.
        inTenant(() -> service.rematchUnmatched(100));
        assertThat(inTenant(() -> statusOf(resolved.getId()))).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("Пересчёт подхватывает наименования после пополнения каталога")
    void rematchPicksUpNewKinds() {
        PartName resolved = inTenant(() -> service.resolve("шумоизоляция арки", null));
        assertThat(resolved.getMatchStatus()).isEqualTo(PartName.MatchStatus.UNMATCHED);

        // Каталог наполняется постепенно: не нашлось в марте — нашлось в мае.
        jdbc.update("""
                INSERT INTO catalog.part_kind (category_id, name, synonyms)
                VALUES (?, 'шумоизоляция арки', ARRAY[]::text[])""", testCategoryId);

        assertThat(inTenant(() -> service.rematchUnmatched(100))).isEqualTo(1);
        assertThat(inTenant(() -> statusOf(resolved.getId()))).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("Счётчик использований растёт на каждое обращение")
    void countsUsage() {
        inTenant(() -> service.resolve("запаска", null));
        inTenant(() -> service.resolve("запаска", null));
        inTenant(() -> service.resolve("запаска", null));

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT usage_count FROM part_name WHERE lower(btrim(name)) = 'запаска'",
                Integer.class))).isEqualTo(3);
    }

    @Test
    @DisplayName("Снятие сопоставления возвращает наименование в нераспознанные")
    void unmatchReturnsToUnmatched() {
        PartName resolved = inTenant(() -> service.resolve("запаска", null));
        assertThat(resolved.getMatchStatus()).isEqualTo(PartName.MatchStatus.AUTO);

        inTenant(() -> service.unmatch(resolved.getId()));

        assertThat(inTenant(() -> statusOf(resolved.getId()))).isEqualTo("UNMATCHED");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT part_kind_id FROM part_name WHERE id = ?", Long.class, resolved.getId()))
        ).isNull();
    }

    @Test
    @DisplayName("Пустое наименование не заводится")
    void rejectsBlankName() {
        assertThatThrownBy(() -> inTenant(() -> service.resolve("   ", null)))
                .hasMessageContaining("не может быть пустым");
    }

    // ---------- вспомогательное ----------

    private String statusOf(Long partNameId) {
        return jdbc.queryForObject("SELECT match_status FROM part_name WHERE id = ?",
                String.class, partNameId);
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
