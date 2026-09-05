package ru.partsflow.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import ru.partsflow.inventory.PartService;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private final PartService parts;
    private final ru.partsflow.platform.security.MemberService members;

    public SalesController(SalesService sales, PartService parts,
                           ru.partsflow.platform.security.MemberService members) {
        this.sales = sales;
        this.parts = parts;
        this.members = members;
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
                request.customerId(), CurrentUser.memberId(), until, request.dealSourceId(),
                request.items().stream()
                        .map(i -> new SalesService.ItemRequest(
                                i.partId(), i.quantity(), i.price(), i.warehouseId()))
                        .toList(),
                servicesOf(request.services()));

        return ResponseEntity.status(HttpStatus.CREATED).body(view(deal));
    }

    /**
     * Принимает заказ, оформленный покупателем на площадке.
     *
     * <p>Пока заводится руками: продавец видит заказ в кабинете Дрома
     * и переносит его сюда. Ключ к API защищённых сделок Дром выдаёт
     * по запросу, и до него автоматический приём написать не на чем —
     * но модель от способа доставки заказа не зависит, и когда ключ появится,
     * поменяется только то, кто зовёт этот метод.
     *
     * <p>Повтор по тому же номеру заказа возвращает прежнюю сделку и код 200
     * вместо 201: продавец мог завести заказ дважды, а вторая сделка на тот же
     * товар — это одна деталь, обещанная двум покупателям.
     */
    @PostMapping("/orders")
    @PreAuthorize(SELLS)
    public ResponseEntity<OrderView> receiveOrder(@Valid @RequestBody OrderRequest request) {
        SalesService.AcceptedOrder accepted;
        try {
            accepted = sales.registerMarketplaceOrder(
                    request.marketplace(), request.orderNo(), request.replyDeadline(),
                    request.customerId(), CurrentUser.memberId(), request.dealSourceId(),
                    request.deliveryNote(), request.reservedUntil(),
                    request.items().stream()
                            .map(i -> new SalesService.ItemRequest(
                                    i.partId(), i.quantity(), i.price(), i.warehouseId()))
                            .toList(),
                    servicesOf(request.services()));
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // Одновременный повтор: первый запрос успел завести сделку, второй
            // упёрся в deal_external_order_uk. Заказ при этом принят и товар
            // отложен — отвечать «нарушает целостность данных» продавцу,
            // нажавшему «Принять заказ» второй раз, значит послать его искать
            // поломку сервера. Та же половина защиты, что была у приёмки.
            SalesService.AcceptedOrder done = sales.replayOrderAfterConflict(
                    request.marketplace(), request.orderNo() == null
                            ? null : request.orderNo().strip());
            if (done == null) {
                throw conflict;
            }
            accepted = done;
        }

        OrderView body = new OrderView(view(accepted.deal()), accepted.replayed(),
                accepted.missing());
        return ResponseEntity.status(accepted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(body);
    }

    /**
     * Заказы площадок, по которым ещё не ответили.
     *
     * <p>Отдельный список, а не отметка в общем: у Дрома по защищённой сделке
     * трое рабочих суток, после чего деньги возвращаются покупателю, — заказ,
     * потерявшийся среди сотни сделок, это потерянные деньги и рейтинг
     * у площадки.
     */
    @GetMapping("/orders/awaiting-reply")
    @PreAuthorize(SELLS)
    public List<DealView> ordersAwaitingReply() {
        return views(sales.ordersAwaitingReply());
    }

    /** Подтверждает заказ площадке: убирает его из очереди «ждут ответа». */
    @PostMapping("/orders/{id}/accept")
    @PreAuthorize(SELLS)
    public DealView acceptOrder(@PathVariable Long id) {
        return view(sales.acceptOrder(id, CurrentUser.memberId()));
    }

    @GetMapping("/{id}")
    public DealView get(@PathVariable Long id) {
        return view(sales.require(id));
    }

    /** Сделки клиента — история покупок, свежие сверху. */
    @GetMapping
    public List<DealView> byCustomer(@RequestParam Long customerId) {
        return views(sales.ofCustomer(customerId));
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
        return views(sales.expiredReservations());
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(ISSUES)
    public DealView issue(@PathVariable Long id) {
        return view(sales.issue(id, CurrentUser.memberId()));
    }

    /** Отмена возможна, пока товар не ушёл. После выдачи — только возврат. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(SELLS)
    public DealView cancel(@PathVariable Long id,
                           @RequestBody(required = false) CancelRequest request) {
        return view(sales.cancel(id, CurrentUser.memberId(),
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
     * Зачёт с лицевого счёта клиента.
     *
     * <p>Отдельным путём, а не флагом в обычной оплате: платежа в кассу здесь
     * не создаётся — деньги получены раньше, тогда же записан приход.
     * Второй платёж на зачёте задвоил бы выручку.
     */
    @PostMapping("/{id}/payments/from-account")
    @PreAuthorize(SELLS)
    public DealView payFromAccount(@PathVariable Long id,
                                   @Valid @RequestBody FromAccountRequest request) {
        return view(sales.payFromAccount(id, request.amount(), CurrentUser.memberId()));
    }

    public record FromAccountRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Positive java.math.BigDecimal amount) {
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
        return ResponseEntity.status(HttpStatus.CREATED).body(view(created));
    }

    /** История документа: кто что сделал и когда. */
    /**
     * История документа: кто и что сделал со сделкой.
     *
     * <p>Имена сотрудников идут одним запросом на всю выдачу — как
     * и наименования в строках. Без имени строка читается «автор 3»,
     * а разбирают историю через недели, когда по номеру никто никого
     * не вспомнит.
     */
    /**
     * Ссылка на сделку для клиента: продавец отправляет её сам.
     *
     * <p>Своего отправителя SMS или Telegram нет намеренно — это договор
     * с провайдером и деньги, а интерфейс, написанный раньше договора,
     * повторит форму воображаемого провайдера. Ссылка работает в любом канале
     * и не требует ничего.
     */
    @PostMapping("/{id}/share")
    @PreAuthorize(SELLS)
    public ShareView share(@PathVariable Long id) {
        Deal deal = sales.share(id);
        // Код компании в адресе: он говорит, у какого арендатора искать,
        // а доступ открывает токен. Сам по себе код не даёт ничего —
        // то же устройство, что у ссылки на прайс площадки.
        return new ShareView("/s/%s/%s".formatted(sales.companyCode(), deal.getShareToken()),
                deal.getShareExpires());
    }

    public record ShareView(String path, Instant expires) {
    }

    @GetMapping("/{id}/history")
    public List<HistoryView> history(@PathVariable Long id) {
        List<DocumentEvent> events = sales.history(id);
        Map<Long, String> names = members.namesOf(
                events.stream().map(DocumentEvent::getAuthorId).toList());
        return events.stream().map(event -> HistoryView.of(event, names)).toList();
    }

    /**
     * Один запрос наименований на всю выдачу, а не на позицию.
     *
     * <p>История клиента — это десятки сделок; запрос на строку превратил бы
     * её открытие в сотню запросов к базе.
     */
    private static List<SalesService.ServiceRequest> servicesOf(List<ServiceBody> bodies) {
        return bodies == null ? List.of() : bodies.stream()
                .map(b -> new SalesService.ServiceRequest(b.serviceId(), b.quantity(), b.price()))
                .toList();
    }

    /**
     * Справочник источников: откуда пришла продажа.
     *
     * <p>Отвечает на вопрос владельца «окупается ли размещение на Дроме»,
     * и потому заполняться должен при каждой продаже, а не только у заказов
     * с площадки.
     */
    @GetMapping("/sources")
    public List<SourceView> sources() {
        return sales.dealSources().stream()
                .map(s -> new SourceView(s.getId(), s.getName()))
                .toList();
    }

    public record SourceView(Long id, String name) {
    }

    /** Справочник услуг для экрана продавца. */
    @GetMapping("/services")
    public List<ServiceView> services() {
        return sales.serviceKinds().stream()
                .map(k -> new ServiceView(k.getId(), k.getName(), k.getPrice()))
                .toList();
    }

    public record ServiceView(Long id, String name, BigDecimal price) {
    }

    /**
     * Строка услуги в сделке: доставка, упаковка. Склад не двигает.
     *
     * @param name название услуги. Без него экран продавца показать её
     *             не может, а не показать нельзя: сумма строк тогда
     *             не сходится с итогом документа — «итого 7 500» под
     *             деталями на 7 000, — и спор об этом начинается
     *             в момент оплаты. Та же причина, по которой наименование
     *             несёт и строка запчасти
     */
    public record ServiceLineView(Long id, Long serviceId, String name,
                                  BigDecimal quantity, BigDecimal price) {
    }

    private List<DealView> views(List<Deal> deals) {
        Map<Long, String> titles = parts.titlesOf(deals.stream()
                .flatMap(deal -> deal.getItems().stream())
                .map(DealItem::getPartId)
                .distinct()
                .toList());
        // Справочник услуг — это несколько строк на арендатора, и читается он
        // одним запросом на всю выдачу: по запросу на строку история клиента
        // превратилась бы в сотню обращений к базе.
        Map<Long, String> serviceNames = sales.serviceKinds().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ru.partsflow.sales.ServiceKind::getId,
                        ru.partsflow.sales.ServiceKind::getName));
        return deals.stream().map(deal -> DealView.of(deal, titles, serviceNames)).toList();
    }

    private DealView view(Deal deal) {
        return views(List.of(deal)).getFirst();
    }

    public record CreateRequest(@NotNull Long customerId,
                                /* Откуда пришла продажа: строка справочника источников. */
                                Long dealSourceId,
                                /* Пусто — резерв на трое суток. */
                                Instant reservedUntil,
                                @NotEmpty List<ItemBody> items,
                                /* Доставка и упаковка: деньги сделки, а не примечание. */
                                List<ServiceBody> services) {
    }

    /** @param price пусто — берётся подсказка из справочника, а не ноль */
    public record ServiceBody(@NotNull Long serviceId,
                              BigDecimal quantity,
                              BigDecimal price) {
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

    /**
     * @param marketplace    {@code DROM} или {@code AVITO}
     * @param orderNo        номер заказа у площадки — тот, который называет покупатель
     * @param replyDeadline  до какого момента площадка ждёт ответа; у Дрома
     *                       это трое рабочих суток, потом деньги вернутся
     * @param dealSourceId   строка справочника источников для отчётов; площадка
     *                       и источник — разные вещи: первый машинный код,
     *                       второй владелец переименовывает как хочет
     */
    public record OrderRequest(@NotBlank String marketplace,
                               @NotBlank String orderNo,
                               Instant replyDeadline,
                               Long customerId,
                               Long dealSourceId,
                               String deliveryNote,
                               Instant reservedUntil,
                               @NotEmpty List<ItemBody> items,
                               List<ServiceBody> services) {
    }

    /**
     * @param replayed заказ с таким номером уже был заведён — вернулась прежняя сделка
     * @param missing  чего не хватило на складе; непусто — сделка осталась
     *                 черновиком, товар не зарезервирован, и заказ,
     *                 скорее всего, придётся отклонить
     */
    public record OrderView(DealView deal, boolean replayed, List<String> missing) {
    }

    /**
     * @param marketplace   площадка, если сделка пришла заказом; иначе пусто
     * @param externalOrderNo номер заказа у площадки — его называет покупатель
     * @param replyDeadline срок ответа площадке; у Дрома пропущенный означает
     *                      возврат денег покупателю
     * @param warehouseId   склад выдачи сделки. Экран возврата подставляет
     *                      его складом возврата: угаданный первым попавшимся
     *                      ставит деталь на чужую полку, а искать её будут
     *                      по прежнему адресу. Пусто — колонку никто
     *                      не заполняет, и откуда ушёл товар, знают позиции
     */
    public record DealView(Long id, Long number, Long customerId, Long managerId,
                           DealStatus status, Instant reservedUntil,
                           BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal debt,
                           Instant createdAt, Instant issuedAt,
                           Long dealSourceId, Long warehouseId,
                           String marketplace, String externalOrderNo,
                           Instant replyDeadline, Instant orderAcceptedAt,
                           String deliveryNote, List<ItemView> items,
                           List<ServiceLineView> services) {

        static DealView of(Deal deal, Map<Long, String> titles,
                           Map<Long, String> serviceNames) {
            return new DealView(deal.getId(), deal.getNumber(), deal.getCustomerId(),
                    deal.getManagerId(), deal.getStatus(), deal.getReservedUntil(),
                    deal.getTotalAmount(), deal.getPaidAmount(), deal.debt(),
                    deal.getCreatedAt(), deal.getIssuedAt(),
                    deal.getDealSourceId(), deal.getWarehouseId(),
                    deal.getMarketplace(), deal.getExternalOrderNo(),
                    deal.getReplyDeadline(), deal.getOrderAcceptedAt(),
                    deal.getDeliveryNote(),
                    deal.getItems().stream().map(item -> ItemView.of(item, titles)).toList(),
                    deal.getServices().stream()
                            .map(s -> new ServiceLineView(s.getId(), s.getServiceId(),
                                    serviceNames.get(s.getServiceId()),
                                    s.getQuantity(), s.getPrice()))
                            .toList());
        }
    }

    /**
     * @param title наименование запчасти. Без него строка сделки — это номер,
     *              а выбирать по номеру, что вернуть, продавец не станет
     */
    public record ItemView(Long id, Long partId, String title, BigDecimal quantity,
                           BigDecimal price, BigDecimal discount, Long warehouseId,
                           DealItemStatus status) {

        static ItemView of(DealItem item, Map<Long, String> titles) {
            return new ItemView(item.getId(), item.getPartId(),
                    titles.get(item.getPartId()), item.getQuantity(),
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

    /**
     * @param authorName пусто — действие сделала система (робот повторной
     *                   доставки, накат) либо сотрудника удалили. «Автор 3»
     *                   вместо этого не говорит ничего
     */
    public record HistoryView(String eventType, String message, Long authorId,
                              String authorName, Instant createdAt) {

        static HistoryView of(DocumentEvent event, Map<Long, String> names) {
            return new HistoryView(event.getEventType(), event.getMessage(),
                    event.getAuthorId(),
                    event.getAuthorId() == null ? null : names.get(event.getAuthorId()),
                    event.getCreatedAt());
        }
    }
}
