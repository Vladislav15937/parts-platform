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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Витрина склада: таблица товаров для владельца.
 *
 * <p>Проверяется то, что ломается тихо: подстановка сортировки, фильтр
 * «отсутствующие» и колонки складов. Ошибка в первой — это внедрение SQL,
 * во второй — склад, который выглядит вдвое больше, чем есть.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class CatalogServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000089";

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        // Журнал движений неизменяем — его нельзя чистить между тестами,
        // и это правильно: удаление движения означало бы остаток, взявшийся
        // ниоткуда. Поэтому каждый тест заводит свои позиции с уникальными
        // названиями и смотрит только на них.
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Проданное по умолчанию не показывается, но по просьбе — да")
    void soldIsHiddenUnlessAsked() {
        Long inStock = part("Фара на складе", 1);
        Long sold = part("Фара проданная", 0);

        List<String> visible = titles(catalog(false, false));
        assertThat(visible).contains("Фара на складе");
        // Склад — это то, что лежит: проданное в общем списке делает его
        // вдвое длиннее и путает пересчёт.
        assertThat(visible)
                .as("проданное показано в складе по умолчанию")
                .doesNotContain("Фара проданная");

        assertThat(titles(catalog(false, true))).contains("Фара проданная");
        assertThat(inStock).isNotNull();
        assertThat(sold).isNotNull();
    }

    @Test
    @DisplayName("Остаток приходит по складам, а не одним числом")
    void stockIsPerWarehouse() {
        Long partId = part("Бампер", 3);

        var row = catalog(true, false).rows().stream()
                .filter(r -> r.id().equals(partId)).findFirst().orElseThrow();

        // У клиента складов несколько, и колонка на каждый — это то, как
        // на складе ищут: «на Ткацкой две, на дальнем ноль».
        assertThat(row.stock()).containsEntry(warehouseId, new java.math.BigDecimal("3.000"));
    }

    @Test
    @DisplayName("Неизвестная сортировка не уходит в SQL")
    void unknownSortIsIgnored() {
        part("Дверь", 1);

        // ORDER BY не принимает параметр, и подстановка пришедшего текста —
        // это внедрение. Неизвестное имя обязано молча стать сортировкой
        // по умолчанию, а не попасть в запрос.
        long before = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part", Long.class));

        var page = inTenant(() -> catalog.list(null, true, false, List.of(),
                "id; DROP TABLE part", false, 0, 50));

        // Запрос отработал, таблица на месте: неизвестное имя стало
        // сортировкой по умолчанию, а не уехало в SQL.
        assertThat(page.total()).isGreaterThan(0);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part", Long.class))).isEqualTo(before);
    }

    private CatalogService.Page catalog(boolean reserved, boolean missing) {
        return inTenant(() -> catalog.list(null, reserved, missing, List.of(), "code", true, 0, 50));
    }

    private List<String> titles(CatalogService.Page page) {
        return page.rows().stream().map(CatalogService.Row::title).toList();
    }

    private Long part(String title, int qty) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, ?, 1000)
                    RETURNING id""", Long.class, title);
            if (qty > 0) {
                jdbc.update("""
                        INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                    to_warehouse_id)
                        VALUES (?, 'INTAKE', ?, ?)""", id, qty, warehouseId);
            }
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
