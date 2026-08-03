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
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сторож отметки об изменении позиции.
 *
 * <p>До 3 августа 2026 отметку ставил триггер базы, и забыть её было нельзя.
 * Теперь её ставит код — значит новая операция над позицией может её
 * не поставить, и узнают об этом по объявлению, которое сутки показывает
 * проданную деталь. Этот тест проходит по операциям и требует отметку
 * от каждой.
 *
 * <p>Проверка «отметка есть», а не «дельта ушла»: отправка проверяется
 * в {@code FeedDeltaRelayTest}, здесь только источник.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class PartChangeLogTest extends PostgresTestBase {

    private static final String TENANT = "t_000094";

    @Autowired
    private StockLedger ledger;

    @Autowired
    private PartService parts;

    @Autowired
    private PhotoService photos;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private StockReservationRepository reservations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            jdbc.update("DELETE FROM part_change");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Смена цены отмечается")
    void priceChangeIsMarked() {
        Long partId = part("Фара", "5000");
        drain();

        inTenant(() -> parts.changePrice(partId, new BigDecimal("7000"), null));

        assertThat(marked()).containsExactly(partId);
    }

    @Test
    @DisplayName("Цена, которая не изменилась, отметки не даёт")
    void sameePriceIsNotMarked() {
        Long partId = part("Бампер", "5000");
        drain();

        inTenant(() -> parts.changePrice(partId, new BigDecimal("5000"), null));

        // Иначе повторное сохранение формы гоняет дельты по кругу.
        assertThat(marked()).isEmpty();
    }

    @Test
    @DisplayName("Правка списком отмечает все тронутые позиции")
    void bulkEditMarksEveryPart() {
        Long first = part("Стартер", "5000");
        Long second = part("Генератор", "6000");
        drain();

        inTenant(() -> parts.updateAll(List.of(first, second),
                Map.of("section", "Распродажа"), null));

        assertThat(marked()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("Снятие с публикации отмечается")
    void publicationChangeIsMarked() {
        Long partId = part("Радиатор", "5000");
        drain();

        inTenant(() -> parts.setPublished(List.of(partId), false));

        // Иначе объявление висит, а продавать её владелец не собирался.
        assertThat(marked()).containsExactly(partId);
    }

    @Test
    @DisplayName("Резерв отмечается: на площадку уезжает свободный остаток")
    void reservationIsMarked() {
        Long partId = part("Дверь", "5000");
        drain();

        inTenant(() -> {
            reservations.reserve(partId, warehouse, BigDecimal.ONE);
            return null;
        });

        assertThat(marked()).containsExactly(partId);
    }

    @Test
    @DisplayName("Снятие резерва отмечается тоже")
    void releaseIsMarked() {
        Long partId = part("Капот", "5000");
        inTenant(() -> {
            reservations.reserve(partId, warehouse, BigDecimal.ONE);
            return null;
        });
        drain();

        inTenant(() -> {
            reservations.release(partId, warehouse, BigDecimal.ONE);
            return null;
        });

        assertThat(marked()).containsExactly(partId);
    }

    @Test
    @DisplayName("Применимость отмечается — прежний триггер этого не ловил вовсе")
    void applicabilityIsMarked() {
        Long partId = part("Фара контрактная", "5000");
        Long brand = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM catalog.brand ORDER BY id LIMIT 1", Long.class));
        drain();

        inTenant(() -> catalog.addApplicability(partId, brand, null));

        // Марка и модель уезжают в прайс отдельными тегами, и у контрактной
        // детали берутся именно отсюда. Триггера на part_applicability
        // не существовало — то есть перенос в Java эту дыру ещё и закрыл.
        assertThat(marked()).containsExactly(partId);
    }

    @Test
    @DisplayName("Назначение главного снимка отмечается")
    void mainPhotoIsMarked() {
        Long partId = part("Крыло", "5000");
        Long photoId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part_photo (part_id, s3_key, status, sort_order, width, height)
                VALUES (?, 't_000094/parts/1/x.jpg', 'PROCESSED', 0, 800, 600) RETURNING id""",
                Long.class, partId));
        drain();

        inTenant(() -> {
            photos.makeMain(photoId);
            return null;
        });

        // Главный снимок идёт в прайсе первым — площадка ставит его обложкой.
        assertThat(marked()).containsExactly(partId);
    }

    // Удаление снимка отмечается тоже, но проверяется оно в PhotoServiceTest:
    // PhotoService.delete ходит в хранилище, а у этого контекста хранилища нет
    // и быть не должно. MinIO поднимают только те два теста, которым он нужен
    // по делу; остальные смотрят в localhost:9000, где на CI не отвечает никто.
    // Локально это невидимо: там на 9000 стоит MinIO из compose разработки —
    // и ровно на этом стенд краснел.

    @Test
    @DisplayName("Повторные правки одной позиции дают одну строку")
    void repeatedChangesCollapse() {
        Long partId = part("Зеркало", "5000");
        drain();

        inTenant(() -> parts.changePrice(partId, new BigDecimal("6000"), null));
        inTenant(() -> parts.changePrice(partId, new BigDecimal("7000"), null));
        inTenant(() -> parts.changePrice(partId, new BigDecimal("8000"), null));

        // Площадке нужно текущее состояние, а не история: три правки —
        // одна дельта.
        assertThat(marked()).containsExactly(partId);
    }

    @Test
    @DisplayName("Новая отметка снимает заявку — правка в пути не потеряется")
    void newMarkReleasesTheClaim() {
        Long partId = part("Решётка", "5000");
        inTenant(() -> {
            jdbc.update("UPDATE part_change SET claimed_at = now() WHERE part_id = ?", partId);
            return null;
        });

        inTenant(() -> parts.changePrice(partId, new BigDecimal("9000"), null));

        // Уборка после успешной отправки удаляет только заявленное. Правка,
        // случившаяся, пока дельта была в пути, обязана заявку снять — иначе
        // площадка останется с состоянием, которого уже нет.
        Integer claimed = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_change WHERE part_id = ? AND claimed_at IS NOT NULL",
                Integer.class, partId));
        assertThat(claimed).isZero();
    }

    // ---------- фикстуры ----------

    private Long part(String title, String price) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, ?, ?::numeric, true) RETURNING id""",
                    Long.class, title, price);
            ledger.record(StockMovement.intake(partId, java.math.BigDecimal.ONE, warehouse, null));
            return partId;
        });
    }

    /** Очередь после заведения позиции не пуста: приёмка — тоже изменение. */
    private void drain() {
        inTenant(() -> jdbc.update("DELETE FROM part_change"));
    }

    private List<Long> marked() {
        return inTenant(() -> jdbc.queryForList(
                "SELECT part_id FROM part_change ORDER BY part_id", Long.class));
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
