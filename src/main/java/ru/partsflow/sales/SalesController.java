package ru.partsflow.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * REST продаж.
 *
 * <p><b>Ключа идемпотентности здесь нет намеренно</b>, в отличие от приёмки.
 * Продают за столом при связи, а не из ангара через офлайн-очередь: повтор
 * запроса — это повтор нажатия, и второй сделки от него не появится, потому
 * что продавец видит первую. Появится офлайн-продажа — понадобится ключ,
 * и это будет отдельное решение, а не догадка заранее.
 *
 * <p><b>Автор операции берётся из сессии.</b> {@code managerId} в телах
 * отсутствует: то, что клиент про себя сообщил, для расчёта премий по продажам
 * не годится.
 *
 * <p>Роли: продаёт продавец, выдаёт ещё и кладовщик — товар со склада отдаёт
 * он. Смотреть может любой вошедший: цена и наличие нужны всем, включая
 * приёмщика, которого спрашивают «а сколько такая стоит».
 */
@RestController
@RequestMapping("/api/deals")
public class SalesController {

    /** Сколько держим резерв, если продавец не указал срок. */
    private static final Duration DEFAULT_RESERVATION = Duration.ofDays(3);

    private static final String SELLS = "hasAnyRole('OWNER','MANAGER','SELLER')";
    private static final String ISSUES = "hasAnyRole('OWNER','MANAGER','SELLER','STOREKEEPER')";

    private final SalesService sales;

    public SalesController(SalesService sales) {
        this.sales = sales;
    }

    /**
     * Создаёт сделку и сразу резервирует товар.
     *
     * <p>Черновика без резерва нет: продавец говорит с клиентом по телефону,
     * и деталь надо отложить в тот же момент, иначе её продаст сосед
     * за соседним столом.
     */
    @PostMapping
    @PreAuthorize(SELLS)
    public ResponseEntity<DealView> create(@Valid @RequestBody CreateRequest request) {
        Instant until = request.reservedUntil() != null
                ? request.reservedUntil()
                : Instant.now().plus(DEFAULT_RESERVATION);

        Deal deal = sales.createReserved(
                request.customerId(), CurrentUser.memberId(), until,
                request.items().stream()
                        .map(i -> new SalesService.ItemRequest(
                                i.partId(), i.quantity(), i.price(), i.warehouseId()))
                        .toList());

        return ResponseEntity.status(HttpStatus.CREATED).body(DealView.of(deal));
    }

    @GetMapping("/{id}")
    public DealView get(@PathVariable Long id) {
        return DealView.of(sales.require(id));
    }

    /** Сделки клиента — история покупок, свежие сверху. */
    @GetMapping
    public List<DealView> byCustomer(@RequestParam Long customerId) {
        return sales.ofCustomer(customerId).stream().map(DealView::of).toList();
    }

    /**
     * Просроченные резервы.
     *
     * <p>Экран для продавца, а не фоновая задача: снимать резерв автоматически
     * нельзя — «до завтра» на разборке часто значит «до послезавтра», и деталь,
     * освобождённая роботом ночью, уедет к другому клиенту.
     */
    @GetMapping("/expired-reservations")
    public List<DealView> expiredReservations() {
        return sales.expiredReservations().stream().map(DealView::of).toList();
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(ISSUES)
    public DealView issue(@PathVariable Long id) {
        return DealView.of(sales.issue(id, CurrentUser.memberId()));
    }

    /** Отмена возможна, пока товар не ушёл. После выдачи — только возврат. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(SELLS)
    public DealView cancel(@PathVariable Long id,
                           @RequestBody(required = false) CancelRequest request) {
        return DealView.of(sales.cancel(id, CurrentUser.memberId(),
                request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize(SELLS)
    public ResponseEntity<PaymentView> pay(@PathVariable Long id,
                                           @Valid @RequestBody PaymentRequest request) {
        Payment payment = sales.takePayment(
                id, request.amount(), request.paymentSourceId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentView.of(payment));
    }

    /**
     * Возврат выданного товара.
     *
     * <p>Отдельный документ со своим номером, а не отмена: деталь у клиента,
     * деньги в кассе, и оба факта надо отразить. Склад возврата не обязан
     * совпадать со складом выдачи.
     */
    @PostMapping("/{id}/returns")
    @PreAuthorize(SELLS)
    public ResponseEntity<ReturnView> registerReturn(@PathVariable Long id,
                                                     @Valid @RequestBody ReturnDocRequest request) {
        DealReturn dealReturn = sales.registerReturn(
                id, request.warehouseId(),
                request.items().stream()
                        .map(i -> new SalesService.ReturnRequest(
                                i.dealItemId(), i.quantity(), i.amount(), i.restocked()))
                        .toList(),
                request.reason(), request.refundToAccount(), request.paymentSourceId(),
                CurrentUser.memberId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ReturnView.of(dealReturn));
    }

    @GetMapping("/{id}/returns")
    public List<ReturnView> returns(@PathVariable Long id) {
        return sales.returnsOf(id).stream().map(ReturnView::of).toList();
    }

    /**
     * Отмена возврата.
     *
     * <p>Возможна, пока возврат не завершён: клиент передумал, деталь уехала
     * обратно с ним.
     */
    @PostMapping("/{dealId}/returns/{returnId}/cancel")
    @PreAuthorize(SELLS)
    public ReturnView cancelReturn(@PathVariable Long dealId, @PathVariable Long returnId) {
        return ReturnView.of(sales.cancelReturn(returnId, CurrentUser.memberId()));
    }

    /**
     * Переносит позиции в новую сделку.
     *
     * <p>Обычное дело: клиент забирает половину сейчас, остальное оставляет
     * на потом. Резерв при этом не снимается — товар просто меняет документ.
     */
    @PostMapping("/{id}/transfer")
    @PreAuthorize(SELLS)
    public ResponseEntity<DealView> transfer(@PathVariable Long id,
                                             @Valid @RequestBody TransferRequest request) {
        Deal created = sales.transferItems(id, request.itemIds(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DealView.of(created));
    }

    /** История документа: кто что сделал и когда. */
    @GetMapping("/{id}/history")
    public List<HistoryView> history(@PathVariable Long id) {
        return sales.history(id).stream().map(HistoryView::of).toList();
    }

    public record CreateRequest(@NotNull Long customerId,
                                /* Пусто — резерв на трое суток. */
                                Instant reservedUntil,
                                @NotEmpty List<ItemBody> items) {
    }

    /** Цена необязательна: по умолчанию берётся из карточки детали. */
    public record ItemBody(@NotNull Long partId,
                           @NotNull @Positive BigDecimal quantity,
                           BigDecimal price,
                           @NotNull Long warehouseId) {
    }

    public record CancelRequest(String reason) {
    }

    public record PaymentRequest(@NotNull @Positive BigDecimal amount, Long paymentSourceId) {
    }

    public record ReturnDocRequest(@NotNull Long warehouseId,
                                   @NotEmpty List<ReturnItemBody> items,
                                   String reason,
                                   boolean refundToAccount,
                                   Long paymentSourceId) {
    }

    /**
     * @param restocked снимают для брака: деньги клиенту вернуть,
     *                  в остаток не ставить
     */
    public record ReturnItemBody(@NotNull Long dealItemId,
                                 BigDecimal quantity,
                                 BigDecimal amount,
                                 boolean restocked) {
    }

    public record TransferRequest(@NotEmpty List<Long> itemIds) {
    }

    public record DealView(Long id, Long number, Long customerId, Long managerId,
                           DealStatus status, Instant reservedUntil,
                           BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal debt,
                           Instant createdAt, Instant issuedAt, List<ItemView> items) {

        static DealView of(Deal deal) {
            return new DealView(deal.getId(), deal.getNumber(), deal.getCustomerId(),
                    deal.getManagerId(), deal.getStatus(), deal.getReservedUntil(),
                    deal.getTotalAmount(), deal.getPaidAmount(), deal.debt(),
                    deal.getCreatedAt(), deal.getIssuedAt(),
                    deal.getItems().stream().map(ItemView::of).toList());
        }
    }

    public record ItemView(Long id, Long partId, BigDecimal quantity, BigDecimal price,
                           BigDecimal discount, Long warehouseId, DealItemStatus status) {

        static ItemView of(DealItem item) {
            return new ItemView(item.getId(), item.getPartId(), item.getQuantity(),
                    item.getPrice(), item.getDiscount(), item.getWarehouseId(), item.getStatus());
        }
    }

    public record ReturnView(Long id, Long number, Long dealId, Long warehouseId,
                             ReturnStatus status, BigDecimal amount, String reason,
                             Instant createdAt) {

        static ReturnView of(DealReturn dealReturn) {
            return new ReturnView(dealReturn.getId(), dealReturn.getNumber(),
                    dealReturn.getDealId(), dealReturn.getWarehouseId(), dealReturn.getStatus(),
                    dealReturn.getAmount(), dealReturn.getReason(), dealReturn.getCreatedAt());
        }
    }

    public record PaymentView(Long id, Long dealId, BigDecimal amount,
                              PaymentDirection direction, Instant paidAt) {

        static PaymentView of(Payment payment) {
            return new PaymentView(payment.getId(), payment.getDealId(), payment.getAmount(),
                    payment.getDirection(), payment.getPaidAt());
        }
    }

    public record HistoryView(String eventType, String message, Long authorId, Instant createdAt) {

        static HistoryView of(DocumentEvent event) {
            return new HistoryView(event.getEventType(), event.getMessage(),
                    event.getAuthorId(), event.getCreatedAt());
        }
    }
}
