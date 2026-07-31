package ru.partsflow.catalog;

import org.junit.jupiter.api.BeforeAll;
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

/**
 * Справочник видов деталей, наполненный миграцией.
 *
 * <p>Пока он был пуст, всякая принятая деталь оставалась без категории
 * и без эталона: приёмщик писал «фара», а сопоставлять было не с чем.
 * Вскрылось сквозным прогоном — по отдельности каждый кусок работал.
 *
 * <p>Проверяется не полнота словаря, а то, ради чего он существует: разные
 * написания одной детали сводятся к одному эталону, а похожее по буквам
 * в автосопоставление не идёт.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class PartKindSeedTest extends PostgresTestBase {

    private static final String TENANT = "t_000071";

    @Autowired
    private PartKindMatcher matcher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Справочник наполнен")
    void dictionaryIsSeeded() {
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM catalog.part_category", Integer.class)))
                .isGreaterThanOrEqualTo(12);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM catalog.part_kind", Integer.class)))
                .isGreaterThan(150);
    }

    @Test
    @DisplayName("Написания приёмщика сводятся к одному эталону")
    void synonymsResolveToOneKind() {
        // Ровно то, ради чего справочник и нужен: «запаска» и «докатка» —
        // это одна и та же позиция, и на площадку они должны уехать одинаково.
        assertThat(nameOf("запаска")).isEqualTo("Запасное колесо");
        assertThat(nameOf("докатка")).isEqualTo("Запасное колесо");
        assertThat(nameOf("Запасное колесо")).isEqualTo("Запасное колесо");
    }

    @Test
    @DisplayName("Жаргон склада узнаётся")
    void slangIsRecognised() {
        assertThat(nameOf("мозги")).isEqualTo("Блок управления двигателем");
        assertThat(nameOf("гена")).isEqualTo("Генератор");
        assertThat(nameOf("граната наружная")).isEqualTo("ШРУС наружный");
        assertThat(nameOf("торпеда")).isEqualTo("Панель приборов");
        assertThat(nameOf("бензонасос")).isEqualTo("Топливный насос");
    }

    @Test
    @DisplayName("Регистр и лишние пробелы не мешают")
    void matchingIgnoresCaseAndSpaces() {
        assertThat(nameOf("  ФАРА  ")).isEqualTo("Фара");
    }

    @Test
    @DisplayName("Похожее по буквам автоматически не сопоставляется")
    void similarNamesDoNotMatch() {
        // «Кронштейн топливного фильтра» → «Топливный фильтр» выглядит
        // правдоподобно и уводит деталь в чужую категорию. Такие пары
        // показываются человеку подсказками, решает он.
        assertThat(inTenant(() -> matcher.findExact("кронштейн топливного фильтра")))
                .isEmpty();
        assertThat(inTenant(() -> matcher.findExact("фара противотуманная левая")))
                .isEmpty();
    }

    @Test
    @DisplayName("У эталона есть категория — иначе деталь не уедет на площадку")
    void everyKindHasCategory() {
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM catalog.part_kind WHERE category_id IS NULL""",
                Integer.class))).isZero();
    }

    @Test
    @DisplayName("Сторона отмечена у того, что бывает левым и правым")
    void sidedKindsAreMarked() {
        // По этому признаку экран приёмки решает, спрашивать ли сторону.
        assertThat(sided("Фара")).isTrue();
        assertThat(sided("Дверь")).isTrue();
        assertThat(sided("Суппорт")).isTrue();
        assertThat(sided("Капот")).isFalse();
        assertThat(sided("Радиатор охлаждения")).isFalse();
    }

    @Test
    @DisplayName("Синонимы не пересекаются между эталонами")
    void synonymsAreUnique() {
        // Только по засеянным категориям: каталог общий на все схемы,
        // и соседние тесты заводят в него свои эталоны с любыми синонимами.
        List<String> clashes = inTenant(() -> jdbc.queryForList("""
                SELECT lower(btrim(s)) AS synonym
                  FROM catalog.part_kind k
                  JOIN catalog.part_category c ON c.id = k.category_id
                     , unnest(k.synonyms) AS s
                 WHERE c.slug IN ('kuzov','optika','dvigatel','transmissiya','podveska',
                                  'tormoza','elektrika','salon','stekla','ohlazhdenie',
                                  'vypusk','kolesa')
                 GROUP BY lower(btrim(s))
                HAVING count(DISTINCT k.id) > 1""", String.class));

        // Один синоним у двух эталонов означает, что сопоставление зависит
        // от порядка строк в таблице — то есть от случайности.
        assertThat(clashes).as("синонимы, ведущие сразу к двум эталонам").isEmpty();
    }

    @Test
    @DisplayName("Пометка о состоянии в конце написания не мешает сопоставлению")
    void conditionMarkerIsStripped() {
        // «Ступица (УЦЕНКА)» — это ступица с ценником, а не другая деталь.
        // У переехавшего клиента таких написаний пятьдесят четыре, и почти
        // каждое — эталон, который в справочнике давно есть.
        assertThat(nameOf("Ступица (УЦЕНКА)")).isEqualTo("Ступица");
        assertThat(nameOf("Фара (уценка)")).isEqualTo("Фара");
        assertThat(nameOf("Дверь багажника (Уценка!)")).isEqualTo("Крышка багажника");
        assertThat(nameOf("Лента Airbag(Дефект)")).isEqualTo("Лента AIRBAG");
        assertThat(nameOf("Капот (б/у)")).isEqualTo("Капот");
    }

    @Test
    @DisplayName("Скобки со смыслом не выкусываются")
    void meaningfulParenthesesSurvive() {
        // Перечень пометок закрытый намеренно: выкусывать любые скобки —
        // это ставка на то, что там никогда не окажется существенного.
        // «Фара (правая)» обязана остаться несопоставленной как написание
        // со стороной, а не превратиться в «Фару» вместе с потерянной стороной.
        assertThat(PartKindMatcher.withoutConditionMarker("Патрубок (верхний)"))
                .isEqualTo("Патрубок (верхний)");
        assertThat(PartKindMatcher.withoutConditionMarker("Датчик (ABS)"))
                .isEqualTo("Датчик (ABS)");
        // И пометка снимается только с конца: в середине она часть названия.
        assertThat(PartKindMatcher.withoutConditionMarker("Фара (уценка) левая"))
                .isEqualTo("Фара (уценка) левая");
    }

    private String nameOf(String raw) {
        return inTenant(() -> matcher.findExact(raw)).orElseThrow(
                () -> new AssertionError("не сопоставилось: " + raw)).name();
    }

    private boolean sided(String name) {
        return Boolean.TRUE.equals(inTenant(() -> jdbc.queryForObject(
                "SELECT has_side FROM catalog.part_kind WHERE name = ?", Boolean.class, name)));
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
