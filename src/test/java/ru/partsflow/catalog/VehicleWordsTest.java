package ru.partsflow.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Русское написание машины в поисковом запросе.
 *
 * <p>Покупатель звонит и говорит «есть фара на камри?». Продавец набирает
 * как слышит — и до появления словаря получал ноль при 1218 позициях
 * от Camry на живом складе: в заголовке стоит «Фара Toyota Camry 2007»,
 * а поиск идёт по нему.
 *
 * <p>Проверяется не полнота словаря, а его свойства: перевод происходит,
 * непонятое не теряется, длинное имя не разбирается по слову, и одно
 * написание не ведёт к двум машинам.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class VehicleWordsTest extends PostgresTestBase {

    @Autowired
    private VehicleWords words;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("«фара камри» ищется как «фара Camry»")
    void modelIsTranslated() {
        assertThat(words.translate("фара камри")).isEqualTo("фара Camry");
    }

    @Test
    @DisplayName("Марка переводится так же, как модель")
    void brandIsTranslated() {
        assertThat(words.translate("бампер тойота")).isEqualTo("бампер Toyota");
    }

    /**
     * Написаний у одного имени несколько, и это главное свойство словаря:
     * правило, выдающее одно, промахивается там, где человек написал второе,
     * а промах молчаливый — продавец видит пустую выдачу.
     */
    @Test
    @DisplayName("Разные ходовые написания ведут к одной машине")
    void spellingsAgree() {
        assertThat(words.translate("аутлендер")).isEqualTo("Outlander");
        assertThat(words.translate("оутлендер")).isEqualTo("Outlander");
        assertThat(words.translate("срв")).isEqualTo("CR-V");
        assertThat(words.translate("црв")).isEqualTo("CR-V");
    }

    /**
     * «Королла Филдер» — своя модель, а не Королла с уточнением. Разобранный
     * по слову запрос отдал бы все Короллы, среди которых Филдер пришлось бы
     * искать глазами.
     */
    @Test
    @DisplayName("Двусловное имя не разбирается по одному слову")
    void twoWordNameWins() {
        assertThat(words.translate("королла филдер")).isEqualTo("Corolla Fielder");
        assertThat(words.translate("королла")).isEqualTo("Corolla");
    }

    /**
     * Запрос, из которого выкинули непонятое, находит не то — и продавец
     * об этом не узнает.
     */
    @Test
    @DisplayName("Непонятое остаётся как было")
    void unknownSurvives() {
        assertThat(words.translate("фара лев туманка")).isEqualTo("фара лев туманка");
        assertThat(words.translate("фара Camry")).isEqualTo("фара Camry");
    }

    @Test
    @DisplayName("Пустой запрос не ломает перевод")
    void emptyIsSafe() {
        assertThat(words.translate(null)).isNull();
        assertThat(words.translate("  ")).isEqualTo("  ");
    }

    /**
     * Написание, ведущее к двум машинам, отдаёт ту, что попалась первой,
     * то есть случайную, — и продавец получает в ответ детали чужой машины.
     * Та же беда, что синоним, ведущий к двум эталонам вида детали.
     */
    @Test
    @DisplayName("Одно написание не ведёт к двум машинам")
    void aliasesAreUnique() {
        List<String> двоящиеся = jdbc.queryForList("""
                SELECT alias FROM (
                    SELECT a.alias, b.name FROM catalog.brand_alias a
                      JOIN catalog.brand b ON b.id = a.brand_id
                    UNION
                    SELECT a.alias, m.name FROM catalog.model_alias a
                      JOIN catalog.model m ON m.id = a.model_id) x
                 GROUP BY alias HAVING count(DISTINCT name) > 1""",
                String.class);

        assertThat(двоящиеся).isEmpty();
    }

    /**
     * Словарь собран по живому складу «YARD Ткацкая»: 112 моделей, с которых
     * там сняты детали. Модель, оставшаяся без написания, — это позиции,
     * которые по-русски не найти, и заметить это можно только счётом.
     */
    @Test
    @DisplayName("Ходовые модели живого склада в словаре есть")
    void liveModelsAreCovered() {
        List<String> missing = jdbc.queryForList("""
                SELECT m.name FROM catalog.model m
                  JOIN catalog.brand b ON b.id = m.brand_id
                 WHERE b.name = 'Toyota'
                   AND m.name IN ('Camry', 'Harrier', 'RAV4', 'Caldina', 'Ipsum', 'Allion')
                   AND NOT EXISTS (SELECT 1 FROM catalog.model_alias a WHERE a.model_id = m.id)""",
                String.class);

        assertThat(missing).isEmpty();
    }
}
