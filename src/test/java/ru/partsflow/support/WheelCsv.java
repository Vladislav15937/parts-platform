package ru.partsflow.support;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор файла шин и дисков в формате Bazon (задача 0340) для тестов.
 *
 * <p>Нарочно не общий CSV-разборщик: он знает ровно этот файл — windows-1251,
 * {@code ;}, все поля в кавычках, {@code ""} внутри значения, одиночный LF
 * внутри комментария — и отказывает на всём, что от него отличается, потому
 * что тест, принявший чужой вид файла, ничего не доказывает.
 */
public final class WheelCsv {

    public static final Charset CHARSET = Charset.forName("windows-1251");

    private final List<List<String>> records;

    private WheelCsv(List<List<String>> records) {
        this.records = records;
    }

    public static WheelCsv parse(byte[] bytes) {
        String text = new String(bytes, CHARSET);
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '"') {
                throw new IllegalStateException("Поле без кавычек на знаке " + i);
            }
            StringBuilder value = new StringBuilder();
            i++;
            while (true) {
                char c = text.charAt(i);
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        value.append('"');
                        i += 2;
                        continue;
                    }
                    i++;
                    break;
                }
                value.append(c);
                i++;
            }
            row.add(value.toString());
            if (text.startsWith("\r\n", i)) {
                records.add(row);
                row = new ArrayList<>();
                i += 2;
            } else if (text.charAt(i) == ';') {
                i++;
            } else {
                throw new IllegalStateException("После поля ни «;», ни CRLF на знаке " + i);
            }
        }
        if (!row.isEmpty()) {
            throw new IllegalStateException("Файл не кончается переводом строки CRLF");
        }
        return new WheelCsv(records);
    }

    public List<String> header() {
        return records.get(0);
    }

    /** Строки данных по заголовкам колонок. */
    public List<Map<String, String>> rows() {
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            if (record.size() != header().size()) {
                throw new IllegalStateException("В строке " + record.size() + " полей из "
                        + header().size());
            }
            Map<String, String> row = new HashMap<>();
            for (int c = 0; c < record.size(); c++) {
                row.put(header().get(c), record.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Строка с этим артикулом («К» и номер) либо {@code null}. */
    public Map<String, String> byArticle(String article) {
        return rows().stream().filter(r -> article.equals(r.get("Артикул")))
                .findFirst().orElse(null);
    }
}
