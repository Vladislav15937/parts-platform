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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Износ шины в процентах (задача 0340, решение владельца 19.10.2026, решение 3).
 *
 * <p><b>Главное здесь — то, что уже лежит.</b> До задачи перенос из Bazon
 * и с Дрома клал «30%» клиента миллиметрами остатка от восьми мм новой шины,
 * а прайс Дрома пересчитывал их обратно. {@code tenant/093} возвращает лежащие
 * миллиметры в проценты, и проверить это можно только так, как это пройдёт
 * у клиента: накатом по непустой таблице. На чистой базе пересчитывать нечего,
 * и {@code db/verify.sh} пропустит любую формулу. Поэтому схема заводится,
 * в неё кладутся колёса с миллиметрами, затем колонка и отметка о накате
 * снимаются — и changeset проигрывается заново (как {@code PartNumberTest}).
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class WheelWearPercentTest extends PostgresTestBase {

    private static final String TENANT = "t_000534";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WheelService wheels;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    /**
     * Проценты, перенесённые до 0340, возвращаются ровно теми же: 5…95 шагом 5.
     *
     * <p>Миллиметры берутся тем же пересчётом, каким их клали переносы
     * ({@code 8 × (100 − p) / 100}, одна цифра после запятой; он и остаётся
     * в {@link WheelService#legacyMillimetres} для прежнего образа). Вписанные
     * руками миллиметры получают тот процент, который прайс Дрома отдавал
     * за них до наката: площадка не должна увидеть разницы ни у одной шины.
     */
    @Test
    @DisplayName("Перенесённый износ 5–95 % шагом 5 возвращается из миллиметров тем же процентом")
    void transferredPercentsComeBackExactly() {
        Map<Long, Integer> expected = new LinkedHashMap<>();
        for (int percent = 5; percent <= 95; percent += 5) {
            expected.put(tyre(WheelService.legacyMillimetres(percent)), percent);
        }
        // Вписанные руками миллиметры — тот процент, что уезжал на Дром до 0340:
        // 100 × (8 − мм) / 8, половина вверх, не меньше нуля и не больше ста.
        expected.put(tyre(new BigDecimal("6")), 25);
        expected.put(tyre(new BigDecimal("5.5")), 31);
        expected.put(tyre(new BigDecimal("9")), 0);
        expected.put(tyre(new BigDecimal("0")), 100);
        // Предел, названный в changeset'е: 12 % и 13 % оба лежат как 7,0 мм,
        // и восстановить двенадцать из миллиметров нечем.
        expected.put(tyre(WheelService.legacyMillimetres(12)), 13);
        Long withoutWear = tyre(null);

        replayChangeset();

        Map<Long, Integer> actual = new LinkedHashMap<>();
        inTenant(() -> {
            jdbc.query("SELECT part_id, wear_percent FROM part_wheel", rs -> {
                actual.put(rs.getLong("part_id"), (Integer) rs.getObject("wear_percent"));
            });
            return null;
        });
        expected.forEach((partId, percent) -> assertThat(actual.get(partId))
                .as("износ позиции %d после наката", partId)
                .isEqualTo(percent));
        assertThat(actual.get(withoutWear))
                .as("износа не было — пересчёт его не выдумывает")
                .isNull();
    }

    @Test
    @DisplayName("Износ больше ста процентов отбивается словами, а не базой")
    void wearOverHundredIsRejected() {
        Long warehouse = inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал износа') RETURNING id", Long.class);
            return jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Склад износа') RETURNING id",
                    Long.class, branch);
        });
        WheelService.WheelRequest worn = new WheelService.WheelRequest("TYRE",
                new BigDecimal("15"), 195, 65, "R", "Легковая", "SUMMER", 150, 2022,
                null, null, null, null, null,
                "Goodyear", "EfficientGrip", null, null, null, null, null, null, null, null,
                new BigDecimal("3000"), null, "USED");

        assertThatThrownBy(() -> inTenant(() -> wheels.createSet(worn, 1, warehouse, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Износ — процент от 0 до 100, а не 150");
    }

    private Long tyre(BigDecimal wearMm) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, product_line, condition)
                    VALUES (1, 'Шина износа', 1000, 'WHEEL', 'USED') RETURNING id""", Long.class);
            jdbc.update("""
                    INSERT INTO part_wheel (part_id, kind, diameter, tyre_width, tyre_height,
                                            wear_mm, wear_percent)
                    VALUES (?, 'TYRE', 15, 195, 65, ?, NULL)""", partId, wearMm);
            return partId;
        });
    }

    private void replayChangeset() {
        inTenant(() -> {
            jdbc.execute("ALTER TABLE part_wheel DROP COLUMN wear_percent");
            jdbc.update("DELETE FROM " + TENANT + ".databasechangelog WHERE id = ?",
                    "tenant-093-wheel-wear-percent");
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
