package ru.partsflow.inventory;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Единственный путь движения на склад: запись в журнал и её применение.
 *
 * <p>Остаток — агрегат журнала {@code stock_movement}. Раскладка по складам
 * ({@code part_stock}), общий остаток ({@code part.qty_on_hand}) и статус
 * карточки — кэш, который обязан меняться той же транзакцией, что и движение.
 * Разъехавшись, они дают склад, которого нет: документ проведён, а на полке
 * ничего.
 *
 * <p>До 3 августа 2026 кэш вёл триггер {@code stock_movement_apply}. Логика
 * переехала сюда по правилу «логика в Java, база хранит данные и связи»; тело
 * перенесено дословно, включая порядок шагов и условия статуса.
 *
 * <p><b>Чем платим.</b> Триггер нельзя было обойти: движение, вставленное
 * откуда угодно, применялось. Теперь применяет вызвавший, и путь мимо этого
 * класса оставит журнал и кэш разошедшимися. Ловится это тремя способами:
 * писателей движений немного и они перечислены в
 * {@code docs/triggers-to-java.md}; сверка {@code v_stock_discrepancy}
 * сравнивает кэш с журналом и обязана быть пустой; и
 * {@code StockLedgerTest} проверяет каждый вид движения.
 *
 * <p><b>Запросы идут через {@code EntityManager}, а не через
 * {@code JdbcTemplate}.</b> Hibernate перед нативным запросом сбрасывает
 * отложенные записи сессии; движение, сохранённое репозиторием и ещё
 * не отправленное, иначе не попало бы в базу к моменту пересчёта. Та же
 * причина, что и в {@link StockReservationRepository}.
 */
@Service
public class StockLedger {

    private final StockMovementRepository movements;
    private final EntityManager entityManager;
    private final PartChangeLog partChanges;

    public StockLedger(StockMovementRepository movements,
                       EntityManager entityManager,
                       PartChangeLog partChanges) {
        this.movements = movements;
        this.entityManager = entityManager;
        this.partChanges = partChanges;
    }

    /**
     * Записывает движение и применяет его к остатку.
     *
     * <p>Вызывающий обязан быть в транзакции: журнал и кэш расходиться
     * не должны ни на мгновение.
     */
    public StockMovement record(StockMovement movement) {
        // Автор проставляется здесь, а не у каждого вызывающего: движение
        // пишут приёмка, продажа, возврат, пересчёт, списание и перевозка,
        // и забыть подписать можно в любом из них — а спрашивают журнал
        // ровно тогда, когда ищут, кто унёс деталь. Пусто — фоновая задача
        // или перенос: вошедшего там нет и быть не может.
        if (movement.getCreatedBy() == null) {
            movement.setCreatedBy(ru.partsflow.platform.security.CurrentUser.memberId());
        }
        StockMovement saved = movements.saveAndFlush(movement);
        apply(saved);
        // Остаток и статус уехали в прайс — площадке надо сообщить.
        partChanges.changed(saved.getPartId());
        return saved;
    }

    /** Пачка движений одной транзакцией: приёмка и проведение документа. */
    public List<StockMovement> record(List<StockMovement> batch) {
        return batch.stream().map(this::record).toList();
    }

    /**
     * Применяет движение: сначала списание со склада-источника, потом приход
     * на склад-приёмник, потом пересчёт карточки.
     *
     * <p>Порядок обязателен. Перемещение между складами — это одно движение
     * с обоими складами, и, поменяв шаги местами, мы на мгновение удвоим
     * остаток; а списание, выполненное после прихода, скроет нехватку.
     */
    private void apply(StockMovement movement) {
        if (movement.getFromWarehouseId() != null) {
            // Условие «хватает свободного» стоит в WHERE, а не проверяется
            // раньше отдельным чтением: между чтением и записью встаёт второй
            // кладовщик, и одну и ту же деталь списывают дважды. Вызывающие
            // проверяют остаток заранее ради внятного текста, но сторожем
            // остаётся эта инструкция — то же правило, что у резерва
            // в StockReservationRepository.
            //
            // Свободный, а не общий: уронив qty ниже qty_reserved, мы обещали
            // бы покупателю деталь, которой уже нет. Оба неравенства стоят
            // и в схеме (part_stock_qty_ck, part_stock_reserved_ck), но
            // нарушение схемы приезжает наружу пятисоткой, которую
            // офлайн-очередь повторяет вечно.
            int updated = entityManager.createNativeQuery("""
                            UPDATE part_stock
                               SET qty = qty - abs(:delta), updated_at = now()
                             WHERE part_id = :part AND warehouse_id = :warehouse
                               AND qty - qty_reserved >= abs(:delta)""")
                    .setParameter("delta", movement.getQtyDelta())
                    .setParameter("part", movement.getPartId())
                    .setParameter("warehouse", movement.getFromWarehouseId())
                    .executeUpdate();

            if (updated == 0) {
                throw shortage(movement);
            }
        }

        if (movement.getToWarehouseId() != null) {
            entityManager.createNativeQuery("""
                            INSERT INTO part_stock (part_id, warehouse_id, qty, cell_id)
                            VALUES (:part, :warehouse, abs(:delta), :cell)
                            ON CONFLICT (part_id, warehouse_id) DO UPDATE
                                SET qty = part_stock.qty + abs(:delta),
                                    cell_id = COALESCE(EXCLUDED.cell_id, part_stock.cell_id),
                                    updated_at = now()""")
                    .setParameter("part", movement.getPartId())
                    .setParameter("warehouse", movement.getToWarehouseId())
                    .setParameter("delta", movement.getQtyDelta())
                    .setParameter("cell", movement.getToCellId())
                    .executeUpdate();
        }

        applyToPart(movement);
    }

    /**
     * Объясняет, почему списание не прошло.
     *
     * <p>«Строки раскладки нет вовсе» и «свободного не хватило» — разные
     * причины и разные действия: в первом случае деталь ищут на другом
     * складе, во втором смотрят, кому она обещана. Один текст на оба
     * отправлял бы кладовщика не туда.
     */
    private RuntimeException shortage(StockMovement movement) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT qty, qty_reserved FROM part_stock
                         WHERE part_id = :part AND warehouse_id = :warehouse""")
                .setParameter("part", movement.getPartId())
                .setParameter("warehouse", movement.getFromWarehouseId())
                .getResultList();

        if (rows.isEmpty()) {
            // Молча пропустив, мы получили бы журнал, по которому со склада
            // уносили то, чего там нет.
            return new IllegalStateException(
                    "Нет остатка детали %d на складе %d: списывать нечего"
                            .formatted(movement.getPartId(), movement.getFromWarehouseId()));
        }

        BigDecimal qty = (BigDecimal) rows.get(0)[0];
        BigDecimal reserved = (BigDecimal) rows.get(0)[1];
        return new StockReservationRepository.InsufficientStockException(
                "На складе %d свободно %s, а требуется %s: деталь %d"
                        .formatted(movement.getFromWarehouseId(),
                                plain(qty.subtract(reserved)),
                                plain(movement.getQtyDelta().abs()),
                                movement.getPartId()),
                null);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * Пересчитывает карточку по раскладке.
     *
     * <p>Остаток берётся суммой по складам, а не приращением: так карточка
     * не разъедется с раскладкой даже если движение окажется странным.
     *
     * <p><b>Статус — производное, и правила у него разные для нуля.</b>
     * Есть остаток — «в наличии». Обнулила продажа — «продано», списание
     * или недостача — «списано». Корректировка инвентаризации в плюс статус
     * не трогает: она не говорит, куда делась деталь.
     */
    private void applyToPart(StockMovement movement) {
        entityManager.createNativeQuery("""
                        UPDATE part p
                           SET qty_on_hand = stock.qty,
                               updated_at = now(),
                               storage_cell_id = COALESCE(:cell, p.storage_cell_id),
                               status = CASE
                                   WHEN stock.qty > 0 THEN 'IN_STOCK'
                                   WHEN :type = 'SALE' THEN 'SOLD'
                                   WHEN :type = 'WRITE_OFF' THEN 'WRITTEN_OFF'
                                   WHEN :type = 'INVENTORY_ADJUST' AND :delta < 0
                                       THEN 'WRITTEN_OFF'
                                   ELSE p.status
                               END
                          FROM (SELECT COALESCE(sum(qty), 0) AS qty
                                  FROM part_stock WHERE part_id = :part) stock
                         WHERE p.id = :part""")
                .setParameter("cell", movement.getToCellId())
                .setParameter("type", movement.getMovementType().name())
                .setParameter("delta", movement.getQtyDelta())
                .setParameter("part", movement.getPartId())
                .executeUpdate();
    }

    /**
     * Пересчитывает карточки по журналу целиком — для массовых операций.
     *
     * <p>Перенос из прежней системы пишет десятки тысяч движений своим
     * соединением, вне JPA; применять их по одному значило бы столько же
     * обращений к базе. Статус при этом выводится из остатка, а не из вида
     * последнего движения: у переехавшего склада все движения — приход.
     *
     * @return сколько карточек пересчитано
     */
    public int recomputeAll() {
        entityManager.createNativeQuery("""
                        INSERT INTO part_stock (part_id, warehouse_id, qty)
                        SELECT part_id, warehouse, sum(delta)
                          FROM (
                              SELECT part_id, to_warehouse_id AS warehouse, abs(qty_delta) AS delta
                                FROM stock_movement WHERE to_warehouse_id IS NOT NULL
                              UNION ALL
                              SELECT part_id, from_warehouse_id, -abs(qty_delta)
                                FROM stock_movement WHERE from_warehouse_id IS NOT NULL
                          ) m
                         GROUP BY part_id, warehouse
                        ON CONFLICT (part_id, warehouse_id) DO UPDATE
                            SET qty = EXCLUDED.qty, updated_at = now()""")
                .executeUpdate();

        return entityManager.createNativeQuery("""
                        UPDATE part p
                           SET qty_on_hand = COALESCE(stock.qty, 0),
                               updated_at = now(),
                               status = CASE WHEN COALESCE(stock.qty, 0) > 0
                                             THEN 'IN_STOCK' ELSE p.status END
                          FROM (SELECT part_id, sum(qty) AS qty
                                  FROM part_stock GROUP BY part_id) stock
                         WHERE p.id = stock.part_id
                           AND (p.qty_on_hand IS DISTINCT FROM COALESCE(stock.qty, 0)
                                OR (COALESCE(stock.qty, 0) > 0 AND p.status <> 'IN_STOCK'))""")
                .executeUpdate();
    }

    /** Свободный остаток по складу — для проверок перед уносом со склада. */
    public BigDecimal onHand(Long partId, Long warehouseId) {
        Object found = entityManager.createNativeQuery("""
                        SELECT COALESCE(sum(qty), 0) FROM part_stock
                         WHERE part_id = :part AND warehouse_id = :warehouse""")
                .setParameter("part", partId)
                .setParameter("warehouse", warehouseId)
                .getSingleResult();
        return found == null ? BigDecimal.ZERO : (BigDecimal) found;
    }
}
