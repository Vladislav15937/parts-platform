package ru.partsflow.migration.bazon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Чтение выгрузки предыдущей учётной системы.
 *
 * <p>Формат выгрузки: windows-1251, разделитель {@code ;}, поля в кавычках,
 * внутри полей встречаются переводы строк — описание товара пишут абзацами.
 * Поэтому «прочитать построчно и разрезать по точке с запятой» здесь не
 * работает: файл на 35 тысяч товаров содержит 44 тысячи физических строк.
 *
 * <p>Читаем потоково. Выгрузка склада в 50 тысяч позиций — это десятки
 * мегабайт, и держать её в памяти целиком незачем: строки обрабатываются
 * и забываются.
 */
public final class BazonCsvReader implements AutoCloseable {

    /** Выгрузка приходит в windows-1251 — это не выбор, а данность. */
    public static final Charset CHARSET = Charset.forName("windows-1251");

    private static final char DELIMITER = ';';
    private static final char QUOTE = '"';

    private final BufferedReader reader;
    private final List<String> header;
    private final Map<String, Integer> columnIndex;

    public BazonCsvReader(InputStream in) {
        this(new InputStreamReader(in, CHARSET));
    }

    public BazonCsvReader(Reader in) {
        this.reader = new BufferedReader(in, 1 << 16);
        List<String> first = readRecord();
        if (first == null) {
            throw new IllegalArgumentException("Выгрузка пуста: нет даже заголовка");
        }
        // BOM в начале первой колонки ломает поиск по имени, а глазами не виден.
        if (!first.isEmpty() && first.get(0).startsWith("﻿")) {
            first.set(0, first.get(0).substring(1));
        }
        this.header = List.copyOf(first);
        this.columnIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columnIndex.putIfAbsent(header.get(i).trim(), i);
        }
    }

    public List<String> header() {
        return header;
    }

    /**
     * Обходит строки выгрузки.
     *
     * <p>Строки с неожиданным числом колонок не пропускаются молча: они уходят
     * в {@code onMalformed}. Молчаливый пропуск при переносе чужого склада —
     * это потерянные позиции, которые клиент обнаружит через месяц.
     */
    public void forEachRow(RowHandler handler, MalformedHandler onMalformed) {
        List<String> values;
        long lineNumber = 1;
        while ((values = readRecord()) != null) {
            lineNumber++;
            if (values.size() != header.size()) {
                onMalformed.handle(lineNumber, values);
                continue;
            }
            handler.handle(new Row(columnIndex, values, lineNumber));
        }
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Одна логическая запись. Может занимать несколько физических строк,
     * если внутри поля в кавычках есть перевод строки.
     */
    private List<String> readRecord() {
        List<String> values = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false;

        try {
            int c;
            while ((c = reader.read()) != -1) {
                any = true;
                char ch = (char) c;

                if (inQuotes) {
                    if (ch == QUOTE) {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == QUOTE) {
                            // Удвоенная кавычка внутри поля — это одна кавычка.
                            field.append(QUOTE);
                        } else {
                            inQuotes = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        field.append(ch);
                    }
                    continue;
                }

                switch (ch) {
                    case QUOTE -> inQuotes = true;
                    case DELIMITER -> {
                        values.add(field.toString());
                        field.setLength(0);
                    }
                    case '\n' -> {
                        values.add(field.toString());
                        return values;
                    }
                    case '\r' -> {
                        // CRLF: сам по себе \r ничего не значит.
                    }
                    default -> field.append(ch);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать выгрузку", e);
        }

        if (!any) {
            return null;
        }
        values.add(field.toString());
        return values;
    }

    @FunctionalInterface
    public interface RowHandler {
        void handle(Row row);
    }

    @FunctionalInterface
    public interface MalformedHandler {
        void handle(long lineNumber, List<String> values);
    }

    /** Строка выгрузки с доступом по имени колонки. */
    public static final class Row {

        private final Map<String, Integer> columnIndex;
        private final List<String> values;
        private final long lineNumber;

        private Row(Map<String, Integer> columnIndex, List<String> values, long lineNumber) {
            this.columnIndex = columnIndex;
            this.values = values;
            this.lineNumber = lineNumber;
        }

        /** Значение по имени колонки; {@code null} для пустого и отсутствующего. */
        public String get(String column) {
            Integer i = columnIndex.get(column);
            if (i == null) {
                return null;
            }
            String value = values.get(i).trim();
            return value.isEmpty() ? null : value;
        }

        public String get(int index) {
            String value = values.get(index).trim();
            return value.isEmpty() ? null : value;
        }

        public boolean hasColumn(String column) {
            return columnIndex.containsKey(column);
        }

        /** Номер физической строки в файле — для сообщений об ошибках импорта. */
        public long lineNumber() {
            return lineNumber;
        }
    }
}
