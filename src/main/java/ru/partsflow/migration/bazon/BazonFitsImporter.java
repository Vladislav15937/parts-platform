package ru.partsflow.migration.bazon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.TextBlockService;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Перенос блока «&lt;Вид&gt; подходит:» и текстового блока из файла выгрузки
 * Bazon на Дром (задача 0337).
 *
 * <p>В выгрузке склада (CSV) этих текстов нет — их собирает Bazon при выгрузке
 * на площадку. Они лежат в {@code <description>} прайса {@code drom-*.xml}, в
 * устройстве, снятом обходом: блоки через пустую строку, порядок «Комментарий,
 * Запчасть снята с, &lt;Вид&gt; подходит, текстовый блок». Позиция узнаётся по
 * {@code <id>} — это её {@code part.number} (задача 0334).
 *
 * <p><b>Из описания берутся только два блока, и только когда место блока
 * однозначно.</b> «Подходит» — по заголовку «… подходит:». Текстовый блок —
 * то, что стоит после «подходит» либо после «Запчасть снята с:». У новой
 * детали без комментария единственный блок может быть и комментарием, и
 * текстовым блоком; различить их по виду нельзя, поэтому такой хвост не
 * трогается и считается в отчёте (угадать значило бы положить комментарий
 * клиента в общий текстовый блок).
 *
 * <p>Правило записи то же, что у повтора переноса: пусто у позиции — берётся
 * из файла, заполнено — не трогается, разница называется. Отметка «позиция
 * изменилась» не ставится: тысячи позиций ушли бы дельтой разом, а полный
 * прайс описание и так подхватит.
 */
@Component
public class BazonFitsImporter {

    /** Позиций в одной транзакции: обрыв оставляет перенесённое перенесённым. */
    private static final int BATCH = 500;

    /** Заголовок блока: «Фара подходит:» — первое слово вида и слово «подходит:». */
    private static final Pattern FITS_HEADING =
            Pattern.compile("(?m)^[^\\r\\n]+ подходит:[ \\t]*\\r?$");

    private static final Pattern VEHICLE_HEADING =
            Pattern.compile("(?m)^Запчасть снята с:[^\\r\\n]*");

    private static final Pattern BLANK_LINE = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TextBlockService textBlocks;

    public BazonFitsImporter(JdbcTemplate jdbc, TransactionTemplate transactions,
                             TextBlockService textBlocks) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.textBlocks = textBlocks;
    }

    /** Два блока описания; {@code null} — блока в описании нет или его место неоднозначно. */
    record Blocks(String fits, String textBlock, boolean tailUnclear) {
    }

    /**
     * Разбирает описание. Блоки не пересобираются: текст берётся срезом, как он
     * стоит в описании, с переводами строк.
     */
    static Blocks split(String description) {
        if (description == null || description.isBlank()) {
            return new Blocks(null, null, false);
        }
        String text = description.startsWith("\n") ? description.substring(1) : description;
        int blockEnd;
        String fits = null;
        Matcher heading = firstAtBlockStart(FITS_HEADING, text);
        if (heading != null) {
            int start = heading.start();
            Matcher end = BLANK_LINE.matcher(text);
            blockEnd = end.find(start) ? end.start() : text.length();
            fits = text.substring(start, blockEnd).stripTrailing();
        } else {
            Matcher vehicle = firstAtBlockStart(VEHICLE_HEADING, text);
            if (vehicle == null) {
                // Ни «подходит», ни «снята с»: единственный блок — комментарий или
                // текстовый блок, различить нечем.
                return new Blocks(null, null, true);
            }
            Matcher end = BLANK_LINE.matcher(text);
            blockEnd = end.find(vehicle.start()) ? end.start() : text.length();
        }
        if (blockEnd >= text.length()) {
            return new Blocks(fits, null, false);
        }
        Matcher gap = BLANK_LINE.matcher(text);
        gap.find(blockEnd);
        String tail = text.substring(gap.end());
        return new Blocks(fits, tail.isBlank() ? null : tail, false);
    }

    /** Первое совпадение, которому предшествует начало текста или пустая строка. */
    private static Matcher firstAtBlockStart(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (m.start() == 0 || precededByBlankLine(text, m.start())) {
                return m;
            }
        }
        return null;
    }

    private static boolean precededByBlankLine(String text, int index) {
        Matcher gap = BLANK_LINE.matcher(text.substring(0, index));
        int last = -1;
        while (gap.find()) {
            last = gap.end();
        }
        return last == index;
    }

    /**
     * Итог переноса. Числа — позиции файла, а не блоки: позиция с обоими блоками
     * считается в обеих строках.
     *
     * @param items           позиций прочитано из файла
     * @param notFound        номеров, которых нет на складе
     * @param fitsFilled      блоков «подходит» записано
     * @param fitsSame        блок у позиции уже тот же
     * @param fitsDiffers     у позиции уже другой блок — не тронут
     * @param textBlocksSet   позиций, которым выбран текстовый блок
     * @param textBlockBusy   у позиции уже есть текстовый блок или выбор — не тронут
     * @param textBlocksNew   именованных блоков заведено
     * @param tailUnclear     позиций, у которых текстовый блок не отделить от комментария
     */
    public record Report(int items, int notFound, int fitsFilled, int fitsSame, int fitsDiffers,
                         int textBlocksSet, int textBlockBusy, int textBlocksNew, int tailUnclear) {
    }

    private record Item(long number, Blocks blocks) {
    }

    public Report load(InputStream xml) throws XMLStreamException {
        // Текст блока → номер блока на время одного переноса: тысячи позиций, один
        // текст. Локальный, а не поле: компонент один на всех арендаторов.
        java.util.Map<String, Long> blockIds = new java.util.HashMap<>();
        int[] c = new int[9];
        List<Item> batch = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // Файл чужой: внешние сущности и DTD не читаем.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader in = factory.createXMLStreamReader(xml);
        try {
            String id = null;
            String description = null;
            boolean inPart = false;
            while (in.hasNext()) {
                int event = in.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = in.getLocalName();
                    if ("part".equals(name)) {
                        inPart = true;
                        id = null;
                        description = null;
                    } else if (inPart && "id".equals(name)) {
                        id = in.getElementText().strip();
                    } else if (inPart && "description".equals(name)) {
                        description = in.getElementText();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "part".equals(in.getLocalName())) {
                    inPart = false;
                    c[0]++;
                    Long number = number(id);
                    if (number == null) {
                        c[1]++;
                        continue;
                    }
                    batch.add(new Item(number, split(description)));
                    if (batch.size() >= BATCH) {
                        flush(batch, c, blockIds);
                    }
                }
            }
            flush(batch, c, blockIds);
        } finally {
            in.close();
        }
        return new Report(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]);
    }

    private static Long number(String id) {
        if (id == null || !id.matches("\\d{1,15}")) {
            return null;
        }
        return Long.parseLong(id);
    }

    private void flush(List<Item> batch, int[] c, java.util.Map<String, Long> blockIds) {
        if (batch.isEmpty()) {
            return;
        }
        List<Item> items = List.copyOf(batch);
        batch.clear();
        transactions.executeWithoutResult(status -> {
            for (Item item : items) {
                List<Long> ids = jdbc.queryForList(
                        "SELECT id FROM part WHERE number = ?", Long.class, item.number());
                if (ids.isEmpty()) {
                    c[1]++;
                    continue;
                }
                long partId = ids.get(0);
                if (item.blocks().tailUnclear()) {
                    c[8]++;
                }
                if (item.blocks().fits() != null) {
                    writeFits(partId, item.blocks().fits(), c);
                }
                if (item.blocks().textBlock() != null) {
                    writeTextBlock(partId, item.blocks().textBlock(), c, blockIds);
                }
            }
        });
    }

    /** Пусто — записываем; то же — считаем; иное — не трогаем и называем. */
    private void writeFits(long partId, String fits, int[] c) {
        int written = jdbc.update(
                "INSERT INTO part_drom_card (part_id, bazon_fits) VALUES (?, ?)"
                        + " ON CONFLICT (part_id) DO UPDATE SET bazon_fits = EXCLUDED.bazon_fits"
                        + " WHERE part_drom_card.bazon_fits IS NULL",
                partId, fits);
        if (written > 0) {
            c[2]++;
            return;
        }
        String now = jdbc.queryForObject(
                "SELECT bazon_fits FROM part_drom_card WHERE part_id = ?", String.class, partId);
        c[fits.equals(now) ? 3 : 4]++;
    }

    private void writeTextBlock(long partId, String body, int[] c,
                                java.util.Map<String, Long> blockIds) {
        Long cached = blockIds.get(body);
        long blockId;
        if (cached != null) {
            blockId = cached;
        } else {
            int before = jdbc.queryForObject("SELECT count(*) FROM text_block", Integer.class);
            blockId = textBlocks.findOrCreateByBody(body).getId();
            int after = jdbc.queryForObject("SELECT count(*) FROM text_block", Integer.class);
            c[7] += after - before;
            blockIds.put(body, blockId);
        }
        // Одна инструкция с условием: у позиции уже есть выбор или текст — не трогаем.
        int set = jdbc.update("UPDATE part SET text_block_id = ?, text_block = ?"
                + " WHERE id = ? AND text_block_id IS NULL AND text_block IS NULL",
                blockId, body, partId);
        c[set > 0 ? 5 : 6]++;
    }
}
