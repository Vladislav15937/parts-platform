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
    private final CustomerService customers;

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
                        CustomerService customers,
                        org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.customers = customers;
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
     *
     * <p><b>Клиент необязателен: не названного подменяет контрагент
     * розничной продажи.</b> Половина продаж — человек с улицы, и требовать
     * до товара имя, которого он не называл, значит заставлять продавца
     * выдумывать его («мужик на приоре») либо не давать оформить продажу
     * вовсе. Подстановка стоит здесь, а не только на экране: пустой
     * {@code customer_id} у обычной продажи означал бы ветвление в каждом
     * месте, где к сделке приходят деньги, возврат и печатная форма.
     * У заказа с площадки всё наоборот — там клиента нет по-настоящему,
     * и подставлять его нельзя: это другой метод
     * ({@link #registerMarketplaceOrder}), и он не тронут.
     */
    @Transactional
    public Deal createReserved(Long customerId, Long managerId, Instant reservedUntil,
                               Long dealSourceId, List<ItemRequest> items,
                               List<ServiceRequest> services) {

        if (customerId == null) {
            customerId = customers.retail().id();
        }
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
     * <p><b>Одновременный повтор проверку не проходит, и это половина
     * защиты.</b> Между чтением и вставкой второй запрос ещё ничего не видит:
     * дубля не появляется — его отбивает {@code deal_external_order_uk}, —
     * но наружу летело «Операция нарушает целостность данных». Продавец
     * нажимает «Принять заказ» второй раз, потому что первое нажатие
     * не показало результата, и получает ответ про поломку сервера на заказ,
     * который уже принят. {@link #replayOrderAfterConflict} перечитывает
     * прежнюю сделку и отдаёт её — то же лечение, что у приёмки и у ссылки
     * на снимок, и болезнь одна.
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
        // Обрезается один раз и на всё: пишется в сделку обрезанный номер,
        // а искался прежде тот, что пришёл, — номер с лишним пробелом
        // не находил своей же сделки и уходил на повторное заведение.
        String number = orderNo.strip();

        AcceptedOrder replayed = replayOrder(marketplace, number);
        if (replayed != null) {
            return replayed;
        }

        Deal deal = new Deal(customerId, managerId);
        deal.setCreatedBy(managerId);
        deal.setDealSourceId(dealSourceId);
        deal.setDeliveryNote(deliveryNote);
        deal.fromMarketplace(marketplace, number,
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
     * Прежняя сделка после одновременного повтора.
     *
     * <p>Читается новой транзакцией: та, в которой случилось нарушение
     * уникальности, помечена на откат, и запрос из неё не пройдёт. Та же
     * причина, что у {@code IntakeService.replayAfterConflict}.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                   readOnly = true)
    public AcceptedOrder replayOrderAfterConflict(String marketplace, String orderNo) {
        return replayOrder(marketplace, orderNo);
    }

    private AcceptedOrder replayOrder(String marketplace, String orderNo) {
        return dealRepository.findByMarketplaceAndExternalOrderNo(marketplace, orderNo)
                .map(existing -> new AcceptedOrder(detachable(existing), true, List.of()))
                .orElse(null);
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
            // Источника у этого расхода нет, и это единственное из пяти мест,
            // создающих платёж, где его не спрашивают: отмену делают одним
            // нажатием, без разговора о деньгах. Цена и почему так —
            // в CLAUDE.md рядом («Мест, создающих платёж, пять»).
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
     * Реестр возвратов: все документы обзором, а не только по открытой сделке.
     *
     * <p>До этого экрана возврат было не найти иначе, чем через клиента
     * и его сделку — продавец, сменившийся со смены, найти его не мог вовсе.
     * Отбор общий на все три способа поиска, которыми владелец печатает
     * то, что помнит, не выбирая заранее, что именно это — номер, имя
     * или причина.
     *
     * <p>Список читают с конца и не листают вглубь: вместо курсора —
     * растущий предел {@code limit} с постоянным {@code OFFSET 0}, и это
     * не костыль, а то же свойство, ради которого курсор обычно и нужен —
     * база не читает и не отбрасывает то, что уже показано.
     *
     * @param query      поиск: точное совпадение по номеру сделки, вхождение —
     *                   по имени клиента и по причине
     * @param from       начало периода по {@code created_at}; пусто — с начала времён
     * @param to         конец периода (исключая); пусто — по текущий момент
     * @param limit      сколько строк вернуть
     * @param customerId непусто — только возвраты этого клиента; вкладка «Возвраты»
     *                   в карточке клиента (задача 0022) переиспользует этот же
     *                   запрос, а не заводит свой
     */
    @Transactional(readOnly = true)
    public ReturnsPage listReturns(String query, Instant from, Instant to, int limit, Long customerId) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (customerId != null) {
            where.append(" AND r.customer_id = ?");
            args.add(customerId);
        }
        if (from != null) {
            where.append(" AND r.created_at >= ?");
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND r.created_at < ?");
            args.add(java.sql.Timestamp.from(to));
        }
        if (query != null && !query.isBlank()) {
            String term = query.strip();
            String like = "%" + term + "%";
            Long number = parseNumber(term);
            if (number != null) {
                where.append(" AND (d.number = ? OR c.name ILIKE ? OR r.reason ILIKE ?)");
                args.add(number);
                args.add(like);
                args.add(like);
            } else {
                where.append(" AND (c.name ILIKE ? OR r.reason ILIKE ?)");
                args.add(like);
                args.add(like);
            }
        }

        // Разделители — явной строкой, а не отступом текстового блока: блок,
        // чьи закрывающие кавычки стоят на строке содержимого, срезает весь
        // общий отступ, и «r.reason» + joins склеивается в «r.reasonFROM» —
        // один слитный идентификатор вместо конца списка колонок и ключевого
        // слова. Здесь ловится тестом, а не только грамматикой Postgres:
        // «count(*)» перед тем же блоком не ломается — скобка отделяет
        // токены и без пробела, — и до строкового запроса ошибка не доезжала.
        String joins = " FROM deal_return r"
                + " JOIN deal d ON d.id = r.deal_id"
                + " LEFT JOIN customer c ON c.id = r.customer_id"
                + " LEFT JOIN warehouse w ON w.id = r.warehouse_id";

        long total = jdbc.queryForObject("SELECT count(*)" + joins + where, Long.class, args.toArray());
        // Отменённый возврат в счёт по отбору входит, а в сумму — нет: строка
        // видна списком, но деньги по ней не двигались по-настоящему.
        BigDecimal totalAmount = jdbc.queryForObject(
                "SELECT coalesce(sum(r.amount), 0)" + joins + where + " AND r.status <> 'CANCELLED'",
                BigDecimal.class, args.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(limit);
        List<ReturnListRow> rows = jdbc.query(
                "SELECT r.id, r.number, r.created_at, r.deal_id, d.number AS deal_number,"
                        + " r.customer_id, c.name AS customer_name,"
                        + " r.warehouse_id, w.name AS warehouse_name, r.restocked,"
                        + " r.status, r.amount, r.reason"
                        + joins + where
                        + " ORDER BY r.id DESC LIMIT ?",
                (rs, i) -> new ReturnListRow(
                        rs.getLong("id"), rs.getLong("number"), rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("deal_id"), rs.getLong("deal_number"),
                        (Long) rs.getObject("customer_id"), rs.getString("customer_name"),
                        (Long) rs.getObject("warehouse_id"), rs.getString("warehouse_name"),
                        rs.getBoolean("restocked"), ReturnStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("amount"), rs.getString("reason")),
                rowArgs.toArray());

        return new ReturnsPage(rows, total, totalAmount);
    }

    private static Long parseNumber(String term) {
        try {
            return Long.parseLong(term);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Список сделок для продавца: воронка по состоянию, поиск, отбор «мои».
     *
     * <p>До этого экрана единственной дорогой к чужой сделке была ссылка
     * «Найти сделку клиента», и она спрашивала <b>только клиента</b>.
     * Продавец, вышедший на смену, не видел ни что отложено вчера,
     * ни что просрочено, ни сколько сделок висит на нём самом; а на звонок
     * «мне звонили, деталь номер такой-то» ответить было нечем вовсе —
     * пути от товара к сделке в системе не существовало.
     *
     * <p><b>Поиск — три ветки через {@code UNION}, а не три условия через
     * {@code OR}.</b> С {@code OR} планировщик берёт индекс, только если
     * проиндексированы все ветки, и всё равно мажет с кардинальностью
     * {@code '%…%'}; с {@code UNION} каждая идёт своим — номер сделки
     * уникальным {@code deal_number_uk}, имя клиента триграммным
     * {@code customer_name_trgm}, код детали триграммным
     * {@code part_code_trgm}. Тот же приём и по той же причине, что
     * в поиске продавца и на витрине склада.
     *
     * <p><b>Номер сделки сравнивается точно, код детали и клиент —
     * вхождением.</b> Номер человек называет целиком («восьмая»), и подстрока
     * от него находит чужие документы; имя клиента и код детали называют
     * по памяти и по кускам.
     *
     * <p>Вместо курсора — растущий предел, как у реестра возвратов: список
     * читают с конца и вглубь не листают, и {@code OFFSET} здесь всегда ноль.
     *
     * <p>Себестоимости и наценки в строке нет: это отчёты владельца, а список
     * открыт продавцу.
     *
     * @param statuses воронка; пусто — все состояния разом. Группировку задаёт
     *                 экран ({@code DEAL_FUNNEL}), сервер отбирает по набору —
     *                 иначе имена воронок пришлось бы знать обеим сторонам,
     *                 и они разошлись бы на первой правке
     * @param query    поиск: точное совпадение по номеру сделки, вхождение —
     *                 по имени клиента и по публичному коду детали
     * @param managerId непусто — только сделки этого сотрудника (отбор «мои»)
     * @param limit    сколько строк вернуть
     */
    @Transactional(readOnly = true)
    public DealsPage listDeals(List<DealStatus> statuses, String query, Long managerId, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!statuses.isEmpty()) {
            where.append(" AND d.status IN (")
                    .append(String.join(", ", java.util.Collections.nCopies(statuses.size(), "?")))
                    .append(")");
            statuses.forEach(status -> args.add(status.name()));
        }
        if (managerId != null) {
            where.append(" AND d.manager_id = ?");
            args.add(managerId);
        }
        if (query != null && !query.isBlank()) {
            String term = query.strip();
            String like = "%" + term + "%";
            List<String> branches = new ArrayList<>();
            Long number = parseNumber(term);
            if (number != null) {
                branches.add("SELECT id FROM deal WHERE number = ?");
                args.add(number);
            }
            branches.add("SELECT d2.id FROM deal d2"
                    + " JOIN customer c2 ON c2.id = d2.customer_id"
                    + " WHERE c2.name ILIKE ?");
            args.add(like);
            branches.add("SELECT i.deal_id FROM deal_item i"
                    + " JOIN part p ON p.id = i.part_id"
                    + " WHERE p.public_code ILIKE ?");
            args.add(like);
            where.append(" AND d.id IN (").append(String.join(" UNION ", branches)).append(")");
        }

        // Разделители — явной строкой, а не отступом текстового блока:
        // записанная ловушка модуля, из-за которой «r.reason» + joins однажды
        // склеилось в «r.reasonFROM». Компилятор про это молчит всегда.
        String joins = " FROM deal d"
                + " LEFT JOIN customer c ON c.id = d.customer_id"
                + " LEFT JOIN tenant_member m ON m.id = d.manager_id";

        long total = jdbc.queryForObject("SELECT count(*)" + joins + where,
                Long.class, args.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(limit);
        List<DealListRow> rows = jdbc.query(
                "SELECT d.id, d.number, d.created_at, d.customer_id, c.name AS customer_name,"
                        + " d.total_amount, d.paid_amount, d.status, d.reserved_until,"
                        + " d.manager_id, m.display_name AS manager_name"
                        + joins + where
                        + " ORDER BY d.id DESC LIMIT ?",
                (rs, i) -> new DealListRow(
                        rs.getLong("id"), rs.getLong("number"),
                        rs.getTimestamp("created_at").toInstant(),
                        (Long) rs.getObject("customer_id"), rs.getString("customer_name"),
                        rs.getBigDecimal("total_amount"), rs.getBigDecimal("paid_amount"),
                        DealStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("reserved_until") == null
                                ? null : rs.getTimestamp("reserved_until").toInstant(),
                        (Long) rs.getObject("manager_id"), rs.getString("manager_name")),
                rowArgs.toArray());

        return new DealsPage(rows, total);
    }

    /**
     * Строка списка сделок.
     *
     * <p>Имя ответственного берётся {@code LEFT JOIN}'ом, а не картой имён
     * поверх выдачи: у сделки без ответственного (сотрудника удалили, заказ
     * с площадки ещё не принят) карта уже дважды оборачивалась пятисоткой —
     * {@code Map.of().get(null)} бросает {@code NullPointerException}.
     * Соединение такой ловушки не имеет вовсе.
     *
     * @param customerName пусто — у сделки нет клиента (заказ с площадки);
     *                     экран показывает это словами
     * @param reservedUntil срок резерва (задача 0012). Показывается только
     *                      у отложенной: у выданной и отменённой он уже
     *                      ни о чём, а дата рядом с ними читается как
     *                      обещание, которого никто не давал
     * @param managerName  пусто — ответственного нет
     */
    public record DealListRow(Long id, Long number, Instant createdAt,
                              Long customerId, String customerName,
                              BigDecimal totalAmount, BigDecimal paidAmount,
                              DealStatus status, Instant reservedUntil,
                              Long managerId, String managerName) {
    }

    /**
     * @param total сколько нашлось по отбору — список обрезан пределом,
     *              и подвал обязан назвать, сколько показано из скольких
     */
    public record DealsPage(List<DealListRow> items, long total) {
    }

    /**
     * Колонки доски сделок по состояниям — порядок и слова.
     *
     * <p><b>Слова здесь, а не на экране, и это расхождение с воронкой
     * списка.</b> У воронки группировку задаёт клиент ({@code DEAL_FUNNEL}
     * знает имена, сервер принимает набор состояний) — иначе имена воронок
     * пришлось бы знать обеим сторонам. Здесь наоборот: группу вычисляет
     * {@code CASE} в SQL, и название той же группы, написанное вторым
     * списком на экране, разошлось бы с ним молча — ровно как расходились
     * белые списки колонок. Группа и её имя — одна вещь, и живут они
     * в одном месте.
     *
     * <p>Названия дословно из задачи 0052 («Новая сделка · Истек срок ·
     * Ждет оплаты · Частично оплачен · Готов к выдаче»): по ним переходящий
     * клиент узнаёт свой экран.
     */
    private static final List<BoardStage> BOARD_STAGES = List.of(
            new BoardStage("NEW", "Новая сделка"),
            new BoardStage("EXPIRED", "Истек срок"),
            new BoardStage("AWAITING_PAYMENT", "Ждет оплаты"),
            new BoardStage("PARTLY_PAID", "Частично оплачен"),
            new BoardStage("READY", "Готов к выдаче"));

    /** Незакрытая сделка: товар ещё числится обещанным, и по ней есть работа. */
    private static final String BOARD_OPEN = " d.status IN ('DRAFT', 'RESERVED', 'READY')";

    /**
     * Сколько карточек отдаётся в колонке.
     *
     * <p>Счётчик над колонкой считает всё, что в неё попало, а карточек едет
     * не больше сотни: у живого клиента в «Истек срок» полсотни, но резерв
     * не снимается сам, и за год их накопится сколько угодно. Экран говорит,
     * что список обрезан, — молчащая обрезка это то же враньё, что «Показаны
     * первые 50» без числа найденного.
     */
    private static final int BOARD_CARDS = 100;

    /**
     * Стадия сделки — одно выражение на весь проект.
     *
     * <p><b>Почему константой, а не строкой внутри {@link #dealBoard}.</b>
     * Стадию читают уже две поверхности: доска (колонка и подпись карточки)
     * и карточка самой сделки со списком сделок клиента
     * ({@link #stagesOf(List)}). Вторая копия этих пяти веток разошлась бы
     * с первой молча — и разошлась бы не при копировании, а позже, когда
     * правят одну из них. В этом проекте так уже было трижды: белые списки
     * колонок, копии словаря состояний, таблица месяцев.
     *
     * <p>Один параметр — «сейчас», для ветки просроченного резерва. Он идёт
     * первым в списке колонок, значит и в аргументах запроса стоит первым.
     *
     * <p>Разделители — явной строкой, а не отступом текстового блока:
     * записанная ловушка проекта, из-за которой склейка однажды дала
     * «r.reasonFROM». Компилятор про это молчит всегда.
     */
    private static final String STAGE_CASE = " CASE"
            + " WHEN d.status = 'DRAFT' THEN 'NEW'"
            + " WHEN d.status = 'RESERVED' AND d.reserved_until IS NOT NULL"
            + " AND d.reserved_until < ? THEN 'EXPIRED'"
            + " WHEN d.status = 'READY'"
            + " OR (d.total_amount > 0 AND d.paid_amount >= d.total_amount) THEN 'READY'"
            + " WHEN d.paid_amount <= 0 THEN 'AWAITING_PAYMENT'"
            + " ELSE 'PARTLY_PAID'"
            + " END AS stage";

    /**
     * Стадия названных сделок — тем же {@code CASE}, что раскладывает доску.
     *
     * <p><b>Зачем это нужно за пределами доски.</b> Задача 0052 починила
     * подпись карточки на доске, а те же слова стоят ещё в двух местах:
     * в заголовке карточки сделки и в списке сделок клиента
     * ({@code DealFinder}). Там они выводились из {@code status} без поправки
     * на оплату — то есть полностью оплаченная и готовая к выдаче сделка
     * называлась «отложена» и подписывалась «Отложено до 15 сентября»,
     * ровно противоположным смыслом. Нажатие на карточку «Готов к выдаче»
     * ведёт именно в эту карточку: продавец читал исправленное слово
     * на доске и тут же, одним движением, исходный обман снова.
     *
     * <p><b>Закрытой сделки в ответе нет вовсе, и это не пропуск.</b>
     * Стадия — про незакрытую сделку ({@code BOARD_OPEN}), и у выданной,
     * отменённой или возвращённой её не существует: {@code CASE} назвал бы
     * оплаченную выданную сделку «готовой к выдаче». Пусто означает
     * «стадии нет, подписывай состоянием документа», и экран так и делает —
     * «Сделка №20 · выдана».
     *
     * <p>Своя транзакция обязательна: {@code JdbcTemplate}, позванный
     * снаружи, берёт соединение из пула напрямую и уходит в {@code public}.
     *
     * @param dealIds чьи стадии нужны; пустой список не ходит в базу вовсе
     * @return стадия по идентификатору сделки; незакрытых в карте нет
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, String> stagesOf(List<Long> dealIds) {
        List<Long> ids = dealIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(Instant.now()));
        args.addAll(ids);
        java.util.Map<Long, String> stages = new java.util.HashMap<>();
        jdbc.query("SELECT d.id," + STAGE_CASE + " FROM deal d"
                        + " WHERE d.id IN (" + ids.stream().map(id -> "?")
                        .collect(java.util.stream.Collectors.joining(", ")) + ")"
                        + " AND" + BOARD_OPEN,
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        stages.put(rs.getLong("id"), rs.getString("stage")),
                args.toArray());
        return stages;
    }

    /**
     * Доска сделок по состояниям: пять колонок со счётчиками.
     *
     * <p><b>Зачем она рядом со списком.</b> Список отвечает на «покажи все
     * сделки», а первый вопрос продавца на смене другой — «что мне сегодня
     * делать». Воронка списка на него не отвечает: чтобы узнать, сколько
     * выданных, надо переключиться на «Выданные» и потерять из виду
     * отложенные, а стадий «ждёт оплаты», «частично оплачен» и «истёк срок»
     * у неё нет вовсе — её пункты это статус документа, а не стадия работы
     * над ним.
     *
     * <p><b>Стадия вычисляется, а не хранится.</b> Всё, из чего она
     * складывается — состояние документа, оплаченное против суммы и срок
     * резерва, — уже лежит в строке; хранимая стадия разъехалась бы с этими
     * тремя на первой же оплате, и разъехалась бы молча. По той же причине
     * колонка «Готов к выдаче» не зовёт {@link Deal#markReady()}: полная
     * оплата при невыданном товаре — это и есть «готов», а переход, который
     * никто не нажимает, дал бы пустую колонку рядом с оплаченными сделками.
     *
     * <p><b>{@code CASE} — потому что колонка обязана быть ровно одна.</b>
     * Пять отдельных запросов с условиями, написанными по одному, дают
     * сделку в двух колонках сразу (или ни в одной) при первой же правке
     * одного из них, и сумма счётчиков перестаёт сходиться с числом
     * незакрытых сделок — а именно эти числа продавец и читает за секунду.
     * {@code CASE} берёт первую подошедшую ветку по устройству языка,
     * и доказывать тут нечего.
     *
     * <p><b>«Истек срок» — тот же набор, что у {@link #expiredReservations()}
     * ({@code GET /api/deals/expired-reservations}).</b> Условие повторено
     * дословно ({@code status = 'RESERVED'} и срок в прошлом) и стоит
     * <b>раньше</b> ветки «Готов к выдаче»: оплаченная сделка с просроченным
     * резервом остаётся просроченной, иначе два ответа на один вопрос
     * разошлись бы. Стережёт это {@code DealBoardTest}, сверяющий счётчик
     * колонки с числом строк того эндпоинта.
     *
     * @param warehouseId склад выдачи; пусто — все. Отбирается по складам
     *                    позиций ({@code deal_item.warehouse_id}), а не
     *                    по {@code deal.warehouse_id}: ту колонку не пишет
     *                    никто, и отбор по ней не нашёл бы ничего никогда
     * @param sourceId    источник сделки; пусто — все
     * @param managerId   ответственный; пусто — все
     */
    @Transactional(readOnly = true)
    public DealBoard dealBoard(Long warehouseId, Long sourceId, Long managerId) {
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Timestamp.from(Instant.now()));

        StringBuilder where = new StringBuilder(" WHERE" + BOARD_OPEN);
        if (warehouseId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM deal_item i"
                    + " WHERE i.deal_id = d.id AND i.warehouse_id = ?)");
            args.add(warehouseId);
        }
        if (sourceId != null) {
            where.append(" AND d.deal_source_id = ?");
            args.add(sourceId);
        }
        if (managerId != null) {
            where.append(" AND d.manager_id = ?");
            args.add(managerId);
        }

        String inner = "SELECT" + STAGE_CASE
                + ", d.id, d.number, d.created_at, c.name AS customer_name,"
                + " d.total_amount, d.paid_amount, d.status, d.reserved_until"
                + " FROM deal d"
                + " LEFT JOIN customer c ON c.id = d.customer_id"
                + where;

        // Счёт колонки и её карточки — одно окно над одной выборкой. Посчитай
        // их разными запросами, и счётчик разойдётся со списком на первой
        // правке условия: продавец прочтёт «Истек срок 58» над другими
        // пятьюдесятью восемью.
        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(BOARD_CARDS);
        java.util.Map<String, List<BoardCard>> cards = new java.util.HashMap<>();
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        jdbc.query("SELECT * FROM (SELECT s.*,"
                        + " row_number() OVER (PARTITION BY stage ORDER BY id DESC) AS rn,"
                        + " count(*) OVER (PARTITION BY stage) AS cnt"
                        + " FROM (" + inner + ") s) w"
                        + " WHERE rn <= ?"
                        + " ORDER BY stage, id DESC",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String key = rs.getString("stage");
                    counts.put(key, rs.getLong("cnt"));
                    cards.computeIfAbsent(key, k -> new ArrayList<>()).add(new BoardCard(
                            key, rs.getLong("id"), rs.getLong("number"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("customer_name"),
                            rs.getBigDecimal("total_amount"), rs.getBigDecimal("paid_amount"),
                            DealStatus.valueOf(rs.getString("status")),
                            rs.getTimestamp("reserved_until") == null
                                    ? null : rs.getTimestamp("reserved_until").toInstant()));
                },
                rowArgs.toArray());

        List<BoardColumn> columns = BOARD_STAGES.stream()
                .map(s -> new BoardColumn(s.key(), s.title(),
                        counts.getOrDefault(s.key(), 0L),
                        cards.getOrDefault(s.key(), List.of())))
                .toList();

        return new DealBoard(columns, boardOptions(
                "SELECT DISTINCT w.id, w.name AS name FROM warehouse w"
                        + " JOIN deal_item i ON i.warehouse_id = w.id"
                        + " JOIN deal d ON d.id = i.deal_id"),
                boardOptions("SELECT DISTINCT s.id, s.name AS name FROM deal_source s"
                        + " JOIN deal d ON d.deal_source_id = s.id"),
                boardOptions("SELECT DISTINCT m.id, m.display_name AS name"
                        + " FROM tenant_member m"
                        + " JOIN deal d ON d.manager_id = m.id"));
    }

    /**
     * Значения отбора — те, что встретились в незакрытых сделках, и считает
     * их сервер.
     *
     * <p>Список, собранный на экране из показанных карточек, назвал бы только
     * то, что уместилось в сотню; список, написанный на клиенте отдельно,
     * разошёлся бы с отбором молча — то же правило, по которому значения
     * отбора витрины приезжают вместе со страницей.
     *
     * <p>Считаются они <b>до</b> отбора, а не после: сузив доску одним
     * складом, продавец обязан по-прежнему видеть остальные — иначе
     * поставленный отбор снять нечем, кроме перезагрузки. Ровно это уже
     * случалось на витрине склада.
     */
    private List<BoardOption> boardOptions(String select) {
        return jdbc.query(select + " WHERE" + BOARD_OPEN + " ORDER BY name",
                (rs, i) -> new BoardOption(rs.getLong("id"), rs.getString("name")));
    }

    private record BoardStage(String key, String title) {
    }

    /**
     * Доска целиком: колонки и значения трёх отборов.
     *
     * @param warehouses склады выдачи, встретившиеся в незакрытых сделках
     * @param sources    источники сделок
     * @param managers   ответственные
     */
    public record DealBoard(List<BoardColumn> columns, List<BoardOption> warehouses,
                            List<BoardOption> sources, List<BoardOption> managers) {
    }

    /**
     * @param count сколько сделок в колонке — всех, а не показанных: это то
     *              самое число над колонкой, ради которого доска и нужна
     * @param cards первые сто карточек колонки
     */
    public record BoardColumn(String key, String title, long count, List<BoardCard> cards) {
    }

    /**
     * Карточка колонки.
     *
     * <p>Позиций и услуг здесь нет намеренно: доска — обзор, а не документ,
     * и полторы сотни сделок с их составом это N+1 на каждый вход продавца
     * в смену. Нажатие открывает сделку, и вот там она приезжает целиком.
     *
     * @param stage        стадия, в которую карточку положил {@code CASE}, —
     *                     из той же строки, что и сама карточка, поэтому
     *                     разойтись с колонкой она не может. Экран подписывает
     *                     карточку <b>по ней</b>, а не по {@code status}:
     *                     стадия вычисляется, и у полностью оплаченной
     *                     невыданной сделки документ так и остаётся
     *                     {@code RESERVED} — подписанная сырым статусом,
     *                     готовая к выдаче сделка читалась бы как «Отложена
     *                     до 15 сентября», то есть противоположным смыслом
     * @param status       состояние самого документа, а не стадия. Остаётся
     *                     в ответе: по нему открывается сделка и им же
     *                     объясняется, почему стадия отличается
     * @param customerName пусто — клиента у сделки нет (заказ с площадки),
     *                     и карточка тогда не говорит о нём ничего
     */
    public record BoardCard(String stage, Long id, Long number, Instant createdAt,
                            String customerName,
                            BigDecimal totalAmount, BigDecimal paidAmount,
                            DealStatus status, Instant reservedUntil) {
    }

    public record BoardOption(Long id, String name) {
    }

    /**
     * Платежи клиента: касса, а не движения лицевого счёта.
     *
     * <p>Вкладка «Платежи» карточки клиента (задача 0022) показывает их рядом
     * с журналом счёта, но это разные вещи — платёж это факт кассы (кто внёс
     * и кто получил деньги), а движение счёта это обязательство перед
     * клиентом. У платежа без сделки ({@code dealId == null}) номер сделки
     * пуст — это пополнение или выдача со счёта, а не оплата документа.
     */
    @Transactional(readOnly = true)
    public List<PaymentRow> paymentsOfCustomer(Long customerId) {
        requireExistingCustomer(customerId);
        return jdbc.query(
                "SELECT p.id, p.paid_at, p.deal_id, d.number AS deal_number,"
                        + " p.amount, p.direction, p.comment"
                        + " FROM payment p"
                        + " LEFT JOIN deal d ON d.id = p.deal_id"
                        + " WHERE p.customer_id = ?"
                        + " ORDER BY p.id DESC",
                (rs, i) -> new PaymentRow(rs.getLong("id"), rs.getTimestamp("paid_at").toInstant(),
                        (Long) rs.getObject("deal_id"), (Long) rs.getObject("deal_number"),
                        rs.getBigDecimal("amount"), PaymentDirection.valueOf(rs.getString("direction")),
                        rs.getString("comment")),
                customerId);
    }

    /** @param dealNumber пусто — платёж без сделки: пополнение или выдача со счёта */
    public record PaymentRow(Long id, Instant paidAt, Long dealId, Long dealNumber,
                             BigDecimal amount, PaymentDirection direction, String comment) {
    }

    /**
     * Реестр платежей: все деньги компании одним списком (задача 0045).
     *
     * <p>До него прочитать кассу было негде. Источник платежа система пишет
     * с задачи 0024, а показывал его только отчёт по источникам — то есть
     * суммы за месяц, а не сами платежи: на вопрос «что это за расход
     * в четверг» ответить было нечем, кроме как поднимать сделку за сделкой.
     * Соседний {@link #paymentsOfCustomer} спрашивает то же самое, но про
     * одного клиента, а кассу сводят по всей компании.
     *
     * <p><b>Справочник источников подцеплен {@code LEFT JOIN}, и это
     * не мелочь.</b> Отбор идёт по платежам, а не по справочнику: у платежа
     * может не быть источника вовсе (до задачи 0024 его не писали, и у
     * переехавшего клиента такова вся история), а сам источник может быть
     * снят в архив — владелец наводит порядок в справочнике сегодня,
     * а кассу сводит за прошлую неделю. Внутреннее соединение потеряло бы
     * и то и другое молча.
     *
     * <p><b>Итог считается своим запросом, а не сложением строк.</b>
     * Сложенные строки сходятся сами с собой при любой поломке отбора;
     * число, посчитанное независимо по той же выборке, — единственное, что
     * ловит разъехавшийся знак у расхода. Приход и расход при этом отдаются
     * порознь: сумма платежа всегда положительная, знак несёт
     * {@code direction}, и сложение дало бы «прошло через кассу» больше,
     * чем было.
     *
     * <p>Вместо курсора — растущий предел, как у реестра возвратов и списка
     * сделок: реестр читают с конца и вглубь не листают.
     *
     * @param direction непусто — только приход или только расход (воронка
     *                  «Приходные»/«Расходные»); подвал считается по всей
     *                  выборке отбора, воронку включая
     * @param from      начало периода по {@code paid_at}; пусто — с начала времён
     * @param to        конец периода (исключая); пусто — по текущий момент
     * @param limit     сколько строк вернуть
     */
    @Transactional(readOnly = true)
    public PaymentsPage listPayments(PaymentDirection direction, Instant from, Instant to,
                                     int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (direction != null) {
            where.append(" AND p.direction = ?");
            args.add(direction.name());
        }
        if (from != null) {
            where.append(" AND p.paid_at >= ?");
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            where.append(" AND p.paid_at < ?");
            args.add(java.sql.Timestamp.from(to));
        }

        // Разделители — явной строкой, а не отступом текстового блока: блок,
        // чьи закрывающие кавычки стоят на строке содержимого, срезает весь
        // общий отступ, и «p.source_name» + joins склеилось бы в один слитный
        // идентификатор. Ловушка проекта, ловившая уже дважды.
        String joins = " FROM payment p"
                + " LEFT JOIN deal d ON d.id = p.deal_id"
                + " LEFT JOIN customer c ON c.id = p.customer_id"
                + " LEFT JOIN payment_source s ON s.id = p.payment_source_id";

        Object[] filter = args.toArray();
        long total = jdbc.queryForObject("SELECT count(*)" + joins + where, Long.class, filter);
        BigDecimal income = jdbc.queryForObject(
                "SELECT coalesce(sum(p.amount) FILTER (WHERE p.direction = 'IN'), 0)"
                        + joins + where, BigDecimal.class, filter);
        BigDecimal expense = jdbc.queryForObject(
                "SELECT coalesce(sum(p.amount) FILTER (WHERE p.direction = 'OUT'), 0)"
                        + joins + where, BigDecimal.class, filter);
        // Итог — свой запрос со своим выражением, а не «приход минус расход»
        // в Java: посчитанный из тех же двух чисел, он подтверждал бы только
        // сам себя.
        BigDecimal net = jdbc.queryForObject(
                "SELECT coalesce(sum(CASE WHEN p.direction = 'IN' THEN p.amount"
                        + " ELSE -p.amount END), 0)" + joins + where, BigDecimal.class, filter);

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(limit);
        List<PaymentListRow> rows = jdbc.query(
                "SELECT p.id, p.paid_at, p.direction, p.amount, p.comment,"
                        + " p.deal_id, d.number AS deal_number,"
                        + " p.customer_id, c.name AS customer_name,"
                        + " p.payment_source_id, s.name AS source_name"
                        + joins + where
                        + " ORDER BY p.id DESC LIMIT ?",
                (rs, i) -> new PaymentListRow(
                        rs.getLong("id"), rs.getTimestamp("paid_at").toInstant(),
                        PaymentDirection.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("amount"), rs.getString("comment"),
                        (Long) rs.getObject("deal_id"), (Long) rs.getObject("deal_number"),
                        (Long) rs.getObject("customer_id"), rs.getString("customer_name"),
                        (Long) rs.getObject("payment_source_id"), rs.getString("source_name")),
                rowArgs.toArray());

        return new PaymentsPage(rows, total, income, expense, net);
    }

    /**
     * Строка реестра платежей.
     *
     * @param dealNumber   пусто — платёж без сделки: пополнение или выдача
     *                     со счёта
     * @param customerName пусто — клиента у платежа нет; так возвращают деньги
     *                     по заказу с площадки, где покупатель не назван
     * @param sourceName   пусто — способ не записан; это незаполненное поле,
     *                     а не «прочее»: до задачи 0024 его не писали вовсе
     */
    public record PaymentListRow(Long id, Instant paidAt, PaymentDirection direction,
                                 BigDecimal amount, String comment,
                                 Long dealId, Long dealNumber,
                                 Long customerId, String customerName,
                                 Long sourceId, String sourceName) {
    }

    /**
     * @param total   сколько нашлось по отбору — список обрезан пределом,
     *                и подвал обязан назвать, сколько показано из скольких
     * @param income  приход по всей выборке отбора
     * @param expense расход по всей выборке отбора, числом положительным
     * @param net     итог по той же выборке, посчитанный своим запросом:
     *                приход минус расход
     */
    public record PaymentsPage(List<PaymentListRow> items, long total,
                               BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

    /**
     * Строка реестра возвратов.
     *
     * @param customerName  пусто — у сделки нет клиента; экран показывает
     *                      «Частное лицо»
     * @param warehouseName склад, на который принят товар; у брака
     *                      ({@code !restocked}) на склад ничего не вставало,
     *                      и экран показывает это словами, а не адресом
     */
    public record ReturnListRow(Long id, Long number, Instant createdAt, Long dealId, Long dealNumber,
                                Long customerId, String customerName,
                                Long warehouseId, String warehouseName, boolean restocked,
                                ReturnStatus status, BigDecimal amount, String reason) {
    }

    /**
     * @param total       сколько нашлось по отбору — список может быть обрезан пределом
     * @param totalAmount сумма найденного без отменённых возвратов
     */
    public record ReturnsPage(List<ReturnListRow> items, long total, BigDecimal totalAmount) {
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
    /**
     * Остаток счёта существующего клиента.
     *
     * <p>Проверка есть и на чтении, а не только на записи: без неё счёт
     * несуществующего клиента отвечал «баланс 0 ₽» — то есть утверждал
     * что-то о деньгах того, кого нет. Продавец, открывший не того клиента,
     * читает это как «за ним ничего не числится» и говорит покупателю,
     * что аванса у него нет. Пустой журнал и отсутствующий клиент — разные
     * вещи, и различать их обязан сервер: на экране они выглядят одинаково.
     */
    @Transactional(readOnly = true)
    public BigDecimal accountBalance(Long customerId) {
        requireExistingCustomer(customerId);
        return accountRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(CustomerAccountEntry::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<CustomerAccountEntry> accountEntries(Long customerId) {
        requireExistingCustomer(customerId);
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

    /**
     * Меняет контрагента сделки: «Частное лицо» оказалось Евгением Гридиным.
     *
     * <p>Так и устроен разговор на разборке: сначала товар, потом — если
     * покупатель назвался — клиент. Сделка создаётся на контрагенте
     * розничной продажи, и это единственный путь заменить его настоящим
     * покупателем; проверки, при которых менять нельзя, живут в
     * {@link Deal#changeCustomer}.
     *
     * <p><b>В историю документа это пишется отдельной строкой с обоими
     * именами.</b> «Сделка была оформлена на кого-то другого» выясняется
     * через недели, при возврате или разборе долга, и ответ «изменил Пётр,
     * с „Частного лица“ на Гридина» должен читаться прямо из журнала —
     * иначе остаётся молча изменившееся поле, по которому не восстановить
     * ни кто, ни зачем.
     */
    @Transactional
    public Deal changeCustomer(Long dealId, Long customerId, Long managerId) {
        requireExistingCustomer(customerId);
        Deal deal = requireDeal(dealId);
        Long previous = deal.getCustomerId();
        if (customerId.equals(previous)) {
            // Ничего не меняется — и записи в историю быть не должно:
            // строка «изменён контрагент с Гридина на Гридина» только мешает
            // читать журнал.
            return detachable(deal);
        }
        deal.changeCustomer(customerId);

        Deal saved = detachable(dealRepository.saveAndFlush(deal));
        log(saved, "CUSTOMER_CHANGED",
                "Изменён контрагент с %s на %s".formatted(
                        customerName(previous), customerName(customerId)),
                managerId);
        return saved;
    }

    /**
     * Имена клиентов одним запросом на всю выдачу — как наименования
     * запчастей и имена ответственных.
     *
     * <p>Пустые номера отбрасываются здесь, а не у вызывающего: у заказа
     * с площадки клиента нет вовсе, и {@code IN (NULL)} тихо вернул бы
     * пустую карту на всю страницу.
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, String> customerNamesOf(List<Long> customerIds) {
        List<Long> ids = customerIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new java.util.HashMap<>();
        }
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        jdbc.query("SELECT id, name FROM customer WHERE id IN (%s)"
                        .formatted(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))),
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }, ids.toArray());
        return names;
    }

    /** Имя клиента для истории документа; у заказа с площадки его нет вовсе. */
    private String customerName(Long customerId) {
        if (customerId == null) {
            return "«без клиента»";
        }
        List<String> found = jdbc.queryForList(
                "SELECT name FROM customer WHERE id = ?", String.class, customerId);
        return found.isEmpty() ? "клиента " + customerId : found.get(0);
    }

    /**
     * Продлевает срок резерва: клиент позвонил и попросил подержать ещё.
     *
     * <p>Склад это не двигает — товар и так отложен под того же клиента,
     * меняется только число, до которого его держат.
     *
     * <p>Пишется в историю документа отдельной строкой: через неделю
     * «почему деталь всё ещё лежит» спрашивают именно по ней, и ответом
     * должно быть «продлил Пётр третьего числа», а не молча изменившаяся
     * дата в карточке.
     */
    @Transactional
    public Deal extendReservation(Long dealId, Instant until, Long managerId) {
        Deal deal = requireDeal(dealId);
        deal.extendReservation(until);

        Deal saved = detachable(dealRepository.saveAndFlush(deal));
        log(saved, "RESERVATION_EXTENDED", "Срок резерва продлён до " + dayOf(until), managerId);
        return saved;
    }

    /**
     * Дата словами для истории документа: «8 сентября».
     *
     * <p>Тем же видом, что и в карточке продавца: одна и та же величина,
     * записанная в двух местах по-разному, читается как две разные.
     */
    private static String dayOf(Instant moment) {
        return java.time.format.DateTimeFormatter
                .ofPattern("d MMMM", java.util.Locale.of("ru"))
                .withZone(java.time.ZoneOffset.UTC)
                .format(moment);
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
