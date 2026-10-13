package ru.partsflow.migration.excel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.catalog.PartName;
import ru.partsflow.catalog.PartNameService;
import ru.partsflow.migration.excel.ColumnMapping.Field;
import ru.partsflow.migration.excel.ExcelWarehouseImporter.Report.Skipped;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Перенос склада из таблицы клиента.
 *
 * <p>Без этого ни один клиент не переходит: склад в три тысячи позиций
 * не перезаносят руками, а в пятьдесят — тем более.
 *
 * <p><b>Пишется движениями, а не остатком.</b> Соблазн проставить
 * {@code part.qty_on_hand} прямо велик — так быстрее, — но остаток здесь
 * агрегат журнала, и запись мимо него разъедется с {@code part_stock}
 * на первой же продаже. Каждая строка таблицы даёт приходное движение,
 * а остаток считает триггер.
 *
 * <p><b>Наименование идёт через тот же справочник, что и приёмка.</b>
 * {@code PartNameService.resolve} заводит его, если такого не было, и пытается
 * сопоставить с эталоном точным совпадением. Не сопоставилось — позиция
 * попадает на экран нераспознанных вместе с теми, что завёл приёмщик.
 * Отдельный путь «импортированные наименования» означал бы второй справочник,
 * который расходится с первым.
 *
 * <p><b>Категория берётся от эталона или не берётся вовсе.</b> Придумать её
 * при импорте нельзя: категория — свойство вида детали, а не строки чужой
 * таблицы. Позиция без категории не уедет на площадку, и это правильное
 * следствие: пока наименование не разобрано, публиковать нечего.
 */
@Service
public class ExcelWarehouseImporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelWarehouseImporter.class);

    /** Сколько строк показываем в предпросмотре. Больше человеку не нужно. */
    private static final int PREVIEW_ROWS = 15;

    private static final int BATCH_SIZE = 500;

    /** Значение «Наличия» с карточки Дрома, при котором позиция заводится. */
    static final String IN_STOCK = "В наличии";

    private final JdbcTemplate jdbc;
    private final PartNameService partNames;

    private final ru.partsflow.inventory.StockLedger ledger;
    private final ru.partsflow.inventory.PartChangeLog changeLog;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public ExcelWarehouseImporter(JdbcTemplate jdbc, PartNameService partNames,
                                  ru.partsflow.inventory.StockLedger ledger,
                                  ru.partsflow.inventory.PartChangeLog changeLog,
                                  com.fasterxml.jackson.databind.ObjectMapper json) {
        this.jdbc = jdbc;
        this.partNames = partNames;
        this.ledger = ledger;
        this.changeLog = changeLog;
        this.json = json;
    }

    /**
     * Разбирает файл, ничего не записывая.
     *
     * <p>Обязательный шаг: сопоставление колонок — догадка, а ошибка в ней
     * тихая. Перепутанные цена и количество дают склад, где всё по три рубля,
     * и замечают это на первой продаже.
     */
    public Preview preview(InputStream in) throws Exception {
        List<String> header = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        ExcelSheetReader.read(in, row -> {
            if (header.isEmpty()) {
                header.addAll(row.cells());
                return true;
            }
            if (!row.isEmpty()) {
                rows.add(row.cells());
            }
            return rows.size() < PREVIEW_ROWS;
        });

        if (header.isEmpty()) {
            throw new IllegalArgumentException("В файле нет ни одной строки");
        }
        ColumnMapping mapping = ColumnMapping.detect(header);
        return new Preview(header, mapping.importedFields(), mapping.missingRequired(), rows,
                mapping.isDromTransfer() ? Preview.DROM_TRANSFER : null);
    }

    /**
     * Заливает склад.
     *
     * <p><b>Повтор с тем же ключом не заводит второй склад.</b> Загрузка —
     * самая разрушительная операция в системе: тысячи позиций, и отменить её
     * можно только восстановлением из бэкапа. Первая же проверка это
     * и показала: запись прошла, ответ упал на сериализации, владелец увидел
     * ошибку и нажал ещё раз — склад стал двойным.
     *
     * <p>Ключ занимается вставкой в начале той же транзакции. Второй
     * одновременный запрос упрётся в уникальный индекс, а не в проверку
     * «нет ли уже такого», которая его пропустила бы. Сорвавшийся импорт
     * откатит и ключ, поэтому повторить его можно.
     *
     * @param warehouseId куда класть остаток. Один на всю таблицу: колонка
     *                    склада в чужих выгрузках встречается редко, а
     *                    раскладывать по несуществующим складам нечем
     * @param requestId   ключ клиента: генерируется при выборе файла
     *                    и не меняется при повторах
     */
    @Transactional
    public Report importInto(InputStream in, ColumnMapping mapping, long warehouseId,
                             String requestId) throws Exception {

        List<Field> missing = mapping.missingRequired();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Не сопоставлены обязательные колонки: " + missing);
        }

        Report already = replayOf(requestId);
        if (already != null) {
            log.info("Повтор загрузки по ключу {}: отдаём прежний итог", requestId);
            return already;
        }
        // Занимаем ключ до работы: второй одновременный запрос упрётся сюда.
        jdbc.update("INSERT INTO import_run (client_request_id, warehouse_id) VALUES (?, ?)",
                requestId, warehouseId);

        Map<String, Long> cells = existingCells(warehouseId);
        // Наименования кэшируются: строк пятьдесят тысяч, а различных названий
        // среди них — тысячи. Ходить в справочник на каждую строку значит
        // потратить импорт на повторные запросы об одном и том же.
        Map<String, PartName> names = new HashMap<>();
        Tally report = new Tally();
        List<Row> batch = new ArrayList<>(BATCH_SIZE);
        boolean[] headerSeen = {false};
        // Хвосты длинных значений файла переноса с Дрома (лист «Продолжение»):
        // склеиваются с ячейкой до разбора строки, см. DromContinuation.
        DromContinuation continuation = new DromContinuation();
        boolean[] stitching = {false};

        String firstSheet = ExcelSheetReader.read(in, DromContinuation.SHEET, side -> {
            continuation.accept(side);
            return true;
        }, row -> {
            if (!headerSeen[0]) {
                headerSeen[0] = true;
                // Сопоставление сюда приходит без заголовка — файл переноса
                // узнаётся по самой первой строке.
                stitching[0] = DromTransferFile.recognizes(row.cells()) && !continuation.isEmpty();
                if (stitching[0]) {
                    continuation.header(row.cells());
                }
                return true;
            }
            if (row.isEmpty()) {
                return true;
            }
            if (stitching[0]) {
                row = continuation.stitch(row);
            }
            Row parsed = parse(row, mapping, report);
            if (parsed != null) {
                batch.add(parsed);
                if (batch.size() >= BATCH_SIZE) {
                    flush(batch, warehouseId, cells, names, report);
                }
            }
            return true;
        });
        flush(batch, warehouseId, cells, names, report);
        if (stitching[0]) {
            continuation.finish(firstSheet).forEach(p -> report.continuation(p.row(), p.reason()));
        }

        // Движения записаны, применить их надо пачкой: тысяча строк — тысяча
        // обращений к базе, если делать это по одному. Остаток и статус
        // считаются по журналу, поэтому результат тот же. Повтор, не заведший
        // ни одной позиции, движений не писал — и пересчитывать ему нечего:
        // пересчёт трогает каждую строку part_stock, и шесть одновременных
        // повторов дрались бы за них вместо того, чтобы разойтись.
        if (report.imported > 0) {
            ledger.recomputeAll();
        }

        log.info("Импорт из таблицы: заведено {} позиций, дозаполнено {}, без изменений {}, "
                        + "расхождений {}, пропущено {}",
                report.imported, report.refilled, report.unchanged, report.discrepancyCount,
                report.skipped.size());

        Report result = report.toReport();
        jdbc.update("UPDATE import_run SET imported = ?, skipped = ?::jsonb "
                        + "WHERE client_request_id = ?",
                result.imported(), skippedJson(result), requestId);
        return result;
    }

    /**
     * Итог прошлой загрузки с тем же ключом.
     *
     * <p>Повтор получает ответ, а не отказ: клиент не отличает «получилось
     * только что» от «получилось в прошлый раз», а увидев ошибку — нажмёт
     * ещё раз.
     */
    private Report replayOf(String requestId) {
        List<Integer> imported = jdbc.queryForList(
                "SELECT imported FROM import_run WHERE client_request_id = ?",
                Integer.class, requestId);
        if (imported.isEmpty()) {
            return null;
        }
        return new Report(imported.get(0), skippedOf(requestId), quantityDefaultedOf(requestId),
                repeatOf(requestId));
    }

    /**
     * Дозаполнение и расхождения прошлого прогона — тем же обходом, что число
     * «по одной штуке»: элемент {@code {"repeat": …}} в {@code skipped}
     * (своей колонки у {@code import_run} нет, а заводить её — changeset).
     * Повтор по ключу смотрят, когда первого ответа не видели, и «заведено 0»
     * без «дозаполнено 2 410» читалось бы как «ничего не произошло».
     */
    private Report.Repeat repeatOf(String requestId) {
        List<String> stored = jdbc.queryForList("""
                        SELECT (e->'repeat')::text
                          FROM import_run r
                         CROSS JOIN LATERAL jsonb_array_elements(r.skipped) e
                         WHERE r.client_request_id = ?
                           AND e->'repeat' IS NOT NULL""",
                String.class, requestId);
        if (stored.isEmpty()) {
            return Report.Repeat.NONE;
        }
        try {
            return json.readValue(stored.get(0), Report.Repeat.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Итог повтора по ключу {} не разобран: {}", requestId, e.getMessage());
            return Report.Repeat.NONE;
        }
    }

    /**
     * Сколько позиций прошлый прогон завёл по одной штуке.
     *
     * <p>Число лежит отдельным элементом в том же {@code skipped}: своей
     * колонки у {@code import_run} нет, а заводить её ради одного числа
     * значит changeset и отдельную ветку. Потерять его на повторе нельзя —
     * повтор смотрят тогда, когда первого ответа не видели, и «заведено
     * 2 897» без «по одной штуке» читается как точный склад.
     */
    private int quantityDefaultedOf(String requestId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COALESCE(sum((e->>'quantityDefaulted')::int), 0)::int
                          FROM import_run r
                         CROSS JOIN LATERAL jsonb_array_elements(r.skipped) e
                         WHERE r.client_request_id = ?
                           AND e->>'quantityDefaulted' IS NOT NULL""",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }

    /**
     * Пропущенные строки прошлого прогона — разбирает их Postgres, а не мы.
     *
     * <p>Колонка объявлена {@code jsonb}, и база хранит не тот текст, что мы
     * записали, а свой: пробел после двоеточия, свой порядок ключей. Разбор
     * регуляркой по нашему же написанию не совпадал с этим ни разу, и повтор
     * загрузки отвечал «пропущено 0» при непустом списке — то есть терял
     * ровно тот перечень строк, ради которого повтор и смотрят. «Пропущено
     * 0» читается как «всё загрузилось», и битые строки владелец
     * не исправляет никогда.
     *
     * <p>Записываем по-прежнему руками: список короткий и плоский, а тянуть
     * сериализатор ради двух полей значит связать импортёр с представлением.
     * Читать так же нельзя — на чтении собеседник не мы, а база.
     */
    private List<Report.Skipped> skippedOf(String requestId) {
        return jdbc.query("""
                        SELECT (e->>'row')::int AS row_number, e->>'reason' AS reason,
                               e->>'sheet' AS sheet
                          FROM import_run r
                         CROSS JOIN LATERAL jsonb_array_elements(r.skipped) e
                         WHERE r.client_request_id = ?
                           AND e->>'reason' IS NOT NULL""",
                (rs, i) -> new Report.Skipped(rs.getInt("row_number"), rs.getString("reason"),
                        rs.getString("sheet")),
                requestId);
    }

    /**
     * Пропущенные строки в JSON.
     *
     * <p>Руками, без Jackson: список короткий и плоский, а тянуть сюда
     * сериализатор ради двух полей значит связать импортёр с представлением.
     * Кавычки в причине заменяются — причины пишем мы сами, и кавычек
     * в них нет, но полагаться на это нельзя.
     *
     * <p>Число позиций, заведённых по одной штуке, дописывается в конец
     * отдельным элементом без {@code row} — почему здесь, а не своей
     * колонкой, сказано у {@link #quantityDefaultedOf}.
     */
    private String skippedJson(Report report) {
        java.util.stream.Stream<String> skipped = report.skipped().stream()
                .map(s -> "{\"row\":%d,\"reason\":\"%s\"%s}"
                        .formatted(s.row(), jsonText(s.reason()), s.sheet() == null ? ""
                                : ",\"sheet\":\"" + jsonText(s.sheet()) + "\""));
        java.util.stream.Stream<String> defaulted = report.quantityDefaulted() == 0
                ? java.util.stream.Stream.empty()
                : java.util.stream.Stream.of(
                        "{\"quantityDefaulted\":%d}".formatted(report.quantityDefaulted()));
        // Итог повтора — вложенный и с текстом из файла, поэтому сериализатором,
        // а не руками: экранировать значения ячеек самим значит повторить
        // ту дыру, что уже ронялась на обратной косой (задача 0281).
        java.util.stream.Stream<String> repeat;
        try {
            repeat = java.util.stream.Stream.of(
                    "{\"repeat\":" + json.writeValueAsString(report.repeat()) + "}");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("итог повтора не записан", e);
        }
        return java.util.stream.Stream.of(skipped, defaulted, repeat)
                .flatMap(s -> s)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * Причина как строка JSON без кавычек по краям.
     *
     * <p>Причина несёт текст из файла — «Наличие» дословно, название колонки
     * с листа «Продолжение» (задача 0281), — и обратная косая или перевод
     * строки в нём делали JSON битым: запись итога падала, и весь импорт
     * откатывался отказом базы вместо названной строки. Кавычка по-прежнему
     * меняется на апостроф — так было, и повтор отдаёт то же написание.
     */
    private static String jsonText(String reason) {
        StringBuilder out = new StringBuilder(reason.length());
        for (char c : reason.toCharArray()) {
            if (c == '"') {
                out.append('\'');
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }


    private Row parse(ExcelSheetReader.Row row, ColumnMapping mapping, Tally report) {
        String name = mapping.value(row, Field.NAME).strip();
        if (name.isEmpty()) {
            report.skip(row.number(), "пустое наименование");
            return null;
        }

        if (mapping.has(Field.AVAILABILITY)) {
            // Дром пишет на карточке только «В наличии» — это и есть правда
            // о том, что позиция лежит. Иное значение называется дословно:
            // по нему видно, что писал Дром, и правило можно расширить.
            String availability = mapping.value(row, Field.AVAILABILITY).strip();
            if (!IN_STOCK.equals(availability)) {
                report.skip(row.number(), availability.isEmpty()
                        ? "не в наличии: значение пустое"
                        : "не в наличии: «" + availability + "»");
                return null;
            }
        }

        BigDecimal quantity;
        boolean quantityFromFile = true;
        String rawQuantity = mapping.value(row, Field.QUANTITY);
        if (rawQuantity.isBlank()) {
            // Количества нет — ни колонки, ни значения в строке. У Дрома его
            // нет на странице вовсе (задача 0279), и пропуск здесь значил бы
            // «пропущено 2 897: нет количества» на весь перенос. Одна штука —
            // догадка, поэтому её число уходит в отчёт, а экран говорит
            // о ней до загрузки.
            quantity = BigDecimal.ONE;
            report.quantityDefaulted++;
            quantityFromFile = false;
        } else {
            quantity = number(rawQuantity);
            if (quantity == null || quantity.signum() <= 0) {
                // Ноль — это не остаток, а строка о том, что позиции нет.
                // Заводить карточку под неё значит наполнить склад несуществующим.
                // То же со словом вместо числа: «нет» в колонке количества
                // одной штукой не читается.
                report.skip(row.number(), "нет количества");
                return null;
            }
        }

        String adNumber = dromAdNumber(mapping.value(row, Field.DROM_AD_NUMBER));
        if (adNumber != null && !adNumber.matches("[0-9]+")) {
            // Число в Excel («1,26E+08») или слово вместо номера: заводить позицию
            // с номером объявления, который нельзя сверить с кабинетом, значит
            // пообещать связь, которой нет. Строку пропускаем и называем, что в ней.
            report.skip(row.number(), "номер объявления Дрома не из цифр: «"
                    + shortened(adNumber) + "»");
            return null;
        }

        // Поля карточки Дрома (задача 0277). Слово, которого разборщик
        // не знает, останавливает строку и называется дословно: молча
        // подставленная сторона или «б/у» — объявление клиента, переехавшее
        // неправдой (почему не умолчание — в DromCardWords).
        CardFields card;
        try {
            card = new CardFields(
                    DromCardWords.condition(mapping.value(row, Field.CONDITION)),
                    blankToNull(mapping.value(row, Field.MANUFACTURER)),
                    blankToNull(mapping.value(row, Field.AUTHENTICITY)),
                    substitutes(mapping.value(row, Field.OEM_SUBSTITUTES)),
                    DromCardWords.lateral(mapping.value(row, Field.SIDE_LR)),
                    DromCardWords.longitudinal(mapping.value(row, Field.SIDE_FR)),
                    DromCardWords.vertical(mapping.value(row, Field.SIDE_UD)));
        } catch (DromCardWords.Unknown unknown) {
            report.skip(row.number(), unknown.getMessage());
            return null;
        }

        return new Row(
                row.number(),
                name,
                number(mapping.value(row, Field.PRICE)),
                quantity,
                quantityFromFile,
                blankToNull(mapping.value(row, Field.CELL)),
                blankToNull(mapping.value(row, Field.OEM)),
                blankToNull(mapping.value(row, Field.NOTE)),
                photoLinks(mapping.value(row, Field.PHOTOS)),
                adNumber,
                verbatim(mapping.value(row, Field.DESCRIPTION)),
                card);
    }

    /**
     * Номера замен из ячейки — все и в том порядке, в каком стоят.
     *
     * <p>Договор пишет их через «, » (у амортизатора клиента их 55, и все
     * обязаны доехать: по ним площадка находит деталь, когда спрашивают
     * номером аналога). Точка с запятой и перевод строки тоже делят —
     * таблица, поправленная руками, приносит и их. Повтор номера отбивает
     * {@code part_oem_uk}, совпадение с главным — он же.
     */
    static List<String> substitutes(String cell) {
        if (cell == null || cell.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(cell.split("[,;\\n\\r]"))
                .map(String::strip)
                .filter(number -> !number.isEmpty())
                .toList();
    }

    /**
     * Ссылки на снимки из ячейки — в том порядке, в каком они там стоят.
     *
     * <p><b>Порядок и есть смысл колонки.</b> Первая ссылка — обложка
     * объявления у клиента, и она обязана остаться главной у нас: перенос
     * отмечает главным снимок с нулевым порядком. Повтор одной ссылки
     * в ячейке оставляет её на первом месте, где она встретилась, а не
     * дырой в нумерации.
     *
     * <p>Делится по любому пробельному знаку, а не только по переводу
     * строки: в адресе пробела не бывает, а таблица, прошедшая через Excel,
     * приносит {@code \r\n} и пробелы по краям. Что не похоже на адрес,
     * не отбрасывается молча — уходит в очередь и отказывает там с причиной
     * словами, как любая битая ссылка: молча потерянный снимок не найдёт
     * никто.
     */
    static List<String> photoLinks(String cell) {
        if (cell == null || cell.isBlank()) {
            return List.of();
        }
        return List.copyOf(new java.util.LinkedHashSet<>(
                List.of(cell.strip().split("\\s+"))));
    }


    /**
     * Номер объявления из ячейки: края обрезаны, знак «№» перед числом
     * снят (на карточке Дрома он стоит именно так), пусто — {@code null}.
     * Пустая строка в базу не уйдёт никогда: колонка {@code NULL} либо цифры.
     */
    private static String dromAdNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.startsWith("№")) {
            value = value.substring(1).strip();
        }
        return value.isEmpty() ? null : value;
    }

    private static String shortened(String value) {
        return value.length() <= 30 ? value : value.substring(0, 30) + "…";
    }

    private void flush(List<Row> batch, long warehouseId, Map<String, Long> cells,
                       Map<String, PartName> names, Tally report) {
        for (Row row : batch) {
            // Объявление, которое у нас уже есть, второй позицией не заводится:
            // повтор файла переноса не должен удваивать склад клиента. Но и не
            // пропускается — пустые поля позиции дозаполняются из файла
            // (задача 0282, см. refill). Проверка стоит до работы со справочником
            // и ячейками, чтобы узнанная строка не оставляла побочных заведений.
            if (row.dromAd() != null) {
                if (!report.seenAds.add(row.dromAd())) {
                    // Тот же номер второй раз в одном файле — это изъян файла,
                    // а не второй заход: дозаполнять позицию её же соседней
                    // строкой значило бы выбрать одну из двух молча.
                    skipAsTaken(row, report);
                    continue;
                }
                Map<String, Object> existing = existing(row.dromAd());
                if (existing != null) {
                    refill(row, existing, report);
                    continue;
                }
            }
            Long cellId = row.cell() == null ? null
                    : cells.computeIfAbsent(row.cell().toUpperCase(),
                            code -> createCell(warehouseId, row.cell()));

            PartName partName = names.computeIfAbsent(
                    row.name().toLowerCase(), key -> partNames.resolve(row.name(), null));

            // ON CONFLICT — на одновременный повтор: проверка выше читает, а между
            // чтением и вставкой встанет вторая загрузка. Индекс дубль не пустит
            // в любом случае, но без этого человек получил бы «Операция нарушает
            // целостность данных» на весь файл вместо названной позиции.
            CardFields card = row.card();
            List<Long> inserted = jdbc.queryForList("""
                    INSERT INTO part (category_id, part_name_id, title, price, note,
                                      storage_cell_id, drom_ad_number, description,
                                      condition, manufacturer, authenticity,
                                      side_lr, side_fr, side_ud)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (drom_ad_number) WHERE drom_ad_number IS NOT NULL
                    DO NOTHING
                    RETURNING id""",
                    Long.class, partName.getCategoryId(), partName.getId(),
                    row.name(), row.price(), row.note(), cellId, row.dromAd(),
                    row.description(),
                    (card.condition() == null
                            ? ru.partsflow.inventory.PartCondition.USED : card.condition()).name(),
                    card.manufacturer(), card.authenticity(),
                    nameOf(card.sideLr()), nameOf(card.sideFr()), nameOf(card.sideUd()));
            if (inserted.isEmpty()) {
                // Одновременная загрузка того же файла завела позицию между
                // нашим чтением и вставкой: к этому моменту она закоммичена
                // (вставка ждала её), и строка — такой же повтор, как и любой.
                Map<String, Object> existing = existing(row.dromAd());
                if (existing == null) {
                    skipAsTaken(row, report);
                } else {
                    refill(row, existing, report);
                }
                continue;
            }
            Long partId = inserted.get(0);

            // Номер, от которого после приведения ничего не осталось, — не номер:
            // колонка normalized объявлена NOT NULL, и такая строка отвечает
            // отказом базы вместо загруженной позиции.
            String normalizedOem = ru.partsflow.catalog.OemNumbers.normalize(row.oem());
            if (normalizedOem != null) {
                jdbc.update("""
                        INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                        VALUES (?, ?, ?, true)
                        ON CONFLICT DO NOTHING""",
                        partId, row.oem(), normalizedOem);
            }
            // Номера замен — не главные, по одному на строку и в порядке
            // ячейки: прайс и карточка собирают их по порядку заведения.
            // Номер, от которого после приведения ничего не осталось,
            // отбрасывается — та же причина, что у главного выше.
            for (String substitute : card.substitutes()) {
                String normalized = ru.partsflow.catalog.OemNumbers.normalize(substitute);
                if (normalized != null) {
                    jdbc.update("""
                            INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                            VALUES (?, ?, ?, false)
                            ON CONFLICT DO NOTHING""",
                            partId, substitute, normalized);
                }
            }

            // Снимки только ставятся в очередь: качает их отдельный проход
            // (PhotoMigration), который можно прервать и продолжить. Внутри
            // запроса импорта это часы — у клиента с Дрома 2 897 карточек
            // по 14–16 снимков. Повтор той же ссылки той же позиции отбивает
            // part_photo_import_uk, а не проверка «нет ли уже такого».
            List<Object[]> photos = new ArrayList<>(row.photos().size());
            for (int order = 0; order < row.photos().size(); order++) {
                photos.add(new Object[] {partId, row.photos().get(order), order});
            }
            if (!photos.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO part_photo_import (part_id, url, sort_order)
                        VALUES (?, ?, ?)
                        ON CONFLICT (part_id, url) DO NOTHING""", photos);
            }

            // Остаток появляется движением: писать qty_on_hand напрямую значит
            // разъехаться с part_stock на первой же продаже.
            jdbc.update("""
                    INSERT INTO stock_movement (part_id, movement_type, qty_delta,
                                                to_warehouse_id, to_cell_id, reason)
                    VALUES (?, 'INTAKE', ?, ?, ?, 'Импорт из таблицы')""",
                    partId, row.quantity(), warehouseId, cellId);
            // Применяется это пачкой, после всех строк — см. recomputeAll
            // в конце importInto.

            report.imported++;
            if (row.dromAd() == null) {
                report.withoutAdNumber++;
            }
        }
        batch.clear();
    }

    /** Второе появление номера в одном файле: строка пропущена и названа. */
    private void skipAsTaken(Row row, Tally report) {
        List<String> holder = jdbc.queryForList(
                "SELECT public_code FROM part WHERE drom_ad_number = ?",
                String.class, row.dromAd());
        report.skip(row.number(), "объявление № " + row.dromAd()
                + (holder.isEmpty() ? " встречается в файле повторно"
                        : " уже у позиции " + holder.get(0)));
    }

    /** Позиция с этим номером объявления, со всем, что сверяет повтор; нет — {@code null}. */
    private Map<String, Object> existing(String adNumber) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT p.id, p.public_code, p.title, p.price, p.qty_on_hand, c.code AS cell,
                       p.note, p.description, p.condition, p.manufacturer, p.authenticity,
                       p.side_lr, p.side_fr, p.side_ud
                  FROM part p
                  LEFT JOIN storage_cell c ON c.id = p.storage_cell_id
                 WHERE p.drom_ad_number = ?""", adNumber);
        return found.isEmpty() ? null : found.get(0);
    }

    // --- Повторная загрузка: дозаполнение (задача 0282) ---

    /**
     * Скалярное поле, которое повтор дозаполняет.
     *
     * @param column  колонка {@code part}; берётся из этого списка, а не из запроса
     * @param cutTail дописывать ли хвост: значение у позиции — начало значения
     *                из файла (первая загрузка была без листа «Продолжение»,
     *                и длинное значение доехало обрезанным, задача 0281)
     */
    private record Scalar(Field field, String column, String label,
                          java.util.function.Function<Row, Object> value, boolean cutTail) {
    }

    private static final List<Scalar> SCALARS = List.of(
            new Scalar(Field.PRICE, "price", "Цена", Row::price, false),
            new Scalar(Field.DESCRIPTION, "description", "Описание", Row::description, true),
            new Scalar(Field.NOTE, "note", "Примечание", Row::note, true),
            new Scalar(Field.MANUFACTURER, "manufacturer", "Производитель",
                    r -> r.card().manufacturer(), true),
            new Scalar(Field.AUTHENTICITY, "authenticity", "Оригинальность",
                    r -> r.card().authenticity(), true),
            new Scalar(Field.SIDE_LR, "side_lr", "Сторона", r -> nameOf(r.card().sideLr()), false),
            new Scalar(Field.SIDE_FR, "side_fr", "Положение", r -> nameOf(r.card().sideFr()), false),
            new Scalar(Field.SIDE_UD, "side_ud", "Верх/низ", r -> nameOf(r.card().sideUd()), false));

    /**
     * Что повтор дозаполняет — поле и его имя в отчёте, в порядке отчёта.
     *
     * <p>Правило одно на все: <b>пусто у позиции — берётся из файла, заполнено —
     * не трогается</b>, а разница называется расхождением. Владелец мог
     * поправить у нас цену или описание после переноса, и повтор файла не имеет
     * права это стереть. Обход идёт именно по этому списку ({@link #refill}),
     * поэтому поле, вынутое отсюда, перестаёт дозаполняться, а сторож
     * ({@code RepeatLoadRulesTest}) требует, чтобы каждое читаемое поле стояло
     * либо здесь, либо в {@link #NOT_REFILLED} с причиной: иначе следующая
     * задача научит импорт новой колонке, а повтор будет её молча пропускать.
     */
    static final Map<Field, String> REFILLED;

    static {
        Map<Field, String> refilled = new java.util.LinkedHashMap<>();
        refilled.put(Field.CONDITION, "Состояние");
        SCALARS.forEach(s -> refilled.put(s.field(), s.label()));
        refilled.put(Field.OEM, "Номер детали");
        refilled.put(Field.OEM_SUBSTITUTES, "Номера замен");
        refilled.put(Field.PHOTOS, "Снимки");
        REFILLED = java.util.Collections.unmodifiableMap(refilled);
    }

    /** Читаемые поля, которые повтор не дозаполняет, — и почему. */
    static final Map<Field, String> NOT_REFILLED = Map.of(
            Field.DROM_AD_NUMBER,
            "ключ, по которому повтор и узнаёт позицию",
            Field.NAME,
            "заголовок собран справочником наименований (на нём стоит part_name_id) — "
                    + "разница только называется",
            Field.QUANTITY,
            "остаток правят документом, а не загрузкой: второе движение склада удвоило бы его — "
                    + "разница только называется",
            Field.CELL,
            "адрес полки меняет перестановка, а это движение склада, которого повтор не пишет — "
                    + "разница только называется",
            Field.AVAILABILITY,
            "не поле позиции, а условие, заводить ли строку вовсе");

    /** Сколько расхождений приходит в ответе; число — полное. */
    static final int DISCREPANCY_LIMIT = 200;

    /**
     * Повтор строки, номер объявления которой уже у позиции.
     *
     * <p><b>Каждое дозаполнение — одна инструкция с условием в {@code WHERE}</b>
     * ({@code … WHERE id = ? AND col IS NULL}), а число изменённых строк и есть
     * счёт «дозаполнено»: шесть одновременных загрузок одного файла не запишут
     * поле дважды и не насчитают его шесть раз. Прочитанное до записи решает
     * только, звать ли запись и что назвать расхождением.
     *
     * <p>Остаток и движения склада не трогаются вовсе: строка файла — не приход.
     */
    private void refill(Row row, Map<String, Object> part, Tally report) {
        long partId = ((Number) part.get("id")).longValue();
        String ad = row.dromAd();
        boolean changed = false;

        for (Field field : REFILLED.keySet()) {
            String label = REFILLED.get(field);
            changed |= switch (field) {
                case CONDITION -> refillCondition(row, part, partId, label, report);
                case OEM -> refillPrimaryOem(row, partId, label, report);
                case OEM_SUBSTITUTES -> refillSubstitutes(row, partId, label, report);
                case PHOTOS -> refillPhotos(row, partId, label, report);
                default -> refillScalar(scalarOf(field), row, part, partId, report);
            };
        }

        // Сверяется, но не дозаполняется (почему — NOT_REFILLED).
        if (!row.name().equals(part.get("title"))) {
            report.discrepancy(ad, "Наименование", row.name(), (String) part.get("title"));
        }
        BigDecimal qty = (BigDecimal) part.get("qty_on_hand");
        if (row.quantityFromFile() && (qty == null || qty.compareTo(row.quantity()) != 0)) {
            report.discrepancy(ad, "Количество", plain(row.quantity()), plain(qty));
        }
        String cell = (String) part.get("cell");
        if (row.cell() != null && (cell == null || !cell.equalsIgnoreCase(row.cell()))) {
            report.discrepancy(ad, "Ячейка", row.cell(), cell);
        }

        if (changed) {
            // Площадка узнаёт о дозаполненной позиции дельтой — но только
            // о реально изменённой: повтор без изменений отметку не двигает.
            changeLog.changed(partId);
            report.refilled++;
        } else {
            report.unchanged++;
        }
    }

    private static Scalar scalarOf(Field field) {
        return SCALARS.stream().filter(s -> s.field() == field).findFirst()
                .orElseThrow(() -> new IllegalStateException("нет правила дозаполнения: " + field));
    }

    private boolean refillScalar(Scalar rule, Row row, Map<String, Object> part, long partId,
                                 Tally report) {
        Object fromFile = rule.value().apply(row);
        if (fromFile == null) {
            return false;
        }
        Object current = part.get(rule.column());
        if (same(current, fromFile)) {
            return false;
        }
        boolean fillable = current == null
                || (rule.cutTail() && current instanceof String have && fromFile instanceof String want
                        && want.length() > have.length() && want.startsWith(have));
        if (fillable) {
            // Условие повторено в WHERE: между чтением и записью поле могла
            // заполнить одновременная загрузка или человек.
            String sql = rule.cutTail()
                    ? "UPDATE part SET " + rule.column() + " = ? WHERE id = ? AND (" + rule.column()
                            + " IS NULL OR (length(?) > length(" + rule.column()
                            + ") AND starts_with(?, " + rule.column() + ")))"
                    : "UPDATE part SET " + rule.column() + " = ? WHERE id = ? AND "
                            + rule.column() + " IS NULL";
            int updated = rule.cutTail()
                    ? jdbc.update(sql, fromFile, partId, fromFile, fromFile)
                    : jdbc.update(sql, fromFile, partId);
            if (updated > 0) {
                report.filled(rule.label(), 0);
                return true;
            }
            // Ноль строк: поле заполнили между нашим чтением и записью.
            // Прочитанное раньше уже неправда — сравниваем с тем, что есть.
            current = reread(partId, rule.column());
            if (same(current, fromFile)) {
                return false;
            }
        }
        report.discrepancy(row.dromAd(), rule.label(), words(fromFile), words(current));
        return false;
    }

    /**
     * Состояние: у колонки умолчание «б/у», и по самой позиции не отличить
     * «в файле не было» от «в файле было „Б/у“». Поэтому «б/у» считается
     * пустым, <b>пока человек состояние не правил</b> — а правку видно
     * в журнале изменений: загрузка пишет мимо Hibernate и следов там
     * не оставляет, правка из карточки — оставляет снимок до и после.
     * Правленое (или любое иное, кроме «б/у») не перезаписывается.
     */
    private boolean refillCondition(Row row, Map<String, Object> part, long partId,
                                    String label, Tally report) {
        ru.partsflow.inventory.PartCondition fromFile = row.card().condition();
        if (fromFile == null) {
            return false;
        }
        String current = (String) part.get("condition");
        if (fromFile.name().equals(current)) {
            return false;
        }
        int updated = jdbc.update("""
                UPDATE part p SET condition = ?
                 WHERE p.id = ? AND p.condition = 'USED'
                   AND NOT EXISTS (
                       SELECT 1 FROM audit_log a
                        WHERE a.table_name = 'part' AND a.record_id = p.id
                          AND a.operation = 'UPDATE'
                          AND (a.old_value IS NULL
                               OR a.old_value->>'condition' IS DISTINCT FROM
                                  a.new_value->>'condition'))""",
                fromFile.name(), partId);
        if (updated > 0) {
            report.filled(label, 0);
            return true;
        }
        // Ноль строк — либо правка человека, либо одновременная загрузка уже
        // записала то же значение. Расхождение называется по тому, что лежит
        // сейчас, а не по прочитанному до записи: иначе проигравший повтор
        // сказал бы «у позиции б/у» про позицию, где уже стоит значение файла.
        current = (String) reread(partId, "condition");
        if (fromFile.name().equals(current)) {
            return false;
        }
        report.discrepancy(row.dromAd(), label, fromFile.title(), words(current));
        return false;
    }

    /** Колонка позиции сейчас; имя колонки — из списков этого класса, не из запроса. */
    private Object reread(long partId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM part WHERE id = ?",
                (rs, i) -> rs.getObject(1), partId);
    }

    /** Главный номер производителя: нет у позиции — заводится, есть другой — расхождение. */
    private boolean refillPrimaryOem(Row row, long partId, String label, Tally report) {
        String normalized = ru.partsflow.catalog.OemNumbers.normalize(row.oem());
        if (normalized == null) {
            return false;
        }
        List<Map<String, Object>> primary = jdbc.queryForList(
                "SELECT raw_number, normalized FROM part_oem WHERE part_id = ? AND is_primary",
                partId);
        if (!primary.isEmpty()) {
            if (!normalized.equals(primary.get(0).get("normalized"))) {
                report.discrepancy(row.dromAd(), label, row.oem(),
                        (String) primary.get(0).get("raw_number"));
            }
            return false;
        }
        int inserted = jdbc.update("""
                INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                SELECT ?, ?, ?, true
                 WHERE NOT EXISTS (SELECT 1 FROM part_oem WHERE part_id = ? AND is_primary)
                ON CONFLICT DO NOTHING""", partId, row.oem(), normalized, partId);
        if (inserted > 0) {
            report.filled(label, 0);
            return true;
        }
        return false;
    }

    /**
     * Номера замен: у позиции нет ни одного — заводятся все по порядку ячейки;
     * есть начало списка из файла (первая загрузка обрезала ячейку) — дописывается
     * остаток; иной список — расхождение. Совпадение с главным номером
     * в сравнение не идёт: его при первой загрузке отбил {@code part_oem_uk}.
     */
    private boolean refillSubstitutes(Row row, long partId, String label, Tally report) {
        List<String> raw = row.card().substitutes();
        if (raw.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> have = jdbc.queryForList("""
                SELECT raw_number, normalized, is_primary FROM part_oem
                 WHERE part_id = ? ORDER BY id""", partId);
        java.util.Set<String> primary = new java.util.HashSet<>();
        List<String> existing = new ArrayList<>();
        List<String> existingRaw = new ArrayList<>();
        for (Map<String, Object> oem : have) {
            if (Boolean.TRUE.equals(oem.get("is_primary"))) {
                primary.add((String) oem.get("normalized"));
            } else {
                existing.add((String) oem.get("normalized"));
                existingRaw.add((String) oem.get("raw_number"));
            }
        }
        java.util.LinkedHashMap<String, String> wanted = new java.util.LinkedHashMap<>();
        for (String number : raw) {
            String normalized = ru.partsflow.catalog.OemNumbers.normalize(number);
            if (normalized != null && !primary.contains(normalized)) {
                wanted.putIfAbsent(normalized, number);
            }
        }
        List<String> wantedKeys = new ArrayList<>(wanted.keySet());
        if (existing.equals(wantedKeys)) {
            return false;
        }
        if (existing.size() >= wantedKeys.size()
                || !wantedKeys.subList(0, existing.size()).equals(existing)) {
            report.discrepancy(row.dromAd(), label, String.join(", ", raw),
                    String.join(", ", existingRaw));
            return false;
        }
        int inserted = 0;
        for (String normalized : wantedKeys.subList(existing.size(), wantedKeys.size())) {
            inserted += jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)
                    VALUES (?, ?, ?, false)
                    ON CONFLICT DO NOTHING""", partId, wanted.get(normalized), normalized);
        }
        if (inserted > 0) {
            report.filled(label, 0);
            return true;
        }
        return false;
    }

    /**
     * Снимки: в очередь встают только ссылки, которых у позиции ещё нет,
     * и встают <b>после</b> существующих — и перенесённых, и добавленных
     * владельцем руками. Главным {@code PhotoMigration} делает снимок с нулевым
     * порядком, только если главного ещё нет, поэтому прежний главный остаётся.
     * Строка очереди после переноса остаётся ({@code DONE} с {@code photo_id}),
     * так что уже скачанный снимок по тому же адресу второй раз не заводится —
     * это держит {@code part_photo_import_uk}, а не проверка чтением.
     */
    private boolean refillPhotos(Row row, long partId, String label, Tally report) {
        if (row.photos().isEmpty()) {
            return false;
        }
        java.util.Set<String> queued = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT url FROM part_photo_import WHERE part_id = ?", String.class, partId));
        List<String> fresh = row.photos().stream().filter(url -> !queued.contains(url)).toList();
        if (fresh.isEmpty()) {
            return false;
        }
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(max(sort_order), -1) + 1 FROM (
                    SELECT sort_order FROM part_photo_import WHERE part_id = ?
                    UNION ALL
                    SELECT sort_order FROM part_photo WHERE part_id = ?) orders""",
                Integer.class, partId, partId);
        int order = next == null ? 0 : next;
        int added = 0;
        for (String url : fresh) {
            added += jdbc.update("""
                    INSERT INTO part_photo_import (part_id, url, sort_order)
                    VALUES (?, ?, ?)
                    ON CONFLICT (part_id, url) DO NOTHING""", partId, url, order++);
        }
        if (added > 0) {
            report.filled(label, added);
            return true;
        }
        return false;
    }

    private static boolean same(Object current, Object fromFile) {
        if (current instanceof BigDecimal have && fromFile instanceof BigDecimal want) {
            return have.compareTo(want) == 0;
        }
        return java.util.Objects.equals(current, fromFile);
    }

    /** Значение для отчёта словами человека: код стороны — словом Дрома, число — без хвоста нулей. */
    private static String words(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal number) {
            return plain(number);
        }
        String text = value.toString();
        String side = DromCardWords.word(text);
        if (side != null) {
            return side;
        }
        for (ru.partsflow.inventory.PartCondition condition
                : ru.partsflow.inventory.PartCondition.values()) {
            if (condition.name().equals(text)) {
                return condition.title();
            }
        }
        return text;
    }

    private static String plain(BigDecimal number) {
        return number == null ? null : number.stripTrailingZeros().toPlainString();
    }

    private Long createCell(long warehouseId, String code) {
        return jdbc.queryForObject("""
                INSERT INTO storage_cell (warehouse_id, code) VALUES (?, ?)
                ON CONFLICT (warehouse_id, code) DO UPDATE SET code = excluded.code
                RETURNING id""", Long.class, warehouseId, code);
    }

    private Map<String, Long> existingCells(long warehouseId) {
        Map<String, Long> cells = new HashMap<>();
        jdbc.query("SELECT id, code FROM storage_cell WHERE warehouse_id = ?",
                rs -> {
                    cells.put(rs.getString("code").toUpperCase(), rs.getLong("id"));
                }, warehouseId);
        return cells;
    }

    /**
     * Число из ячейки таблицы.
     *
     * <p>Запятая как разделитель дробной части, пробелы между разрядами,
     * «₽» и «руб» в той же ячейке — обычное дело в таблицах, которые вели
     * руками. Не разобралось — считаем, что значения нет: подставлять ноль
     * значит завести деталь по нулевой цене и продать её даром.
     */
    static BigDecimal number(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace(' ', ' ')
                .replaceAll("[^0-9,.-]", "")
                .replace(" ", "")
                .replace(',', '.');
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Текст клиента как есть: пусто — {@code null}, иначе ни одного знака
     * не трогаем — ни переводов строк, ни пробелов внутри, ни длины
     * (задача 0275). Описание объявления — его слова, и «повторить
     * на сто процентов» значит не нормализовать их за него.
     */
    private static String verbatim(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @param quantityFromFile {@code false} — количества в файле не было,
     *                         и одна штука подставлена: сверять её с остатком
     *                         позиции при повторе нечего
     */
    private record Row(int number, String name, BigDecimal price, BigDecimal quantity,
                       boolean quantityFromFile, String cell, String oem, String note, List<String> photos,
                       String dromAd, String description, CardFields card) {
    }

    /**
     * Поля карточки Дрома (задача 0277): состояние, производитель,
     * оригинальность, номера замен, три стороны. Отдельной записью, а не
     * семью полями {@link Row}: читаются и пишутся они одним куском.
     *
     * @param condition {@code null} — в файле не сказано, и позиция получает
     *                  умолчание колонки «б/у», как любая без слова
     */
    private record CardFields(ru.partsflow.inventory.PartCondition condition,
                              String manufacturer, String authenticity,
                              List<String> substitutes,
                              ru.partsflow.inventory.LateralSide sideLr,
                              ru.partsflow.inventory.LongitudinalSide sideFr,
                              ru.partsflow.inventory.VerticalSide sideUd) {
    }

    /**
     * Что распознали в файле. Показывается человеку до записи.
     *
     * @param detected только поля, которые импорт читает: узнанная, но
     *                 не читаемая колонка показывается «не переносится»
     * @param format   {@link #DROM_TRANSFER}, если это файл переноса с Дрома
     *                 (сопоставлен договором, а не догадкой); иначе {@code null}
     */
    public record Preview(List<String> header, Map<Field, Integer> detected,
                          List<Field> missingRequired, List<List<String>> rows,
                          String format) {

        public static final String DROM_TRANSFER = "DROM_TRANSFER";
    }

    /**
     * Итог импорта. Пропущенные строки — с номером, как их видно в Excel.
     *
     * <p>Именно record, а не класс с методами в стиле record: обычный класс
     * без getX() Jackson не сериализует, и ответ уходит пятисоткой
     * «No acceptable representation». Тесты этого не ловят — они зовут
     * импортёр напрямую, минуя HTTP.
     */
    public record Report(int imported, List<Skipped> skipped, int quantityDefaulted,
                         Repeat repeat) {

        /**
         * Что сделала загрузка с позициями, номер объявления которых уже был
         * (задача 0282). Отдельной записью: её же целиком кладём в
         * {@code import_run}, чтобы повтор по тому же ключу отдал те же числа.
         *
         * @param refilled         позиций, у которых хоть что-то дозаполнено
         * @param fields           по полям, в порядке {@link ExcelWarehouseImporter#REFILLED}
         * @param unchanged        номер совпал, нового в файле нет
         * @param discrepancyCount расхождений всего; в списке — не больше
         *                         {@link ExcelWarehouseImporter#DISCREPANCY_LIMIT}
         * @param withoutAdNumber  заведено строк без номера объявления — их
         *                         повтор файла не узнает и заведёт снова
         */
        public record Repeat(int refilled, List<FieldCount> fields, int unchanged,
                             int discrepancyCount, List<Discrepancy> discrepancies,
                             int withoutAdNumber) {

            static final Repeat NONE = new Repeat(0, List.of(), 0, 0, List.of(), 0);
        }

        /**
         * @param positions у скольких позиций поле дозаполнено
         * @param links     для снимков — сколько ссылок встало в очередь; иначе 0
         */
        public record FieldCount(String field, int positions, int links) {
        }

        /**
         * Поле, у которого в файле одно, а у позиции другое, — оставлено как есть.
         *
         * @param text строка отчёта целиком; слова собирает сервер, чтобы их
         *             можно было закрепить проверкой ответа HTTP
         */
        public record Discrepancy(String adNumber, String field, String inFile, String atPart,
                                  String text) {
        }

        /**
         * @param sheet лист, к которому относится строка; {@code null} — первый.
         *              Повреждения листа «Продолжение» несут его имя: номер
         *              строки у них — строка того листа, и экран показывает их
         *              отдельно и целиком, а не в общем списке под пределом
         *              (задача 0281)
         */
        public record Skipped(int row, String reason, String sheet) {

            public Skipped(int row, String reason) {
                this(row, reason, null);
            }
        }
    }

    /** Накопитель итога: во время разбора он меняется, наружу уходит record. */
    private static final class Tally {

        private int imported;
        /** Строк, заведённых по одной штуке, потому что количества в файле нет. */
        private int quantityDefaulted;
        private final List<Skipped> skipped = new ArrayList<>();

        /** Номера объявлений, уже встреченные в этом файле. */
        private final java.util.Set<String> seenAds = new java.util.HashSet<>();
        private int refilled;
        private int unchanged;
        private int withoutAdNumber;
        private int discrepancyCount;
        private final List<Report.Discrepancy> discrepancies = new ArrayList<>();
        /** Поле → {позиций, ссылок}. */
        private final Map<String, int[]> filled = new java.util.LinkedHashMap<>();

        Tally() {
            REFILLED.values().forEach(label -> filled.put(label, new int[2]));
        }

        void filled(String label, int links) {
            int[] counts = filled.get(label);
            counts[0]++;
            counts[1] += links;
        }

        /**
         * Строка «№ N, поле «…»: в файле «…», у позиции «…» (оставлено как есть)».
         * Длинное значение сокращается — описание в пять тысяч знаков в строке
         * отчёта не прочесть, а разницу видно по началу.
         */
        void discrepancy(String ad, String field, String inFile, String atPart) {
            discrepancyCount++;
            if (discrepancies.size() < DISCREPANCY_LIMIT) {
                // Значения — те же восемьдесят знаков, что в строке: двести пар
                // полных описаний раздули бы и ответ, и import_run, а экран
                // показывает только строку.
                String fileBrief = brief(inFile);
                String partBrief = brief(atPart);
                discrepancies.add(new Report.Discrepancy(ad, field, fileBrief, partBrief,
                        "№ %s, поле «%s»: в файле %s, у позиции %s (оставлено как есть)"
                                .formatted(ad, field, quoted(fileBrief), quoted(partBrief))));
            }
        }

        private static String brief(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            String line = value.replaceAll("\\s+", " ").strip();
            return line.length() <= 80 ? line : line.substring(0, 80) + "…";
        }

        private static String quoted(String value) {
            return value == null ? "пусто" : "«" + value + "»";
        }

        Report.Repeat repeat() {
            List<Report.FieldCount> fields = new ArrayList<>();
            filled.forEach((label, counts) -> {
                if (counts[0] > 0) {
                    fields.add(new Report.FieldCount(label, counts[0], counts[1]));
                }
            });
            return new Report.Repeat(refilled, List.copyOf(fields), unchanged,
                    discrepancyCount, List.copyOf(discrepancies), withoutAdNumber);
        }

        void skip(int rowNumber, String reason) {
            // Список пропущенных не бесконечен: файл с неверным сопоставлением
            // даст пятьдесят тысяч одинаковых строк, и ответ раздуется.
            if (skipped.size() < 200) {
                skipped.add(new Skipped(rowNumber, reason));
            }
        }

        /**
         * Сказать без предела в двести. Повреждения листа «Продолжение»
         * дописываются после всех строк первого листа, и под пределом они
         * пропадали бы ровно у того файла, где пропусков и так много, — то есть
         * значение было бы обрезано молча. Их не больше, чем строк того листа.
         */
        void continuation(int rowNumber, String reason) {
            skipped.add(new Skipped(rowNumber, reason, DromContinuation.SHEET));
        }

        Report toReport() {
            return new Report(imported, List.copyOf(skipped), quantityDefaulted, repeat());
        }
    }
}
