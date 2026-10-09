package ru.partsflow.intake;

import ru.partsflow.shared.NotFound;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.catalog.PartName;
import ru.partsflow.catalog.PartNameService;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.Part;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.PartRepository;
import ru.partsflow.inventory.PartTitleGenerator;
import ru.partsflow.inventory.QualityGrade;
import ru.partsflow.inventory.StockDocument;
import ru.partsflow.inventory.StockDocumentService;
import ru.partsflow.inventory.VerticalSide;
import ru.partsflow.platform.outbox.DomainEvent;
import ru.partsflow.platform.outbox.DomainEventPublisher;
import ru.partsflow.platform.outbox.EventPayloads;
import ru.partsflow.platform.outbox.contract.PartEvent;
import ru.partsflow.sales.PreorderService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Приёмка: от контейнера до детали на полке.
 *
 * <p>Сценарий целиком: пришла поставка → в ней машины и контрактные детали →
 * с машины снимают запчасть → заводят карточку → кладут в ячейку. Всё это
 * одна цепочка, и рвётся она в предсказуемом месте — на наименовании: приёмщик
 * пишет «телевизор», а в справочнике такого нет.
 *
 * <p><b>Несопоставленное наименование приёмку не останавливает.</b> На складе
 * стоит человек с деталью в руках; отказать ему значит получить деталь
 * без карточки, то есть невидимую для продажи. Наименование заводится как есть
 * и попадает в список нераспознанных, а разгребают его вечером.
 */
@Service
public class IntakeService {

    private final SupplyRepository supplies;
    private final DonorRepository donors;
    private final PartRepository parts;
    private final PartNameService partNames;
    private final StockDocumentService documents;
    private final DonorVehicleResolver vehicles;
    private final PartTitleGenerator titleGenerator;
    private final DomainEventPublisher eventPublisher;
    private final PreorderService preorders;
    private final JdbcTemplate jdbc;

    public IntakeService(SupplyRepository supplies,
                         DonorRepository donors,
                         PartRepository parts,
                         PartNameService partNames,
                         StockDocumentService documents,
                         DonorVehicleResolver vehicles,
                         PartTitleGenerator titleGenerator,
                         DomainEventPublisher eventPublisher,
                         PreorderService preorders,
                         JdbcTemplate jdbc) {
        this.preorders = preorders;
        this.supplies = supplies;
        this.donors = donors;
        this.parts = parts;
        this.partNames = partNames;
        this.documents = documents;
        this.vehicles = vehicles;
        this.titleGenerator = titleGenerator;
        this.eventPublisher = eventPublisher;
        this.jdbc = jdbc;
    }

    // ---------- поставки ----------

    /**
     * Регистрирует поставку или возвращает уже существующую.
     *
     * <p>Повторный вызов не создаёт вторую: номер контейнера уникален в паре
     * с видом, и приёмщик, заводящий его второй раз, обычно просто не нашёл
     * первый.
     */
    @Transactional
    public Supply registerSupply(Supply.SupplyKind kind, String number,
                                 String supplierName, Long authorId) {
        Supply.SupplyKind actualKind = kind == null ? Supply.SupplyKind.CONTAINER : kind;
        return supplies.findByKindAndNumber(actualKind, number.strip())
                .orElseGet(() -> {
                    Supply supply = new Supply(actualKind, number);
                    supply.setSupplierName(supplierName);
                    supply.setCreatedBy(authorId);
                    return supplies.saveAndFlush(supply);
                });
    }

    @Transactional
    public Supply markSupplyArrived(Long supplyId, LocalDate when) {
        Supply supply = requireSupply(supplyId);
        supply.markArrived(when);
        return supplies.saveAndFlush(supply);
    }

    // ---------- доноры ----------

    /** Заводит машину и привязывает её к поставке, если та указана. */
    @Transactional
    public Donor registerDonor(Donor donor, Long supplyId, Long authorId) {
        if (supplyId != null) {
            requireSupply(supplyId);
            donor.arrivedWith(supplyId);
        }
        if (donor.getGenerationId() == null) {
            donor.setGenerationId(vehicles.generationFor(donor.getModelId(), donor.getYear()));
        }
        donor.setCreatedBy(authorId);
        return donors.saveAndFlush(donor);
    }

    /** Меняет локацию машины в логистической цепочке. Стадия разбора не двигается. */
    @Transactional
    public Donor moveDonor(Long donorId, String location) {
        Donor donor = requireDonor(donorId);
        donor.moveTo(location);
        return donors.saveAndFlush(donor);
    }

    @Transactional
    public Donor startDismantling(Long donorId) {
        Donor donor = requireDonor(donorId);
        donor.startDismantling();
        return donors.saveAndFlush(donor);
    }

    // ---------- детали ----------

    /**
     * Принимает партию деталей одним складским документом.
     *
     * <p>Документ, а не набор отдельных движений: кладовщик собирает
     * поступление списком и проводит целиком. Черновик остаток не двигает —
     * это лист, с которым ходят по складу.
     *
     * @param donorId  машина, с которой снято; {@code null} для контрактных
     *                 деталей, приехавших контейнером напрямую
     * @param supplyId поставка — есть всегда, в отличие от донора
     */
    @Transactional
    public Receipt receive(Long warehouseId, Long supplyId, Long donorId,
                           List<ItemRequest> items, Long authorId, String requestId) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Приёмка без позиций не имеет смысла");
        }

        // Повтор офлайн-очереди: телефон отправил партию, ответ не дошёл,
        // очередь повторила. Возвращаем то, что получилось в первый раз,
        // а не заводим вторую партию.
        Receipt alreadyDone = replayOf(requestId);
        if (alreadyDone != null) {
            return alreadyDone;
        }

        requireWarehouse(warehouseId);
        if (supplyId != null) {
            requireSupply(supplyId);
        }
        Donor donor = donorId == null ? null : requireDonor(donorId);

        // Заведённые заранее (ожидаемые по поставке) принимаются той же
        // позицией, а не новой: вторая карточка той же детали ломает
        // окупаемость машины и историю, а объявление уводит покупателя на
        // списанную. Проверка раскладки по складам стоит ДО записи документа:
        // отказ виден приёмщику в ту же минуту, а не сверке следующей ночью.
        Map<Long, Part> accepted = acceptExpected(items, warehouseId, supplyId);

        StockDocument document = documents.save(StockDocument.intake(warehouseId, supplyId));
        document.setCreatedBy(authorId);
        document.setClientRequestId(requestId);

        // Части заголовка от машины достаются один раз на всю партию: с одного
        // донора снимают десятки деталей подряд.
        PartTitleGenerator.VehicleTitlePart vehicle = vehicles.resolve(donorId);

        List<Part> created = new ArrayList<>(items.size());
        List<Part> fresh = new ArrayList<>(items.size());
        for (ItemRequest item : items) {
            if (item.partId() != null) {
                Part existing = accepted.get(item.partId());
                document.addLine(existing.getId(), item.quantity(), item.cellId());
                created.add(existing);
                continue;
            }
            PartName partName = partNames.resolve(item.rawName(), authorId);
            Part part = buildPart(item, partName, vehicle, donor, supplyId, authorId);

            Part savedPart = parts.saveAndFlush(part);
            if (item.oemNumber() != null && !item.oemNumber().isBlank()) {
                primaryOem(savedPart.getId(), item.oemNumber());
            }
            document.addLine(savedPart.getId(), item.quantity(), item.cellId());
            created.add(savedPart);
            fresh.add(savedPart);
        }

        StockDocument saved = documents.save(document);
        StockDocument completed = documents.complete(saved.getId());

        // Деталь легла на склад — предзаказы под неё становятся обычным
        // резервом, на тот склад, где она лежит целиком.
        accepted.keySet().stream().sorted()
                .forEach(partId -> preorders.convertOnArrival(partId, authorId));

        // Событие после проведения: до него остатка нет, и площадка получила бы
        // деталь, которой на складе ещё не лежит. Только по новым карточкам:
        // принятая заведённая уже была объявлена, когда её заводили.
        fresh.forEach(this::publishCreated);
        return new Receipt(completed, created);
    }

    /**
     * Принимаемые позиции, заведённые заранее: проверяет, что их можно
     * принять, берёт под блокировку и проверяет раскладку предзаказов.
     *
     * <p>Блокировка строк {@code part} — та же, что берёт продавец,
     * откладывая ту же деталь предзаказом: приём и новый предзаказ
     * выстраиваются в очередь, и ни один не увидит список другого
     * наполовину. Порядок по номеру — один у всех, чтобы две приёмки с
     * общими позициями не ждали друг друга насмерть.
     */
    private Map<Long, Part> acceptExpected(List<ItemRequest> items, Long warehouseId,
                                           Long supplyId) {
        List<Long> ids = items.stream().map(ItemRequest::partId)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (supplyId == null) {
            throw new IllegalArgumentException(
                    "Заведённую заранее позицию принимают в её поставку — выберите поставку");
        }
        jdbc.queryForList("SELECT id FROM part WHERE id = ANY (?) ORDER BY id FOR UPDATE",
                Long.class, (Object) ids.toArray(Long[]::new));
        Map<Long, Part> found = new HashMap<>();
        for (Part part : parts.findAllById(ids)) {
            found.put(part.getId(), part);
        }
        Map<Long, BigDecimal> incoming = new HashMap<>();
        for (ItemRequest item : items) {
            if (item.partId() == null) {
                continue;
            }
            Part part = found.get(item.partId());
            if (part == null) {
                throw NotFound.PART.error(item.partId());
            }
            if (!part.isExpectedOrigin()) {
                throw new IllegalArgumentException(
                        "«%s» не заводили как ожидаемую по поставке — принимайте её обычной приёмкой"
                                .formatted(part.getTitle()));
            }
            if (!supplyId.equals(part.getSupplyId())) {
                throw new IllegalArgumentException(
                        "«%s» ожидается по другой поставке — выберите её".formatted(part.getTitle()));
            }
            incoming.merge(part.getId(), item.quantity(), BigDecimal::add);
        }
        incoming.forEach((partId, quantity) ->
                preorders.checkArrival(partId, warehouseId, quantity));
        return found;
    }

    // ---------- ожидаемый товар ----------

    /**
     * Заводит позицию по ожидаемой поставке: товар, которого ещё нет на складе.
     *
     * <p>Заводит владелец с компьютера (путь закрыт ролью в контроллере):
     * предзаказ по контейнеру в пути — кабинетная работа. Известно минимум —
     * вид детали, машина, цена, количество (решение владельца продукта
     * 30.09.2026, вариант «а»): за полминуты, и объявление выходит без снимков.
     *
     * <p>Позиция остаётся черновиком (склад ей ничего не откладывал) и
     * помечается ожидаемой; продаётся она предзаказом, а при приходе
     * принимается той же карточкой.
     *
     * @param donorId машина, с которой снято, — необязательна: контрактные
     *                агрегаты возят партиями без машин (ответ владельца
     *                9 октября 2026); без неё заголовок собирается из одного
     *                вида детали, а марка в объявлении берётся из применимости
     * @param requestId ключ запроса клиента (задача 0170): повтор с тем же
     *                  ключом возвращает первую позицию, а не заводит вторую;
     *                  пустой — без защиты от повтора
     */
    @Transactional
    public Part registerExpectedPart(Long supplyId, String rawName, Long donorId,
                                     BigDecimal quantity, BigDecimal price, Long authorId,
                                     String requestId) {
        Supply supply = requireSupply(supplyId);
        String key = requestId == null || requestId.isBlank() ? null : requestId.trim();
        if (key != null) {
            // Обычный повтор, пришедший после ответа. Одновременный проходит
            // эту проверку оба раза — его отбивает part_client_request_uk,
            // а ответ даёт replayExpectedAfterConflict.
            Part already = parts.findByClientRequestId(key).orElse(null);
            if (already != null) {
                return sameSupplyOrRefuse(already, supplyId);
            }
        }
        if (supply.getStatus() != Supply.SupplyStatus.EXPECTED
                && supply.getStatus() != Supply.SupplyStatus.IN_TRANSIT) {
            throw new IllegalStateException(
                    "Поставка уже приехала: ожидаемый товар по ней заводить поздно — "
                            + "примите деталь обычной приёмкой");
        }
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Не указан вид детали");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Количество должно быть больше нуля");
        }
        // Ноль в прайс не уезжает: «0 ₽» в объявлении — публичное обещание
        // отдать деталь даром. Лучше отказать здесь, чем завести позицию,
        // которая молча не попадёт в выгрузку.
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Цена должна быть больше нуля: с нулевой ценой объявление не уйдёт на площадку");
        }
        // Машина необязательна: контрактные агрегаты приезжают партиями без
        // машин (ответ владельца 9 октября 2026). Указанная — проверяется.
        Donor donor = donorId == null ? null : requireDonor(donorId);
        PartName partName = partNames.resolve(rawName, authorId);
        Part part = buildPart(ItemRequest.of(rawName, quantity, price, null), partName,
                vehicles.resolve(donorId), donor, supplyId, authorId);
        part.setQuantity(quantity);
        part.setExpectedOrigin(true);
        part.setClientRequestId(key);
        Part saved = parts.saveAndFlush(part);
        publishCreated(saved);
        return saved;
    }

    /**
     * Первая позиция по ключу запроса — ответ на одновременный повтор.
     *
     * <p>Второй из двух разом пришедших запросов упирается в уникальный ключ
     * и без этого метода получал бы 409 «Операция нарушает целостность
     * данных» на заведение, которое прошло: владелец нажимал второй раз
     * как раз потому, что первое нажатие не показало результата.
     *
     * <p>Читается новой транзакцией: та, где случилось нарушение, помечена
     * на откат, и запрос из неё не пройдёт. Пусто — ключа нет или позицию
     * создал кто-то иной, тогда исходная ошибка остаётся ошибкой.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                   readOnly = true)
    public Part replayExpectedAfterConflict(Long supplyId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        Part first = parts.findByClientRequestId(requestId.trim()).orElse(null);
        if (first == null || !java.util.Objects.equals(first.getSupplyId(), supplyId)) {
            return null;
        }
        return first;
    }

    private static Part sameSupplyOrRefuse(Part already, Long supplyId) {
        if (!java.util.Objects.equals(already.getSupplyId(), supplyId)) {
            throw new IllegalStateException(
                    "Этот запрос уже заведён по другой поставке — обновите страницу и повторите");
        }
        return already;
    }

    /**
     * Назначает или сдвигает ожидаемую дату поставки.
     *
     * <p>Срок резерва открытых предзаказов едет за датой и пишется в историю
     * каждой сделки; продавцу на сделке остаётся пометка. Подробности и
     * причина — {@link PreorderService#shiftExpected}.
     */
    @Transactional
    public Supply setExpectedOn(Long supplyId, LocalDate expectedOn, Long authorId) {
        Supply supply = requireSupply(supplyId);
        if (supply.getStatus() != Supply.SupplyStatus.EXPECTED
                && supply.getStatus() != Supply.SupplyStatus.IN_TRANSIT) {
            throw new IllegalStateException(
                    "Поставка уже приехала: ожидаемую дату менять поздно");
        }
        if (expectedOn == null) {
            throw new IllegalArgumentException("Не указана ожидаемая дата");
        }
        LocalDate was = supply.getExpectedOn();
        if (expectedOn.equals(was)) {
            return supply;
        }
        supply.setExpectedOn(expectedOn);
        Supply saved = supplies.saveAndFlush(supply);
        preorders.shiftExpected(supplyId, was, expectedOn, authorId);
        return saved;
    }

    /**
     * Ожидаемые позиции поставки: что владелец завёл и сколько ещё не принято.
     *
     * <p>Принятое считается по журналу движений, а не по остатку: остаток
     * падает от продаж, и «осталось принять» росло бы с каждой проданной
     * деталью.
     */
    @Transactional(readOnly = true)
    public List<ExpectedPart> expectedPartsOf(Long supplyId) {
        requireSupply(supplyId);
        return jdbc.query("""
                SELECT p.id, p.number, p.title, p.quantity, p.price,
                       COALESCE((SELECT sum(m.qty_delta) FROM stock_movement m
                                  WHERE m.part_id = p.id AND m.movement_type = 'INTAKE'), 0)
                           AS received,
                       COALESCE((SELECT sum(i.quantity) FROM deal_item i
                                  WHERE i.part_id = p.id AND i.status = 'PREORDER'), 0)
                           AS preordered
                  FROM part p
                 WHERE p.supply_id = ? AND p.expected_origin
                 ORDER BY p.id""",
                (rs, i) -> new ExpectedPart(rs.getLong("id"), rs.getLong("number"),
                        rs.getString("title"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("price"), rs.getBigDecimal("received"),
                        rs.getBigDecimal("preordered")),
                supplyId);
    }

    /**
     * @param quantity   обещано по поставке
     * @param received   принято на склад по этой позиции
     * @param preordered отложено под клиентов, но ещё не принято
     */
    public record ExpectedPart(long id, long number, String title, BigDecimal quantity,
                               BigDecimal price, BigDecimal received, BigDecimal preordered) {
    }

    private Part buildPart(ItemRequest item, PartName partName,
                           PartTitleGenerator.VehicleTitlePart vehicle,
                           Donor donor, Long supplyId, Long authorId) {

        // Заголовок собирается, а не приходит извне: набранное руками название
        // у каждого своё, и по такому складу нельзя ни искать, ни выгружать.
        // Вид детали берётся эталонный — в этом и смысл справочника.
        String title = titleGenerator.generate(
                partNames.displayNameOf(partName),
                vehicle,
                new PartTitleGenerator.Sides(item.sideFr(), item.sideLr(), item.sideUd()),
                item.condition(),
                item.oemNumber());

        Part part = new Part(partName.getCategoryId(), title, item.price());
        part.setPartNameId(partName.getId());
        part.setPartKindId(partName.getPartKindId());
        part.setCostPrice(item.costPrice());
        part.setSides(item.sideLr(), item.sideFr(), item.sideUd());
        part.setManufacturer(item.manufacturer());
        part.setMarking(item.marking());
        part.setNote(item.note());
        if (item.condition() != null) {
            part.setCondition(item.condition());
        }
        if (item.qualityGrade() != null) {
            part.setQualityGrade(item.qualityGrade());
        }
        if (donor != null) {
            part.setDonorId(donor.getId());
        }
        part.setSupplyId(supplyId);
        part.setCreatedBy(authorId);
        return part;
    }

    /**
     * Номер производителя кладётся основным.
     *
     * <p>Через SQL, а не сущностью: приведённый номер считает
     * {@code OemNumbers.normalize} — тем же методом, которым потом ищут,
     * и любое написание номера должно находить одну и ту же деталь.
     *
     * <p><b>Номер, от которого после приведения ничего не осталось, не
     * записывается.</b> Приёмщик пишет в это поле и «б/н», и «нет», а колонка
     * {@code normalized} объявлена {@code NOT NULL}: строка ушла бы отказом
     * базы, то есть пятисоткой на приёмке — а её офлайн-очередь повторяет
     * вечно. Деталь при этом заводится, просто без номера.
     */
    private void primaryOem(Long partId, String rawNumber) {
        String normalized = ru.partsflow.catalog.OemNumbers.normalize(rawNumber);
        if (normalized == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                VALUES (?, ?, ?, true)
                ON CONFLICT DO NOTHING""",
                partId, rawNumber.strip(), normalized);
    }

    private void publishCreated(Part part) {
        eventPublisher.publish(DomainEvent.of(
                "part", part.getId(), "part.created.v1", payloadOf(part)));
    }

    private byte[] payloadOf(Part part) {
        return EventPayloads.write(new PartEvent(part.getId(), part.getPublicCode(),
                part.getTitle(), part.getPrice(), String.valueOf(part.getStatus())));
    }

    /**
     * Результат уже выполненной приёмки по тому же ключу запроса.
     *
     * <p>Возвращается именно результат, а не ошибка «уже сделано»: очередь
     * не отличает «получилось только что» от «получилось в прошлый раз»,
     * ей нужен ответ, чтобы удалить запись и идти дальше.
     */
    /**
     * Повтор, случившийся одновременно с первым запросом.
     *
     * <p>Проверка «нет ли уже такого» ловит обычный повтор — тот, что пришёл
     * после ответа. А два повтора в один момент проходят её оба: между
     * чтением и вставкой второй ещё ничего не видит. Дубля при этом
     * не появляется — его отбивает уникальный индекс, — но наружу летел
     * 409 «Операция нарушает целостность данных», то есть **ошибка на
     * успешную приёмку**. Офлайн-очередь читает 409 как отказ по существу
     * и уводит запись в «требует внимания»: приёмщик видит красное там,
     * где деталь уже на складе, и заводит её второй раз руками.
     *
     * <p>Читается новой транзакцией: та, в которой случилось нарушение,
     * помечена на откат, и запрос из неё не пройдёт.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                   readOnly = true)
    public Receipt replayAfterConflict(String requestId) {
        return replayOf(requestId);
    }

    private Receipt replayOf(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return documents.findByClientRequestId(requestId)
                .map(document -> new Receipt(document, partsOf(document)))
                .orElse(null);
    }

    private List<Part> partsOf(StockDocument document) {
        List<Long> partIds = document.getLines().stream()
                .map(ru.partsflow.inventory.StockDocumentLine::getPartId)
                .toList();
        return parts.findAllById(partIds);
    }

    /**
     * Склад обязан существовать, и сказать об этом надо словами.
     *
     * <p>Поставка и машина проверялись, склад — нет: он доезжал до внешнего
     * ключа и возвращался как «Операция нарушает целостность данных».
     * Приёмщик по такому ответу идёт искать поломку сервера, а офлайн-очередь
     * читает 409 как отказ по существу и уводит партию в «требует внимания» —
     * то есть работа смены останавливается на сообщении, из которого
     * не понять, что делать.
     *
     * <p>Случай не выдуманный: склад могли выключить, пока телефон был
     * без связи, а в теле записи очереди лежит его прежний номер.
     */
    private void requireWarehouse(Long warehouseId) {
        if (warehouseId == null) {
            throw new IllegalArgumentException("Не указан склад приёмки");
        }
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM warehouse WHERE id = ?", Integer.class, warehouseId);
        if (found == null || found == 0) {
            throw NotFound.WAREHOUSE.error(warehouseId);
        }
    }

    private Supply requireSupply(Long supplyId) {
        return supplies.findById(supplyId).orElseThrow(
                () -> NotFound.SUPPLY.error(supplyId));
    }

    private Donor requireDonor(Long donorId) {
        return donors.findById(donorId).orElseThrow(
                () -> NotFound.DONOR.error(donorId));
    }

    /**
     * Позиция приёмки.
     *
     * <p>Наименования карточки здесь нет намеренно: его собирает
     * {@link PartTitleGenerator} из вида детали, машины и сторон. Приёмщик
     * выбирает атрибуты, а не пишет заголовок.
     *
     * @param rawName написание приёмщика — оно попадёт в справочник наименований
     *                как есть и там будет сопоставлено с эталоном
     */
    public record ItemRequest(String rawName,
                              BigDecimal quantity,
                              BigDecimal price,
                              BigDecimal costPrice,
                              Long cellId,
                              LateralSide sideLr,
                              LongitudinalSide sideFr,
                              VerticalSide sideUd,
                              PartCondition condition,
                              QualityGrade qualityGrade,
                              String manufacturer,
                              String oemNumber,
                              String marking,
                              String note,
                              Long partId) {

        /** Прежняя форма без {@code partId}: новая карточка. */
        public ItemRequest(String rawName, BigDecimal quantity, BigDecimal price,
                           BigDecimal costPrice, Long cellId, LateralSide sideLr,
                           LongitudinalSide sideFr, VerticalSide sideUd, PartCondition condition,
                           QualityGrade qualityGrade, String manufacturer, String oemNumber,
                           String marking, String note) {
            this(rawName, quantity, price, costPrice, cellId, sideLr, sideFr, sideUd, condition,
                    qualityGrade, manufacturer, oemNumber, marking, note, null);
        }

        /** Минимальный случай: вид детали, количество и цена. */
        public static ItemRequest of(String rawName, BigDecimal quantity, BigDecimal price,
                                    Long cellId) {
            return new ItemRequest(rawName, quantity, price, null, cellId,
                    null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Результат приёмки: проведённый документ и заведённые карточки.
     *
     * <p><b>Порядок карточек повторяет порядок позиций запроса.</b> Это контракт,
     * а не совпадение: телефон привязывает снятые фотографии к деталям по номеру
     * позиции — другого способа у него нет, идентификаторы выдаёт сервер.
     * Перестановка здесь означает снимки, уехавшие к чужим деталям, и заметят
     * это не скоро. Стережёт {@code partsFollowRequestOrder}.
     */
    public record Receipt(StockDocument document, List<Part> parts) {
    }
}
