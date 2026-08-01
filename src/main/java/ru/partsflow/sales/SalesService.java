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
import ru.partsflow.platform.outbox.EventPayloads;
import ru.partsflow.platform.outbox.contract.DealEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private final ServiceKindRepository serviceKinds;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final DealSourceRepository dealSources;

    public SalesService(DealRepository dealRepository,
                        DealReturnRepository dealReturnRepository,
                        PaymentRepository paymentRepository,
                        CustomerAccountEntryRepository accountRepository,
                        DocumentEventRepository eventRepository,
                        PartRepository partRepository,
                        StockMovementRepository movementRepository,
                        StockReservationRepository reservationRepository,
                        DomainEventPublisher eventPublisher,
                        ServiceKindRepository serviceKinds,
                        DealSourceRepository dealSources,
                        org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.dealRepository = dealRepository;
        this.dealReturnRepository = dealReturnRepository;
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.eventRepository = eventRepository;
        this.partRepository = partRepository;
        this.movementRepository = movementRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.serviceKinds = serviceKinds;
        this.dealSources = dealSources;
        this.jdbc = jdbc;
    }

    /**
     * Услуги, которые можно добавить в сделку.
     *
     * <p>Архивные не отдаются: справочник у клиента живёт годами, и услуга,
     * которую перестали оказывать, не должна предлагаться продавцу — но
     * и удалять её нельзя, она стоит в прошлых сделках.
     */
    /**
     * Выдаёт ссылку на сделку для клиента.
     *
     * <p>Повторный вызов возвращает прежнюю, пока она не просрочена: продавец
     * нажимает «ссылка» второй раз, потому что потерял её в переписке,
     * а не потому, что хочет отозвать прежнюю. Новая ссылка при каждом нажатии
     * оставила бы у клиента мёртвый адрес.
     *
     * <p>Срок — две недели: столько живёт разговор про отложенную деталь.
     * Просроченная ссылка перестаёт показывать склад тому, кто её однажды
     * получил, — а получить её мог кто угодно, кому клиент переслал переписку.
     */
    @Transactional
    public Deal share(Long dealId) {
        Deal deal = requireDeal(dealId);
        if (deal.getShareToken() == null
                || deal.getShareExpires() == null
                || deal.getShareExpires().isBefore(Instant.now())) {
            byte[] bytes = new byte[24];
            new java.security.SecureRandom().nextBytes(bytes);
            deal.share(java.util.HexFormat.of().formatHex(bytes),
                    Instant.now().plus(java.time.Duration.ofDays(14)));
        }
        return detachable(dealRepository.saveAndFlush(deal));
    }

    /**
     * Сделка по ссылке — то, что видит клиент.
     *
     * <p>Сравнение постоянного времени: по времени ответа ссылка подбирается
     * посимвольно, как и токен прайса площадки.
     */
    @Transactional(readOnly = true)
    public Optional<Deal> byShareToken(String token) {
        if (token == null || token.length() < 32) {
            return Optional.empty();
        }
        byte[] presented = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Deal found = null;
        for (Deal candidate : dealRepository.findShared(Instant.now())) {
            if (java.security.MessageDigest.isEqual(
                    candidate.getShareToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    presented)) {
                found = candidate;
            }
        }
        return Optional.ofNullable(found).map(this::detachable);
    }

    /**
     * Код компании арендатора — часть публичной ссылки на сделку.
     *
     * <p>Схема указана явно: реестр живёт в {@code public}, а не в схеме
     * арендатора, и правило про транзакцию сюда не относится.
     */
    @Transactional(readOnly = true)
    public String companyCode() {
        return jdbc.queryForObject(
                "SELECT code FROM public.tenant_registry WHERE schema_name = ?",
                String.class, ru.partsflow.platform.tenant.TenantContext.require());
    }

    /** Источники сделок: откуда пришла продажа. Архивные не предлагаются. */
    @Transactional(readOnly = true)
    public List<DealSource> dealSources() {
        return dealSources.findByArchivedFalseOrderByName();
    }

    @Transactional(readOnly = true)
    public List<ServiceKind> serviceKinds() {
        return serviceKinds.findByArchivedFalseOrderByName();
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
                               Long dealSourceId, List<ItemRequest> items,
                               List<ServiceRequest> services) {

        Deal deal = new Deal(customerId, managerId);
        deal.setCreatedBy(managerId);
        // Откуда пришла продажа. Заполняется при каждой сделке, а не только
        // у заказа с площадки: отчёт по каналам, в котором половина выручки
        // без источника, не отвечает ни на один вопрос.
        deal.setDealSourceId(dealSourceId);

        for (ItemRequest item : items) {
            Part part = requirePart(item.partId());
            deal.addItem(part.getId(), item.quantity(),
                    item.price() != null ? item.price() : part.getPrice(), item.warehouseId());

            // Резерв на складе ставится здесь же, в той же транзакции.
            // Отложить его «на потом» значит открыть окно, в котором ту же
            // деталь положит в свою сделку другой продавец.
            reservationRepository.reserve(part.getId(), item.warehouseId(), item.quantity());
        }
        addServices(deal, services);
        deal.reserve(reservedUntil);

        Deal saved = detachable(dealRepository.saveAndFlush(deal));
        log(saved, "CREATED", "Сделка создана и зарезервирована. Позиций: "
                + saved.getItems().size(), managerId);
        return saved;
    }

    /**
     * Услуги в сделку — по цене из строки, а не из справочника.
     *
     * <p>Справочная цена только подставляется по умолчанию: доставка до Надыма
     * и до соседней улицы стоит по-разному, и тариф был бы враньём. Пустая
     * цена в запросе означает «взять подсказку», а не «бесплатно».
     */
    private void addServices(Deal deal, List<ServiceRequest> services) {
        if (services == null) {
            return;
        }
        for (ServiceRequest service : services) {
            ServiceKind kind = serviceKinds.findById(service.serviceId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Услуга не найдена: " + service.serviceId()));
            BigDecimal price = service.price() != null ? service.price() : kind.getPrice();
            // Ни в запросе, ни в справочнике цены нет — значит её никто
            // не называл. Ноль здесь превратился бы в строку «Доставка 0 ₽»
            // в документе клиента, то есть в утверждение, которого не делали:
            // пустое поле означает «услуги не было», а не «оказали бесплатно».
            if (price == null) {
                throw new IllegalArgumentException(
                        "Цена услуги «%s» не указана и в справочнике её нет: доставка до Надыма "
                                .formatted(kind.getName())
                                + "и до соседней улицы стоит по-разному");
            }
            deal.addService(kind.getId(), service.quantity(), price);
        }
    }

    /** @param price пусто — берётся подсказка из справочника, а не ноль */
    public record ServiceRequest(Long serviceId, BigDecimal quantity, BigDecimal price) {
    }

    /**
     * Принимает заказ, оформленный покупателем на площадке.
     *
     * <p><b>Заказ уже существует, когда мы о нём узнаём.</b> Покупатель
     * на Дроме нажал «купить» и заплатил, деньги held площадкой, а мы узнаём
     * последними. Поэтому метод не отказывает: он записывает заказ при любом
     * состоянии склада, — отказ означал бы заказ, о котором знает площадка
     * и не знает разборка.
     *
     * <p><b>Товар резервируется сразу, в этой же транзакции.</b> Покупатель
     * заплатил, и незарезервированную деталь через час продадут с прилавка —
     * а вернуть придётся деньги и репутацию у площадки.
     *
     * <p><b>Или не резервируется вовсе.</b> Не хватило хоть одной позиции —
     * не резервируем ничего, сделка остаётся черновиком и попадает продавцу
     * в «ждут ответа» с пометкой. Частичный резерв тут хуже никакого: заказ
     * всё равно придётся отклонить целиком — покупатель платил за всё, —
     * а до отклонения он будет держать товар, который можно продать.
     *
     * <p><b>Повтор безопасен.</b> Тот же номер заказа возвращает ту же сделку,
     * а не заводит вторую: второй резерв на тот же товар — это одна деталь,
     * обещанная двум покупателям. Уникальность стережёт индекс в БД, проверка
     * здесь лишь избавляет от исключения на обычном повторе. Это понадобится
     * и сейчас (продавец завёл заказ дважды), и позже, когда заказы поедут
     * из API площадки: доставка там будет at-least-once, как и всюду.
     *
     * @param replyDeadline до какого момента площадка ждёт ответа; у Дрома
     *                      по защищённой сделке это трое рабочих суток,
     *                      после чего деньги возвращаются покупателю
     */
    @Transactional
    public AcceptedOrder registerMarketplaceOrder(String marketplace, String orderNo,
                                                  Instant replyDeadline, Long customerId,
                                                  Long managerId, Long dealSourceId,
                                                  String deliveryNote, Instant reservedUntil,
                                                  List<ItemRequest> items,
                                                  List<ServiceRequest> services) {
        if (marketplace == null || orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("Не указана площадка или номер заказа");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("В заказе нет позиций");
        }

        Deal existing = dealRepository.findByMarketplaceAndExternalOrderNo(marketplace, orderNo)
                .orElse(null);
        if (existing != null) {
            return new AcceptedOrder(detachable(existing), true, List.of());
        }

        Deal deal = new Deal(customerId, managerId);
        deal.setCreatedBy(managerId);
        deal.setDealSourceId(dealSourceId);
        deal.setDeliveryNote(deliveryNote);
        deal.fromMarketplace(marketplace, orderNo.strip(),
                replyDeadline != null ? replyDeadline : defaultReplyDeadline());

        for (ItemRequest item : items) {
            Part part = requirePart(item.partId());
            deal.addItem(part.getId(), item.quantity(),
                    item.price() != null ? item.price() : part.getPrice(), item.warehouseId());
        }
        // Доставка входит в сумму документа: площадка переводит деньги
        // за деталь вместе с ней, и сойтись перевод должен с суммой сделки.
        addServices(deal, services);

        // Сначала смотрим, хватает ли всего, и только потом резервируем:
        // резерв половины заказа держал бы товар ради сделки, которую всё
        // равно придётся отклонить.
        List<String> missing = shortagesOf(items);
        if (missing.isEmpty()) {
            for (ItemRequest item : items) {
                reservationRepository.reserve(item.partId(), item.warehouseId(), item.quantity());
            }
            deal.reserve(reservedUntil != null ? reservedUntil : defaultReserveUntil(replyDeadline));
        }

        Deal saved = detachable(dealRepository.saveAndFlush(deal));
        log(saved, "ORDER_RECEIVED", missing.isEmpty()
                        ? "Заказ %s №%s принят и зарезервирован".formatted(marketplace, orderNo)
                        : "Заказ %s №%s принят, но обеспечить нечем: %s"
                                .formatted(marketplace, orderNo, String.join("; ", missing)),
                managerId);
        return new AcceptedOrder(saved, false, missing);
    }

    /**
     * Отмечает заказ подтверждённым площадке.
     *
     * <p>Склад это не двигает — товар зарезервирован с момента приёма. Отметка
     * убирает заказ из очереди «ждут ответа»: пока её нет, продавец видит
     * заказ в списке, а по истечении срока площадка вернёт деньги покупателю.
     */
    @Transactional
    public Deal acceptOrder(Long dealId, Long managerId) {
        Deal deal = requireDeal(dealId);
        deal.getItems().size();
        boolean wasAwaiting = deal.isAwaitingReply();
        deal.acceptOrder();

        Deal saved = detachable(dealRepository.saveAndFlush(deal));
        if (wasAwaiting) {
            log(saved, "ORDER_ACCEPTED", "Заказ подтверждён площадке", managerId);
        }
        return saved;
    }

    /**
     * Заказы площадок, по которым продавец ещё не ответил.
     *
     * <p>Сортировка по сроку ответа, а не по дате заказа: пропущенный срок
     * у Дрома означает возврат денег покупателю, и заказ, до которого осталось
     * два часа, важнее вчерашнего, у которого их сутки.
     */
    @Transactional(readOnly = true)
    public List<Deal> ordersAwaitingReply() {
        return withItems(dealRepository.findAwaitingReply());
    }

    /**
     * Чего не хватает под заказ.
     *
     * <p>Считается по свободному остатку, а не по наличию: деталь, отложенная
     * другому покупателю, для этого заказа всё равно что продана.
     */
    private List<String> shortagesOf(List<ItemRequest> items) {
        List<String> missing = new ArrayList<>();
        for (ItemRequest item : items) {
            BigDecimal available = reservationRepository.availableQuantity(
                    item.partId(), item.warehouseId());
            if (available.compareTo(item.quantity()) < 0) {
                Part part = requirePart(item.partId());
                missing.add("%s — нужно %s, свободно %s".formatted(
                        part.getTitle(), item.quantity().stripTrailingZeros().toPlainString(),
                        available.stripTrailingZeros().toPlainString()));
            }
        }
        return missing;
    }

    /**
     * Срок ответа по умолчанию: трое рабочих суток.
     *
     * <p>Так у Дрома по защищённой сделке: не ответили — деньги вернулись
     * покупателю. Пока заказ заводят руками, срок никто не вводит, а без него
     * очередь «ждут ответа» сортировать нечем и каждая карточка пишет «срок
     * не указан» — то есть очередь, заведённая ради срока, его и не знает.
     *
     * <p>Выходные пропускаются, праздники — нет. Производственный календарь
     * пришлось бы откуда-то брать и обновлять каждый год ради того, чтобы
     * подсказка сдвинулась на день; ошибка в эту сторону безопасна —
     * продавца поторопят раньше, чем нужно, а не позже.
     */
    private Instant defaultReplyDeadline() {
        java.time.ZonedDateTime at = Instant.now().atZone(java.time.ZoneOffset.UTC);
        int left = 3;
        while (left > 0) {
            at = at.plusDays(1);
            java.time.DayOfWeek day = at.getDayOfWeek();
            if (day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return at.toInstant();
    }

    /**
     * Срок резерва по умолчанию — срок ответа площадке.
     *
     * <p>Держать дольше нечего: не ответили вовремя — заказа больше нет,
     * а товар остался бы заблокированным неизвестно до какого числа.
     */
    private Instant defaultReserveUntil(Instant replyDeadline) {
        return replyDeadline != null && replyDeadline.isAfter(Instant.now())
                ? replyDeadline
                : Instant.now().plus(java.time.Duration.ofDays(3));
    }

    /**
     * @param replayed заказ уже был заведён раньше — вернулась прежняя сделка
     * @param missing  чего не хватило на складе; пусто — заказ обеспечен
     */
    public record AcceptedOrder(Deal deal, boolean replayed, List<String> missing) {
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
        Deal saved = detachable(dealRepository.saveAndFlush(deal));

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

        Deal saved = detachable(dealRepository.saveAndFlush(deal));
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
        Deal savedTarget = detachable(dealRepository.saveAndFlush(target));

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
        return detachable(requireDeal(dealId));
    }

    /** История покупок клиента, свежие сверху. */
    @Transactional(readOnly = true)
    public List<Deal> ofCustomer(Long customerId) {
        return withItems(dealRepository.findByCustomerIdOrderByIdDesc(customerId));
    }

    /**
     * Подтягивает ленивые коллекции сделки, пока транзакция ещё открыта.
     *
     * <p>{@code open-in-view} выключен намеренно, а позиции и услуги ленивые:
     * контроллер, добравшийся до них после коммита, получает
     * {@code LazyInitializationException}, а клиент — пятисотку.
     *
     * <p><b>Обе коллекции здесь, а не по одной на месте.</b> Первый раз это
     * поймал живой прогон — на двух путях подряд; второй раз, когда появились
     * услуги, легли шестнадцать тестов сразу, потому что подтягивались только
     * позиции. Единственное место на все выходы наружу — чтобы третьего раза
     * не было: новая коллекция добавляется здесь, а не в каждом методе.
     *
     * <p>Не {@code JOIN FETCH} в запросе: он у каждого метода свой, и две
     * коллекции в одном запросе дают декартово произведение строк.
     */
    private Deal detachable(Deal deal) {
        deal.getItems().size();
        deal.getServices().size();
        return deal;
    }

    private List<Deal> withItems(List<Deal> deals) {
        deals.forEach(this::detachable);
        return deals;
    }

    @Transactional(readOnly = true)
    public List<Deal> expiredReservations() {
        return withItems(dealRepository.findExpiredReservations(Instant.now()));
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
        return EventPayloads.write(new DealEvent(deal.getId(), deal.getNumber(),
                String.valueOf(deal.getStatus()), deal.getTotalAmount(),
                deal.getPaidAmount()));
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
