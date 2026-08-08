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
    private final ru.partsflow.inventory.StockLedger ledger;
    private final StockReservationRepository reservationRepository;
    private final DomainEventPublisher eventPublisher;
    private final ServiceKindRepository serviceKinds;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final DealSourceRepository dealSources;
    private final ru.partsflow.inventory.PartChangeLog partChanges;

    public SalesService(DealRepository dealRepository,
                        DealReturnRepository dealReturnRepository,
                        PaymentRepository paymentRepository,
                        CustomerAccountEntryRepository accountRepository,
                        DocumentEventRepository eventRepository,
                        PartRepository partRepository,
                        ru.partsflow.inventory.StockLedger ledger,
                        StockReservationRepository reservationRepository,
                        DomainEventPublisher eventPublisher,
                        ServiceKindRepository serviceKinds,
                        DealSourceRepository dealSources,
                        ru.partsflow.inventory.PartChangeLog partChanges,
                        org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.dealRepository = dealRepository;
        this.dealReturnRepository = dealReturnRepository;
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.eventRepository = eventRepository;
        this.partRepository = partRepository;
        this.ledger = ledger;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.serviceKinds = serviceKinds;
        this.dealSources = dealSources;
        this.partChanges = partChanges;
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

        requireCustomer(customerId);
        requireDealSource(dealSourceId);

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
        } else {
            // Позиция обязана говорить правду о том, отложил ли под неё склад.
            // Умолчание «зарезервирована» верно для обычной продажи, где резерв
            // ставится тут же; здесь оно превращало отмену заказа в попытку
            // снять несуществующий резерв, то есть в 409 на единственное
            // действие, которое с необеспеченным заказом можно сделать.
            deal.markUnreserved();
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

            ledger.record(StockMovement.sale(
                    item.getPartId(), item.getQuantity(), item.getWarehouseId(), dealId));
            // Проданная позиция обязана уехать на площадку недоступной, и чем
            // раньше, тем меньше звонков «а она у вас есть».
            partChanges.changed(item.getPartId());
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
        refundOnCancel(deal, managerId);
        deal.cancel(Instant.now());

        Deal saved = detachable(dealRepository.saveAndFlush(deal));
        log(saved, "CANCELLED",
                reason == null || reason.isBlank() ? "Сделка отменена" : "Сделка отменена: " + reason,
                managerId);
        eventPublisher.publish(DomainEvent.of("deal", dealId, "deal.cancelled.v1", payloadOf(saved)));
        return saved;
    }

    /**
     * Отмена оплаченной сделки возвращает деньги на лицевой счёт клиента.
     *
     * <p><b>Пока этого не было, деньги пропадали.</b> Клиент оставил аванс,
     * продавец зачёл его в отложенную сделку, клиент передумал — сделка
     * отменена, товар на складе, а полторы тысячи не числятся ни за сделкой
     * (она закрыта), ни на счёте (их оттуда списали). Ровно из таких
     * расхождений и растёт нужда в ручной правке остатка: правка лечит
     * симптом, а деньги теряются дальше.
     *
     * <p><b>На счёт, а не наличными.</b> Отмена — это решение продавца
     * в системе, а не открытая касса: клиент может стоять у прилавка,
     * а может позвонить. Запись на счёте не утверждает, что деньги отдали,
     * — она фиксирует, что мы их должны. Захочет забрать сейчас — продавец
     * нажмёт «Выдать», и расход появится в кассе.
     *
     * <p><b>Сделке без клиента деньги возвращаются расходом из кассы.</b>
     * Счёта у неё нет, а запретить отмену нельзя: у заказа с площадки клиент
     * необязателен, назначить его задним числом нечем, и отказ запер бы
     * продавца в сделке, которую не отменить и не выдать. Деньги уходят так же,
     * как пришли, — наличными.
     */
    private void refundOnCancel(Deal deal, Long managerId) {
        BigDecimal paid = deal.getPaidAmount();
        if (paid == null || paid.signum() <= 0) {
            return;
        }
        // Без клиента счёта не существует, а запретить отмену нельзя: у заказа
        // с площадки клиент необязателен, назначить его задним числом нечем,
        // и отказ запер бы продавца в сделке, которую не отменить и не выдать.
        // Поэтому деньги уходят расходом из кассы — так же, как их и приняли.
        if (deal.getCustomerId() == null) {
            Payment refund = new Payment(PaymentDirection.OUT, paid, null);
            refund.setDealId(deal.getId());
            refund.setComment("Отмена сделки " + deal.getNumber() + ": оплата возвращена");
            refund.setCreatedBy(managerId);
            paymentRepository.save(refund);
            return;
        }

        CustomerAccountEntry entry = new CustomerAccountEntry(
                deal.getCustomerId(), AccountEntryType.DEAL_REFUND, paid);
        entry.setDealId(deal.getId());
        entry.setComment("Отмена сделки " + deal.getNumber() + ": оплата возвращена на счёт");
        entry.setCreatedBy(managerId);
        accountRepository.save(entry);
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
            ledger.record(StockMovement.returned(
                    item.getPartId(), item.getQuantity(), warehouseId, saved.getId()));
            // Вернувшаяся деталь снова в продаже — объявление надо оживить.
            partChanges.changed(item.getPartId());
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
     * Деньги по закрытой сделке не принимаются — ни в кассу, ни зачётом.
     *
     * <p>Отменённая не состоялась, возвращённая закрыта встречным документом:
     * платить не за что. Пока проверки не было, оба пути пропускали такую
     * оплату молча — отменённая сделка получала приход в кассу, которого
     * вечером не сойдётся с ящиком, а зачёт списывал деньги <b>со счёта
     * клиента</b> в счёт товара, который тот сам принёс обратно. Второе
     * не ловила даже сверка: {@code v_account_discrepancy} знает про
     * отменённую с невозвращённой оплатой, а про возвращённую — нет.
     *
     * <p>Проверка стоит здесь, а не только в нулевом долге: с {@code debt()
     * == 0} приём денег не отказал бы, а тихо положил всю сумму на лицевой
     * счёт — то есть сделал бы не то, что просили, и без единого слова.
     */
    private void requireOpen(Deal deal) {
        if (deal.getStatus().isClosed()) {
            throw new IllegalStateException(
                    "Сделка %s закрыта (%s): платить по ней не за что"
                            .formatted(deal.getNumber(), statusWord(deal.getStatus())));
        }
    }

    private static String statusWord(DealStatus status) {
        return status == DealStatus.CANCELLED ? "отменена" : "возвращена";
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
        requireOpen(deal);

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

    /**
     * Остаток на лицевом счёте клиента.
     *
     * <p>Считается по журналу операций, а не хранится полем: хранимый остаток
     * разъедется с журналом на первой же правке, и разбирать это придётся
     * с клиентом, который помнит свою тысячу лучше нас.
     *
     * <p>Знак задаёт тип операции, а не сумма: в записях лежат положительные
     * числа, иначе «минус тысяча» и «тысяча со знаком минус» перестают
     * различаться при чтении глазами.
     */
    @Transactional(readOnly = true)
    public BigDecimal accountBalance(Long customerId) {
        return accountRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(CustomerAccountEntry::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<CustomerAccountEntry> accountEntries(Long customerId) {
        return accountRepository.findByCustomerIdOrderByIdDesc(customerId);
    }

    /**
     * Оплата сделки с лицевого счёта.
     *
     * <p><b>Платежа в кассу при этом не создаётся, и это главное.</b> Деньги
     * уже получены — тогда, когда клиент их оставил, и тогда же записан
     * приход. Второй платёж на зачёте задвоил бы выручку: в отчёте появилась
     * бы тысяча, которую никто не приносил.
     *
     * <p>Больше остатка зачесть нельзя: счёт — это обязательство перед
     * клиентом, и уйдя в минус, оно превращается в долг клиента, о котором
     * он не договаривался.
     */
    @Transactional
    public Deal payFromAccount(Long dealId, BigDecimal amount, Long managerId) {
        Deal deal = requireDeal(dealId);
        requireOpen(deal);
        if (deal.getCustomerId() == null) {
            throw new IllegalStateException(
                    "У сделки нет клиента: списывать не с чего");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Сумма зачёта должна быть больше нуля");
        }
        // Сначала сделка, потом клиент — порядок обязателен. Отмена, возврат
        // и приём денег пишут запись счёта уже после того, как Hibernate
        // отправил правку сделки, то есть держат строку сделки и просят
        // клиента (внешний ключ берёт на нём FOR KEY SHARE). Возьми мы клиента
        // первым, два продавца по одной сделке встали бы друг против друга
        // насмерть, и Postgres убил бы одного из них.
        lockDeal(dealId);
        lockCustomer(deal.getCustomerId());

        BigDecimal balance = accountBalance(deal.getCustomerId());
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "На счёте клиента %s ₽, а зачесть просят %s ₽"
                            .formatted(balance.stripTrailingZeros().toPlainString(),
                                    amount.stripTrailingZeros().toPlainString()));
        }
        BigDecimal debt = deal.debt();
        if (debt.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "Долг по сделке %s ₽, а зачесть просят %s ₽: лишнее осталось бы "
                            .formatted(debt.stripTrailingZeros().toPlainString(),
                                    amount.stripTrailingZeros().toPlainString())
                            + "переплатой поверх уже оплаченной сделки");
        }

        CustomerAccountEntry entry = new CustomerAccountEntry(
                deal.getCustomerId(), AccountEntryType.DEAL_PAYMENT, amount);
        entry.setDealId(dealId);
        entry.setComment("Оплата сделки " + deal.getNumber() + " с лицевого счёта");
        entry.setCreatedBy(managerId);
        accountRepository.save(entry);

        deal.registerPayment(amount);
        Deal saved = dealRepository.saveAndFlush(deal);

        log(saved, "PAYMENT",
                "Зачтено с лицевого счёта %s ₽".formatted(amount.toPlainString()), managerId);
        return detachable(saved);
    }

    /**
     * Выдача денег со счёта наличными.
     *
     * <p><b>Платёж здесь создаётся, в отличие от зачёта.</b> Разница
     * не формальная: при зачёте деньги остаются у нас и просто меняют
     * назначение, а тут физически уходят из кассы клиенту, и касса, в которой
     * этого расхода нет, к вечеру не сойдётся.
     *
     * <p>Больше остатка не выдать: счёт — обязательство перед клиентом,
     * а не кредит ему.
     */
    @Transactional
    public CustomerAccountEntry withdrawFromAccount(Long customerId, BigDecimal amount,
                                                    Long paymentSourceId, Long managerId) {
        lockCustomer(customerId);
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Сумма выдачи должна быть больше нуля");
        }
        BigDecimal balance = accountBalance(customerId);
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "На счёте клиента %s ₽, а выдать просят %s ₽"
                            .formatted(balance.stripTrailingZeros().toPlainString(),
                                    amount.stripTrailingZeros().toPlainString()));
        }

        Payment payment = new Payment(PaymentDirection.OUT, amount, customerId);
        payment.setPaymentSourceId(paymentSourceId);
        payment.setComment("Выдача с лицевого счёта");
        payment.setCreatedBy(managerId);
        Payment savedPayment = paymentRepository.saveAndFlush(payment);

        CustomerAccountEntry entry =
                new CustomerAccountEntry(customerId, AccountEntryType.WITHDRAW, amount);
        entry.setPaymentId(savedPayment.getId());
        entry.setComment("Выдача наличными");
        entry.setCreatedBy(managerId);
        return accountRepository.save(entry);
    }

    /**
     * Ручная правка остатка.
     *
     * <p><b>Только то, что нельзя закрыть кодом.</b> Расхождения, растущие
     * из самой системы, лечатся в системе: отмена оплаченной сделки теперь
     * возвращает деньги на счёт, зачёт не создаёт лишнего платежа, выдача
     * создаёт нужный. Правка остаётся для того, что случилось вне её: деньги
     * приняли мимо кассы, старый долг простили, при переезде из прежней
     * системы остаток приехал не тем.
     *
     * <p><b>Причина обязательна и в комментарий не прячется.</b> Правка —
     * единственная операция, меняющая деньги клиента ничем не подтверждённым
     * решением; без «почему» через месяц её не отличить от ошибки, а спорить
     * о ней придётся с клиентом.
     *
     * <p><b>Правка со знаком, и это осознанно.</b> Остальные операции знак
     * получают от типа, а тут он в сумме: правка бывает в обе стороны, и два
     * типа ради этого («правка вверх», «правка вниз») читались бы в журнале
     * как разные события, хотя это одно и то же действие.
     *
     * <p>В минус остаток не уводит: отрицательный счёт — это долг клиента,
     * а такого договора нет.
     */
    @Transactional
    public CustomerAccountEntry correctAccount(Long customerId, BigDecimal amount,
                                               String reason, Long managerId) {
        lockCustomer(customerId);
        if (amount == null || amount.signum() == 0) {
            throw new IllegalArgumentException("Правка на ноль ничего не меняет");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Правка остатка без причины: через месяц её не отличить от ошибки");
        }
        BigDecimal balance = accountBalance(customerId);
        if (balance.add(amount).signum() < 0) {
            throw new IllegalStateException(
                    "На счёте %s ₽, правка на %s уводит остаток в минус: "
                            .formatted(balance.stripTrailingZeros().toPlainString(),
                                    amount.stripTrailingZeros().toPlainString())
                            + "отрицательный счёт — это долг клиента, а такого договора нет");
        }

        CustomerAccountEntry entry =
                new CustomerAccountEntry(customerId, AccountEntryType.CORRECTION, amount);
        entry.setComment(reason.strip());
        entry.setCreatedBy(managerId);
        return accountRepository.save(entry);
    }

    /** Пополнение лицевого счёта без привязки к сделке. */
    @Transactional
    public CustomerAccountEntry topUpAccount(Long customerId, BigDecimal amount,
                                             Long paymentSourceId, Long managerId) {
        // Клиент обязан существовать: иначе деньги ложатся на счёт, которого
        // нет, и отказ приходит как «нарушает целостность данных» — продавцу
        // непонятно, ошибся он или сломался сервер.
        requireExistingCustomer(customerId);
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

    /**
     * Клиент обязан существовать, и сказать об этом надо словами.
     *
     * <p>Деталь и услуга проверялись, клиент — нет: он доезжал до внешнего
     * ключа и возвращался как «Операция нарушает целостность данных».
     * Продавец по такому ответу идёт искать поломку сервера, стоя перед
     * покупателем.
     *
     * <p>Пусто — законно: у заказа с площадки клиента нет, покупателя она
     * не называет.
     */
    private void requireCustomer(Long customerId) {
        if (customerId == null) {
            return;
        }
        requireExistingCustomer(customerId);
    }

    /** То же, но клиент обязателен: у денег на счёте владелец есть всегда. */
    private void requireExistingCustomer(Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("Не указан клиент");
        }
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE id = ?", Integer.class, customerId);
        if (found == null || found == 0) {
            throw new IllegalArgumentException("Клиент не найден: " + customerId);
        }
    }

    /**
     * Клиент под блокировкой строки: для всего, что смотрит на остаток счёта
     * и потом пишет.
     *
     * <p>Остаток считается по журналу — а значит проверка «хватает» и запись
     * это два действия, и между ними встаёт второй продавец. Замерено живьём:
     * счёт в 1000 ₽ и две одновременные выдачи по 1000 дали **два расхода
     * по 1000**, остаток минус тысяча и два нарушения в
     * {@code v_account_discrepancy}. Оба ответа при этом 201 — продавец
     * дважды увидел успех, а деньги ушли настоящие.
     *
     * <p>Инструкцией это не закрыть, в отличие от остатка склада. Там условие
     * ставится в {@code WHERE} у {@code UPDATE}, и Postgres перечитывает
     * строку после снятия блокировки; здесь мы **вставляем** запись, блокировать
     * нечего, и условие в insert проверялось бы по своему снимку, не видя
     * чужой невидимой строки. Поэтому строка клиента — та самая точка,
     * за которую операции счёта выстраиваются в очередь.
     *
     * <p>Берётся она **до** сделки во всех трёх местах: обратный порядок
     * с чем-нибудь, что идёт от сделки к счёту, даёт взаимную блокировку.
     */
    private void lockCustomer(Long customerId) {
        requireExistingCustomer(customerId);
        jdbc.queryForObject("SELECT id FROM customer WHERE id = ? FOR UPDATE",
                Long.class, customerId);
    }

    /** Строка сделки: берётся перед клиентом, чтобы порядок был один у всех. */
    private void lockDeal(Long dealId) {
        jdbc.queryForObject("SELECT id FROM deal WHERE id = ? FOR UPDATE", Long.class, dealId);
    }

    /** Источник сделки — из справочника; пусто значит «не указан». */
    private void requireDealSource(Long dealSourceId) {
        if (dealSourceId == null) {
            return;
        }
        if (!dealSources.existsById(dealSourceId)) {
            throw new IllegalArgumentException("Источник сделки не найден: " + dealSourceId);
        }
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
