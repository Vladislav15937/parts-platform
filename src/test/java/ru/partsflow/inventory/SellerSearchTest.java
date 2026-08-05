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

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Поиск продавца находит то же, что и витрина владельца.
 *
 * <p><b>Что было.</b> Продавец искал только по морфологии, а витрина —
 * и подстрокой тоже. Числам морфология не годится: покупатель называет номер
 * куском («1150-33»), а продавец читает с этикетки на детали код товара.
 * Замерено на живом складе: «140125» находило у владельца три позиции
 * и ни одной у продавца, код товара «7584A8FEAE3D» — одну у владельца
 * и ноль у продавца. Деталь лежит на полке, её номер напечатан на ней же,
 * и продавец отвечает «нет такого».
 *
 * <p>Кросс-номера не искались вовсе: {@code part_oem} в запросе
 * не участвовал, — а по ним и звонят, когда своего номера нет.
 *
 * <p>Ровно эта расходимость уже чинилась с другой стороны, когда витрина
 * искала только подстрокой и показывала 521 позицию против 739 у продавца.
 * Два поиска по одному складу отвечают по-разному, и неправ тот, о ком
 * не спрашивали.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class SellerSearchTest extends PostgresTestBase {

    private static final String TENANT = "t_000106";

    @Autowired
    private PartService parts;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;
    private Long partId;
    private String code;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);

            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price, is_published)
                    VALUES (1, 'Фара Toyota Camry 2007 лев. (б/у) 8414012530', 8500, 4000, true)
                    RETURNING id""", Long.class);
            // Номер производителя и кросс-номер: по второму звонят, когда
            // своего номера у покупателя нет.
            jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized)
                    VALUES (?, '8414012530', '8414012530'), (?, '8465242100', '8465242100')""",
                    partId, partId);
            code = jdbc.queryForObject(
                    "SELECT public_code FROM part WHERE id = ?", String.class, partId);
            return null;
        });
        inTenant(() -> ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouseId, null)));
    }

    /**
     * Код товара напечатан на этикетке, приклеенной к самой детали.
     * Не найти по нему — значит не найти деталь, держа её в руках.
     */
    @Test
    @DisplayName("Продавец находит деталь по коду товара с этикетки")
    void findsByItemCode() {
        assertThat(found(code))
                .as("код товара с этикетки не находит собственную деталь")
                .contains(partId);
    }

    /** Номер называют куском: «есть 1150-33?» */
    @Test
    @DisplayName("Продавец находит по обрывку номера производителя")
    void findsByNumberFragment() {
        assertThat(found("140125"))
                .as("номер, названный куском, не находит деталь")
                .contains(partId);
    }

    /** По кросс-номеру звонят, когда своего номера у покупателя нет. */
    @Test
    @DisplayName("Продавец находит по кросс-номеру")
    void findsByCrossNumber() {
        assertThat(found("8465242100"))
                .as("кросс-номер не ищется вовсе")
                .contains(partId);
    }

    /**
     * Морфология обязана остаться: «фары» и «фару» спрашивают чаще, чем
     * «фара», и подстрока их не свяжет.
     */
    @Test
    @DisplayName("Слово в другом падеже по-прежнему находится")
    void findsByMorphology() {
        assertThat(found("фары")).contains(partId);
    }

    /** Русское написание машины — то, как покупатель её называет. */
    @Test
    @DisplayName("Машина по-русски по-прежнему находится")
    void findsByRussianVehicle() {
        assertThat(found("камри")).contains(partId);
    }

    /**
     * Расширение поиска не должно превращать его в «находит всё»: продавец
     * читает выдачу глазами, и лишняя деталь в ней хуже отсутствующей.
     */
    @Test
    @DisplayName("Чужой номер деталь не находит")
    void doesNotFindForeignNumber() {
        assertThat(found("9999999999")).isEmpty();
    }

    private List<Long> found(String query) {
        return inTenant(() -> parts.searchAvailable(query, 50)).stream()
                .map(PartService.StockRow::partId)
                .toList();
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
