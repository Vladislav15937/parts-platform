package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.outbox.DomainEvent;
import ru.partsflow.platform.outbox.DomainEventPublisher;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Работа с уже заведёнными карточками: цена и поиск.
 *
 * <p><b>Приёмки здесь нет.</b> Она живёт в {@code IntakeService}: карточка
 * создаётся не сама по себе, а складским документом, с наименованием
 * из справочника и собранным заголовком. Второй путь «просто создать деталь
 * и написать движение» существовал до этого и расходился с первым — заводил
 * позицию без документа и без сопоставления наименования.
 */
@Service
public class PartService {

    private final PartRepository partRepository;
    private final DomainEventPublisher eventPublisher;
    private final JdbcTemplate jdbc;

    public PartService(PartRepository partRepository, DomainEventPublisher eventPublisher,
                       JdbcTemplate jdbc) {
        this.partRepository = partRepository;
        this.eventPublisher = eventPublisher;
        this.jdbc = jdbc;
    }

    @Transactional
    public Part changePrice(Long partId, BigDecimal newPrice, Long changedBy) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new IllegalArgumentException("Запчасть не найдена: " + partId));

        if (newPrice.compareTo(part.getPrice() == null ? BigDecimal.ZERO : part.getPrice()) == 0) {
            return part;
        }
        part.changePrice(newPrice, changedBy);

        // Ключ партиции включает id запчасти, поэтому события по одной детали
        // не переставятся местами и на площадку не уедет устаревшая цена.
        eventPublisher.publish(DomainEvent.of(
                "part", part.getId(), "part.price_changed.v1", payloadOf(part)));

        return part;
    }

    /**
     * Поиск для продавца: что можно продать прямо сейчас.
     *
     * <p><b>Отдаёт свободный остаток по складам, а не статус карточки.</b>
     * Статус говорит про наличие, а продавать нельзя то, что обещано другому
     * клиенту: из трёх штук одна отложена — продать можно две, и статус
     * карточки об этом не скажет ничего.
     *
     * <p>Позиции без свободного остатка не прячутся. Продавцу нужно ответить
     * «есть, но отложена до завтра», а не «нет»: клиент перезвонит, а деталь
     * освободится. Отсортировано так, что свободное — сверху.
     *
     * <p>Читается напрямую, а не через сущности: экрану нужны склад, ячейка
     * и три числа, а поднимать ради этого агрегат детали с фотографиями
     * и OEM-номерами незачем.
     */
    @Transactional(readOnly = true)
    public List<StockRow> searchAvailable(String query, int limit) {
        return jdbc.query("""
                SELECT p.id, p.public_code, p.title, p.price, p.status,
                       w.id AS warehouse_id, w.name AS warehouse_name,
                       c.code AS cell_code,
                       s.qty, s.qty_reserved, s.qty_available
                  FROM part p
                  JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
                  JOIN warehouse w ON w.id = s.warehouse_id
                  LEFT JOIN storage_cell c ON c.id = s.cell_id
                 WHERE p.search_vector @@ plainto_tsquery('russian', ?)
                 ORDER BY (s.qty_available > 0) DESC,
                          ts_rank(p.search_vector, plainto_tsquery('russian', ?)) DESC,
                          p.id
                 LIMIT ?""",
                (rs, i) -> new StockRow(
                        rs.getLong("id"),
                        rs.getString("public_code"),
                        rs.getString("title"),
                        rs.getBigDecimal("price"),
                        rs.getString("status"),
                        rs.getLong("warehouse_id"),
                        rs.getString("warehouse_name"),
                        rs.getString("cell_code"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("qty_reserved"),
                        rs.getBigDecimal("qty_available")),
                query, query, limit);
    }

    /** Строка выдачи продавцу: деталь на конкретном складе. */
    public record StockRow(Long partId, String publicCode, String title, BigDecimal price,
                           String status, Long warehouseId, String warehouseName,
                           String cellCode, BigDecimal qty, BigDecimal qtyReserved,
                           BigDecimal qtyAvailable) {
    }

    /**
     * Включает или выключает выгрузку позиций на площадки.
     *
     * <p>Пачкой, а не по одной: после импорта склада исключений набирается
     * несколько сотен, и по одному их отмечать никто не будет.
     *
     * <p>Обратное действие полное: снятый флаг убирает объявление не сразу.
     * У Дрома оно уезжает с {@code available = false} в ближайшем прайсе —
     * удалять его нельзя, вместе с ним пропадут накопленные просмотры.
     *
     * @return сколько позиций изменилось
     */
    @Transactional
    public int setPublished(List<Long> partIds, boolean published) {
        if (partIds == null || partIds.isEmpty()) {
            throw new IllegalArgumentException("Не указано ни одной позиции");
        }
        return jdbc.update("UPDATE part SET is_published = ? WHERE id = ANY (?)",
                published, partIds.toArray(Long[]::new));
    }

    @Transactional(readOnly = true)
    public List<Part> search(String query, int limit) {
        return partRepository.search(query, limit);
    }

    @Transactional(readOnly = true)
    public List<Part> findByOem(String number) {
        return partRepository.findByOemNumber(number);
    }

    /**
     * Наименования по идентификаторам.
     *
     * <p>Нужно продажам: позиция сделки хранит только {@code part_id}, а продавец
     * при возврате выбирает строку глазами и должен видеть «фара левая»,
     * а не «деталь 4712». Своим репозиторием запчастей продажи не ходят —
     * модули общаются через интерфейсы.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> titlesOf(Collection<Long> partIds) {
        if (partIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = new HashMap<>();
        for (Part part : partRepository.findAllById(partIds)) {
            titles.put(part.getId(), part.getTitle());
        }
        return titles;
    }

    /**
     * TODO: заменить на Protobuf со Schema Registry, как описано в архитектуре.
     * Сейчас — простая сериализация, чтобы контур работал целиком.
     */
    private byte[] payloadOf(Part part) {
        return """
                {"id":%d,"publicCode":"%s","title":"%s","price":%s,"status":"%s"}"""
                .formatted(part.getId(),
                        part.getPublicCode(),
                        part.getTitle().replace("\"", "\\\""),
                        part.getPrice(),
                        part.getStatus())
                .getBytes(StandardCharsets.UTF_8);
    }
}
