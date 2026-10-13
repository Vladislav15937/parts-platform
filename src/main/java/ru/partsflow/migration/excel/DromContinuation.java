package ru.partsflow.migration.excel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Лист «Продолжение» файла переноса с Дрома: хвосты значений, не влезших
 * в ячейку Excel, и их склейка обратно (задача 0281).
 *
 * <p><b>Зачем.</b> В ячейке Excel не больше 32 767 знаков, а «Применимость»
 * мелочи, подходящей к сотням машин, бывает длиннее: у клиента шесть карточек
 * из 2 897, до 41 902 знаков. Парсер их не режет — начало кладёт в ячейку
 * первого листа, остальное сюда, частями до 32 000 знаков. Импорт, читающий
 * только первый лист, получил бы значение обрезанным и не сказал бы об этом —
 * ровно то, что запрещает требование владельца повторить объявление «на сто
 * процентов».
 *
 * <p><b>Склейка идёт до разбора полей, а не в нём.</b> Строка первого листа
 * выходит отсюда с уже целым значением, и разбор любой колонки — сегодняшней
 * или той, что научат читать потом, — его не отличит от короткого. Колонка
 * берётся по названию из «Колонка», а не из списка: склеивается любая колонка
 * первого листа.
 *
 * <p><b>Края не обрезаются ни у ячейки, ни у части</b> — склеиваются значения
 * как есть ({@link ExcelSheetReader.Row#raw}). Парсер режет по последнему
 * переводу строки внутри куска и оставляет его <i>в конце</i> куска
 * ({@code DromShopWorkbook.split}, ветка {@code drom-migration-export},
 * коммит 9402e36): обрезанный край ячейки склеил бы две строки в одну.
 *
 * <p><b>Повреждённый лист называется словами, а не обходится молча.</b> Часть
 * без строки на первом листе, разрыв нумерации, повтор номера, колонка,
 * которой на первом листе нет, — каждое уходит в отчёт импорта с номером
 * объявления Дрома, названием колонки и номером части: по номеру объявления
 * человек находит карточку у Дрома. При разрыве значение берётся до разрыва:
 * склеенное через дыру было бы текстом, которого у клиента нет.
 */
public final class DromContinuation {

    /** Имя листа. Ищется по имени (без учёта регистра), а не по номеру. */
    public static final String SHEET = "Продолжение";

    /** Как первый лист называет парсер; импорт имени первого листа не проверяет. */
    static final String MAIN_SHEET = "Объявления";

    static final String COLUMN = "Колонка";
    static final String PART = "Часть";
    static final String TEXT = "Текст";

    /** Заголовок листа — четыре колонки, порядок не важен: ищутся по названию. */
    public static final List<String> HEADER =
            List.of(DromTransferFile.ANCHOR, COLUMN, PART, TEXT);

    /**
     * Что сказать человеку.
     *
     * @param row номер строки на листе «Продолжение», как в Excel
     */
    public record Problem(int row, String reason) {
    }

    private record Key(String ad, String column) {
    }

    private record Piece(int row, int number, String text) {
    }

    /** Части по объявлению и колонке, в порядке листа. */
    private final Map<Key, List<Piece>> pieces = new LinkedHashMap<>();
    /** Как колонка названа на листе — для сообщения, ключ хранит приведённое. */
    private final Map<Key, String> titles = new LinkedHashMap<>();
    private final Set<Key> stitched = new HashSet<>();
    private final List<Problem> problems = new ArrayList<>();

    /** Номера колонок листа «Продолжение»; {@code null} — заголовка нет, лист не читается. */
    private int[] sideColumns;
    private boolean sideHeaderSeen;

    /** Номер колонки первого листа по приведённому названию. */
    private Map<String, Integer> mainColumns;
    private int mainAnchor = -1;

    /**
     * Строка листа «Продолжение», в порядке чтения. Первая строка — заголовок;
     * нет в ней всех четырёх колонок — лист не читается вовсе, молча: такой
     * лист не наш.
     */
    public void accept(ExcelSheetReader.Row row) {
        if (!sideHeaderSeen) {
            sideHeaderSeen = true;
            int[] found = new int[HEADER.size()];
            for (int i = 0; i < HEADER.size(); i++) {
                found[i] = indexOf(row.cells(), HEADER.get(i));
                if (found[i] < 0) {
                    return;
                }
            }
            sideColumns = found;
            return;
        }
        if (sideColumns == null || row.isEmpty()) {
            return;
        }
        String ad = adNumber(row.at(sideColumns[0]));
        String column = row.at(sideColumns[1]);
        String part = row.at(sideColumns[2]);
        if (ad.isEmpty() || column.isEmpty()) {
            problems.add(new Problem(row.number(), "Лист «" + SHEET + "»: в строке нет "
                    + (ad.isEmpty() ? "номера объявления" : "названия колонки") + "; пропущена"));
            return;
        }
        Key key = new Key(ad, DromTransferFile.normalize(column));
        Integer number = partNumber(part);
        if (number == null) {
            problems.add(new Problem(row.number(), "Лист «" + SHEET + "»: у объявления № " + ad
                    + " колонка «" + shortened(column) + "» — часть «" + shortened(part)
                    + "» не номер; пропущена"));
            return;
        }
        titles.putIfAbsent(key, column);
        pieces.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new Piece(row.number(), number, row.rawAt(sideColumns[3])));
    }

    /** Есть ли что склеивать. */
    public boolean isEmpty() {
        return pieces.isEmpty() && problems.isEmpty();
    }

    /** Заголовок первого листа: колонки ищутся по нему. */
    public void header(List<String> header) {
        mainColumns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            mainColumns.putIfAbsent(DromTransferFile.normalize(header.get(i)), i);
        }
        mainAnchor = mainColumns.getOrDefault(DromTransferFile.normalize(DromTransferFile.ANCHOR), -1);
    }

    /**
     * Строка первого листа с дописанными хвостами: значение ячейки плюс части
     * 1, 2, … по порядку. Без частей строка возвращается та же.
     */
    public ExcelSheetReader.Row stitch(ExcelSheetReader.Row row) {
        if (pieces.isEmpty() || mainAnchor < 0) {
            return row;
        }
        String ad = adNumber(row.at(mainAnchor));
        if (ad.isEmpty()) {
            return row;
        }
        ExcelSheetReader.Row result = row;
        for (Map.Entry<Key, List<Piece>> entry : pieces.entrySet()) {
            Key key = entry.getKey();
            if (!key.ad().equals(ad)) {
                continue;
            }
            Integer index = mainColumns.get(key.column());
            if (index == null) {
                continue; // назовёт finish(): колонки на первом листе нет
            }
            boolean first = stitched.add(key);
            result = result.with(index, join(result.rawAt(index), entry.getValue(), key,
                    first ? problems : null));
        }
        return result;
    }

    /**
     * Что не склеилось: части без строки на первом листе и колонки, которых
     * там нет. Зовётся после последней строки первого листа; вместе с тем, что
     * названо по ходу, — всё, что надо сказать человеку.
     *
     * @param sheetName имя первого листа — его называют сообщения; пусто —
     *                  «Объявления», как его называет парсер
     */
    public List<Problem> finish(String sheetName) {
        String mainSheet = sheetName == null || sheetName.isBlank() ? MAIN_SHEET : sheetName.strip();
        for (Map.Entry<Key, List<Piece>> entry : pieces.entrySet()) {
            Key key = entry.getKey();
            if (stitched.contains(key)) {
                continue;
            }
            int row = entry.getValue().get(0).row();
            String column = titles.get(key);
            if (mainColumns != null && !mainColumns.containsKey(key.column())) {
                problems.add(new Problem(row, "Лист «" + SHEET + "»: у объявления № " + key.ad()
                        + " колонки «" + shortened(column) + "» на листе «" + mainSheet
                        + "» нет; часть пропущена"));
            } else {
                problems.add(new Problem(row, "Лист «" + SHEET + "»: часть для объявления № "
                        + key.ad() + " (колонка «" + shortened(column) + "») не имеет строки на листе «"
                        + mainSheet + "»; пропущена"));
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Склейка: голова плюс части по номеру с единицы. Повтор номера —
     * берётся первая; разрыв — значение до разрыва, и это названо.
     *
     * @param report куда назвать неладное; {@code null} — уже названо
     *               (объявление встретилось на первом листе второй раз)
     */
    private String join(String head, List<Piece> parts, Key key, List<Problem> report) {
        List<Piece> ordered = new ArrayList<>(parts);
        ordered.sort(Comparator.comparingInt(Piece::number)); // устойчивая: повтор — по листу
        int last = ordered.get(ordered.size() - 1).number();
        StringBuilder value = new StringBuilder(head);
        int expected = 1;
        for (Piece piece : ordered) {
            if (piece.number() < expected) {
                if (report != null) {
                    report.add(new Problem(piece.row(), "Лист «" + SHEET + "»: у объявления № "
                            + key.ad() + " колонка «" + shortened(titles.get(key)) + "» — часть "
                            + piece.number() + " повторяется; взята первая"));
                }
                continue;
            }
            if (piece.number() > expected) {
                if (report != null) {
                    report.add(new Problem(piece.row(), "Лист «" + SHEET + "»: у объявления № "
                            + key.ad() + " колонка «" + shortened(titles.get(key)) + "» — нет части "
                            + expected + " из " + last + "; значение взято до разрыва"));
                }
                break;
            }
            value.append(piece.text());
            expected++;
        }
        return value.toString();
    }

    private static int indexOf(List<String> header, String title) {
        String wanted = DromTransferFile.normalize(title);
        for (int i = 0; i < header.size(); i++) {
            if (DromTransferFile.normalize(header.get(i)).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    /** Номер объявления: края и «№» перед числом сняты — как у импорта. */
    private static String adNumber(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("№")) {
            value = value.substring(1).strip();
        }
        return value;
    }

    private static Integer partNumber(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!value.matches("[0-9]{1,6}")) {
            return null;
        }
        int number = Integer.parseInt(value);
        return number >= 1 ? number : null;
    }

    private static String shortened(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }
}
