package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.partsflow.intake.DonorDirectory;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST для запчастей. Арендатор берётся из контекста (см. {@code TenantFilter}),
 * в путях и телах запросов его нет — иначе рано или поздно кто-то подставит чужой.
 */
@RestController
@RequestMapping("/api/parts")
public class PartController {

    private final PartService partService;
    private final PartHistoryService history;
    private final DonorDirectory donors;
    private final PartIdentity identity;

    public PartController(PartService partService, PartHistoryService history,
                          DonorDirectory donors, PartIdentity identity) {
        this.partService = partService;
        this.history = history;
        this.donors = donors;
        this.identity = identity;
    }

    /**
     * История позиции: правки и движения остатка.
     *
     * <p>Открыта всем вошедшим, а не только владельцу: «куда делась деталь»
     * спрашивает кладовщик, и отправлять его за ответом к владельцу — это
     * ответ через сутки. Деньги при этом отделены: себестоимость
     * и минимальная цена в ленту правок попадают только владельцу
     * и менеджеру, и не «скрываются на экране», а не уезжают с сервера.
     * Стережёт {@code PartHistoryHttpTest}.
     */
    @GetMapping("/{id}/history")
    public PartHistoryService.History history(@PathVariable long id) {
        var principal = CurrentUser.require();
        boolean money = principal.actsAsOwner() || "MANAGER".equals(principal.role());
        return history.of(id, money);
    }

    /**
     * Машина, с которой снята позиция.
     *
     * <p>Карточка сообщала «Донор задан» — отметку о том, что данные есть,
     * вместо самих данных. А продавец по телефону отвечает именно ими:
     * подойдёт ли деталь, решает не марка, а руль, коробка и привод машины,
     * с которой её сняли.
     *
     * <p>Запрос отдаёт {@code intake}: машины принадлежат ему. Контроллер
     * здесь потому, что спрашивает карточка позиции.
     */
    @GetMapping("/{id}/donor")
    public DonorDirectory.Card donor(@PathVariable long id) {
        return donors.cardByPart(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/search")
    public List<PartView> search(@RequestParam("q") String query,
                                 @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return partService.search(query, limit).stream().map(PartView::of).toList();
    }

    /**
     * Поиск для продавца: свободный остаток по складам.
     *
     * <p>Отдельно от {@code /search}: тот отдаёт карточку с общим остатком,
     * а продавать можно только то, что не обещано другому клиенту.
     */
    /**
     * @param brand      марка машины, с которой снята деталь
     * @param sort       {@code price} или {@code intake}; пусто — по совпадению
     * @param desc       обратный порядок выбранной сортировки
     */
    // Отбор параметрами запроса, а не телом: это по-прежнему чтение,
    // и ссылка с готовым отбором обязана открываться повторно.
    @GetMapping("/stock")
    public PartService.StockSearch stock(@RequestParam("q") String query,
                                         @RequestParam(value = "limit", defaultValue = "50") int limit,
                                         @RequestParam(required = false) String brand,
                                         @RequestParam(required = false) String model,
                                         @RequestParam(required = false) Integer yearFrom,
                                         @RequestParam(required = false) Integer yearTo,
                                         @RequestParam(required = false) String side,
                                         @RequestParam(required = false) String position,
                                         @RequestParam(required = false) Long warehouseId,
                                         @RequestParam(required = false) String grade,
                                         @RequestParam(required = false) BigDecimal priceFrom,
                                         @RequestParam(required = false) BigDecimal priceTo,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "false") boolean desc) {
        // Вместе с числом найденного: список обрезан на полусотне, и экран
        // обязан об этом сказать. Продавец, глядя на обрезанный список,
        // отвечает покупателю «нет такого» с той же уверенностью, что и
        // на пустом, — только тут он ещё и думает, что посмотрел всё.
        //
        // Отбор уходит в тот же запрос: сузив показанные пятьдесят строк,
        // «фара + Nissan» не нашла бы ничего при полной полке ниссановских
        // фар — именно потому, что режется всё до того, как продавец успел
        // назвать марку.
        return partService.searchAvailable(query, limit, new PartService.StockFilter(
                brand, model, yearFrom, yearTo, side, position, warehouseId, grade,
                priceFrom, priceTo, sort, desc));
    }

    @GetMapping("/by-oem/{number}")
    public List<PartView> byOem(@PathVariable String number) {
        return partService.findByOem(number).stream().map(PartView::of).toList();
    }

    /**
     * Включить или выключить выгрузку на площадки.
     *
     * <p>Списком: после импорта склада исключений набирается несколько сотен.
     * По умолчанию принятая деталь публикуется — на разборке её для того
     * и снимают, а «не выгружать» это отметка руками для битых и отложенных
     * под заказ.
     */
    // Роль та же, что у правки списком и у карточки: «Выгружать» правят три
    // пути, и разойдясь, они дают изменение, которое одному запрещено,
    // а другим разрешено. Продавцу это было можно только здесь — то есть
    // он мог снять с площадки хоть весь склад, а через форму и через отбор
    // не мог тронуть ни одной позиции. Снятое объявление уносит с собой
    // накопленные просмотры, и заметно это через дни по пустому прайсу.
    @PostMapping("/publication")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public PublicationResult setPublication(@Valid @RequestBody PublicationRequest request) {
        return new PublicationResult(
                partService.setPublished(request.partIds(), request.published()));
    }

    /**
     * Правка нескольких карточек разом.
     *
     * <p>Меняется только то, что владелец тронул: у выбранных позиций заметки
     * разные, и «пустое значит очистить» стёрло бы их все одним нажатием.
     * Поэтому карта «поле → значение», а не запись со всеми полями, как
     * в правке одной карточки.
     *
     * <p>Роль та же, что у правки одной: здесь цена и себестоимость.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public BulkResult updateAll(@Valid @RequestBody BulkRequest request) {
        PartService.BulkOutcome outcome = partService.updateAll(request.partIds(),
                request.changes(), request.operations(), CurrentUser.memberId());
        return BulkResult.of(outcome);
    }

    /**
     * @param changes    только тронутые поля: непереданное не меняется
     * @param operations что сделать с денежным полем — процент, сумма,
     *                   округление; непереданное означает «заменить»,
     *                   то есть прежнее поведение
     */
    public record BulkRequest(@NotEmpty List<Long> partIds,
                              @NotEmpty java.util.Map<String, Object> changes,
                              java.util.Map<String, PriceOperation> operations) {
    }

    /**
     * @param skipped у скольких позиций поле было пустым: считать процент
     *                не от чего, и они не тронуты. Молчать об этом нельзя —
     *                «изменено 40» читается как «сделано всем»
     * @param rejected сколько позиций не прошло операцию вовсе — у них она
     *                 дала бы минус или ноль. Остальные при этом изменены:
     *                 одна дешёвая деталь не отменяет переоценку склада
     * @param rejectedCodes первые из них поимённо — по публичному коду
     *                      позицию видно на витрине
     * @param rejectedReason  чем ответил расчёт на первой такой позиции:
     *                        число без причины не говорит, что делать
     */
    public record BulkResult(int changed, int skipped, int rejected,
                             java.util.List<String> rejectedCodes, String rejectedReason) {

        static BulkResult of(PartService.BulkOutcome outcome) {
            return new BulkResult(outcome.changed(), outcome.skipped(), outcome.rejected(),
                    outcome.rejectedCodes(), outcome.rejectedReason());
        }
    }

    /**
     * Карточка для правки: все поля формы, включая те, которых нет на витрине.
     *
     * <p><b>Форма не собирается из витринной строки.</b> Себестоимости
     * и минимальной цены там нет и быть не должно — витрину читают все
     * вошедшие, включая продавца и кладовщика, а закупочная цена не их дело.
     * Собери форму из того, что отдаёт витрина, — и сохранение стёрло бы
     * себестоимость у каждой правленой позиции. А она снимком уходит в сделку
     * и в отчёт окупаемости, то есть терялась бы навсегда.
     */
    @GetMapping("/{id}/editable")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public UpdateRequest editable(@PathVariable Long id) {
        return UpdateRequest.of(partService.require(id), partService.dromCardOf(id),
                identity.of(id));
    }

    /**
     * Правка карточки товара.
     *
     * <p><b>Роль — владелец или менеджер.</b> Здесь цена, минимальная цена
     * и себестоимость: продавец, торгующийся с покупателем, не должен уметь
     * подвинуть себе нижнюю границу. Кладовщику править нечего — адрес полки
     * он меняет перемещением, а не карточкой.
     *
     * <p><b>Ключа нет — поле не трогается; ключ есть и пуст — очищается</b>
     * (задача 0307, решение владельца продукта 16.10.2026: «1-б»). До неё
     * приходила вся форма разом, и вторая вкладка, сохраняя заметку, откатывала
     * цену, которую успела поменять первая, — замерено на ПРОМ. Различие
     * «нет ключа» и «пусто» читается из самого тела ({@link EditRequest}):
     * Jackson отсутствующий ключ и явный {@code null} в записи не различает.
     * Вкладка, открытая до выкладки, шлёт форму целиком — все ключи есть,
     * и принимается это как раньше.
     *
     * <p><b>Рядом с изменённым экран шлёт то, что человек видел</b> ({@code seen}).
     * Не совпало с базой — поле успел изменить другой, и сервер не сохраняет
     * только его (задача 0313, ответ владельца продукта 17.10.2026: «не
     * сохраняются только конфликтные поля, а остальные сохраняются»): ответ 200,
     * а в {@code notSaved} — что не легло, словами формы ({@link PartEditConflicts}).
     * 409 при частичном успехе нельзя: любой клиент прочёл бы его как «ничего
     * не сохранено» и показал красное над уже записанным. Занято всё присланное —
     * записывать нечего, и ответ прежний: 409 с названием поля. Без {@code seen}
     * сверять не с чем, и правка принимается без проверки — так шлёт старая вкладка.
     */
    /**
     * <p><b>Замков на полях нет ни у одного, и у состояния тоже</b> (задача 0302,
     * решение владельца продукта 15.10.2026: «Клиент может править у себя
     * абсолютно всё… Никаких замков и запретов»). До неё состояние менял только
     * владелец (задача 0039), а номер производителя, наименование, стороны,
     * машина и идентификаторы не правились вовсе. Роль на самом методе осталась:
     * кто вообще правит карточку, решает владелец, назначая роли.
     *
     * <p>Занятый номер, код или штрихкод отвечает словами и называет позицию,
     * которая его держит, — и при одновременной правке тоже: проигравшего
     * отбивает уникальный индекс, и ответ перечитывается новой транзакцией.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public EditResult update(@PathVariable Long id, @Valid @RequestBody EditRequest request) {
        UpdateRequest fields = request.fields();
        try {
            return EditResult.of(partService.update(id, fields.toUpdate(request.sent(),
                    new PartService.EditBase(request.wanted(), request.seen())),
                    CurrentUser.memberId()));
        } catch (DataIntegrityViolationException taken) {
            identity.explainConflict(id, fields.identity(), fields.barcode(),
                    fields.legacyCode(), taken);
            throw taken;
        }
    }

    /**
     * Тело правки карточки вместе с тем, какие ключи в нём были (задача 0307).
     *
     * <p>Свой разбор, а не запись с полями-{@code Optional}: правило «нет ключа —
     * не трогать» нужно у полусотни полей, и обёртка у каждого читалась бы хуже
     * одного множества {@code sent}. Поля разбираются тем же {@code ObjectMapper},
     * что и прежде ({@code readTreeAsValue}), — и валидация, и ответ «запрос
     * не разобран» на строку вместо числа остаются прежними.
     *
     * @param fields поля формы — как раньше, отсутствующий ключ здесь {@code null}
     * @param sent   какие ключи пришли: только они и правятся
     * @param wanted тело без {@code seen} — с ним сравнивается текущее, чтобы
     *               одинаковая правка двух людей конфликтом не считалась
     * @param seen   что человек видел у изменённых полей; нет — сверки нет
     */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = EditRequest.Reader.class)
    public record EditRequest(@Valid @NotNull UpdateRequest fields,
                              java.util.Set<String> sent,
                              com.fasterxml.jackson.databind.JsonNode wanted,
                              com.fasterxml.jackson.databind.JsonNode seen) {

        public static final class Reader
                extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<EditRequest> {

            public Reader() {
                super(EditRequest.class);
            }

            @Override
            public EditRequest deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                           com.fasterxml.jackson.databind.DeserializationContext context)
                    throws java.io.IOException {
                com.fasterxml.jackson.databind.JsonNode tree = context.readTree(parser);
                if (!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode body)) {
                    throw com.fasterxml.jackson.databind.exc.MismatchedInputException.from(
                            parser, EditRequest.class, "тело правки — не объект");
                }
                com.fasterxml.jackson.databind.node.ObjectNode wanted = body.deepCopy();
                com.fasterxml.jackson.databind.JsonNode seen = wanted.remove("seen");
                UpdateRequest fields = context.readTreeAsValue(wanted, UpdateRequest.class);
                java.util.Set<String> sent = new java.util.HashSet<>();
                wanted.fieldNames().forEachRemaining(sent::add);
                return new EditRequest(fields, java.util.Set.copyOf(sent), wanted,
                        seen == null || seen.isNull() ? null : seen);
            }
        }
    }

    /**
     * Где позиция лежит: адрес полки по каждому складу.
     *
     * <p>Открыто всем вошедшим, как и остаток: «куда идти за деталью»
     * спрашивает и продавец по телефону, и кладовщик у стеллажа. Переставить
     * при этом может не всякий — см. {@link #changeCell}.
     *
     * <p>Своим запросом, а не строкой витрины: адрес у позиции на двух
     * складах свой на каждом, а строка витрины про склады знает только
     * остаток. Тем же запросом живёт и карточка колеса — она та же самая.
     */
    @GetMapping("/{id}/cells")
    public List<PartService.PartCell> cells(@PathVariable Long id) {
        return partService.cellsOf(id);
    }

    /**
     * Переставить позицию на другую полку того же склада.
     *
     * <p><b>Роль — владелец, менеджер или кладовщик</b>, та же, что
     * у перевозки между складами: деталь у кладовщика в руках, и перестановка
     * на полку — работа, а не расход. Отдельная точка входа, а не поле
     * в {@code PUT /api/parts/{id}}: там цена, минимальная цена
     * и себестоимость, и открывать их кладовщику ради адреса нельзя.
     *
     * <p>Склад указывается явно: у позиции на двух складах две полки,
     * и подставить «тот, который первый» значит поменять адрес там,
     * куда никто не подходил.
     */
    @PutMapping("/{id}/cell")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','STOREKEEPER')")
    public PartService.PartCell changeCell(@PathVariable Long id,
                                           @Valid @RequestBody CellRequest request) {
        return partService.changeCell(id, request.warehouseId(), request.cellId(),
                CurrentUser.memberId());
    }

    /**
     * @param cellId пусто — «без адреса»: у клиента без полок ячеек нет вовсе,
     *               и прочерк тут значит «не заведено», а не «не знаем»
     */
    public record CellRequest(@NotNull Long warehouseId, Long cellId) {
    }

    /**
     * Тело правки карточки.
     *
     * <p>С задачи 0302 здесь всё, что есть в форме: стороны, номер
     * производителя и машину позиции. Наименование в записи осталось — его
     * отдаёт {@code GET /editable}, — но с задачи 0336 оно производное
     * и руками не правится: другое присланное — отказ ({@code PartService.update}).
     *
     * <p>Пустое состояние означает «не трогать», а не «очистить». С задачи
     * 0322 позиция без состояния бывает (ожидаемая, «Не указано»), но снять
     * состояние правкой нельзя — снятое ушло бы из прайса вместе
     * с объявлением ({@code PartService.update}); выбрать его позиции
     * без состояния — можно.
     *
     * <p>Отсутствующий ключ с задачи 0307 — «не трогать» у всех полей: какие
     * ключи пришли, знает {@link EditRequest}, а не эта запись.
     */
    public record UpdateRequest(@PositiveOrZero BigDecimal price,
                                @PositiveOrZero BigDecimal minPrice,
                                @PositiveOrZero BigDecimal costPrice,
                                @PositiveOrZero BigDecimal installationPrice,
                                PartCondition condition,
                                QualityGrade qualityGrade,
                                String description,
                                String note,
                                String textBlock,
                                String videoUrl,
                                String marking,
                                String manufacturer,
                                String color,
                                String section,
                                String barcode,
                                @PositiveOrZero BigDecimal weightKg,
                                @PositiveOrZero Integer lengthMm,
                                @PositiveOrZero Integer widthMm,
                                @PositiveOrZero Integer heightMm,
                                @PositiveOrZero Integer packageLengthMm,
                                @PositiveOrZero Integer packageWidthMm,
                                @PositiveOrZero Integer packageHeightMm,
                                @PositiveOrZero BigDecimal packageWeightKg,
                                Long storageCellId,
                                // Объект, а не примитив (задача 0307): отсутствующий ключ
                                // был бы false, то есть снятием позиции с выгрузки.
                                Boolean published,
                                // Блоки карточки Дрома (задача 0285): гарантия, самовывоз,
                                // доставка и оплата — тексты клиента, хранятся дословно.
                                // В отличие от остальных текстов формы, у них два способа
                                // «пусто», и они разные: {@code null} (ключа нет вовсе) —
                                // не трогать, пустая строка — очистить. Иначе вкладка,
                                // открытая до выкладки этой задачи, при сохранении стёрла бы
                                // тексты клиента, о которых её форма не знает: Jackson
                                // отсутствующий ключ и явный null не различает. Экран шлёт
                                // всегда строку, пустую при очистке.
                                String warranty,
                                String pickup,
                                String deliveryPayment,
                                // Вид детали и раздел как у клиента (задача 0286): те же два
                                // способа «пусто», что у блоков выше.
                                String dromKind,
                                String dromSection,
                                // «Применимость» («Подходит») дословно (задача 0287): так же.
                                String applicability,
                                // «Характеристики» («Прочие поля» Дрома) дословно (задача 0292): так же.
                                String characteristics,
                                // «Может подойти» дословно (задача 0293): так же.
                                String mayFit,
                                // «Дата» объявления Дрома текстом (задача 0298): так же.
                                String adDate,
                                // Что сделать с ценой: «Изменить» (умолчание и прежнее
                                // поведение), процент, сумма, округление. При арифметике
                                // поле price несёт значение операции, а не новую цену, —
                                // поэтому @PositiveOrZero на нём верен и там.
                                PriceOperation priceOp,
                                // Задача 0302 — всё, что в форме стояло с замком. У всех
                                // два способа «пусто», как у блоков Дрома выше: null (ключа
                                // нет) — не трогать, пустая строка — очистить. Наименование
                                // и номер товара пустыми не бывают: пустое наименование —
                                // не трогать, пустой номер товара — отказ словами.
                                // Целым любой длины, а не long: номер за пределом long
                                // иначе отбил бы разбор тела словами «запрос не разобран»,
                                // а не причиной (задача 0303). Границу проверяет PartIdentity.
                                java.math.BigInteger number,
                                String code,
                                String title,
                                String sideLr,
                                String sideFr,
                                String authenticity,
                                String oem,
                                String crosses,
                                String legacyCode,
                                String dromAdNumber,
                                // Машина позиции — вписанное у неё руками; пусто —
                                // показывается донор или применимость.
                                String donorCode,
                                String brand,
                                String model,
                                String generation,
                                String body,
                                String engine,
                                String year,
                                String equipment) {

        static UpdateRequest of(Part part, PartDromCard card, PartIdentity.Values own) {
            return new UpdateRequest(part.getPrice(), part.getMinPrice(), part.getCostPrice(),
                    part.getInstallationPrice(), part.getCondition(),
                    part.getQualityGrade(), part.getDescription(),
                    part.getNote(), part.getTextBlock(), part.getVideoUrl(), part.getMarking(),
                    part.getManufacturer(), part.getColor(), part.getSection(), part.getBarcode(),
                    part.getWeightKg(), part.getLengthMm(), part.getWidthMm(), part.getHeightMm(),
                    part.getPackageLengthMm(), part.getPackageWidthMm(), part.getPackageHeightMm(),
                    part.getPackageWeightKg(), part.getStorageCellId(), part.isPublished(),
                    card == null ? null : card.getWarranty(),
                    card == null ? null : card.getPickup(),
                    card == null ? null : card.getDeliveryPayment(),
                    card == null ? null : card.getDromKind(),
                    card == null ? null : card.getDromSection(),
                    card == null ? null : card.getApplicability(),
                    card == null ? null : card.getCharacteristics(),
                    card == null ? null : card.getMayFit(),
                    card == null ? null : card.getAdDate(),
                    // Форма открывается на «Изменить»: самый частый случай —
                    // вписать новое число — остаётся одним движением.
                    PriceOperation.SET,
                    own.number() == null ? null : java.math.BigInteger.valueOf(own.number()),
                    own.code(), part.getTitle(),
                    part.getSideLr() == null ? null : part.getSideLr().name(),
                    part.getSideFr() == null ? null : part.getSideFr().name(),
                    own.authenticity(), own.oem(), own.crosses(), part.getLegacyCode(),
                    own.dromAdNumber(),
                    own.donorCode(), own.brand(), own.model(), own.generation(),
                    own.body(), own.engine(),
                    own.year() == null ? null : String.valueOf(own.year()),
                    own.equipment());
        }

        PartIdentity.Edit identity() {
            return new PartIdentity.Edit(number, code, authenticity, dromAdNumber, oem, crosses,
                    donorCode, brand, model, generation, body, engine, year, equipment);
        }

        PartService.PartUpdate toUpdate(java.util.Set<String> sent, PartService.EditBase base) {
            return new PartService.PartUpdate(price, minPrice, costPrice, installationPrice,
                    condition, qualityGrade, description, note, textBlock, videoUrl,
                    marking, manufacturer,
                    color, section, barcode, weightKg, lengthMm, widthMm, heightMm,
                    packageLengthMm, packageWidthMm, packageHeightMm, packageWeightKg,
                    storageCellId, published, warranty, pickup, deliveryPayment,
                    dromKind, dromSection, applicability, characteristics, mayFit, adDate, priceOp,
                    title, sideLr, sideFr, legacyCode, identity(), sent, base);
        }
    }

    /**
     * Ответ правки карточки: прежние поля {@link PartView} на своих местах —
     * вкладка, не знающая {@code notSaved}, читает ответ как раньше, — плюс
     * что не сохранилось (пусто — сохранено всё) и форма после записи: экран
     * обновляет ею снимок у сохранённого, чтобы следующее «Сохранить» не слало
     * его заново (задача 0313).
     */
    public record EditResult(
            Long id,
            String publicCode,
            String title,
            BigDecimal price,
            BigDecimal qtyOnHand,
            PartStatus status,
            List<PartService.NotSaved> notSaved,
            UpdateRequest form
    ) {
        static EditResult of(PartService.Edited edited) {
            Part part = edited.part();
            return new EditResult(part.getId(), part.getPublicCode(), part.getTitle(),
                    part.getPrice(), part.getQtyOnHand(), part.getStatus(),
                    edited.notSaved(), edited.form());
        }
    }

    public record PublicationRequest(@NotEmpty List<Long> partIds, boolean published) {
    }

    public record PublicationResult(int changed) {
    }

    public record PartView(
            Long id,
            String publicCode,
            String title,
            BigDecimal price,
            BigDecimal qtyOnHand,
            PartStatus status
    ) {
        static PartView of(Part part) {
            return new PartView(part.getId(), part.getPublicCode(), part.getTitle(),
                    part.getPrice(), part.getQtyOnHand(), part.getStatus());
        }
    }
}
