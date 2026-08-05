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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

    public PartService(PartRepository partRepository, DomainEventPublisher eventPublisher,
                       PartNameService partNames, PartChangeLog partChanges, JdbcTemplate jdbc,
                       VehicleWords vehicleWords) {
        this.partRepository = partRepository;
        this.eventPublisher = eventPublisher;
        this.partNames = partNames;
        this.partChanges = partChanges;
        this.jdbc = jdbc;
        this.vehicleWords = vehicleWords;
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
     */
    @Transactional
    public Part update(Long partId, PartUpdate update, Long authorId) {
        Part part = partRepository.findById(partId)
                .orElseThrow(() -> new IllegalArgumentException("Запчасть не найдена: " + partId));

        if (update.price() != null && update.price().signum() < 0) {
            throw new IllegalArgumentException("Цена не может быть отрицательной");
        }

        boolean priceChanged = update.price() != null
                && (part.getPrice() == null || part.getPrice().compareTo(update.price()) != 0);
        if (priceChanged) {
            part.changePrice(update.price(), authorId);
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
        part.setBarcode(update.barcode());
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
     * @return сколько карточек изменилось
     */
    @Transactional
    public int updateAll(List<Long> partIds, Map<String, Object> changes, Long authorId) {
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

        int changed = 0;
        List<Long> touched = new java.util.ArrayList<>();
        for (Long partId : partIds) {
            Part part = partRepository.findById(partId).orElse(null);
            if (part == null) {
                continue;
            }
            touched.add(partId);
            boolean priceChanged = false;
            for (var change : changes.entrySet()) {
                priceChanged |= apply(part, change.getKey(), change.getValue(), authorId);
            }
            part.touchedBy(authorId);
            changed++;

            if (priceChanged) {
                eventPublisher.publish(DomainEvent.of(
                        "part", part.getId(), "part.price_changed.v1", payloadOf(part)));
            }
        }
        // Одной отметкой на позицию, а не по полю: площадке нужно текущее
        // состояние, и правка сотни позиций уедет одной дельтой.
        partChanges.changed(touched);

        log.info("Правка списком: позиций {}, полей {}", changed, changes.size());
        return changed;
    }

    /** @return правда, если это была смена цены на другую */
    private boolean apply(Part part, String field, Object value, Long authorId) {
        switch (field) {
            case "price" -> {
                BigDecimal price = decimal(value);
                boolean other = price != null
                        && (part.getPrice() == null || part.getPrice().compareTo(price) != 0);
                if (other) {
                    part.changePrice(price, authorId);
                }
                return other;
            }
            case "minPrice" -> part.setMinPrice(decimal(value));
            case "costPrice" -> part.setCostPrice(decimal(value));
            case "installationPrice" -> part.setInstallationPrice(decimal(value));
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
        return false;
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
                             boolean published) {
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
                          UNION SELECT part_id FROM part_oem WHERE raw_number ILIKE ?)""";

    @Transactional(readOnly = true)
    public StockSearch searchAvailable(String query, int limit) {
        // «фара камри» приводится к «фара Camry»: покупатель звонит и говорит
        // по-русски, а в заголовке стоит латиница.
        String text = vehicleWords.translate(query);
        String like = "%" + text.strip() + "%";
        List<StockRow> rows = jdbc.query("""
                SELECT p.id, p.public_code, p.title, p.price, p.status,
                       w.id AS warehouse_id, w.name AS warehouse_name,
                       c.code AS cell_code,
                       s.qty, s.qty_reserved, s.qty - s.qty_reserved AS qty_available
                  FROM part p
                  JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
                  JOIN warehouse w ON w.id = s.warehouse_id
                  LEFT JOIN storage_cell c ON c.id = s.cell_id
                """ + STOCK_SEARCH_MATCH + """
                 ORDER BY (s.qty - s.qty_reserved > 0) DESC,
                          ts_rank(to_tsvector('russian', coalesce(p.title, '') || ' '
                              || coalesce(p.description, '') || ' '
                              || coalesce(p.marking, '')),
                              plainto_tsquery('russian', ?)) DESC,
                          p.id
                 LIMIT ?""",
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
                // Четыре ветки UNION, потом ранжирование, потом предел.
                like, like, text, like, text, limit);

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
        long total = rows.size() < limit ? rows.size() : countAvailable(like, text);
        return new StockSearch(rows, total);
    }

    private long countAvailable(String like, String text) {
        Long found = jdbc.queryForObject("""
                SELECT count(*)
                  FROM part p
                  JOIN part_stock s ON s.part_id = p.id AND s.qty > 0
                """ + STOCK_SEARCH_MATCH,
                Long.class, like, like, text, like);
        return found == null ? 0 : found;
    }

    /**
     * Выдача продавцу вместе с общим числом найденного.
     *
     * @param total сколько нашлось всего; больше длины {@code rows} — список
     *              обрезан, и экран обязан об этом сказать
     */
    public record StockSearch(List<StockRow> rows, long total) {
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
