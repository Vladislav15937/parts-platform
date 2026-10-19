package ru.partsflow.inventory;

import ru.partsflow.shared.NotFound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.catalog.PartName;
import ru.partsflow.catalog.PartNameService;
import ru.partsflow.catalog.VehicleWords;
import ru.partsflow.platform.outbox.DomainEvent;
import ru.partsflow.platform.outbox.DomainEventPublisher;
import ru.partsflow.platform.outbox.EventPayloads;
import ru.partsflow.platform.outbox.contract.PartEvent;
import ru.partsflow.shared.PartNumberQuery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    private static final Logger log = LoggerFactory.getLogger(PartService.class);

    private final PartRepository partRepository;
    private final DomainEventPublisher eventPublisher;
    private final PartNameService partNames;
    private final PartChangeLog partChanges;
    private final JdbcTemplate jdbc;
    private final VehicleWords vehicleWords;
    /** Отказ называет деталь и склад теми же словами, что и перевозка. */
    private final StockNaming naming;
    /** В {@code part_stock} не пишет никто мимо журнала — перестановка тоже. */
    private final StockLedger ledger;
    /** Блоки карточки Дрома лежат в своей таблице и правятся вместе с формой (задача 0285). */
    private final jakarta.persistence.EntityManager entityManager;

    private final PartIdentity identity;

    /** Карточка формы правки — тем же разбором, что ответ наружу (сверка задачи 0307). */
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    /**
     * Журнал для правок позиции прямым SQL — сопоставление написания
     * и «Выгружать» пачкой (задача 0325): слушатель Hibernate их не видит.
     */
    private final ru.partsflow.platform.audit.AuditJournal journal;

    /** Пересборка названия из полей формулы (задача 0336). */
    private final PartTitles titles;

    public PartService(PartRepository partRepository, DomainEventPublisher eventPublisher,
                       PartNameService partNames, PartChangeLog partChanges, JdbcTemplate jdbc,
                       VehicleWords vehicleWords, StockNaming naming, StockLedger ledger,
                       jakarta.persistence.EntityManager entityManager,
                       PartIdentity identity, com.fasterxml.jackson.databind.ObjectMapper json,
                       ru.partsflow.platform.audit.AuditJournal journal, PartTitles titles) {
        this.titles = titles;
        this.json = json;
        this.journal = journal;
        this.partRepository = partRepository;
        this.eventPublisher = eventPublisher;
        this.partNames = partNames;
        this.partChanges = partChanges;
        this.jdbc = jdbc;
        this.vehicleWords = vehicleWords;
        this.naming = naming;
        this.ledger = ledger;
        this.entityManager = entityManager;
        this.identity = identity;
    }

    /**
     * Отмечает изменившимися все карточки под наименованием.
     *
     * <p>Списком, а не по одной: сопоставление правит сотни карточек одним
     * запросом, и вытаскивать их идентификаторы в приложение ради отметки
     * значило бы возить сотни чисел туда и обратно.
     */
    private void markByPartName(Long partNameId) {
        jdbc.update("""
                INSERT INTO part_change (part_id)
                SELECT id FROM part WHERE part_name_id = ?
                ON CONFLICT (part_id) DO UPDATE SET marked_at = now(), claimed_at = NULL""",
                partNameId);
    }

    /**
     * Сопоставляет написание с эталоном и доводит уже заведённые под ним карточки.
     *
     * <p><b>Одним действием, а не двумя.</b> Сопоставить наименование и оставить
     * склад как есть значит починить будущее и не починить прошлое: справочник
     * разгребают после импорта, когда все карточки уже созданы. Ради них экран
     * и существует — сопоставление, не меняющее ни одного заголовка, владельцу
     * незаметно.
     *
     * <p><b>Заголовок не трогается.</b> Раньше начало заголовка, совпавшее
     * с написанием, подменялось эталоном; с 0304 карточки получают только вид
     * детали и категорию, а с 0336 это и есть правило: вид в названии —
     * написание клиента, а не эталон (как у Bazon).
     *
     * @return сколько карточек доведено
     */
    @Transactional
    public MatchResult applyMatch(Long partNameId, Long partKindId) {
        String localSpelling = partNames.require(partNameId).getName();
        PartName matched = partNames.matchManually(partNameId, partKindId);
        String kindName = partNames.displayNameOf(matched);

        // Прежний вид — тем же запросом, под блокировкой строк: журнал обязан
        // назвать то «было», которое правка и переписала (задача 0325).
        List<KindChange> rows = jdbc.query("""
                UPDATE part p
                   SET category_id  = COALESCE(?, p.category_id),
                       part_kind_id = ?,
                       -- Момент правки раньше ставил триггер; теперь его
                       -- ставит тот, кто правит.
                       updated_at = now()
                  FROM (SELECT id, part_kind_id AS was FROM part
                         WHERE part_name_id = ? FOR UPDATE) old
                 WHERE p.id = old.id
                RETURNING p.id, old.was, p.part_kind_id AS now""",
                KIND_CHANGE, matched.getCategoryId(), matched.getPartKindId(), partNameId);
        int updated = rows.size();
        journalKinds(rows);

        // Категория и вид детали уехали в прайс — площадке надо сообщить.
        markByPartName(partNameId);

        log.info("Наименование «{}» сопоставлено с «{}», доведено карточек: {}",
                localSpelling, kindName, updated);
        return new MatchResult(matched, updated);
    }

    /**
     * Заголовок одной карточки под каждым из наименований — образец для экрана
     * разбора.
     *
     * <p>Сопоставление правит заголовки сотен карточек разом и назад
     * не откатывается. Разница между «тросик ручного тормоза» → «Трос ручника»
     * и «Знак аварийной остановки» → «Набор инструментов» видна только
     * в получившемся заголовке, и увидеть его надо до нажатия, а не после.
     * Кодом это не различить: «фара лев.» → «Фара» — тоже укорачивание,
     * и там оно верное.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> sampleTitles(List<Long> partNameIds) {
        if (partNameIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> samples = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (part_name_id) part_name_id, title
                  FROM part
                 WHERE part_name_id = ANY (?)
                 ORDER BY part_name_id, id """,
                rs -> {
                    samples.put(rs.getLong("part_name_id"), rs.getString("title"));
                },
                (Object) partNameIds.toArray(Long[]::new));
        return samples;
    }

    /** @param updated сколько карточек получили категорию и вид детали (заголовок не меняется, 0304) */
    public record MatchResult(PartName partName, int updated) {
    }

    /**
     * Доводит карточки по уже сопоставленным наименованиям — пакетом.
     *
     * <p>Нужно после переноса из чужой системы: карточки там создаются раньше,
     * чем наименования сопоставляются с эталонами, и категория у всего склада
     * остаётся заглушкой «Не разобрано». Прогон на чистой ячейке показал это
     * во всей красе: наименования распознаны, а двести карточек по-прежнему
     * без категории.
     *
     * <p>Заголовок не трогается, как и в {@link #applyMatch} (задача 0304):
     * доводятся категория и вид детали, название меняет только человек.
     *
     * @return сколько карточек доведено
     */
    @Transactional
    public int applyMatchedNames() {
        return applyMatchedNames(false);
    }

    /**
     * То же по нажатию «Пересопоставить по справочнику» — с записью в журнал
     * по каждой доведённой позиции (задача 0325). Перенос из Bazon зовёт
     * {@link #applyMatchedNames()} без журнала: разовое дозаполнение мимо
     * интерфейса владелец журналом не требует, а это десятки тысяч строк.
     */
    @Transactional
    public int rematchCards() {
        return applyMatchedNames(true);
    }

    private int applyMatchedNames(boolean journaled) {
        // Отметки об изменении здесь нет намеренно: это доводка после
        // переезда, за раз она правит десятки тысяч карточек, и площадка
        // узнает о них полным прайсом. Смотри PartChangeLog.
        List<KindChange> rows = jdbc.query("""
                WITH old AS (
                    SELECT p.id, p.part_kind_id AS was
                      FROM part p JOIN part_name pn ON pn.id = p.part_name_id
                     WHERE pn.part_kind_id IS NOT NULL
                       AND p.part_kind_id IS DISTINCT FROM pn.part_kind_id
                       FOR UPDATE OF p)
                UPDATE part p
                   SET category_id = pn.category_id,
                       part_kind_id = pn.part_kind_id,
                       updated_at = now()
                  FROM part_name pn, old
                 WHERE p.id = old.id AND pn.id = p.part_name_id
                RETURNING p.id, old.was, p.part_kind_id AS now""", KIND_CHANGE);
        if (journaled) {
            journalKinds(rows);
        }
        return rows.size();
    }

    /** Позиция, у которой сопоставление поменяло вид детали: было → стало. */
    private record KindChange(long partId, Long was, Long now) {
    }

    private static final org.springframework.jdbc.core.RowMapper<KindChange> KIND_CHANGE =
            (rs, i) -> new KindChange(rs.getLong("id"), rs.getObject("was", Long.class),
                    rs.getObject("now", Long.class));

    /**
     * Запись в журнал по позиции на каждую, у которой вид детали стал другим
     * (задача 0325). Категория в журнал не идёт: её журнал не называет
     * ({@code AuditedColumns.PART}), и человек видит её через вид детали.
     */
    private void journalKinds(List<KindChange> rows) {
        Map<Long, ru.partsflow.platform.audit.AuditJournal.Change> changes =
                new java.util.LinkedHashMap<>();
        for (KindChange row : rows) {
            if (java.util.Objects.equals(row.was(), row.now())) {
                continue;
            }
            Map<String, Object> was = new HashMap<>();
            Map<String, Object> now = new HashMap<>();
            was.put("part_kind_id", row.was());
            now.put("part_kind_id", row.now());
            changes.put(row.partId(), new ru.partsflow.platform.audit.AuditJournal.Change(was, now));
        }
        journal.changedEach("part", changes);
    }

    @Transactional
    public Part changePrice(Long partId, BigDecimal newPrice, Long changedBy) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> NotFound.PART.error(partId));

        if (newPrice.compareTo(part.getPrice() == null ? BigDecimal.ZERO : part.getPrice()) == 0) {
            return part;
        }
        part.changePrice(newPrice, changedBy);
        partChanges.changed(partId);

        // Ключ партиции включает id запчасти, поэтому события по одной детали
        // не переставятся местами и на площадку не уедет устаревшая цена.
        eventPublisher.publish(DomainEvent.of(
                "part", part.getId(), "part.price_changed.v1", payloadOf(part)));

        return part;
    }

    @Transactional(readOnly = true)
    public Part require(Long partId) {
        return partRepository.findById(partId)
                .orElseThrow(() -> NotFound.PART.error(partId));
    }

    /**
     * Правка карточки товара.
     *
     * <p><b>До этого править карточку было нечем.</b> {@code changePrice} был
     * написан и даже публиковал событие для площадок, но снаружи его
     * не вызывал никто: ни эндпоинта, ни экрана. То есть цену принятой детали
     * владелец изменить не мог вовсе — а на разборке это ежедневная работа,
     * от «повисло полгода, снижаем» до опечатки в приёмке.
     *
     * <p><b>Правится только пришедшее</b> (задача 0307; до неё — «форма, а не
     * патч»). Ключа нет — поле не трогается ({@link PartUpdate#has}); ключ есть
     * и {@code null} — очищено. Вторая вкладка, сохранявшая форму целиком,
     * откатывала чужую цену до увиденной при открытии. Увиденное, разошедшееся
     * с базой, снимает с записи только это поле (задача 0313, ответ владельца
     * продукта 17.10.2026: «не сохраняются только конфликтные поля, а остальные
     * сохраняются»); занято всё присланное — отказ целиком
     * ({@link PartEditConflicts}).
     *
     * <p><b>Наименование руками не правится — оно производное</b> (задача 0336,
     * решение владельца продукта 19.10.2026, «как в Bazon»): вид, машина, год,
     * положение, состояние и основной номер — правка любого из них
     * пересобирает название ({@link PartTitles}). Присланное другое
     * наименование — отказ словами: «сохранено» над молча выброшенным полем
     * было бы неправдой. Записи 0302 и 0304 («название меняет только
     * человек») с этого дня — история.
     *
     * <p><b>Состояние правится</b> (задача 0039, решение владельца продукта
     * от 8 сентября 2026) — и с 0336 меняет пометку «(б/у)» в названии.
     *
     * <p>Событие о смене цены уходит только когда цена действительно другая:
     * площадке незачем дельта на правку заметки, а {@code price_changed_at}
     * обязан означать «цену меняли», иначе по нему нельзя искать.
     *
     * <p><b>Цена приходит операцией, а не только готовым числом.</b>
     * {@code priceOp} — «Изменить» (умолчание, прежнее поведение),
     * «Увеличить/Уменьшить на %», «Увеличить/Уменьшить на сумму»,
     * «Округлить до»; при арифметике поле {@code price} несёт не новую цену,
     * а значение операции — процент, сумму или шаг. Считает
     * {@link PriceOperation}, а не браузер: тот же расчёт нужен правке
     * списком, и две копии разошлись бы на первом округлении.
     */
    /**
     * Ключи формы, которые пишет {@link PartIdentity} и читает формула названия
     * (задача 0336): номер производителя и машина позиции. Положение и состояние
     * сверяются у сущности.
     */
    private static final List<String> TITLE_KEYS =
            List.of("oem", "brand", "model", "body", "engine", "year");

    /** Отказ на присланное наименование (задача 0336) — его же видит форма правки. */
    static final String TITLE_IS_DERIVED = "Наименование собирается само — из вида детали, "
            + "машины, года, положения, состояния и номера производителя — и руками не правится. "
            + "Исправьте эти поля, и наименование поменяется вместе с ними.";

    @Transactional
    public Edited update(Long partId, PartUpdate request, Long authorId) {
        // Под блокировкой строки (задача 0307): две правки одной карточки идут
        // по очереди, и вторая сверяет увиденное уже с записанным первой.
        // Без неё обе прочли бы одно и то же и обе прошли бы сверку — ровно
        // тот read-modify-write, от которого сверка и заведена.
        Part part = partRepository.findByIdForUpdate(partId)
                .orElseThrow(() -> NotFound.PART.error(partId));

        // Наименование производное (задача 0336): другое присланное — отказ
        // словами до любой записи. Совпадающее с хранимым — не правка: так шлёт
        // форму вкладка, открытая до выкладки, и отказывать ей не в чем.
        if (request.title() != null && !request.title().isBlank()
                && !request.title().strip().equals(part.getTitle())) {
            log.warn("Правка позиции {} отбита: прислано наименование", partId);
            throw new IllegalArgumentException(TITLE_IS_DERIVED);
        }

        // Номер, который нельзя сохранить (задача 0312), — отказ словами раньше
        // сверки чужой правки: он про сам запрос, а не про то, что успел другой.
        PartIdentity.requireStorable(request.identity());
        PartUpdate update = request;
        List<NotSaved> notSaved = List.of();
        // Сверка — до всякой записи и под той же блокировкой (задача 0313):
        // занятое поле исключается из присваиваний, а не откатывается после.
        // Так история, журнал и событие цены для площадок видят только то, что
        // прошло сверку, — откат после записи оставил бы в них правку, которой нет.
        if (request.base() != null && request.base().seen() != null) {
            PriceOperation askedOp = request.priceOp() == null ? PriceOperation.SET : request.priceOp();
            List<String> taken = PartEditConflicts.conflicting(formOf(part),
                    request.base().wanted(), request.base().seen(), askedOp.arithmetic());
            if (!taken.isEmpty()) {
                notSaved = PartEditConflicts.notSaved(taken, request.base().wanted());
                update = withoutTaken(request, notSaved);
                if (update == null) {
                    // Записывать нечего — тот же отказ, что и до 0313: 409 честнее,
                    // чем «сохранено» над пустой записью.
                    log.warn("Правка позиции {} отбита: поля {} уже изменил другой", partId, taken);
                    throw new IllegalStateException(PartEditConflicts.message(
                            taken.stream().map(PartEditConflicts::label).toList()));
                }
                log.warn("Правка позиции {}: поля {} уже изменил другой, сохранено остальное",
                        partId, taken);
            }
        }
        PriceOperation priceOp = update.priceOp() == null ? PriceOperation.SET : update.priceOp();

        if (!priceOp.arithmetic() && update.price() != null && update.price().signum() < 0) {
            throw new IllegalArgumentException("Цена не может быть отрицательной");
        }

        // Отказ называет позицию так, как её зовёт человек: публичный код
        // виден на витрине и на этикетке, внутренний номер — нигде.
        // Пустая цена при арифметике — отказ словами, а не придуманный ноль:
        // «поднять на 5 %» от ничего превратилось бы в «поставить ноль»,
        // то есть в снятое объявление. Здесь правят одну карточку, и человек
        // выбрал операцию именно ей — молчать нельзя.
        // Цену трогает её ключ — или арифметика без него: выбранная операция
        // это просьба о цене, и «округлить» без шага обязано отказать словами.
        boolean priceSent = update.has("price") || (update.has("priceOp") && priceOp.arithmetic());
        BigDecimal wanted = !priceSent ? null : priceOp.apply(part.getPrice(),
                update.price(), MoneyField.PRICE, part.getPublicCode());
        boolean priceChanged = wanted != null
                && (part.getPrice() == null || part.getPrice().compareTo(wanted) != 0);
        // Поля формулы названия до правки (задача 0336): пересобирается оно,
        // только если одно из них стало другим.
        PartCondition conditionWas = part.getCondition();
        LateralSide lrWas = part.getSideLr();
        LongitudinalSide frWas = part.getSideFr();
        if (priceChanged) {
            part.changePrice(wanted, authorId);
        }

        // Стороны: null — ключа не было, не трогать; пустое — снять сторону.
        if (update.sideLr() != null || update.sideFr() != null) {
            part.setSides(
                    update.sideLr() == null ? part.getSideLr() : side(LateralSide.class, update.sideLr()),
                    update.sideFr() == null ? part.getSideFr()
                            : side(LongitudinalSide.class, update.sideFr()),
                    part.getSideUd());
        }
        if (update.legacyCode() != null) {
            String legacy = blankToNull(update.legacyCode());
            if (legacy != null) {
                identity.requireFree(partId, "legacy_code", legacy, "Старые данные");
            }
            part.setLegacyCode(legacy);
        }

        // Состояние меняется, и с ним пометка в названии (0336) — её ставит
        // пересборка ниже, после всех полей формулы.
        //
        // Пустое — «не трогать», и с задачи 0322 это уже не следствие NOT NULL,
        // а решение: позиция без состояния бывает (ожидаемая, «Не указано»),
        // но снять состояние правкой нельзя. Снятое ушло бы из прайса
        // (HAS_CONDITION), а полный прайс Дром читает как список — объявление
        // пропало бы вместе с просмотрами. Выбрать состояние позиции без него
        // правкой можно: это и есть путь «указать позже».
        if (update.condition() != null && update.condition() != part.getCondition()) {
            part.setCondition(update.condition());
        }

        // Каждое поле — только если его ключ пришёл (задача 0307): форма шлёт
        // изменённое, и отсутствующий ключ значит «не трогать», а не «очистить».
        if (update.has("minPrice")) part.setMinPrice(update.minPrice());
        if (update.has("costPrice")) part.setCostPrice(update.costPrice());
        if (update.has("installationPrice")) part.setInstallationPrice(update.installationPrice());
        if (update.has("qualityGrade")) part.setQualityGrade(update.qualityGrade());
        if (update.has("description")) part.setDescription(update.description());
        if (update.has("note")) part.setNote(update.note());
        if (update.has("textBlock")) part.setTextBlock(update.textBlock());
        if (update.has("videoUrl")) part.setVideoUrl(update.videoUrl());
        if (update.has("marking")) part.setMarking(update.marking());
        if (update.has("manufacturer")) part.setManufacturer(update.manufacturer());
        if (update.has("color")) part.setColor(update.color());
        if (update.has("section")) part.setSection(update.section());
        if (update.has("barcode")) {
            requireFreeBarcode(partId, update.barcode());
            // Пустое — это «штрихкода нет», то есть NULL, а не пустая строка.
            // Пустых строк в уникальном индексе может быть только одна: сняв
            // штрихкод с одной позиции, владелец на второй получал «Операция
            // нарушает целостность данных» — при том что ничего не задвоил.
            // NULL же в Postgres друг с другом не сталкиваются.
            part.setBarcode(blankToNull(update.barcode()));
        }
        if (update.has("weightKg")) part.setWeightKg(update.weightKg());
        part.setDimensionsMm(
                update.has("lengthMm") ? update.lengthMm() : part.getLengthMm(),
                update.has("widthMm") ? update.widthMm() : part.getWidthMm(),
                update.has("heightMm") ? update.heightMm() : part.getHeightMm());
        part.setPackageDimensionsMm(
                update.has("packageLengthMm") ? update.packageLengthMm() : part.getPackageLengthMm(),
                update.has("packageWidthMm") ? update.packageWidthMm() : part.getPackageWidthMm(),
                update.has("packageHeightMm") ? update.packageHeightMm() : part.getPackageHeightMm());
        if (update.has("packageWeightKg")) part.setPackageWeightKg(update.packageWeightKg());
        if (update.has("storageCellId")) part.setStorageCellId(update.storageCellId());
        // Пустое — «не трогать»: у позиции нет состояния «не знаем, выгружать ли».
        if (update.published() != null) part.setPublished(update.published());
        // Меняло ли форму что-нибудь в самой позиции — спрашивается до того,
        // как руку приложили автор и блоки карточки Дрома: в прайс они пока
        // не уходят, и правка одних блоков не должна слать площадке дельту.
        boolean partChanged = entityManager.unwrap(org.hibernate.Session.class).isDirty();
        // Номер производителя, кросс-номера, номер позиции и товара, номер
        // объявления Дрома, своя машина позиции — мимо сущности (см. PartIdentity).
        boolean ownChanged = false;
        if (update.identity() != null) {
            PartIdentity.Applied applied = identity.apply(partId, update.identity(), authorId);
            if (applied.changed()) {
                partChanged = true;
                ownChanged = TITLE_KEYS.stream().anyMatch(update::has);
            }
        }
        // Название — после всех полей формулы, включая записанные мимо сущности
        // (номер производителя, машина позиции): собранное раньше несло бы
        // прежний номер (задача 0336). Только когда поле формулы стало другим:
        // правка заметки название не трогает — у позиции, перенесённой с Дрома
        // или собранной до 0336, она меняла бы его без единой правки формулы.
        boolean formulaChanged = ownChanged || conditionWas != part.getCondition()
                || lrWas != part.getSideLr() || frWas != part.getSideFr();
        if (formulaChanged && titles.retitle(part)) {
            partChanged = true;
        }
        part.touchedBy(authorId);
        boolean termsChanged = applyDromCard(partId, update);

        // Отметка на любой правке, а не только на смене цены: в прайс уезжают
        // и наименование, и цвет, и «Выгружать». Событие о цене — другой
        // вопрос и другое условие. Пропуск — только когда изменились ОДНИ блоки
        // карточки Дрома: прайс их пока не несёт (задача 0285, пункт 7), и
        // отметка стоила бы площадке дельты без единого изменения для неё.
        // Включит её тот, кто начнёт отдавать блоки в прайс.
        if (partChanged || !termsChanged) {
            partChanges.changed(partId);
        }

        if (priceChanged) {
            // Ключ партиции включает id запчасти, поэтому события по одной
            // детали не переставятся местами и на площадку не уедет
            // устаревшая цена.
            eventPublisher.publish(DomainEvent.of(
                    "part", part.getId(), "part.price_changed.v1", payloadOf(part)));
        }
        return new Edited(part, notSaved, formValues(part));
    }

    /**
     * Запрос без занятых полей и их связок — тем же разбором, что у контроллера:
     * поле, снятое из тела, становится «ключа нет», то есть «не трогать», у всех
     * полей разом, включая те, что мимо сущности. Оставшийся набор — подмножество
     * уже проверенного тела, поэтому валидация не повторяется.
     *
     * @return {@code null}, если снимать пришлось всё присланное
     */
    private PartUpdate withoutTaken(PartUpdate request, List<NotSaved> notSaved) {
        com.fasterxml.jackson.databind.node.ObjectNode rest =
                ((com.fasterxml.jackson.databind.node.ObjectNode) request.base().wanted()).deepCopy();
        for (NotSaved field : notSaved) {
            rest.remove(field.key());
            if (field.key().equals("price")) {
                // Операция без цены — снова просьба о цене (0307), поэтому уходит вместе с ней.
                rest.remove(PartEditConflicts.PRICE_OP);
            }
        }
        java.util.Set<String> left = new java.util.HashSet<>();
        rest.fieldNames().forEachRemaining(left::add);
        left.remove(PartEditConflicts.PRICE_OP);
        if (left.isEmpty()) {
            return null;
        }
        try {
            PartController.UpdateRequest fields = json.treeToValue(rest, PartController.UpdateRequest.class);
            java.util.Set<String> sent = new java.util.HashSet<>();
            rest.fieldNames().forEachRemaining(sent::add);
            return fields.toUpdate(java.util.Set.copyOf(sent),
                    new EditBase(rest, request.base().seen()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            // То же тело уже разобрано контроллером; сюда не дойти.
            throw new IllegalStateException("Запрос не разобран", unreadable);
        }
    }

    /**
     * Карточка в том виде, в каком её отдаёт форма правки, — с ней сверяется
     * увиденное человеком (задача 0307). Тот же {@code UpdateRequest.of},
     * что у {@code GET /api/parts/{id}/editable}: собери сверку своими руками —
     * и поле, которое форма показывает одним написанием, сравнивалось бы с другим.
     */
    private com.fasterxml.jackson.databind.JsonNode formOf(Part part) {
        return json.valueToTree(formValues(part));
    }

    private PartController.UpdateRequest formValues(Part part) {
        return PartController.UpdateRequest.of(part,
                entityManager.find(PartDromCard.class, part.getId()), identity.of(part.getId()));
    }

    /**
     * Блоки карточки Дрома позиции; строки нет — {@code null}.
     *
     * <p>Читает форма правки ({@code GET /api/parts/{id}/editable}) и больше
     * никто: ни витрина, ни продажа этих текстов не несут.
     */
    @Transactional(readOnly = true)
    public PartDromCard dromCardOf(Long partId) {
        return entityManager.find(PartDromCard.class, partId);
    }

    /**
     * Блоки карточки Дрома из формы: пусто — {@code null}, строка заводится при
     * первом непустом блоке и дальше остаётся (с пустыми блоками), чтобы очистка
     * последнего блока тоже была правкой в журнале, а не удалением строки.
     * Идёт через Hibernate, а не {@code JdbcTemplate}: автора правки пишет
     * слушатель журнала ({@code AuditLogListener}), и прямой SQL его бы обошёл.
     *
     * @return изменилось ли хоть что-то
     */
    private static String termOf(String sent, String current) {
        return sent == null ? current : PartDromCard.clean(sent);
    }

    private boolean applyDromCard(Long partId, PartUpdate update) {
        PartDromCard card = entityManager.find(PartDromCard.class, partId);
        String warranty = termOf(update.warranty(), card == null ? null : card.getWarranty());
        String pickup = termOf(update.pickup(), card == null ? null : card.getPickup());
        String delivery = termOf(update.deliveryPayment(),
                card == null ? null : card.getDeliveryPayment());
        String kind = termOf(update.dromKind(), card == null ? null : card.getDromKind());
        String section = termOf(update.dromSection(), card == null ? null : card.getDromSection());
        String applicability = termOf(update.applicability(),
                card == null ? null : card.getApplicability());
        String characteristics = termOf(update.characteristics(),
                card == null ? null : card.getCharacteristics());
        String mayFit = termOf(update.mayFit(), card == null ? null : card.getMayFit());
        String adDate = termOf(update.adDate(), card == null ? null : card.getAdDate());
        if (card == null) {
            if (warranty == null && pickup == null && delivery == null
                    && kind == null && section == null && applicability == null
                    && characteristics == null && mayFit == null && adDate == null) {
                return false;
            }
            card = new PartDromCard(partId);
            card.set(warranty, pickup, delivery, kind, section, applicability, characteristics,
                    mayFit, adDate);
            entityManager.persist(card);
            return true;
        }
        if (java.util.Objects.equals(card.getWarranty(), warranty)
                && java.util.Objects.equals(card.getPickup(), pickup)
                && java.util.Objects.equals(card.getDeliveryPayment(), delivery)
                && java.util.Objects.equals(card.getDromKind(), kind)
                && java.util.Objects.equals(card.getDromSection(), section)
                && java.util.Objects.equals(card.getApplicability(), applicability)
                && java.util.Objects.equals(card.getCharacteristics(), characteristics)
                && java.util.Objects.equals(card.getMayFit(), mayFit)
                && java.util.Objects.equals(card.getAdDate(), adDate)) {
            return false;
        }
        card.set(warranty, pickup, delivery, kind, section, applicability, characteristics,
                    mayFit, adDate);
        return true;
    }

    /**
     * Где позиция лежит: адрес полки по каждому складу.
     *
     * <p><b>Адрес — свойство раскладки, а не карточки.</b> Позиция, лежащая
     * на двух складах, лежит на двух разных полках, и одним полем такое
     * не записать: {@code part_stock.cell_id} у каждой строки свой.
     * {@code part.storage_cell_id} при этом остаётся — его пишет приёмка
     * и читает журнал изменений, — но как ответ на вопрос «где лежит»
     * он верен только у позиции на одном складе.
     *
     * <p>Отдаются только склады, где строка раскладки есть: у склада,
     * на котором позиции нет, адреса нет и быть не может — и предложить
     * там перестановку значит предложить работу, которой не существует.
     */
    @Transactional(readOnly = true)
    public List<PartCell> cellsOf(Long partId) {
        return jdbc.query("""
                SELECT s.warehouse_id, s.qty, c.id AS cell_id, c.code AS cell_code
                  FROM part_stock s
                  LEFT JOIN storage_cell c ON c.id = s.cell_id
                 WHERE s.part_id = ?
                 ORDER BY s.warehouse_id""",
                (rs, i) -> new PartCell(rs.getLong("warehouse_id"),
                        (Long) rs.getObject("cell_id"), rs.getString("cell_code"),
                        rs.getBigDecimal("qty")),
                partId);
    }

    /**
     * Переставляет позицию на другую полку того же склада.
     *
     * <p><b>Зачем отдельно от {@link #update}.</b> Это самое частое движение
     * на разборке после продажи: деталь сняли с полки, показали покупателю,
     * положили обратно не туда; освободили стеллаж; собрали все фары в один
     * ряд. Делает это кладовщик — деталь у него в руках, — а форма правки
     * карточки закрыта владельцем и менеджером, и открывать её ради адреса
     * значило бы показать кладовщику себестоимость и минимальную цену.
     * Поэтому своя точка входа со своим списком ролей, а не расширение
     * прежней.
     *
     * <p><b>По складу, а не сразу по всем.</b> У позиции на двух складах
     * две полки, и тихо поменять адрес обеим — это соврать про ту, к которой
     * никто не подходил.
     *
     * <p>{@code cellId == null} — «без адреса»: у клиента без полок ячеек
     * нет вовсе, и требовать её нельзя. В базу это идёт NULL, а не пустое
     * значение, — та же природа, что у снятого штрихкода.
     *
     * <p><b>Идёт движением через {@link StockLedger}, а не своим
     * {@code UPDATE part_stock}.</b> Главное правило модуля — в раскладку
     * не пишет никто мимо журнала, — и адрес полки из него не исключение:
     * «деталь лежала на А-01-1, её там нет» разбирают именно журналом,
     * а прямая правка не оставляет о перекладке ни строки. Движение —
     * {@code MOVE} с нулевым количеством: остаток не менялся, менялся адрес,
     * и схема такое разрешает прямо ({@code stock_movement_delta_ck}).
     */
    @Transactional
    public PartCell changeCell(Long partId, Long warehouseId, Long cellId, Long authorId) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> NotFound.PART.error(partId));

        String cellCode = null;
        if (cellId != null) {
            // Ячейка чужого склада доехала бы до внешнего ключа и вернулась
            // как «Операция нарушает целостность данных» — по такому ответу
            // кладовщик идёт искать поломку сервера.
            cellCode = jdbc.query(
                    "SELECT code FROM storage_cell WHERE id = ? AND warehouse_id = ?",
                    rs -> rs.next() ? rs.getString("code") : null, cellId, warehouseId);
            if (cellCode == null) {
                throw new IllegalArgumentException(
                        "Ячейки нет на складе %s".formatted(naming.warehouse(warehouseId)));
            }
        }

        // Строка раскладки нужна дважды: чтобы отказать словами, если позиции
        // на этом складе нет, и чтобы записать в журнал, с какой полки деталь
        // ушла. «С А-01-1 на А-02-1» — это и есть ответ, за которым в журнал
        // приходят; «стало А-02-1» без «было» на него не отвечает.
        List<Placement> here = jdbc.query("""
                        SELECT cell_id, qty FROM part_stock
                         WHERE part_id = ? AND warehouse_id = ?""",
                (rs, i) -> new Placement((Long) rs.getObject("cell_id"), rs.getBigDecimal("qty")),
                partId, warehouseId);
        if (here.isEmpty()) {
            throw new IllegalArgumentException("На складе %s нет остатка: %s — переставлять нечего"
                    .formatted(naming.warehouse(warehouseId), naming.part(partId)));
        }
        Placement was = here.get(0);

        ledger.record(StockMovement.reshelve(partId, warehouseId, was.cellId(), cellId));

        // А вот `part.storage_cell_id` — одно скалярное поле на весь товар,
        // и полок у позиции столько, на скольких складах она лежит. Записав
        // туда адрес ближнего склада поверх адреса дальнего, лента правок
        // скажет «Ячейка: было Б-01-1, стало А-02-1» — про склад, к которому
        // в этой операции никто не подходил. Поэтому пишем его только когда
        // склад у позиции один и вопрос «где лежит» имеет один ответ;
        // у позиции на двух складах след остаётся движением, где склад
        // и обе полки названы явно.
        //
        // Через сущность, а не native SQL: журнал изменений пишет слушатель
        // Hibernate, и правка мимо сессии в ленту правок не попадёт.
        if (isOnlyWarehouse(partId, warehouseId)) {
            part.setStorageCellId(cellId);
            part.touchedBy(authorId);
        }

        return new PartCell(warehouseId, cellId, cellCode, was.qty());
    }

    /**
     * Лежит ли позиция только на этом складе.
     *
     * <p>Считается по остатку, а не по наличию строки раскладки: строка
     * с нулём остаётся после того, как со склада увезли всё, и позиция,
     * когда-то лежавшая на дальнем, навсегда потеряла бы адрес в карточке.
     */
    private boolean isOnlyWarehouse(Long partId, Long warehouseId) {
        Integer elsewhere = jdbc.queryForObject("""
                        SELECT count(*) FROM part_stock
                         WHERE part_id = ? AND warehouse_id <> ? AND qty > 0""",
                Integer.class, partId, warehouseId);
        return elsewhere == null || elsewhere == 0;
    }

    /** Строка раскладки до перестановки: с какой полки и сколько там лежит. */
    private record Placement(Long cellId, BigDecimal qty) {
    }

    /**
     * Адрес позиции на одном складе.
     *
     * @param cellId   пусто — «без адреса»: у клиента без полок ячеек нет вовсе
     * @param cellCode код полки так, как он напечатан на этикетке
     */
    public record PartCell(Long warehouseId, Long cellId, String cellCode, BigDecimal qty) {
    }

    /**
     * Правка нескольких карточек разом.
     *
     * <p><b>Меняется только то, что владелец тронул.</b> Это главное отличие
     * от правки одной карточки: там форма уезжает целиком и пустое поле
     * означает «очищено», а здесь у выбранных позиций заметки разные,
     * и «пустое значит очистить» стёрло бы их все одним нажатием.
     * Отсюда карта «поле → значение» вместо записи со всеми полями:
     * непереданное поле не трогается вовсе.
     *
     * <p><b>Зачем.</b> После переезда со старой системы владельцу надо
     * проставить секцию сотне позиций или снять «Выгружать» у битых — руками
     * по одной это день работы, и потому её не делают вовсе.
     *
     * <p>Событие о смене цены уходит по каждой позиции, у которой цена
     * действительно стала другой: площадке нужна дельта, а не отметка
     * о том, что кто-то открыл форму.
     *
     * <p><b>Деньги правятся операцией, а не только готовым числом.</b>
     * {@code operations} — что сделать с полем: заменить (умолчание),
     * подвинуть процентом или суммой, округлить. Считается от прежнего
     * значения <b>каждой</b> позиции, а не сводится к общему числу: «минус
     * десять процентов» у трёх позиций даёт три разные цены. Расчёт тот же
     * {@link PriceOperation}, что и в карточке, — две копии разошлись бы
     * на первом округлении.
     *
     * <p><b>Позиция с незаполненным полем не трогается, а не считается
     * от нуля.</b> Считать процент не от чего, а придуманный ноль превратил
     * бы «поднять на 5 %» в «поставить ноль» — то есть в снятое объявление.
     * Сколько таких, возвращается вызывающему: «изменено 40» без слова
     * о пропущенных читается как «сделано всем».
     *
     * <p><b>Позиция, у которой операция дала бы минус или ноль, тоже
     * пропускается — а не отменяет всю пачку.</b> Головной сценарий здесь
     * один: «подними всё, что лежит с зимы, на пять процентов» по большому
     * отбору — у переехавшего клиента это 35 841 позиция. Отбить его целиком
     * из-за одной детали за сто рублей, попавшей под «скинуть пятьсот»,
     * значит не сделать ничего и назвать человеку одну позицию из скольких-то:
     * сколько там ещё таких, он не узнает, а править по одной ему нечем.
     * Тот же довод и то же устройство, что у {@code moveBatch}: проблемные
     * пропускаются, считаются отдельно и называются в ответе.
     *
     * <p>Пропускается при этом <b>вся позиция</b>, а не одно поле: применив
     * ей заметку и не применив цену, мы оставили бы карточку в состоянии,
     * которого никто не просил, и сказали бы «пропущена» о наполовину
     * изменённой. Поэтому деньги считаются до правки, целиком.
     *
     * <p>Когда не прошла <b>ни одна</b> позиция, это уже не «частично
     * сделано», а невыполнимый запрос: тогда отказ словами, чтобы человек
     * увидел причину, а не «изменено 0». Так же поступает {@code moveBatch},
     * когда отложены все строки.
     *
     * <p><b>История позиции называет такую правку массовой</b> (задача
     * 0373): «всего были отредактированы 40 товаров по фильтру». Пометка
     * ложится в журнал изменений при сбросе правок в базу, поэтому сброс
     * здесь явный, под {@link ru.partsflow.platform.audit.AuditBatch}, —
     * число изменённых известно только после прохода. Правка одной позиции
     * (оценка состояния из карточки ходит сюда же) пометки не получает:
     * для человека это правка карточки, а не массовая.
     *
     * @param scope чем выбраны позиции — отмеченные строки или отбор витрины
     * @return сколько карточек изменилось, у скольких арифметике не над чем
     *         было работать и какие не прошли вовсе
     */
    @Transactional
    public BulkOutcome updateAll(List<Long> partIds, Map<String, Object> changes,
                                 Map<String, PriceOperation> operations, Long authorId,
                                 ru.partsflow.platform.audit.AuditBatch.Scope scope) {
        if (partIds == null || partIds.isEmpty()) {
            throw new IllegalArgumentException("Не выбрано ни одной позиции");
        }
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("Не задано ни одного изменения");
        }
        for (String field : changes.keySet()) {
            if (!BULK_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Это поле нельзя править списком: " + field);
            }
        }
        Map<String, PriceOperation> ops = operations == null ? Map.of() : operations;
        for (var op : ops.entrySet()) {
            // Арифметика бывает только у денег: «увеличить заметку на 10 %»
            // не значит ничего, а молча выполненная замена вместо неё —
            // это стёртая заметка у сотни позиций.
            if (op.getValue() != null && op.getValue().arithmetic()
                    && !MONEY_FIELDS.contains(op.getKey())) {
                throw new IllegalArgumentException(
                        "Операция «%s» применима только к деньгам, а не к полю «%s»"
                                .formatted(op.getValue().title(), op.getKey()));
            }
        }

        int changed = 0;
        int skipped = 0;
        int rejected = 0;
        List<String> rejectedCodes = new java.util.ArrayList<>();
        String rejectedReason = null;
        List<Long> touched = new java.util.ArrayList<>();
        for (Long partId : partIds) {
            Part part = partRepository.findById(partId).orElse(null);
            if (part == null) {
                continue;
            }

            // Деньги считаются до правки и целиком: узнать, что позиция
            // не проходит, надо раньше, чем ей записана заметка.
            Map<String, Money> money;
            try {
                money = plannedMoney(part, changes, ops);
            } catch (PriceOperation.Refused refused) {
                rejected++;
                if (rejectedCodes.size() < REJECTED_NAMED) {
                    rejectedCodes.add(part.getPublicCode());
                }
                if (rejectedReason == null) {
                    rejectedReason = refused.getMessage();
                }
                continue;
            }

            touched.add(partId);
            boolean priceChanged = false;
            boolean nothingToCountFrom = false;
            for (var change : changes.entrySet()) {
                Applied applied = apply(part, change.getKey(), change.getValue(),
                        money.get(change.getKey()), authorId);
                priceChanged |= applied == Applied.PRICE_CHANGED;
                nothingToCountFrom |= applied == Applied.SKIPPED;
            }
            part.touchedBy(authorId);
            changed++;
            if (nothingToCountFrom) {
                skipped++;
            }

            if (priceChanged) {
                eventPublisher.publish(DomainEvent.of(
                        "part", part.getId(), "part.price_changed.v1", payloadOf(part)));
            }
        }

        // Не прошла ни одна — это невыполнимый запрос, а не частичная работа:
        // «изменено 0» не говорит ни что случилось, ни что делать.
        if (changed == 0 && rejected > 0) {
            throw new IllegalArgumentException(rejected == 1 ? rejectedReason
                    : "Ни одна из %d позиций не прошла операцию. Первая причина: %s"
                            .formatted(rejected, rejectedReason));
        }

        // Массовая — от двух позиций. Сброс здесь, а не при коммите: пометка
        // живёт, пока открыта, а журнал пишется при сбросе. Ничто в проходе
        // выше правки позиций в базу не сбрасывает (поиск по номеру, расчёт,
        // запись события в outbox), и тест на сорок позиций это стережёт.
        if (changed > 1) {
            try (var batch = ru.partsflow.platform.audit.AuditBatch.open(changed, scope)) {
                entityManager.flush();
            }
        }

        // Одной отметкой на позицию, а не по полю: площадке нужно текущее
        // состояние, и правка сотни позиций уедет одной дельтой.
        partChanges.changed(touched);

        log.info("Правка списком: позиций {}, полей {}, пропущено пустых {}, не прошло {}",
                changed, changes.size(), skipped, rejected);
        return new BulkOutcome(changed, skipped, rejected, List.copyOf(rejectedCodes),
                rejectedReason);
    }

    /**
     * Скольких непрошедших называем поимённо.
     *
     * <p>Не всех: под «скинуть пятьсот со всего склада» может не пройти
     * половина из тридцати пяти тысяч, и список кодов такой длины — это ответ
     * в мегабайт и экран, на котором не видно ничего. Числа хватает, чтобы
     * понять размер беды, а имён — чтобы пойти и посмотреть, что это за
     * позиции.
     */
    private static final int REJECTED_NAMED = 10;

    /**
     * Итог правки списком.
     *
     * @param skipped       у скольких позиций поле было пустым, и арифметике
     *                      не над чем работать: они не тронуты этим полем
     * @param rejected      сколько позиций не прошло вовсе: операция дала бы
     *                      им минус или ноль, и они не тронуты ни одним полем
     * @param rejectedCodes первые из них поимённо — не все: см.
     *                      {@link #REJECTED_NAMED}
     * @param rejectedReason  чем ответил расчёт на первой такой позиции;
     *                        {@code null}, если не прошедших нет
     */
    public record BulkOutcome(int changed, int skipped, int rejected,
                              List<String> rejectedCodes, String rejectedReason) {
    }

    /**
     * Что станет с денежными полями этой позиции.
     *
     * <p>Отдельным проходом до правки: {@link PriceOperation.Refused} здесь
     * означает «позицию пропустить целиком», и узнать это надо раньше, чем
     * ей записано хоть одно поле.
     */
    private static Map<String, Money> plannedMoney(Part part, Map<String, Object> changes,
                                                   Map<String, PriceOperation> ops) {
        Map<String, Money> planned = new java.util.HashMap<>();
        for (var change : changes.entrySet()) {
            MoneyField field = MoneyField.of(change.getKey());
            if (field == null) {
                continue;
            }
            PriceOperation op = ops.getOrDefault(change.getKey(), PriceOperation.SET);
            planned.put(change.getKey(), money(op == null ? PriceOperation.SET : op,
                    currentOf(part, field), change.getValue(), part, field));
        }
        return planned;
    }

    private static BigDecimal currentOf(Part part, MoneyField field) {
        return switch (field) {
            case PRICE -> part.getPrice();
            case MIN_PRICE -> part.getMinPrice();
            case COST_PRICE -> part.getCostPrice();
            case INSTALLATION_PRICE -> part.getInstallationPrice();
        };
    }

    /** Что случилось с полем одной позиции. */
    private enum Applied {
        /** Поле записано (или менять было нечего — например пустое значение операции). */
        CHANGED,
        /** Цена стала другой: площадке нужна дельта. */
        PRICE_CHANGED,
        /** Считать не от чего: поле пустое, а операция арифметическая. */
        SKIPPED
    }

    /**
     * @param money посчитанное {@link #plannedMoney} — для денежных полей
     *              и только для них; у остальных {@code null}
     */
    private Applied apply(Part part, String field, Object value, Money money, Long authorId) {
        switch (field) {
            case "price" -> {
                boolean other = money.change() && money.value() != null
                        && (part.getPrice() == null
                            || part.getPrice().compareTo(money.value()) != 0);
                if (other) {
                    part.changePrice(money.value(), authorId);
                }
                return other ? Applied.PRICE_CHANGED
                             : money.skipped() ? Applied.SKIPPED : Applied.CHANGED;
            }
            case "minPrice" -> {
                if (money.change()) {
                    part.setMinPrice(money.value());
                }
                return money.skipped() ? Applied.SKIPPED : Applied.CHANGED;
            }
            case "costPrice" -> {
                if (money.change()) {
                    part.setCostPrice(money.value());
                }
                return money.skipped() ? Applied.SKIPPED : Applied.CHANGED;
            }
            case "installationPrice" -> {
                if (money.change()) {
                    part.setInstallationPrice(money.value());
                }
                return money.skipped() ? Applied.SKIPPED : Applied.CHANGED;
            }
            case "qualityGrade" -> part.setQualityGrade(
                    value == null ? null : QualityGrade.valueOf(String.valueOf(value)));
            case "description" -> part.setDescription(text(value));
            case "note" -> part.setNote(text(value));
            case "textBlock" -> part.setTextBlock(text(value));
            case "marking" -> part.setMarking(text(value));
            case "manufacturer" -> part.setManufacturer(text(value));
            case "color" -> part.setColor(text(value));
            case "section" -> part.setSection(text(value));
            // Незнакомое поле сюда не доходит: его отбивает BULK_FIELDS
            // до начала работы. Второй проверки нет намеренно — разойдясь,
            // они дали бы поле, которое одна пускает, а другая нет.
            default -> part.setPublished(Boolean.parseBoolean(String.valueOf(value)));
        }
        return Applied.CHANGED;
    }

    /**
     * Денежное поле после операции.
     *
     * <p>Один расчёт на четыре поля: цена, минимальная цена, себестоимость
     * и цена установки двигаются одинаково, и четыре копии разошлись бы —
     * как уже расходились копии словарей и белых списков.
     *
     * @param change  писать ли поле вовсе: при арифметике без значения менять
     *                нечего, а при замене пустое означает «очистить»
     * @param skipped считать было не от чего — поле у этой позиции пустое
     */
    private record Money(BigDecimal value, boolean change, boolean skipped) {
    }

    private static Money money(PriceOperation op, BigDecimal current, Object raw, Part part,
                               MoneyField what) {
        BigDecimal operand = decimal(raw);
        if (!op.arithmetic()) {
            // Прежнее поведение: пустое отмеченное поле — «очистить».
            return new Money(operand, true, false);
        }
        if (op.hasOperand(operand) && current == null) {
            // Пропускаем позицию, а не отказываем всей пачке: у склада
            // после переезда себестоимость заполнена не у всех, и один
            // прочерк не должен отменять правку тридцати тысяч позиций.
            // Сколько пропущено, вызывающий скажет человеку.
            return new Money(null, false, true);
        }
        BigDecimal next = op.apply(current, operand, what, part.getPublicCode());
        return next == null ? new Money(null, false, false) : new Money(next, true, false);
    }

    /**
     * Поля, которые правятся списком.
     *
     * <p>Белый список, а не «всё, что есть у карточки»: заголовок собирается
     * справочником, остаток ведёт журнал, а ячейку правят перемещением.
     * Разрешить их здесь значило бы дать испортить сотню позиций одним
     * нажатием — и без возможности откатить.
     */
    private static final java.util.Set<String> BULK_FIELDS = java.util.Set.of(
            "price", "minPrice", "costPrice", "installationPrice", "qualityGrade",
            "description", "note", "textBlock", "marking", "manufacturer", "color",
            "section", "published");

    /**
     * Поля, которые можно двигать процентом, суммой и округлением.
     *
     * <p>Подмножество {@code BULK_FIELDS}: арифметика бывает только у денег.
     * Считается по {@link MoneyField}, а не пишется вторым списком — иначе
     * поле нашлось бы в одном и потерялось в другом.
     */
    static final java.util.Set<String> MONEY_FIELDS = MoneyField.keys();

    /**
     * Штрихкод не должен стоять у двух позиций сразу.
     *
     * <p>Уникальность стережёт индекс, а проверка нужна ради текста: владелец
     * вводит штрихкод с этикетки, и «Операция нарушает целостность данных»
     * не говорит ни что случилось, ни у какой позиции этот код уже стоит.
     * Позиция называется публичным кодом, а не номером в базе: по нему её
     * можно найти на витрине, по внутреннему номеру — нельзя.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void requireFreeBarcode(Long partId, String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return;
        }
        List<String> taken = jdbc.queryForList(
                "SELECT public_code FROM part WHERE barcode = ? AND id <> ?",
                String.class, barcode.strip(), partId);
        if (!taken.isEmpty()) {
            throw new IllegalArgumentException(
                    "Штрихкод «%s» уже стоит у позиции %s".formatted(barcode.strip(), taken.get(0)));
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Поля карточки, которые правит человек.
     *
     * <p>Отдельной записью, а не набором аргументов: их два десятка, и порядок
     * одинаковых по типу — цена, минимальная цена, себестоимость — перепутать
     * в вызове проще, чем заметить.
     */
    public record PartUpdate(BigDecimal price,
                             BigDecimal minPrice,
                             BigDecimal costPrice,
                             BigDecimal installationPrice,
                             // Пустое — «не трогать», а не «очистить»: колонка
                             // NOT NULL с умолчанием, пустого состояния у детали
                             // не бывает вовсе. Это отличается от остальных полей
                             // формы намеренно, и ровно поэтому стоит здесь.
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
                             BigDecimal weightKg,
                             Integer lengthMm,
                             Integer widthMm,
                             Integer heightMm,
                             Integer packageLengthMm,
                             Integer packageWidthMm,
                             Integer packageHeightMm,
                             BigDecimal packageWeightKg,
                             Long storageCellId,
                             Boolean published,
                             // Блоки карточки Дрома (задача 0285). {@code null} — в запросе
                             // ключа не было, блок не трогать; пустой текст — очистить,
                             // в базу уходит NULL.
                             String warranty,
                             String pickup,
                             String deliveryPayment,
                             // Вид детали и раздел как у клиента (задача 0286): так же.
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
                             // Что сделать с ценой: заменить (умолчание), подвинуть
                             // процентом или суммой, округлить. При арифметике
                             // price — значение операции, а не новая цена.
                             PriceOperation priceOp,
                             // Задача 0302: всё, что раньше стояло в форме с замком.
                             // {@code null} — ключа не было, не трогать; пустое — очистить
                             // (у наименования — не трогать: колонка NOT NULL).
                             String title,
                             String sideLr,
                             String sideFr,
                             String legacyCode,
                             PartIdentity.Edit identity,
                             // Какие ключи пришли в запросе (задача 0307): правятся только они.
                             // {@code null} — «все», так зовёт тот, кто собрал запись сам.
                             java.util.Set<String> sent,
                             // С чем сверять чужую правку; {@code null} — не сверять.
                             EditBase base) {

        /** Пришёл ли ключ: нет — поле не трогается, как бы ни было пусто значение. */
        boolean has(String key) {
            return sent == null || sent.contains(key);
        }
    }

    /**
     * Что человек хотел записать и что видел при открытии формы (задача 0307).
     *
     * @param wanted тело запроса как пришло — его значения сравниваются с текущими
     * @param seen   увиденное у изменённых полей; {@code null} — сверки нет
     *               (вкладка, открытая до выкладки, шлёт форму целиком)
     */
    public record EditBase(com.fasterxml.jackson.databind.JsonNode wanted,
                           com.fasterxml.jackson.databind.JsonNode seen) {
    }

    /**
     * Поле запроса, не сохранённое из-за чужой правки (задача 0313).
     *
     * @param key   ключ поля формы — по нему экран находит строку
     * @param label подпись строки, словами формы
     * @param cause подпись поля, которое изменил другой: у занятого — оно само,
     *              у члена связки — тот, из-за кого связку не записали
     */
    public record NotSaved(String key, String label, String cause) {
    }

    /**
     * Итог правки: позиция, что не сохранилось и форма после записи — той же
     * транзакцией, чтобы экран обновил снимок тем, что легло, а не перечитал
     * позже и не принял чужую правку за увиденную.
     */
    public record Edited(Part part, List<NotSaved> notSaved, PartController.UpdateRequest form) {
    }

    /** Сторона кодом перечисления; пусто — стороны нет; чужое слово — отказ словами. */
    private static <E extends Enum<E>> E side(Class<E> type, String code) {
        if (code.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, code.strip());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Сторона «" + code.strip() + "» не распознана");
        }
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
    /*
     * Ищется тем же способом, что и витрина: подстрокой и по морфологии.
     *
     * Морфология одна не годится числам. Покупатель называет номер куском
     * («1150-33»), а продавец читает с этикетки на детали код товара —
     * и пока условие было только полнотекстовым, «140125» находило
     * у владельца три позиции и ни одной у продавца, а код товара
     * «7584A8FEAE3D» — одну у владельца и ноль у продавца. То есть деталь
     * лежала на полке, её номер был напечатан на ней же, и продавец
     * отвечал «нет такого».
     *
     * Кросс-номера тем более: по ним и звонят, когда своего номера нет.
     * Их поиск не видел вовсе — part_oem в запросе не участвовал.
     *
     * Ровно эта же расходимость уже была починена с другой стороны, когда
     * витрина искала только подстрокой: два поиска по одному складу
     * отвечают по-разному, и неправ тот, о ком не спрашивали.
     *
     * UNION, а не OR, и по той же причине, что на витрине: с OR планировщик
     * уходит в полный перебор. Триграммные индексы на public_code
     * и raw_number уже стоят (tenant/055) — они заводились для витрины.
     *
     * Последняя ветка — номер позиции (задача 0064; см. textMatch). Она приходит одной
     * строкой из PartNumberQuery, общей с витриной: разойдись они, «позиция
     * 347» нашлась бы у владельца и не нашлась у продавца — ровно та болезнь,
     * что уже чинилась тут дважды.
     */
    /**
     * Откуда продавец берёт «что можно предложить»: остаток на складах и
     * ожидаемые по поставке позиции (задача 0170) — двумя ветвями
     * {@code UNION ALL}, у каждой своё {@code FROM}, а выдача, счёт и списки
     * значений собирают обе одинаково, чтобы не разойтись.
     *
     * <p><b>Ожидаемая позиция — строка того же вида, что и остаток.</b> Её
     * «свободное» — не складское, а сколько ещё можно отложить предзаказом:
     * обещано по поставке минус уже отложенное (то же вычитание делает
     * {@code PreorderService.claim}, но под блокировкой — здесь только
     * показ). Склад у неё один, первый действующий: деталь придёт на тот,
     * куда приёмщик её положит, и сделка при приходе перепишется на
     * фактический с записью в историю. Строка с нулём свободного не
     * прячется — как и отложенная на складе: продавец отвечает «ожидается,
     * но всё под заказ», а не «нет такого».
     *
     * <p>Складской резерв этим не затронут: ветвь остатка — прежний
     * {@code JOIN part_stock ... qty > 0} до задачи 0170, а ожидаемая
     * позиция под него не подпадает вовсе — у неё нет строки раскладки.
     *
     * <p><b>Частичный приход:</b> пришедшее, но обещанное предзаказам, в
     * «свободно» не входит — иначе продавец увидел бы деталь, которую сервер
     * ему не отдаст (см. {@code PreorderService.holdOf}). Обещанное
     * вычитается из склада с меньшим номером первым, остальное — со
     * следующего.
     *
     * <p><b>Что мерили и чем кончилось (задачи 0170 и 0261, 9 октября 2026).</b>
     * Склад в 50 300 позиций, «фара» (5 563 находки) и «модель96» (503),
     * медиана Execution Time (генератор и замер — {@code tools/bench/},
     * планы — в PR 0261). Ожидаемые позиции в одном соединении со складом
     * дорожали на любом устройстве запроса:
     * <ul>
     *   <li>{@code UNION ALL} внутри {@code JOIN} — планировщик выполняет
     *       целиком: хэш всего склада, а оценка в сотни тысяч включает JIT —
     *       в десятки раз дороже;</li>
     *   <li>{@code JOIN LATERAL} из двух ветвей по строке (0170) — втрое
     *       дороже: на каждую найденную деталь {@code Append} и подзапросы;</li>
     *   <li>{@code LEFT JOIN part_stock} и проверка ожидаемой в условии
     *       соединения (0261, первая редакция) — 1,2 на «фаре» и 1,7–3 на
     *       узком запросе: склад становится «необязательной» стороной, и
     *       выдача читает {@code part} целиком там, где хватало индекса;</li>
     *   <li>нынешняя: две полные ветви {@code UNION ALL} <i>сверху</i>. Ветвь
     *       остатка — прежний запрос с внутренним соединением, ветвь ожидаемых
     *       идёт по частичному индексу {@code part_expected_ix}
     *       (единицы или сотни строк), и условие поиска у неё сужено до
     *       ожидаемых прямо в каждой ветке {@code UNION}: триграммный индекс
     *       не читается заново ради трёхсот строк. Цифры — в памятке модуля.</li>
     * </ul>
     *
     * <p><b>Предзаказы — соединением с малой таблицей, а не подзапросом на
     * строку.</b> Обещанное по детали ({@code deal_item PREORDER}) собирается
     * агрегатом по {@code part_id} один раз, а «сколько свободного лежит
     * на складах с меньшим номером» — оконной суммой только по деталям,
     * у которых предзаказ есть (единицы). Коррелированная сумма внутри
     * {@code CASE} на каждую строку оценивается планировщиком по всем найденным
     * и уводит стоимость плана за {@code jit_above_cost}: первая же такая
     * редакция отвечала за 75 мс при 31 мс работы — остальное компиляция.
     *
     * <p><b>Предикат ожидаемой ветви записан дословно как у индекса</b>
     * ({@code expected_origin AND status = 'DRAFT'}, {@code tenant/074}):
     * планировщик берёт частичный индекс, только если условие запроса его
     * влечёт, а не взяв — молча читает {@code part} целиком; об этом
     * узнают по времени ответа. Стережёт {@code StockSearchExpectedTest}.
     */
    static final String EXPECTED_PREDICATE = "expected_origin AND status = 'DRAFT'";

    /** Ветвь остатка — со всеми столбцами выдачи. */
    private static final String STOCK_BRANCH = """
            SELECT p.id, p.number, p.public_code, p.title, p.price, p.status,
                   p.description, p.marking, p.created_at,
                   w.id AS warehouse_id, w.name AS warehouse_name, c.code AS cell_code,
                   s.qty,
                   s.qty_reserved + CASE WHEN pn.pending IS NULL THEN 0
                        ELSE LEAST(s.qty - s.qty_reserved,
                                   GREATEST(pn.pending - COALESCE(pre.before, 0), 0)) END AS qty_reserved,
                   false AS expected, sup.expected_on, p.condition
              FROM part p
              JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
              LEFT JOIN (SELECT part_id, sum(quantity) AS pending FROM deal_item
                          WHERE status = 'PREORDER' GROUP BY part_id) pn ON pn.part_id = p.id
              LEFT JOIN (SELECT x.part_id, x.warehouse_id,
                                COALESCE(sum(x.qty - x.qty_reserved) OVER (
                                    PARTITION BY x.part_id ORDER BY x.warehouse_id
                                    ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before
                           FROM part_stock x
                          WHERE x.part_id IN (SELECT part_id FROM deal_item
                                               WHERE status = 'PREORDER')) pre
                     ON pre.part_id = s.part_id AND pre.warehouse_id = s.warehouse_id
              JOIN warehouse w ON w.id = s.warehouse_id
              LEFT JOIN storage_cell c ON c.id = s.cell_id
              LEFT JOIN supply sup ON sup.id = p.supply_id
            """;

    /** Ветвь ожидаемых — те же столбцы, что у ветви остатка, в том же порядке. */
    private static final String EXPECTED_BRANCH = """
            SELECT p.id, p.number, p.public_code, p.title, p.price, p.status,
                   p.description, p.marking, p.created_at,
                   w.id, w.name, NULL::text,
                   p.quantity - COALESCE((SELECT sum(i.quantity) FROM deal_item i
                                           WHERE i.part_id = p.id AND i.status = 'PREORDER'), 0),
                   0, true, sup.expected_on, p.condition
              FROM part p
              JOIN supply sup ON sup.id = p.supply_id AND sup.status IN ('EXPECTED', 'IN_TRANSIT')
              JOIN (SELECT min(w0.id) AS warehouse_id FROM warehouse w0 WHERE w0.is_active) s ON true
              JOIN warehouse w ON w.id = s.warehouse_id
            """;

    /** Для счёта и списков значений столбцы выдачи не нужны, склады и ячейки — тем более. */
    private static final String STOCK_FROM =
            "FROM part p JOIN part_stock s ON s.part_id = p.id AND s.qty > 0\n";

    private static final String EXPECTED_FROM = """
            FROM part p
              JOIN supply sup ON sup.id = p.supply_id AND sup.status IN ('EXPECTED', 'IN_TRANSIT')
              JOIN (SELECT min(w0.id) AS warehouse_id FROM warehouse w0 WHERE w0.is_active) s ON true
            """;

    /**
     * Ожидаемая позиция: черновик из поставки, а если у неё всё же есть строка
     * раскладки с остатком, это деталь со склада (при приходе статус уже
     * {@code IN_STOCK}, и обеих строк система не создаёт — но отдавать
     * дубль при ручной правке схемы нельзя).
     */
    static final String EXPECTED_WHERE =
            " p.expected_origin AND p.status = 'DRAFT'"
            + " AND NOT EXISTS (SELECT 1 FROM part_stock x WHERE x.part_id = p.id AND x.qty > 0)";

    /**
     * Условие поиска продавца — одно на выдачу и на счёт.
     *
     * <p>Разойдись они, и «показаны 50 из 741» называло бы число, посчитанное
     * не тем условием, которым собран список: продавец сузил бы запрос
     * по неверной подсказке. Та же причина, по которой отбор один у страницы
     * витрины, её выгрузки и правки списком.
     *
     * <p>Текстовое условие строится дважды — для ветви остатка и для ветви
     * ожидаемых. Во втором случае каждая ветка {@code UNION} сужена до
     * ожидаемых ({@link #EXPECTED_PREDICATE}): без этого планировщик строит
     * весь набор совпадений — «фара» это 11 000 строк и 13 мс — ради трёхсот
     * позиций; с этим ветка идёт по частичному индексу и читает строку
     * ожидаемой один раз. Параметры у обоих построений одни и те же, в том же
     * порядке.
     */
    static String textMatch(boolean expectedOnly, boolean byNumber, boolean byOem) {
        // Разделитель — явной строкой, а не отступом текстового блока: пробел
        // в конце строки блок срезает, и «?» склеивался бы со следующим словом.
        String only = expectedOnly ? " AND " + EXPECTED_PREDICATE : "";
        String oem = expectedOnly
                ? "SELECT o.part_id FROM part_oem o JOIN part e ON e.id = o.part_id"
                        + " WHERE o.raw_number ILIKE ? AND e.expected_origin AND e.status = 'DRAFT'"
                : "SELECT part_id FROM part_oem WHERE raw_number ILIKE ?";
        // Номер производителя в любом написании (задача 0306) — условие общее
        // с витриной (CatalogService.OEM_NORMALIZED), у ожидаемых сужено тем же
        // соединением с part, что ветка набранного номера.
        String oemNormalized = !byOem ? ""
                : expectedOnly
                ? "\n          UNION SELECT o.part_id FROM part_oem o JOIN part e ON e.id = o.part_id"
                        + " WHERE o." + CatalogService.OEM_NORMALIZED
                        + " AND e.expected_origin AND e.status = 'DRAFT'"
                : CatalogService.OEM_NORMALIZED_BRANCH;
        return "p.id IN (\n"
                + "         SELECT id FROM part WHERE "
                + PartNumberQuery.publicCodeMatch("public_code", byNumber) + only + "\n"
                + "          UNION SELECT id FROM part WHERE title ILIKE ?" + only + "\n"
                + "          UNION SELECT id FROM part\n"
                + "                 WHERE to_tsvector('russian', coalesce(title, '') || ' '\n"
                + "                           || coalesce(description, '') || ' '\n"
                + "                           || coalesce(marking, ''))\n"
                + "                       @@ plainto_tsquery('russian', ?)" + only + "\n"
                + "          UNION " + oem
                + oemNormalized
                + (byNumber ? PartNumberQuery.UNION_BRANCH + only
                        + PartNumberQuery.DROM_AD_BRANCH + only : "") + ")";
    }

    /**
     * Условие поиска и его аргументы — вместе, чтобы выдача и счёт брали одно.
     *
     * <p><b>Размер колеса разбирается и здесь, а не только на вкладке
     * «Шины и диски».</b> Покупатель звонит и называет размер словами —
     * «двести двадцать пять пятьдесят пять на восемнадцать», — продавец
     * набирает «225 55 18», а в заголовке стоит «225/55 R18»: по буквам это
     * не совпадает ни с чем. Замерено на живом складе: вкладка колёс отдавала
     * по такому запросу 22 позиции, поиск продавца — ноль. Двадцать два
     * колеса лежат на складе, а продавец отвечает «нет такого», и проверить
     * его некому.
     *
     * <p>Та же болезнь, что дважды чинилась раньше: витрина искала подстрокой
     * там, где продавец искал морфологией, и наоборот. Правило общее — два
     * поиска по одному складу обязаны находить одно и то же, а неправ всегда
     * тот, о ком не спрашивали.
     *
     * <p>Разобранный размер идёт отдельной веткой `UNION` по полям
     * {@code part_wheel}, а нераспознанный остаток ищется словами, как
     * и прежде: «Bridgestone зимняя» так и остаётся текстом.
     */
    private Match matchFor(String query) {
        String text = vehicleWords.translate(query);
        WheelSizeQuery size = WheelSizeQuery.parse(text);

        StringBuilder wheels = new StringBuilder();
        List<Object> wheelArgs = new ArrayList<>();
        appendSize(wheels, wheelArgs, "tyre_width", size.tyreWidth());
        appendSize(wheels, wheelArgs, "tyre_height", size.tyreHeight());
        appendSize(wheels, wheelArgs, "diameter", size.diameter());
        appendSize(wheels, wheelArgs, "disc_width", size.discWidth());
        appendSize(wheels, wheelArgs, "offset_mm", size.offsetMm());
        if (size.boltPattern() != null) {
            wheels.append(" AND bolt_pattern ILIKE ?");
            wheelArgs.add(size.boltPattern());
        }

        if (!wheels.isEmpty()) {
            // Размер распознан — значит спрашивают колесо, и отбор идёт
            // пересечением, как на вкладке «Шины и диски»: размер И слова.
            // Через UNION с текстовыми ветками «данлоп 225 55 18» вернул бы
            // ещё и все Dunlop других размеров — семнадцать позиций там, где
            // владелец видит четыре. Два ответа на один вопрос, и продавец
            // предложил бы покупателю не тот размер.
            if (size.text() != null) {
                wheels.append(" AND part_id IN (SELECT id FROM part WHERE title ILIKE ?)");
                wheelArgs.add("%" + size.text().strip() + "%");
            }
            // Условие у обеих ветвей одно: часть колёс — единицы, и сужать
            // его до ожидаемых незачем.
            String sizeMatch = "p.id IN (SELECT part_id FROM part_wheel WHERE true" + wheels + ")";
            return new Match(sizeMatch, sizeMatch, wheelArgs,
                    size.text() == null ? "" : size.text(), null);
        }

        String like = "%" + text.strip() + "%";
        // Размер и номер позиции не встречаются вместе: голые цифры размером
        // не разбираются вовсе (у шины нужен «/», у диаметра — «R»), поэтому
        // ветка номера живёт только тут, в текстовом условии.
        Long number = PartNumberQuery.parse(text);
        // Номер производителя приводится от набранного, а не от переведённого
        // словарём машин: так же, как на витрине (задача 0306).
        String oemKey = ru.partsflow.catalog.OemNumbers.searchKey(query);
        // Публичный код при запросе-номере сравнивается точно (задача 0263).
        String codeArg = PartNumberQuery.publicCodeArg(text, number != null);
        List<Object> args = new ArrayList<>(List.of(codeArg, like, text, like));
        if (oemKey != null) {
            args.add(CatalogService.oemNormalizedArg(oemKey));
        }
        if (number != null) {
            args.add(number);
            // Номер объявления Дрома (задача 0274): те же цифры, но текстом.
            args.add(codeArg);
        }
        return new Match(textMatch(false, number != null, oemKey != null),
                textMatch(true, number != null, oemKey != null), args, text, number);
    }

    private static void appendSize(StringBuilder where, List<Object> args,
                                   String column, Object value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    /**
     * @param stockSql    условие отбора для ветви остатка, без {@code WHERE}
     * @param expectedSql то же для ветви ожидаемых: те же параметры в том же
     *                    порядке, но сужено до ожидаемых позиций
     * @param args параметры любого из двух условий по порядку
     * @param rankText текст для ранжирования выдачи; отдельно от параметров,
     *                 потому что при отборе по размеру их порядок другой —
     *                 брать «третий по счёту» значило бы однажды подставить
     *                 в {@code plainto_tsquery} ширину шины
     * @param number номер позиции, если весь запрос — он; {@code null} иначе.
     *               Лежит отдельно от параметров по той же причине, что
     *               и текст ранжирования: в `ORDER BY` он подставляется
     *               вторым разом, и брать его «последним из списка» значило
     *               бы однажды поставить в порядок номер производителя
     */
    private record Match(String stockSql, String expectedSql, List<Object> args,
                         String rankText, Long number) {
    }

    @Transactional(readOnly = true)
    public StockSearch searchAvailable(String query, int limit) {
        return searchAvailable(query, limit, StockFilter.NONE);
    }

    /**
     * Поиск продавца с отбором и порядком.
     *
     * <p><b>Транзакция обязательна и здесь, а не только у соседней
     * перегрузки.</b> `search_path` выставляет провайдер соединений Hibernate
     * внутри транзакции, а `JdbcTemplate`, вызванный снаружи, уходит в `public`
     * и отвечает «relation part does not exist». Ровно так уже отвечала витрина
     * после восстановления ячейки: перегрузку `list(...)` с курсором добавили
     * без аннотации.
     *
     * <p><b>Отбор применяется к запросу в базу, а не к показанным строкам.</b>
     * Список обрезан пятьюдесятью, а «фара» на живом складе находит 181:
     * сузив уже показанное, «фара + Nissan» не нашла бы ничего при полке,
     * полной ниссановских фар. Поэтому условия идут в тот же `WHERE`, что
     * и текстовый поиск, и тем же условием считается число найденного.
     */
    @Transactional(readOnly = true)
    public StockSearch searchAvailable(String query, int limit, StockFilter filter) {
        // «фара камри» приводится к «фара Camry», «225 55 18» — к размеру
        // по полям: покупатель звонит и говорит по-русски и словами.
        Match match = matchFor(query);
        Narrowing narrowing = narrowingOf(filter);
        // Условие стоит в обеих ветвях, и параметры идут дважды: сначала
        // ветвь остатка, потом ветвь ожидаемых.
        List<Object> args = new ArrayList<>(match.args());
        args.addAll(narrowing.args());
        args.addAll(match.args());
        args.addAll(narrowing.args());
        String order = orderBy(filter);
        // Найденное по номеру позиции встаёт первым — и при заданном порядке
        // тоже. Выдача обрезана полусотней, а «347» попадает ещё и в куски
        // публичных кодов (шесть шестнадцатеричных байт) и в номера
        // производителя: на живом складе таких десятки, и названная вслух
        // позиция утонула бы в них ровно так же, как тонула до этой правки.
        // Точное совпадение по уникальной колонке — это одна строка, и она
        // и есть ответ на вопрос, а не одно из совпадений.
        if (match.number() != null) {
            args.add(match.number());
        }
        if (order == null) {
            // Ранжирование берёт тот же текст, что и поиск по словам —
            // и только когда порядок не задан продавцом: выбрав «цена
            // по возрастанию», он спрашивает про цену, а не про совпадение.
            args.add(match.rankText());
        }
        args.add(limit);
        // Куски склеиваются явными строками, а не отступом текстового блока:
        // у блока, чьи кавычки стоят на строке содержимого, срезается весь
        // отступ, и «= ?» склеивалось с «ORDER BY» в «?ORDER BY» — отказ
        // Postgres на грамматике, то есть пятисотка на живом запросе. Та же
        // ловушка, что «ENDAS supply» у выгрузки колёс.
        List<StockRow> rows = jdbc.query(
                "SELECT r.id, r.number, r.public_code, r.title, r.price, r.status,\n"
                + "       r.warehouse_id, r.warehouse_name, r.cell_code,\n"
                + "       r.qty, r.qty_reserved, r.qty - r.qty_reserved AS qty_available,\n"
                + "       r.expected, r.expected_on, r.condition,\n"
                // Основной номер производителя (задача 0327) — во внешнем
                // запросе, а не в ветвях: ветви `UNION ALL` сопоставляются
                // по месту, и столбец, добавленный не туда в одной, сдвинул бы
                // другую; заодно номер читается только у строк, прошедших
                // предел, а не у каждой находки. Выражение то же, что в отказах.
                + "       " + PartNaming.primaryOemSql("r") + "\n"
                + "  FROM (\n"
                + STOCK_BRANCH + " WHERE " + match.stockSql() + narrowing.sql() + "\n"
                + "UNION ALL\n"
                + EXPECTED_BRANCH + " WHERE " + EXPECTED_WHERE
                + " AND " + match.expectedSql() + narrowing.sql() + "\n"
                + ") r\n"
                + " ORDER BY " + (match.number() == null ? "" : "(r.number = ?) DESC, ")
                + (order != null ? order : DEFAULT_STOCK_ORDER)
                + "\n LIMIT ?",
                (rs, i) -> new StockRow(
                        rs.getLong("id"),
                        rs.getLong("number"),
                        rs.getString("public_code"),
                        rs.getString("title"),
                        rs.getBigDecimal("price"),
                        rs.getString("status"),
                        rs.getLong("warehouse_id"),
                        rs.getString("warehouse_name"),
                        rs.getString("cell_code"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("qty_reserved"),
                        rs.getBigDecimal("qty_available"),
                        rs.getBoolean("expected"),
                        rs.getDate("expected_on") == null
                                ? null : rs.getDate("expected_on").toLocalDate(),
                        rs.getString("condition"),
                        rs.getString(PartNaming.OEM_COLUMN)),
                // Ветки UNION, потом отбор, потом ранжирование, потом предел.
                args.toArray());

        // Сколько нашлось всего — тем же условием. Список обрезан
        // на полусотне, и молча этого делать нельзя: продавец видел
        // пятьдесят строк из семисот сорока одной и не знал об этом ничего.
        // Ответить покупателю «нет такого», глядя на обрезанный список, —
        // то же самое, что ответить так на пустой, только тут продавец
        // ещё и уверен, что посмотрел всё.
        //
        // Счёт стоит десять миллисекунд на складе в 35 841 позицию —
        // замерено. Когда список короче предела, он и есть всё найденное,
        // и лишний запрос был бы платой ни за что.
        long total = rows.size() < limit ? rows.size() : countAvailable(match, narrowing);
        return new StockSearch(rows, total, facetsOf(match));
    }

    private long countAvailable(Match match, Narrowing narrowing) {
        List<Object> args = new ArrayList<>(match.args());
        args.addAll(narrowing.args());
        args.addAll(match.args());
        args.addAll(narrowing.args());
        // Две ветви тем же условием, что и выдача, — суммой двух счётов в одном
        // запросе: на ветви остатка столбцы `part` не нужны, и планировщик
        // читает один индекс (на узком запросе это 4 мс, а не 12).
        Long found = jdbc.queryForObject(
                "SELECT (SELECT count(*) " + STOCK_FROM
                + " WHERE " + match.stockSql() + narrowing.sql() + ")\n"
                + "     + (SELECT count(*) " + EXPECTED_FROM
                + " WHERE " + EXPECTED_WHERE + " AND " + match.expectedSql()
                + narrowing.sql() + ")",
                Long.class, args.toArray());
        return found == null ? 0 : found;
    }

    /**
     * Из чего продавцу выбирать отбор — по найденному, а не по всему складу.
     *
     * <p>Это отличается от витрины владельца намеренно, и разница в вопросе.
     * Владелец смотрит склад целиком, и список значений там полный: сужающийся
     * не даёт снять один фильтр и поставить другой. Продавец же начинает
     * с «фары» и сужает её — предложить ему все полторы сотни марок склада
     * значило бы предложить выбирать из того, чего в найденном нет.
     *
     * <p>Считается по одному текстовому запросу, без уже поставленного
     * отбора: иначе, выбрав Toyota, продавец не смог бы переключиться
     * на Nissan — того в списке уже не было бы.
     */
    private Facets facetsOf(Match match) {
        // Одним запросом, а не тремя: у каждого свой проход по найденному,
        // а различных троек «марка · модель · оценка» в нём десятки.
        // Марка и модель — машина позиции (задача 0303): вписанная в карточке,
        // иначе донора. Тем же правилом, что отбор ниже, иначе выбранное из
        // списка значение не находило бы позицию, которая его дала.
        String values = "SELECT " + SELLER_BRAND + " AS brand, " + SELLER_MODEL + " AS model, "
                + CatalogService.QUALITY_GRADE + " AS grade\n";
        String donors = "\n  LEFT JOIN donor d ON d.id = p.donor_id"
                + "\n  LEFT JOIN catalog.brand b ON b.id = d.brand_id"
                + "\n  LEFT JOIN catalog.model m ON m.id = d.model_id\n";
        List<Object> args = new ArrayList<>(match.args());
        args.addAll(match.args());
        // UNION, а не UNION ALL: повторы схлопывает он, и DISTINCT сверху не нужен.
        List<String[]> found = jdbc.query(
                values + "  " + STOCK_FROM + donors
                + " WHERE " + match.stockSql() + "\n"
                + "UNION\n"
                + values + "  " + EXPECTED_FROM + donors
                + " WHERE " + EXPECTED_WHERE + " AND " + match.expectedSql() + "\n"
                + " ORDER BY 1, 2, 3",
                (rs, i) -> new String[]{
                        rs.getString("brand"), rs.getString("model"), rs.getString("grade")},
                args.toArray());

        List<VehicleOption> vehicles = new ArrayList<>();
        List<String> grades = new ArrayList<>();
        for (String[] row : found) {
            if (row[0] != null) {
                VehicleOption option = new VehicleOption(row[0], row[1]);
                if (!vehicles.contains(option)) {
                    vehicles.add(option);
                }
            }
            if (row[2] != null && !grades.contains(row[2])) {
                grades.add(row[2]);
            }
        }
        return new Facets(vehicles, grades);
    }

    /**
     * Условие отбора и его параметры — вместе, чтобы выдача, счёт и списки
     * значений брали одно и то же.
     */
    private record Narrowing(String sql, List<Object> args) {
    }

    /**
     * Разрешённые порядки: имя из запроса → выражение SQL.
     *
     * <p>Белый список, а не подстановка: `ORDER BY` не принимает параметр,
     * и пришедший текст в нём — это внедрение SQL. Неизвестное имя молча
     * становится порядком по умолчанию (по совпадению), как на витрине.
     */
    private static final Map<String, String> STOCK_SORTS = Map.of(
            "price", "r.price",
            "intake", "r.created_at");

    /**
     * Порядок по умолчанию — по совпадению, свободное сначала.
     *
     * <p>Без «ORDER BY» в начале: перед ним может встать ключ точного
     * совпадения по номеру позиции, и собирается строка в одном месте.
     */
    private static final String DEFAULT_STOCK_ORDER = """
            (r.qty - r.qty_reserved > 0) DESC,
                      ts_rank(to_tsvector('russian', coalesce(r.title, '') || ' '
                          || coalesce(r.description, '') || ' '
                          || coalesce(r.marking, '')),
                          plainto_tsquery('russian', ?)) DESC,
                      r.id""";

    /**
     * @return {@code null}, если порядок продавцом не задан, — тогда выдача
     *         идёт по совпадению, как раньше. Без «ORDER BY» в начале:
     *         строку собирает вызывающий, чтобы вперёд мог встать ключ
     *         точного совпадения по номеру позиции
     */
    private static String orderBy(StockFilter filter) {
        String column = filter.sort() == null ? null : STOCK_SORTS.get(filter.sort());
        if (column == null) {
            return null;
        }
        // NULLS LAST в обе стороны: цена бывает незаполненной, и по убыванию
        // Postgres поставил бы такие строки первыми — продавец, спросивший
        // «что подороже», получил бы список без цен.
        // Вторым ключом номер позиции: без полного порядка одинаковые цены
        // база вправе вернуть в любой последовательности.
        return column + (filter.descending() ? " DESC" : " ASC") + " NULLS LAST, r.id";
    }

    /**
     * Отбор продавца: марка, модель, год, стороны, склад, оценка и цена.
     *
     * <p>Марка, модель и год — машина позиции: вписанная в карточке
     * (задача 0303), иначе машина, с которой снята деталь. У позиции без
     * донора и без своей машины марки нет, и отбор по марке её не найдёт;
     * своей машины из применимости (витрина, 0290) здесь по-прежнему нет —
     * это отдельная задача. Продавец видит в списке ровно те марки, которые
     * в найденном есть.
     */
    private static Narrowing narrowingOf(StockFilter filter) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();

        // Машина позиции — своя, вписанная в карточке, иначе донора (задача
        // 0303, PartVehicle). Донор читается подзапросом на строку, а не
        // соединением: условие встаёт в WHERE обеих ветвей выдачи, где своих
        // соединений с донором нет. Подзапрос в coalesce считается только
        // у позиций без своего значения.
        if (notBlank(filter.brand())) {
            where.append("\n AND ").append(PartVehicle.brand(DONOR_BRAND_OF_ROW)).append(" = ?");
            args.add(filter.brand().strip());
        }
        if (notBlank(filter.model())) {
            where.append("\n AND ").append(PartVehicle.model(DONOR_MODEL_OF_ROW)).append(" = ?");
            args.add(filter.model().strip());
        }
        if (filter.yearFrom() != null) {
            where.append("\n AND ").append(PartVehicle.year(DONOR_YEAR_OF_ROW)).append(" >= ?");
            args.add(filter.yearFrom());
        }
        if (filter.yearTo() != null) {
            where.append("\n AND ").append(PartVehicle.year(DONOR_YEAR_OF_ROW)).append(" <= ?");
            args.add(filter.yearTo());
        }

        if (notBlank(filter.side())) {
            // Деталь «на обе стороны» — ответ и на «левую», и на «правую»:
            // спросивший левый амортизатор о паре обязан узнать (задача 0277).
            where.append("\n AND p.side_lr IN (?, 'BOTH')");
            args.add(sideOf(filter.side()));
        }
        if (notBlank(filter.position())) {
            // Как со стороной выше: деталь «Переднее, Заднее» — ответ и
            // на «переднюю», и на «заднюю» (задача 0283).
            where.append("\n AND p.side_fr IN (?, 'BOTH')");
            args.add(positionOf(filter.position()));
        }
        if (filter.warehouseId() != null) {
            where.append("\n AND s.warehouse_id = ?");
            args.add(filter.warehouseId());
        }
        if (notBlank(filter.grade())) {
            // Тем же выражением, каким оценка названа на витрине и каким
            // собран список значений: разойдись они — выбранное из списка
            // не находило бы ничего.
            where.append("\n AND ").append(CatalogService.QUALITY_GRADE).append(" = ?");
            args.add(filter.grade().strip());
        }
        if (filter.priceFrom() != null) {
            where.append("\n AND p.price >= ?");
            args.add(filter.priceFrom());
        }
        if (filter.priceTo() != null) {
            where.append("\n AND p.price <= ?");
            args.add(filter.priceTo());
        }
        return new Narrowing(where.toString(), args);
    }

    /** Марка и модель продавца для списка значений: соединения с донором — {@code b}, {@code m}. */
    private static final String SELLER_BRAND = PartVehicle.brand("b.name");
    private static final String SELLER_MODEL = PartVehicle.model("m.name");

    /** Машина донора строки {@code p} — для отбора, где соединений с донором нет. */
    private static final String DONOR_BRAND_OF_ROW = "(SELECT b.name FROM donor d"
            + " JOIN catalog.brand b ON b.id = d.brand_id WHERE d.id = p.donor_id)";
    private static final String DONOR_MODEL_OF_ROW = "(SELECT m.name FROM donor d"
            + " JOIN catalog.model m ON m.id = d.model_id WHERE d.id = p.donor_id)";
    private static final String DONOR_YEAR_OF_ROW =
            "(SELECT d.year FROM donor d WHERE d.id = p.donor_id)";

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // Значение проверяется, а не подставляется параметром молча: неизвестная
    // сторона отдала бы пустую выдачу, и продавец читал бы её как «нет такого».
    private static String sideOf(String value) {
        try {
            return LateralSide.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Сторона может быть только левой или правой");
        }
    }

    private static String positionOf(String value) {
        try {
            return LongitudinalSide.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Может быть только передним или задним");
        }
    }

    /**
     * Чем продавец сужает найденное и в каком порядке хочет это видеть.
     *
     * <p>Одной записью, а не десятью параметрами метода: отбор один
     * на выдачу, на счёт и на списки значений, и разъехаться им нельзя.
     *
     * @param sort       имя из белого списка {@link #STOCK_SORTS}; пусто —
     *                   порядок по совпадению с запросом
     * @param descending обратный порядок выбранной сортировки
     */
    public record StockFilter(String brand, String model,
                              Integer yearFrom, Integer yearTo,
                              String side, String position,
                              Long warehouseId, String grade,
                              BigDecimal priceFrom, BigDecimal priceTo,
                              String sort, boolean descending) {

        /** Ничего не сужено и порядок обычный — как было до появления отбора. */
        public static final StockFilter NONE = new StockFilter(
                null, null, null, null, null, null, null, null, null, null, null, false);
    }

    /**
     * Выдача продавцу вместе с общим числом найденного.
     *
     * @param total сколько нашлось всего; больше длины {@code rows} — список
     *              обрезан, и экран обязан об этом сказать
     * @param facets из чего выбирать отбор: марки с моделями и оценки,
     *               встречающиеся в найденном
     */
    public record StockSearch(List<StockRow> rows, long total, Facets facets) {
    }

    /** Значения отбора, встречающиеся в найденном. */
    public record Facets(List<VehicleOption> vehicles, List<String> grades) {
    }

    /** Марка и модель машины, с которой снята найденная деталь. */
    public record VehicleOption(String brand, String model) {
    }

    /**
     * Строка выдачи продавцу: деталь на конкретном складе.
     *
     * @param expected    позиция ожидается по поставке, на складе её нет;
     *                    {@code qtyAvailable} у неё — сколько ещё можно отложить
     *                    предзаказом (задача 0170)
     * @param expectedOn  ожидаемая дата поставки; пусто — её не называли
     * @param number порядковый номер позиции — тот, которым её называют вслух.
     *               Продавец как раз и есть тот, кто произносит его в трубку:
     *               публичный код («7584A8FEAE3D») по телефону не диктуют
     * @param condition состояние позиции кодом {@link PartCondition} («USED»);
     *                  слово ставит экран тем же словарём, что колонка
     *                  «Состояние» на складе (задача 0314). До неё состояние
     *                  продавец читал только из пометки в наименовании,
     *                  а пометка после смены состояния врёт (0304)
     * @param primaryOem основной номер производителя в виде хранения
     *                   («84140-12530»; задача 0327) — экран ставит его за словом
     *                   состояния. Только {@code is_primary}, кросс-номер вместо
     *                   него не подставляется; пусто — основного нет
     */
    public record StockRow(Long partId, long number, String publicCode, String title, BigDecimal price,
                           String status, Long warehouseId, String warehouseName,
                           String cellCode, BigDecimal qty, BigDecimal qtyReserved,
                           BigDecimal qtyAvailable,
                           boolean expected, java.time.LocalDate expectedOn,
                           String condition, String primaryOem) {
    }

    /**
     * Включает или выключает выгрузку позиций на площадки.
     *
     * <p>Пачкой, а не по одной: после импорта склада исключений набирается
     * несколько сотен, и по одному их отмечать никто не будет.
     *
     * <p>Обратное действие полное: снятый флаг убирает позицию из прайса
     * Дрома, и объявление площадка снимает ближайшим полным забором
     * (с задачи 0339 недоступной позиции в файле не бывает вовсе).
     *
     * @return сколько позиций изменилось
     */
    @Transactional
    public int setPublished(List<Long> partIds, boolean published) {
        if (partIds == null || partIds.isEmpty()) {
            throw new IllegalArgumentException("Не указано ни одной позиции");
        }
        // Прежнее значение — тем же запросом, под блокировкой строк (задача 0325).
        // Экрана у этого пути нет (`endpoint-coverage.py`: «устарел»), но путь
        // жив и открыт вошедшему: правка, которой журнал не видит, — ровно
        // та, о которой потом спрашивают «кто снял с выгрузки».
        Map<Long, ru.partsflow.platform.audit.AuditJournal.Change> changes =
                new java.util.LinkedHashMap<>();
        int[] updated = {0};
        jdbc.query("""
                UPDATE part p SET is_published = ?, updated_at = now()
                  FROM (SELECT id, is_published AS was FROM part
                         WHERE id = ANY (?) FOR UPDATE) old
                 WHERE p.id = old.id
                RETURNING p.id, old.was""",
                rs -> {
                    updated[0]++;
                    if (rs.getBoolean("was") != published) {
                        changes.put(rs.getLong("id"), new ru.partsflow.platform.audit.AuditJournal
                                .Change(Map.of("is_published", !published),
                                        Map.of("is_published", published)));
                    }
                },
                published, partIds.toArray(Long[]::new));
        journal.changedEach("part", changes);
        // Снятая с публикации позиция обязана уехать недоступной, иначе
        // объявление висит, а продавать её владелец не собирался.
        partChanges.changed(partIds);
        return updated[0];
    }

    @Transactional(readOnly = true)
    public List<Part> search(String query, int limit) {
        return partRepository.search(vehicleWords.translate(query), limit);
    }

    @Transactional(readOnly = true)
    public List<Part> findByOem(String number) {
        return partRepository.findByNormalizedOem(
                ru.partsflow.catalog.OemNumbers.normalize(number));
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
     * Состояние позиций по идентификаторам.
     *
     * <p>Нужно печати сделки (задача 0314) и составу сделки — у продавца,
     * в заказах с площадок и на странице по ссылке (0318): там состояние
     * несла только пометка в наименовании, а пометка уходит из него (0315,
     * 0316) и уже сегодня врёт после смены состояния (0304).
     *
     * <p>Отдаётся перечислением, а не словом: печать ставит слово сама
     * ({@link PartCondition#title()}), а в ответ экрану уходит код — слово
     * там ставит {@code conditionTitle}, один словарь на все экраны. Позиции
     * без состояния в карте нет.
     */
    @Transactional(readOnly = true)
    public Map<Long, PartCondition> conditionsOf(Collection<Long> partIds) {
        if (partIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PartCondition> conditions = new HashMap<>();
        for (Part part : partRepository.findAllById(partIds)) {
            if (part.getCondition() != null) {
                conditions.put(part.getId(), part.getCondition());
            }
        }
        return conditions;
    }

    /**
     * Порядковые номера позиций по идентификаторам (задача 0168).
     *
     * <p>Нужно тем же продажам, что и {@link #titlesOf}: состав сделки и выбор
     * позиций на возврат называют деталь человеку, а «Фара Toyota Camry 2007»
     * на живом складе — сотня одинаковых строк. Публичный код для разговора
     * не годится: он шесть случайных байт и живёт на этикетке.
     *
     * <p>Своим запросом, а не через сущность: {@code number} в {@link Part}
     * не отображён вовсе — колонку ведёт база умолчанием ({@code nextval}), —
     * и заводить поле ради чтения значило бы ставить {@code @Generated}
     * на путь каждой приёмки.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> numbersOf(Collection<Long> partIds) {
        if (partIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> numbers = new HashMap<>();
        // Идентификаторы пришли из базы, а не из запроса пользователя, а список
        // бывает в сотни строк: на каждую по параметру Postgres не обязан
        // готовить свой план. Тот же приём, что в InventoryService.
        String in = partIds.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        jdbc.query("SELECT id, number FROM part WHERE id IN (" + in + ")",
                rs -> {
                    numbers.put(rs.getLong("id"), rs.getLong("number"));
                });
        return numbers;
    }

    private byte[] payloadOf(Part part) {
        return EventPayloads.write(new PartEvent(part.getId(), part.getPublicCode(),
                part.getTitle(), part.getPrice(), String.valueOf(part.getStatus())));
    }
}
