package ru.partsflow.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.outbox.DomainEvent;
import ru.partsflow.platform.outbox.DomainEventPublisher;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class PartService {

    private final PartRepository partRepository;
    private final StockMovementRepository movementRepository;
    private final DomainEventPublisher eventPublisher;

    public PartService(PartRepository partRepository,
                       StockMovementRepository movementRepository,
                       DomainEventPublisher eventPublisher) {
        this.partRepository = partRepository;
        this.movementRepository = movementRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Приёмка запчасти: создание карточки и постановка на остаток.
     *
     * <p>Всё в одной транзакции — карточка, движение и событие. Именно ради этого
     * событие пишется в outbox, а не отправляется в брокер напрямую: иначе
     * возможен вариант «деталь сохранена, событие потеряно», при котором она
     * не уедет на площадки и просто не будет продаваться.
     */
    @Transactional
    public Part intake(Part part, BigDecimal quantity, Long warehouseId, Long storageCellId) {
        part.setStatus(PartStatus.IN_STOCK);
        Part saved = partRepository.saveAndFlush(part);

        movementRepository.save(
                StockMovement.intake(saved.getId(), quantity, warehouseId, storageCellId));

        eventPublisher.publish(DomainEvent.of(
                "part", saved.getId(), "part.created.v1", payloadOf(saved)));

        return saved;
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

    @Transactional(readOnly = true)
    public List<Part> search(String query, int limit) {
        return partRepository.search(query, limit);
    }

    @Transactional(readOnly = true)
    public List<Part> findByOem(String number) {
        return partRepository.findByOemNumber(number);
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
