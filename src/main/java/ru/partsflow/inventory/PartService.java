package ru.partsflow.inventory;

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

    public PartService(PartRepository partRepository, DomainEventPublisher eventPublisher,
                       PartNameService partNames, PartChangeLog partChanges, JdbcTemplate jdbc,
                       VehicleWords vehicleWords, StockNaming naming, StockLedger ledger) {
        this.partRepository = partRepository;
        this.eventPublisher = eventPublisher;
        this.partNames = partNames;
        this.partChanges = partChanges;
        this.jdbc = jdbc;
        this.vehicleWords = vehicleWords;
        this.naming = naming;
        this.ledger = ledger;
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
     * <p>Заголовок правится подменой начала, а не пересборкой: собрать его
     * заново значит достать донора, стороны и состояние. Условий два, и второе
     * дороже первого.
     *
     * <p><b>Заголовок должен быть длиннее написания.</b> У позиции из чужой
     * таблицы он и есть само написание, целиком: подмена «Фара левая» на эталон
     * «Фара» стёрла бы сторону, и левая с правой стали бы одной деталью —
     * колонки {@code side_lr} у импорта тоже нет, восстановить её будет неоткуда.
     * Заголовок, собранный нами, длиннее: за видом детали идут машина, сторона
     * и состояние, и они остаются на месте. Пойман живым прогоном на складе,
     * загруженном из таблицы, — тесты на приёмочных заголовках этого не видели.
     *
     * <p>Карточки, чей заголовок начинается иначе (правили руками, пришли
     * из другой системы), не трогаются вовсе: подменять в них нечего.
     * Категорию и эталон получают все — они от заголовка не зависят.
     *
     * @return сколько карточек доведено
     */
    @Transactional
    public MatchResult applyMatch(Long partNameId, Long partKindId) {
        String localSpelling = partNames.require(partNameId).getName();
        PartName matched = partNames.matchManually(partNameId, partKindId);
        String kindName = partNames.displayNameOf(matched);

        int updated = jdbc.update("""
                UPDATE part
                   SET category_id  = COALESCE(?, category_id),
                       part_kind_id = ?,
                       -- Момент правки раньше ставил триггер; теперь его
                       -- ставит тот, кто правит.
                       updated_at = now(),
                       title = CASE WHEN left(title, length(?)) = ?
                                     AND length(title) > length(?)
                                    THEN ? || substr(title, length(?) + 1)
                                    ELSE title END
                 WHERE part_name_id = ?""",
                matched.getCategoryId(), matched.getPartKindId(),
                localSpelling, localSpelling, localSpelling, kindName, localSpelling,
                partNameId);

        // Заголовок и категория уехали в прайс — площадке надо сообщить.
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

    /** @param updated сколько карточек получили категорию и эталонный заголовок */
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
     * <p>Заголовок правится по тому же правилу, что и в {@link #applyMatch}:
     * начало подменяется эталоном, только если оно совпадает с написанием
     * и в заголовке есть что-то ещё. Ради этого справочник и нужен — чтобы
     * «мозги» и «телевизор» стали блоком управления и рамкой радиатора,
     * а прайс перестал быть словарём чужого сленга. Позиция, у которой
     * заголовок и есть само написание, не трогается: подмена стёрла бы
     * сторону, и левая фара слилась бы с правой.
     *
     * @return сколько карточек доведено
     */
    @Transactional
    public int applyMatchedNames() {
        // Отметки об изменении здесь нет намеренно: это доводка после
        // переезда, за раз она правит десятки тысяч карточек, и площадка
        // узнает о них полным прайсом. Смотри PartChangeLog.
        return jdbc.update("""
                UPDATE part p
                   SET category_id = pn.category_id,
                       part_kind_id = pn.part_kind_id,
                       updated_at = now(),
                       title = CASE WHEN left(p.title, length(pn.name)) = pn.name
                                     AND length(p.title) > length(pn.name)
                                    THEN k.name || substr(p.title, length(pn.name) + 1)
                                    ELSE p.title END
                  FROM part_name pn
                  JOIN catalog.part_kind k ON k.id = pn.part_kind_id
                 WHERE p.part_name_id = pn.id
                   AND pn.part_kind_id IS NOT NULL
                   AND p.part_kind_id IS DISTINCT FROM pn.part_kind_id""");
    }

    @Transactional
    public Part changePrice(Long partId, BigDecimal newPrice, Long changedBy) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new IllegalArgumentException("Запчасть не найдена: " + partId));

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
                .orElseThrow(() -> new IllegalArgumentException("Запчасть не найдена: " + partId));
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
     * <p><b>Форма, а не патч.</b> Приходят все поля разом, и {@code null}
     * означает «очищено», а не «не трогать». Иначе стереть заметку с экрана
     * невозможно: пустое поле формы неотличимо от непереданного.
     *
     * <p><b>Заголовок сюда не входит.</b> Он производный — собирается
     * из эталона наименования, машины, стороны и состояния, — и правка руками
     * разъехалась бы с ним при первом же пересопоставлении справочника.
     * По той же причине не правятся сторона и состояние: они в заголовок
     * входят, а пересборки его после правки у нас нет. Предел осознанный:
     * ошибку в стороне лечит разбор наименований, а не поле в карточке.
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
    @Transactional
    public Part update(Long partId, PartUpdate update, Long authorId) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new IllegalArgumentException("Запчасть не найдена: " + partId));

        PriceOperation priceOp = update.priceOp() == null ? PriceOperation.SET : update.priceOp();
        if (!priceOp.arithmetic() && update.price() != null && update.price().signum() < 0) {
            throw new IllegalArgumentException("Цена не может быть отрицательной");
        }

        // Отказ называет позицию так, как её зовёт человек: публичный код
        // виден на витрине и на этикетке, внутренний номер — нигде.
        BigDecimal wanted = priceOp.apply(part.getPrice(), update.price(),
                "цена позиции " + part.getPublicCode());
        boolean priceChanged = wanted != null
                && (part.getPrice() == null || part.getPrice().compareTo(wanted) != 0);
        if (priceChanged) {
            part.changePrice(wanted, authorId);
        }

        part.setMinPrice(update.minPrice());
        part.setCostPrice(update.costPrice());
        part.setInstallationPrice(update.installationPrice());
        part.setQualityGrade(update.qualityGrade());
        part.setDescription(update.description());
        part.setNote(update.note());
        part.setTextBlock(update.textBlock());
        part.setVideoUrl(update.videoUrl());
        part.setMarking(update.marking());
        part.setManufacturer(update.manufacturer());
        part.setColor(update.color());
        part.setSection(update.section());
        requireFreeBarcode(partId, update.barcode());
        // Пустое — это «штрихкода нет», то есть NULL, а не пустая строка.
        // Пустых строк в уникальном индексе может быть только одна: сняв
        // штрихкод с одной позиции, владелец на второй получал «Операция
        // нарушает целостность данных» — при том что ничего не задвоил.
        // NULL же в Postgres друг с другом не сталкиваются.
        part.setBarcode(blankToNull(update.barcode()));
        part.setWeightKg(update.weightKg());
        part.setDimensionsMm(update.lengthMm(), update.widthMm(), update.heightMm());
        part.setPackageDimensionsMm(update.packageLengthMm(), update.packageWidthMm(),
                update.packageHeightMm());
        part.setPackageWeightKg(update.packageWeightKg());
        part.setStorageCellId(update.storageCellId());
        part.setPublished(update.published());
        part.touchedBy(authorId);

        // Отметка на любой правке, а не только на смене цены: в прайс уезжают
        // и наименование, и цвет, и «Выгружать». Событие о цене — другой
        // вопрос и другое условие.
        partChanges.changed(partId);

        if (priceChanged) {
            // Ключ партиции включает id запчасти, поэтому события по одной
            // детали не переставятся местами и на площадку не уедет
            // устаревшая цена.
            eventPublisher.publish(DomainEvent.of(
                    "part", part.getId(), "part.price_changed.v1", payloadOf(part)));
        }
        return part;
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
                .orElseThrow(() -> new IllegalArgumentException("Запчасть не найдена: " + partId));

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
     * @return сколько карточек изменилось и у скольких арифметике не над чем
     *         было работать
     */
    @Transactional
    public BulkOutcome updateAll(List<Long> partIds, Map<String, Object> changes,
                                 Map<String, PriceOperation> operations, Long authorId) {
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
        List<Long> touched = new java.util.ArrayList<>();
        for (Long partId : partIds) {
            Part part = partRepository.findById(partId).orElse(null);
            if (part == null) {
                continue;
            }
            touched.add(partId);
            boolean priceChanged = false;
            boolean nothingToCountFrom = false;
            for (var change : changes.entrySet()) {
                PriceOperation op = ops.getOrDefault(change.getKey(), PriceOperation.SET);
                Applied applied = apply(part, change.getKey(), change.getValue(),
                        op == null ? PriceOperation.SET : op, authorId);
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
        // Одной отметкой на позицию, а не по полю: площадке нужно текущее
        // состояние, и правка сотни позиций уедет одной дельтой.
        partChanges.changed(touched);

        log.info("Правка списком: позиций {}, полей {}, пропущено пустых {}",
                changed, changes.size(), skipped);
        return new BulkOutcome(changed, skipped);
    }

    /**
     * Итог правки списком.
     *
     * @param skipped у скольких позиций поле было пустым, и арифметике
     *                не над чем работать: они не тронуты этим полем
     */
    public record BulkOutcome(int changed, int skipped) {
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

    private Applied apply(Part part, String field, Object value, PriceOperation op,
                          Long authorId) {
        switch (field) {
            case "price" -> {
                Money money = money(op, part.getPrice(), value, part, "цена");
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
                Money money = money(op, part.getMinPrice(), value, part, "минимальная цена");
                if (money.change()) {
                    part.setMinPrice(money.value());
                }
                return money.skipped() ? Applied.SKIPPED : Applied.CHANGED;
            }
            case "costPrice" -> {
                Money money = money(op, part.getCostPrice(), value, part, "себестоимость");
                if (money.change()) {
                    part.setCostPrice(money.value());
                }
                return money.skipped() ? Applied.SKIPPED : Applied.CHANGED;
            }
            case "installationPrice" -> {
                Money money = money(op, part.getInstallationPrice(), value, part,
                        "цена установки");
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
                               String what) {
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
        BigDecimal next = op.apply(current, operand,
                "%s позиции %s".formatted(what, part.getPublicCode()));
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
     */
    static final java.util.Set<String> MONEY_FIELDS = java.util.Set.of(
            "price", "minPrice", "costPrice", "installationPrice");

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
                             boolean published,
                             // Что сделать с ценой: заменить (умолчание), подвинуть
                             // процентом или суммой, округлить. При арифметике
                             // price — значение операции, а не новая цена.
                             PriceOperation priceOp) {
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
     */
    /**
     * Условие поиска продавца — одно на выдачу и на счёт.
     *
     * <p>Разойдись они, и «показаны 50 из 741» называло бы число, посчитанное
     * не тем условием, которым собран список: продавец сузил бы запрос
     * по неверной подсказке. Та же причина, по которой отбор один у страницы
     * витрины, её выгрузки и правки списком.
     */
    private static final String STOCK_SEARCH_MATCH = """
                 WHERE p.id IN (
                         SELECT id FROM part WHERE public_code ILIKE ?
                          UNION SELECT id FROM part WHERE title ILIKE ?
                          UNION SELECT id FROM part
                                 WHERE to_tsvector('russian', coalesce(title, '') || ' '
                                           || coalesce(description, '') || ' '
                                           || coalesce(marking, ''))
                                       @@ plainto_tsquery('russian', ?)
                          UNION SELECT part_id FROM part_oem WHERE raw_number ILIKE ?%s)""";

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
            return new Match(" WHERE p.id IN (SELECT part_id FROM part_wheel WHERE true"
                    + wheels + ")", wheelArgs, size.text() == null ? "" : size.text());
        }

        String like = "%" + text.strip() + "%";
        return new Match(STOCK_SEARCH_MATCH.formatted(""),
                new ArrayList<>(List.of(like, like, text, like)), text);
    }

    private static void appendSize(StringBuilder where, List<Object> args,
                                   String column, Object value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    /**
     * @param sql  условие отбора со своими ветками
     * @param args его параметры по порядку
     * @param rankText текст для ранжирования выдачи; отдельно от параметров,
     *                 потому что при отборе по размеру их порядок другой —
     *                 брать «третий по счёту» значило бы однажды подставить
     *                 в {@code plainto_tsquery} ширину шины
     */
    private record Match(String sql, List<Object> args, String rankText) {
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
        List<Object> args = new ArrayList<>(match.args());
        args.addAll(narrowing.args());
        String order = orderBy(filter);
        if (order == null) {
            // Ранжирование берёт тот же текст, что и поиск по словам —
            // и только когда порядок не задан продавцом: выбрав «цена
            // по возрастанию», он спрашивает про цену, а не про совпадение.
            args.add(match.rankText());
        }
        args.add(limit);
        List<StockRow> rows = jdbc.query("""
                SELECT p.id, p.public_code, p.title, p.price, p.status,
                       w.id AS warehouse_id, w.name AS warehouse_name,
                       c.code AS cell_code,
                       s.qty, s.qty_reserved, s.qty - s.qty_reserved AS qty_available
                  FROM part p
                  JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
                  JOIN warehouse w ON w.id = s.warehouse_id
                  LEFT JOIN storage_cell c ON c.id = s.cell_id
                """ + match.sql() + narrowing.sql()
                // Разделитель явной строкой, а не отступом текстового блока:
                // у блока, чьи кавычки стоят на строке содержимого, срезается
                // весь отступ, и «= ?» склеивалось с «ORDER BY» в «?ORDER BY» —
                // отказ Postgres на грамматике, то есть пятисотка на живом
                // запросе. Та же ловушка, что «ENDAS supply» у выгрузки колёс.
                + "\n" + (order != null ? order : """
                 ORDER BY (s.qty - s.qty_reserved > 0) DESC,
                          ts_rank(to_tsvector('russian', coalesce(p.title, '') || ' '
                              || coalesce(p.description, '') || ' '
                              || coalesce(p.marking, '')),
                              plainto_tsquery('russian', ?)) DESC,
                          p.id""")
                + "\n LIMIT ?",
                (rs, i) -> new StockRow(
                        rs.getLong("id"),
                        rs.getString("public_code"),
                        rs.getString("title"),
                        rs.getBigDecimal("price"),
                        rs.getString("status"),
                        rs.getLong("warehouse_id"),
                        rs.getString("warehouse_name"),
                        rs.getString("cell_code"),
                        rs.getBigDecimal("qty"),
                        rs.getBigDecimal("qty_reserved"),
                        rs.getBigDecimal("qty_available")),
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
        Long found = jdbc.queryForObject("""
                SELECT count(*)
                  FROM part p
                  JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
                """ + match.sql() + narrowing.sql(),
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
        List<String[]> found = jdbc.query("""
                SELECT DISTINCT b.name AS brand, m.name AS model,
                """ + CatalogService.QUALITY_GRADE + " AS grade" + """

                  FROM part p
                  JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                """ + match.sql() + """
                 ORDER BY 1, 2, 3""",
                (rs, i) -> new String[]{
                        rs.getString("brand"), rs.getString("model"), rs.getString("grade")},
                match.args().toArray());

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
            "price", "p.price",
            "intake", "p.created_at");

    /**
     * @return {@code null}, если порядок продавцом не задан, — тогда выдача
     *         идёт по совпадению, как раньше
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
        return " ORDER BY " + column + (filter.descending() ? " DESC" : " ASC")
                + " NULLS LAST, p.id";
    }

    /**
     * Отбор продавца: марка, модель, год, стороны, склад, оценка и цена.
     *
     * <p>Марка, модель и год берутся у машины, с которой снята деталь, —
     * тем же полем, каким названа колонка «Марка» на витрине владельца.
     * У позиции без машины марки нет, и отбор по марке её не найдёт: это
     * то же поведение, что у витрины, и продавец видит в списке ровно те
     * марки, которые в найденном есть.
     */
    private static Narrowing narrowingOf(StockFilter filter) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();

        StringBuilder donor = new StringBuilder();
        List<Object> donorArgs = new ArrayList<>();
        if (notBlank(filter.brand())) {
            donor.append(" AND b.name = ?");
            donorArgs.add(filter.brand().strip());
        }
        if (notBlank(filter.model())) {
            donor.append(" AND m.name = ?");
            donorArgs.add(filter.model().strip());
        }
        if (filter.yearFrom() != null) {
            donor.append(" AND d.year >= ?");
            donorArgs.add(filter.yearFrom());
        }
        if (filter.yearTo() != null) {
            donor.append(" AND d.year <= ?");
            donorArgs.add(filter.yearTo());
        }
        if (!donor.isEmpty()) {
            where.append("\n AND p.donor_id IN (SELECT d.id FROM donor d")
                    .append(" LEFT JOIN catalog.brand b ON b.id = d.brand_id")
                    .append(" LEFT JOIN catalog.model m ON m.id = d.model_id")
                    .append(" WHERE true").append(donor).append(")");
            args.addAll(donorArgs);
        }

        if (notBlank(filter.side())) {
            where.append("\n AND p.side_lr = ?");
            args.add(sideOf(filter.side()));
        }
        if (notBlank(filter.position())) {
            where.append("\n AND p.side_fr = ?");
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

    /** Строка выдачи продавцу: деталь на конкретном складе. */
    public record StockRow(Long partId, String publicCode, String title, BigDecimal price,
                           String status, Long warehouseId, String warehouseName,
                           String cellCode, BigDecimal qty, BigDecimal qtyReserved,
                           BigDecimal qtyAvailable) {
    }

    /**
     * Включает или выключает выгрузку позиций на площадки.
     *
     * <p>Пачкой, а не по одной: после импорта склада исключений набирается
     * несколько сотен, и по одному их отмечать никто не будет.
     *
     * <p>Обратное действие полное: снятый флаг убирает объявление не сразу.
     * У Дрома оно уезжает с {@code available = false} в ближайшем прайсе —
     * удалять его нельзя, вместе с ним пропадут накопленные просмотры.
     *
     * @return сколько позиций изменилось
     */
    @Transactional
    public int setPublished(List<Long> partIds, boolean published) {
        if (partIds == null || partIds.isEmpty()) {
            throw new IllegalArgumentException("Не указано ни одной позиции");
        }
        int updated = jdbc.update("UPDATE part SET is_published = ?, updated_at = now() WHERE id = ANY (?)",
                published, partIds.toArray(Long[]::new));
        // Снятая с публикации позиция обязана уехать недоступной, иначе
        // объявление висит, а продавать её владелец не собирался.
        partChanges.changed(partIds);
        return updated;
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

    private byte[] payloadOf(Part part) {
        return EventPayloads.write(new PartEvent(part.getId(), part.getPublicCode(),
                part.getTitle(), part.getPrice(), String.valueOf(part.getStatus())));
    }
}
