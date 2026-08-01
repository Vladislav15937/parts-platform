package ru.partsflow.intake;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Затраты по машине — база отчёта окупаемости.
 *
 * <p>Ошибка здесь тихая и дорогая: неверный вид затрат отбивает не проверка,
 * а ограничение схемы, то есть 500 вместо внятного отказа, а отрицательная
 * сумма делает вложенное меньше, чем оно есть, — и машина в отчёте выглядит
 * прибыльнее, чем была.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class DonorCostServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000056";

    @Autowired
    private DonorCostService costs;

    @Autowired
    private DonorDirectory directory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long donorId;
    private Long brandId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        brandId = jdbc.queryForObject("""
                INSERT INTO catalog.brand (name, slug) VALUES ('Toyota', 'toyota-donor-cost-test')
                ON CONFLICT (slug) DO UPDATE SET name = excluded.name
                RETURNING id""", Long.class);

        donorId = inTenant(() -> {
            jdbc.update("DELETE FROM donor_cost");
            jdbc.update("DELETE FROM donor");
            return jdbc.queryForObject("""
                    INSERT INTO donor (public_code, brand_id, year, note)
                    VALUES ('ТЕСТ-1', ?, 2012, 'Камри') RETURNING id""", Long.class, brandId);
        });
    }

    @Test
    @DisplayName("Затраты складываются журналом, а не переписывают друг друга")
    void costsAccumulate() {
        inTenant(() -> costs.add(donorId, "PURCHASE", new BigDecimal("180000"),
                LocalDate.of(2026, 7, 1), "с аукциона", null));
        var after = inTenant(() -> costs.add(donorId, "DELIVERY", new BigDecimal("12000"),
                LocalDate.of(2026, 7, 3), null, null));

        assertThat(after).hasSize(2);
        assertThat(after).extracting(DonorCostService.Cost::amount)
                .containsExactly(new BigDecimal("180000.00"), new BigDecimal("12000.00"));
    }

    // Порядок по дате, а не по вставке: эвакуатор оплачивают позже покупки,
    // а вносят в систему когда придётся.
    @Test
    @DisplayName("Журнал упорядочен датой затраты, а не порядком ввода")
    void costsAreOrderedByDate() {
        inTenant(() -> costs.add(donorId, "DELIVERY", new BigDecimal("12000"),
                LocalDate.of(2026, 7, 3), null, null));
        var after = inTenant(() -> costs.add(donorId, "PURCHASE", new BigDecimal("180000"),
                LocalDate.of(2026, 7, 1), null, null));

        assertThat(after).extracting(DonorCostService.Cost::type)
                .containsExactly("PURCHASE", "DELIVERY");
    }

    @Test
    @DisplayName("Неизвестный вид затрат — отказ, а не ограничение схемы")
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> inTenant(() ->
                costs.add(donorId, "ЗАПРАВКА", new BigDecimal("1000"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Отрицательная затрата уменьшает вложенное — машина в отчёте выглядит
    // выгоднее, чем была. Возврат оформляется не минусом здесь.
    @Test
    @DisplayName("Отрицательная сумма не принимается")
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> inTenant(() ->
                costs.add(donorId, "PURCHASE", new BigDecimal("-1000"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Убранная затрата исчезает из журнала")
    void costIsRemovable() {
        var added = inTenant(() -> costs.add(donorId, "STORAGE", new BigDecimal("500"),
                null, null, null));
        var after = inTenant(() -> costs.remove(donorId, added.get(0).id()));

        assertThat(after).isEmpty();
    }

    // Чужую затрату не убрать номером: идентификаторы сквозные по схеме,
    // и промах по машине стёр бы вложенное в другую.
    @Test
    @DisplayName("Затрата чужой машины не убирается")
    void otherDonorsCostSurvives() {
        var added = inTenant(() -> costs.add(donorId, "STORAGE", new BigDecimal("500"),
                null, null, null));
        long other = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO donor (public_code, brand_id) VALUES ('ТЕСТ-2', ?) RETURNING id",
                Long.class, brandId));

        inTenant(() -> costs.remove(other, added.get(0).id()));

        assertThat(inTenant(() -> costs.of(donorId))).hasSize(1);
    }

    // Справочник приёмки отдаёт только машины в разборе; список машин —
    // все, иначе только что купленная не видна нигде.
    @Test
    @DisplayName("Список машин показывает и купленную, не поставленную в разбор")
    void directoryListsPurchasedDonors() {
        var all = inTenant(() -> directory.all());

        assertThat(all).singleElement().satisfies(entry -> {
            assertThat(entry.publicCode()).isEqualTo("ТЕСТ-1");
            assertThat(entry.status()).isEqualTo("PURCHASED");
            assertThat(entry.note()).isEqualTo("Камри");
        });
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
