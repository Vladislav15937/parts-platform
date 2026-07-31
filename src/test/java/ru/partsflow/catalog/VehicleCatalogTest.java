package ru.partsflow.catalog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Справочник машин, наполненный миграцией.
 *
 * <p>Проверяется не полнота данных — она зависит от источника, — а то, что
 * без чего справочник бесполезен: марки нашлись в обоих написаниях, у ходовых
 * марок есть модели, а поколения не врут годами.
 *
 * <p>{@code donor.brand_id} обязателен, то есть до этого наполнения занести
 * машину в систему было нельзя вовсе.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class VehicleCatalogTest extends PostgresTestBase {

    private static final String TENANT = "t_000068";

    @Autowired
    private VehicleCatalogService catalog;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Марка находится по латинскому написанию")
    void findsByLatinName() {
        assertThat(inTenant(() -> catalog.brands("toyo", 10)))
                .extracting(VehicleCatalogService.Brand::name)
                .contains("Toyota");
    }

    @Test
    @DisplayName("Марка находится по русскому написанию")
    void findsByRussianName() {
        // Ради этого и заведена name_ru: на телефоне переключение раскладки —
        // лишнее действие на каждой машине.
        assertThat(inTenant(() -> catalog.brands("тойо", 10)))
                .extracting(VehicleCatalogService.Brand::name)
                .contains("Toyota");
    }

    @Test
    @DisplayName("Совпадение с начала идёт выше совпадения в середине")
    void prefixMatchWins() {
        List<VehicleCatalogService.Brand> found = inTenant(() -> catalog.brands("mazda", 10));

        assertThat(found).isNotEmpty();
        assertThat(found.get(0).name()).isEqualTo("Mazda");
    }

    @Test
    @DisplayName("У японских марок есть модели, включая JDM")
    void jdmModelsArePresent() {
        Long toyota = brandId("toyota");
        List<VehicleCatalogService.Model> models =
                inTenant(() -> catalog.models(toyota, null, 500));

        // На разборках Дальнего Востока эти машины встречаются чаще половины
        // европейского списка, и справочник без них бесполезен.
        assertThat(models).extracting(VehicleCatalogService.Model::name)
                .contains("Mark II", "Chaser", "Cresta", "Camry", "Land Cruiser Prado");
    }

    @Test
    @DisplayName("Отечественные марки на месте")
    void domesticBrandsArePresent() {
        assertThat(inTenant(() -> catalog.brands("", 400)))
                .extracting(VehicleCatalogService.Brand::slug)
                .contains("lada", "uaz", "gaz");
    }

    @Test
    @DisplayName("Поколения идут свежими сверху и не врут годами")
    void generationsAreOrderedAndSane() {
        Long toyota = brandId("toyota");
        Long camry = inTenant(() -> catalog.models(toyota, "camry", 10)).stream()
                .filter(m -> m.name().equals("Camry")).findFirst().orElseThrow().id();

        List<VehicleCatalogService.Generation> generations =
                inTenant(() -> catalog.generations(camry));

        assertThat(generations).hasSizeGreaterThan(10);
        assertThat(generations.get(0).yearFrom())
                .as("сверху должно быть свежее поколение")
                .isGreaterThan(generations.get(generations.size() - 1).yearFrom());

        for (VehicleCatalogService.Generation generation : generations) {
            if (generation.yearTo() != null) {
                // Год окончания выведен из следующего поколения, и наивный
                // расчёт «сосед минус год» давал год окончания раньше начала
                // там, где у модели два поколения с одним годом старта.
                assertThat(generation.yearTo())
                        .as("поколение %s кончается раньше, чем начинается", generation.name())
                        .isGreaterThanOrEqualTo(generation.yearFrom());
            }
        }
    }

    @Test
    @DisplayName("Одинаковых диапазонов лет у модели нет")
    void generationsAreNotDuplicated() {
        Long toyota = brandId("toyota");
        Long camry = inTenant(() -> catalog.models(toyota, "camry", 10)).stream()
                .filter(m -> m.name().equals("Camry")).findFirst().orElseThrow().id();

        List<VehicleCatalogService.Generation> generations =
                inTenant(() -> catalog.generations(camry));

        // У Дрома одно поколение разложено по типам кузова: без схлопывания
        // приёмщик видел бы четыре одинаковых «1982—1983» и не различил бы их.
        assertThat(generations).extracting(VehicleCatalogService.Generation::name)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Весь справочник отдаётся одним запросом и связан по ссылкам")
    void wholeCatalogComesAtOnce() {
        VehicleCatalogService.Vehicles all = inTenant(() -> catalog.all());

        assertThat(all.brands()).hasSizeGreaterThan(300);
        assertThat(all.models()).hasSizeGreaterThan(4000);
        assertThat(all.generations()).hasSizeGreaterThan(10000);

        // Дерево собирает клиент, поэтому ссылки обязаны быть заполнены:
        // без brandId модели не разложить по маркам, и экран покажет пусто.
        assertThat(all.models()).allSatisfy(m -> assertThat(m.brandId()).isNotNull());
        assertThat(all.generations()).allSatisfy(g -> assertThat(g.modelId()).isNotNull());

        Set<Long> brandIds = all.brands().stream()
                .map(VehicleCatalogService.Brand::id).collect(java.util.stream.Collectors.toSet());
        assertThat(all.models()).allSatisfy(m -> assertThat(brandIds).contains(m.brandId()));
    }

    private Long brandId(String slug) {
        return inTenant(() -> catalog.brands("", 400)).stream()
                .filter(b -> b.slug().equals(slug))
                .findFirst().orElseThrow().id();
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
