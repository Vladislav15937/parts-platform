package ru.partsflow.sales;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.Part;
import ru.partsflow.inventory.PartRepository;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.inventory.StockReservationRepository;
import ru.partsflow.inventory.StockMovementRepository;
import ru.partsflow.platform.outbox.DomainEvent;
import ru.partsflow.platform.outbox.DomainEventPublisher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Продажи: сделка, деньги, выдача.
 *
 * <p>Здесь сходятся три вещи, которые обязаны меняться вместе: состояние
 * сделки, остаток на складе и деньги клиента. Разъехавшись, они дают самые
 * дорогие ошибки — проданный дважды товар и не сошедшуюся кассу, — поэтому
 * каждая операция идёт одной транзакцией.
 */
@Service
public class SalesService {

    private final DealRepository dealRepository;
    private final DealReturnRepository dealReturnRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerAccountEntryRepository accountRepository;
    private final DocumentEventRepository eventRepository;
    private final PartRepository partRepository;
    private final StockMovementRepository movementRepository;
    private final StockReservationRepository reservationRepository;
    private final DomainEventPublisher eventPublisher;

    public SalesService(DealRepository dealRepository,
                        DealReturnRepository dealReturnRepository,
                        PaymentRepository paymentRepository,
                        CustomerAccountEntryRepository accountRepository,
                        DocumentEventRepository eventRepository,
                        PartRepository partRepository,
                        StockMovementRepository movementRepository,
                        StockReservationRepository reservationRepository,
                        DomainEventPublisher eventPublisher) {
        this.dealRepository = dealRepository;
        this.dealReturnRepository = dealReturnRepository;
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.eventRepository = eventRepository;
        this.partRepository = partRepository;
        this.movementRepository = movementRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Создаёт сделку и сразу резервирует товар.
     *
     * <p>Черновик без резерва почти не встречается: продавец разговаривает
     * с клиентом по телефону, и деталь нужно отложить в тот же момент, иначе
     * её продаст сосед за соседним столом.
     */
    @Transactional
    public Deal createReserved(Long customerId, Long managerId, Instant reservedUntil,
                               List<ItemRequest> items) {

        Deal deal = new Deal(customerId, managerId);
        deal.setCreatedBy(managerId);

        for (ItemRequest item : items) {
            Part part = requirePart(item.partId());
            deal.addItem(part.getId(), item.quantity(),
                    item.price() != null ? item.price() : part.getPrice(), item.warehouseId());

            // Резерв на складе ставится здесь же, в той же транзакции.
            // Отложить его «на потом» значит открыть окно, в котором ту же
            // деталь положит в свою сделку другой продавец.
            reservationRepository.reserve(part.getId(), item.warehouseId(), item.quantity());
        }
        deal.reserve(reservedUntil);

        Deal saved = dealRepository.saveAndFlush(deal);
        log(saved, "CREATED", "Сделка создана и зарезервирована. Позиций: "
                + saved.getItems().size(), managerId);
        return saved;
    }

    /**
     * Выдача клиенту: единственное место, где товар уходит со склада.
     *
     * <p>До выдачи он числится на складе и лишь помечен зарезервированным —
     * это позволяет отменить сделку, не восстанавливая остаток руками.
     */
    @Transactional
    public Deal issue(Long dealId, Long managerId) {
        Deal deal = requireDeal(dealId);
        Instant now = Instant.now();

        for (DealItem item : deal.getItems()) {
            if (item.getStatus() != DealItemStatus.RESERVED) {
                continue;
            }
            Part part = requirePart(item.getPartId());
            // Снимок себестоимости берётся здесь и больше не меняется:
            // переоценка донора задним числом не должна переписывать прибыль.
            item.captureCost(part.getCostPrice());

            // Порядок обязателен: сначала снять резерв, потом списать.
            // Наоборот получится qty_reserved > qty — «зарезервировано больше,
            // чем лежит», и триггер part_stock_reserved_guard это отобьёт.
            reservationRepository.release(
                    item.getPartId(), item.getWarehouseId(), item.getQuantity());

            movementRepository.save(StockMovement.sale(
                    item.getPartId(), item.getQuantity(), item.getWarehouseId(), dealId));
        }

        deal.issue(now);
        Deal saved = dealRepository.saveAndFlush(deal);

        log(saved, "ISSUED", "Сделка выдана клиенту", managerId);
        eventPublisher.publish(DomainEvent.of("deal", dealId, "deal.issued.v1", payloadOf(saved)));
        return saved;
    }

    /**
     * Отмена: товар остаётся на складе, резерв снимается.
     *
     * <p>Никаких движений склада здесь нет — их и не было: до выдачи товар
     * физически не двигался.
     */
    @Transactional
    public Deal cancel(Long dealId, Long managerId, String reason) {
        Deal deal = requireDeal(dealId);

        // Резерв снимается со склада: иначе отменённая сделка навсегда
        // заблокирует деталь, и её никто не сможет продать.
        for (DealItem item : deal.getItems()) {
            if (item.getStatus() == DealItemStatus.RESERVED) {
                reservationRepository.release(
                        item.getPartId(), item.getWarehouseId(), item.getQuantity());
            }
        }
        deal.cancel(Instant.now());

        Deal saved = dealRepository.saveAndFlush(deal);
        log(saved, "CANCELLED",
                reason == null || reason.isBlank() ? "Сделка отменена" : "Сделка отменена: " + reason,
                managerId);
        eventPublisher.publish(DomainEvent.of("deal", dealId, "deal.cancelled.v1", payloadOf(saved)));
        return saved;
    }

    /**
     * Возврат от клиента: документ, склад и деньги одной транзакцией.
     *
     * <p>Возврат оформляют только по выданной сделке — до выдачи достаточно
     * отмены. Деталь возвращается на склад возврата, который не обязан совпадать
     * со складом выдачи, и только если она пригодна к продаже: у бракованной
     * {@code restocked} снят, деньги клиент получает, а в остаток она не встаёт.
     *
     * <p>Деньги уходят либо из кассы, либо на лицевой счёт клиента. Второе —
     * обычная практика у постоянных покупателей: перекуп сдаёт не подошедшую
     * деталь и тут же берёт другую, и гонять наличные через кассу дважды никто
     * не станет.
     *
     * @param refundToAccount зачислить сумму на лицевой счёт вместо выдачи из кассы
     */
    @Transactional
    public DealReturn registerReturn(Long dealId, Long warehouseId, List<ReturnRequest> requests,
                                     String reason, boolean refundToAccount,
                                     Long paymentSourceId, Long managerId) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Возврат без позиций не имеет смысла");
        }
        Deal deal = requireDeal(dealId);
        Instant now = Instant.now();

        DealReturn dealReturn = new DealReturn(dealId, deal.getCustomerId(), warehouseId);
        dealReturn.setReason(reason);
        dealReturn.setCreatedBy(managerId);

        for (ReturnRequest request : requests) {
            DealItem item = requireIssuedItem(deal, request.dealItemId());
            BigDecimal quantity = request.quantity() != null ? request.quantity() : item.getQuantity();

            if (quantity.compareTo(item.getQuantity()) > 0) {
                throw new IllegalArgumentException(
                        "Возвращают больше, чем выдали: позиция %d, выдано %s, возврат %s"
                                .formatted(item.getId(), item.getQuantity(), quantity));
            }
            dealReturn.addItem(item.getPartId(), quantity,
                    request.amount() != null ? request.amount() : refundFor(item, quantity),
                    request.restocked());
        }

        // Документ сохраняется до движений: журнал склада ссылается на его номер,
        // и без идентификатора ссылку не поставить.
        DealReturn saved = dealReturnRepository.saveAndFlush(dealReturn);

        deal.registerReturn(requests.stream().map(ReturnRequest::dealItemId).toList(), now);
        dealRepository.saveAndFlush(deal);

        saved.complete(now);
        for (DealReturnItem item : saved.restockedItems()) {
            movementRepository.save(StockMovement.returned(
                    item.getPartId(), item.getQuantity(), warehouseId, saved.getId()));
        }
        refund(saved, deal, refundToAccount, paymentSourceId, managerId);
        dealReturnRepository.saveAndFlush(saved);

        String note = "Возврат %s на %s ₽%s".formatted(
                saved.getNumber(), saved.getAmount().toPlainString(),
                reason == null || reason.isBlank() ? "" : ". Причина: " + reason);
        log(deal, "RETURNED", note, managerId);
        eventRepository.save(DocumentEvent.forReturn(saved.getId(), "COMPLETED",
                "Возврат оформлен по сделке %s".formatted(deal.getNumber()), managerId));
        eventPublisher.publish(DomainEvent.of("deal", dealId, "deal.returned.v1", payloadOf(deal)));
        return saved;
    }

    /** Отменяет незавершённый возврат: склад и касса ещё не тронуты. */
    @Transactional
    public DealReturn cancelReturn(Long returnId, Long managerId) {
        DealReturn dealReturn = dealReturnRepository.findById(returnId)
                .orElseThrow(() -> new IllegalArgumentException("Возврат не найден: " + returnId));

        dealReturn.cancel();
        DealReturn saved = dealReturnRepository.saveAndFlush(dealReturn);
        eventRepository.save(DocumentEvent.forReturn(returnId, "CANCELLED",
                "Возврат отменён", managerId));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DealReturn> returnsOf(Long dealId) {
        return dealReturnRepository.findByDealIdOrderByIdAsc(dealId);
    }

    /**
     * Сумма к возврату по позиции. Считается от строки сделки, а не от текущей
     * цены запчасти: клиенту возвращают то, что он заплатил, включая скидку.
     */
    private BigDecimal refundFor(DealItem item, BigDecimal quantity) {
        if (quantity.compareTo(item.getQuantity()) == 0) {
            return item.lineTotal();
        }
        return item.lineTotal()
                .multiply(quantity)
                .divide(item.getQuantity(), 2, RoundingMode.HALF_UP);
    }

    private void refund(DealReturn dealReturn, Deal deal, boolean toAccount,
                        Long paymentSourceId, Long managerId) {
        if (dealReturn.getAmount().signum() == 0) {
            return;
        }
        if (toAccount && deal.getCustomerId() != null) {
            CustomerAccountEntry entry = new CustomerAccountEntry(
                    deal.getCustomerId(), AccountEntryType.DEAL_REFUND, dealReturn.getAmount());
            entry.setDealId(deal.getId());
            entry.setComment("Возврат " + dealReturn.getNumber());
            entry.setCreatedBy(managerId);
            accountRepository.save(entry);
            return;
        }

        Payment payment = new Payment(
                PaymentDirection.OUT, dealReturn.getAmount(), deal.getCustomerId());
        payment.setDealId(deal.getId());
        payment.setPaymentSourceId(paymentSourceId);
        payment.setComment("Возврат " + dealReturn.getNumber());
        payment.setCreatedBy(managerId);
        paymentRepository.save(payment);
    }

    private DealItem requireIssuedItem(Deal deal, Long itemId) {
        DealItem item = deal.getItems().stream()
                .filter(i -> itemId.equals(i.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Позиция %d не найдена в сделке %s".formatted(itemId, deal.getNumber())));

        // Проверяем здесь, а не при пересчёте статусов: иначе сумма возврата
        // будет посчитана по позиции, которую вернуть нельзя.
        if (item.getStatus() != DealItemStatus.ISSUED) {
            throw new IllegalStateException(
                    "Вернуть можно только выданное, а позиция %d в состоянии %s"
                            .formatted(itemId, item.getStatus()));
        }
        return item;
    }

    /**
     * Переносит позиции в новую сделку.
     *
     * <p>Клиент забирает половину сегодня, половину на неделе — и вторая
     * половина уезжает отдельным документом, чтобы первый можно было закрыть.
     * Событие пишется в обе сделки: иначе по истории не понять, куда делась
     * позиция.
     */
    @Transactional
    public Deal transferItems(Long sourceId, List<Long> itemIds, Long managerId) {
        Deal source = requireDeal(sourceId);
        Deal target = new Deal(source.getCustomerId(), managerId);
        target.setCreatedBy(managerId);
        dealRepository.saveAndFlush(target);

        List<DealItem> moved = source.transferTo(target, itemIds);
        // Резерв переезжает вместе с позициями: на складе он не снимался,
        // и новый документ обязан это отражать, иначе он останется черновиком,
        // который нечем выдать.
        if (source.getReservedUntil() != null) {
            target.inheritReservation(source.getReservedUntil());
        }
        dealRepository.saveAndFlush(source);
        Deal savedTarget = dealRepository.saveAndFlush(target);

        String parts = moved.stream().map(i -> String.valueOf(i.getPartId())).toList().toString();
        log(source, "ITEMS_MOVED_OUT",
                "Перенесено в новую сделку %s: %s".formatted(savedTarget.getNumber(), parts), managerId);
        log(savedTarget, "ITEMS_MOVED_IN",
                "Перенесено из сделки %s: %s".formatted(source.getNumber(), parts), managerId);
        return savedTarget;
    }

    /**
     * Оплата сделки.
     *
     * <p>Переплата не отбрасывается и не превращается в отрицательный долг:
     * она уходит на лицевой счёт клиента. На разборке это обычное дело —
     * округлили вверх, отдали лишнюю тысячу, забрали в следующий приезд.
     */
    @Transactional
    public Payment takePayment(Long dealId, BigDecimal amount, Long paymentSourceId, Long managerId) {
        Deal deal = requireDeal(dealId);

        Payment payment = new Payment(PaymentDirection.IN, amount, deal.getCustomerId());
        payment.setDealId(dealId);
        payment.setPaymentSourceId(paymentSourceId);
        payment.setCreatedBy(managerId);
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        BigDecimal debtBefore = deal.debt();
        BigDecimal appliedToDeal = amount.min(debtBefore);
        BigDecimal overpayment = amount.subtract(appliedToDeal);

        deal.registerPayment(appliedToDeal);
        dealRepository.saveAndFlush(deal);

        if (overpayment.signum() > 0 && deal.getCustomerId() != null) {
            CustomerAccountEntry entry = new CustomerAccountEntry(
                    deal.getCustomerId(), AccountEntryType.TOP_UP, overpayment);
            entry.setDealId(dealId);
            entry.setPaymentId(savedPayment.getId());
            entry.setComment("Переплата по сделке " + deal.getNumber());
            entry.setCreatedBy(managerId);
            accountRepository.save(entry);
        }

        log(deal, "PAYMENT",
                "Оплата %s ₽%s".formatted(amount.toPlainString(),
                        overpayment.signum() > 0
                                ? ", из них %s ₽ на лицевой счёт".formatted(overpayment.toPlainString())
                                : ""),
                managerId);
        return savedPayment;
    }

    /** Пополнение лицевого счёта без привязки к сделке. */
    @Transactional
    public CustomerAccountEntry topUpAccount(Long customerId, BigDecimal amount,
                                             Long paymentSourceId, Long managerId) {
        Payment payment = new Payment(PaymentDirection.IN, amount, customerId);
        payment.setPaymentSourceId(paymentSourceId);
        payment.setCreatedBy(managerId);
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        CustomerAccountEntry entry =
                new CustomerAccountEntry(customerId, AccountEntryType.TOP_UP, amount);
        entry.setPaymentId(savedPayment.getId());
        entry.setCreatedBy(managerId);
        return accountRepository.save(entry);
    }

    /** Сделки, у которых вышел срок резерва. Снимать резерв автоматически нельзя. */
    /** Сделка со всеми позициями. Для чтения из REST. */
    @Transactional(readOnly = true)
    public Deal require(Long dealId) {
        Deal deal = requireDeal(dealId);
        // Позиции подтягиваем внутри транзакции: снаружи ленивая коллекция
        // сериализуется в исключение, а не в JSON.
        deal.getItems().size();
        return deal;
    }

    /** История покупок клиента, свежие сверху. */
    @Transactional(readOnly = true)
    public List<Deal> ofCustomer(Long customerId) {
        List<Deal> deals = dealRepository.findByCustomerIdOrderByIdDesc(customerId);
        deals.forEach(deal -> deal.getItems().size());
        return deals;
    }

    @Transactional(readOnly = true)
    public List<Deal> expiredReservations() {
        List<Deal> deals = dealRepository.findExpiredReservations(Instant.now());
        deals.forEach(deal -> deal.getItems().size());
        return deals;
    }

    @Transactional(readOnly = true)
    public List<DocumentEvent> history(Long dealId) {
        return eventRepository.findByDocumentTypeAndDocumentIdOrderByIdAsc("DEAL", dealId);
    }

    private void log(Deal deal, String eventType, String message, Long authorId) {
        eventRepository.save(DocumentEvent.forDeal(deal.getId(), eventType, message, authorId));
    }

    private Deal requireDeal(Long dealId) {
        return dealRepository.findById(dealId)
                .orElseThrow(() -> new IllegalArgumentException("Сделка не найдена: " + dealId));
    }

    private Part requirePart(Long partId) {
        return partRepository.findById(partId)
                .orElseThrow(() -> new IllegalArgumentException("Запчасть не найдена: " + partId));
    }

    private byte[] payloadOf(Deal deal) {
        return """
                {"id":%d,"number":%d,"status":"%s","total":%s,"paid":%s}"""
                .formatted(deal.getId(), deal.getNumber(), deal.getStatus(),
                        deal.getTotalAmount(), deal.getPaidAmount())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Заявка на позицию: цена необязательна — по умолчанию берётся из карточки. */
    public record ItemRequest(Long partId, BigDecimal quantity, BigDecimal price, Long warehouseId) {
    }

    /**
     * Заявка на возврат позиции.
     *
     * <p>Количество и сумма необязательны: по умолчанию возвращают позицию
     * целиком и на ту сумму, которую клиент за неё заплатил. {@code restocked}
     * снимают для брака — деньги вернуть, в остаток не ставить.
     */
    public record ReturnRequest(Long dealItemId, BigDecimal quantity, BigDecimal amount,
                                boolean restocked) {

        public static ReturnRequest whole(Long dealItemId) {
            return new ReturnRequest(dealItemId, null, null, true);
        }

        public static ReturnRequest defective(Long dealItemId) {
            return new ReturnRequest(dealItemId, null, null, false);
        }
    }
}
