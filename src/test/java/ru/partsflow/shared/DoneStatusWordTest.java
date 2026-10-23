package ru.partsflow.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Закрытый документ называется «Выполнен», а не «Проведён» — на всех экранах
 * и в отказах сервера.
 *
 * <p><b>Зачем.</b> Решение владельца продукта 23.10.2026
 * ({@code tasks/0367}, «Ответ владельца — 23 октября 2026», пункт 9):
 * «да, одно и то же должно называться одними и теми же словами» — «Выполнен»
 * вместо «Проведён» на всех экранах (инвентаризация, перемещения и прочее).
 * «Движение товара» карточки пишет «Выполнен» с PR 484, возвраты — с самого
 * начала, а журнал пересчётов писал «Проведён»: владелец видел два слова
 * для одного и того же и шёл искать между ними разницу, которой нет.
 *
 * <p><b>Почему перебор, а не проверка словаря.</b> Слово статуса пишут
 * в трёх местах разного рода: словарь статусов экрана
 * ({@code SESSION_STATUS_LABEL}), отметка строки в таблице и текст отказа
 * сервера («пока пересчёт не проведён»). Проверка одного словаря
 * осталась бы зелёной на любом из двух других — ровно так копии словаря
 * состояний сделки и дожили до разбора PR #78.
 *
 * <p><b>Что считается нарушением, а что нет.</b> Запрещено <b>причастие</b> —
 * «проведён», «проведено», «проведённую», «не проведён»: это слово о том,
 * в каком документ состоянии. Глагол действия законен и остаётся: кнопка
 * «Провести», «Не удалось провести», «Проводят завершённый пересчёт» — это
 * название операции, а не статуса, и 0360 вводит «Провести документ»
 * и «Отменить проводку» дословно с ориентира. Существительное «проведение»
 * тоже не статус и под шаблон не попадает.
 *
 * <p><b>Где ищется.</b> Во фронтенде — всё, кроме комментариев и тестов:
 * слово на экране стоит и в строке, и в тексте разметки между тегами,
 * а ограничься перебор кавычками — «Не проведено — деталь обещана
 * покупателю» в JSX он бы не увидел. На сервере — строковые литералы
 * вне комментариев: отказ доезжает до человека как есть
 * ({@code ApiExceptionHandler} отдаёт текст исключения). Комментарии
 * не трогаются: «проведение записало корректировки в журнал» объясняет
 * код, а не говорит с человеком.
 *
 * <p>И в скриптах {@code tools/*.sh} — строки вне комментариев {@code #}:
 * их печатает оператору сквозной прогон («возврат выполнен»), и проверка,
 * подписанная другим словом, чем экран, учит оператора двум словам для
 * одного статуса (мелочь из ответа владельца по 0044, 24.10.2026).
 *
 * <p><b>Чего перебор не видит.</b> Слово, собранное из частей
 * ({@code "прове" + "дён"}), и слово, приехавшее из базы или из файла
 * переноса. Ни того, ни другого сегодня нет.
 */
class DoneStatusWordTest {

    private static final Path FRONTEND = Path.of("frontend/src");
    private static final Path SERVER = Path.of("src/main/java");
    private static final Path TOOLS = Path.of("tools");

    /**
     * Причастие «провести» в любой форме, с «не» и без; существительное
     * «проведение» и глагол («провести», «проводят») сюда не попадают.
     */
    static final Pattern PARTICIPLE = Pattern.compile(
            "(?<![а-яё])(?:не)?провед[её]нн?(?:о|а|ы|ый|ая|ое|ые|ого|ой|ую|ым|ых|ыми|ом|ому)?(?![а-яё])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?<![:'\"`\\\\])//[^\\n]*");
    private static final Pattern JAVA_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    @Test
    @DisplayName("Шаблон ловит слово статуса и пропускает глагол действия")
    void patternTellsStatusFromAction() {
        for (String status : List.of("Проведён", "проведено", "Проведена", "проведены",
                "Проведённую", "проведенный", "не проведён", "Непроведённой")) {
            assertThat(PARTICIPLE.matcher(status).find())
                    .as("«%s» — слово статуса, перебор обязан его видеть", status)
                    .isTrue();
        }
        for (String action : List.of("Провести", "Не удалось провести", "Проводят",
                "проводится", "проведение", "Проведения", "Отменить проводку")) {
            assertThat(PARTICIPLE.matcher(action).find())
                    .as("«%s» — название действия, а не статуса: краснеть на нём нельзя", action)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Закрытый документ назван «Выполнен» на экранах и в отказах сервера")
    void doneIsCalledVypolnenEverywhere() throws IOException {
        List<String> found = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.walk(FRONTEND)) {
            for (Path file : files.filter(p -> p.toString().matches(".*\\.tsx?$"))
                    .filter(p -> !p.getFileName().toString().contains(".test."))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/test/"))
                    .toList()) {
                scanned++;
                String code = withoutComments(Files.readString(file));
                collect(file, code, PARTICIPLE.matcher(code), found);
            }
        }
        try (Stream<Path> files = Files.walk(SERVER)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String code = withoutComments(Files.readString(file));
                Matcher literal = JAVA_LITERAL.matcher(code);
                while (literal.find()) {
                    Matcher word = PARTICIPLE.matcher(literal.group());
                    if (word.find()) {
                        found.add(file + ":" + lineOf(code, literal.start())
                                + " — «" + word.group() + "» в " + literal.group());
                    }
                }
            }
        }

        int scripts = 0;
        try (Stream<Path> files = Files.walk(TOOLS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sh")).toList()) {
                scripts++;
                List<String> lines = Files.readAllLines(file);
                for (int at = 0; at < lines.size(); at++) {
                    String line = withoutShellComment(lines.get(at));
                    Matcher word = PARTICIPLE.matcher(line);
                    while (word.find()) {
                        found.add(file + ":" + (at + 1) + " — «" + word.group() + "»");
                    }
                }
            }
        }
        assertThat(scripts).as("перебор не нашёл скриптов в %s", TOOLS).isGreaterThan(5);

        // Перебор без файлов — зелёный тест ни о чём: ошибись путь, и он
        // молчал бы при любом коде. Исходников экрана и сервера — сотни.
        assertThat(scanned)
                .as("перебор не нашёл исходников — проверьте пути %s и %s", FRONTEND, SERVER)
                .isGreaterThan(300);

        assertThat(found)
                .as("закрытый документ назван «Проведён»: решение владельца 23.10.2026"
                        + " (tasks/0367, пункт 9) — «Выполнен» на всех экранах, одно и то же"
                        + " называется одними словами. Глагол «Провести» законен — это кнопка,"
                        + " а не статус; меняйте только слово о состоянии документа")
                .isEmpty();
    }

    private static void collect(Path file, String code, Matcher word, List<String> found) {
        while (word.find()) {
            found.add(file + ":" + lineOf(code, word.start()) + " — «" + word.group() + "»");
        }
    }

    /** Строка скрипта без комментария: целиком {@code # …} или хвост после « #». */
    static String withoutShellComment(String line) {
        if (line.strip().startsWith("#")) {
            return "";
        }
        int tail = line.indexOf(" #");
        return tail < 0 ? line : line.substring(0, tail);
    }

    /** Комментарии заменяются пустыми строками той же высоты: номер строки в отчёте верен. */
    static String withoutComments(String code) {
        String noBlocks = replaceKeepingLines(BLOCK_COMMENT, code);
        return replaceKeepingLines(LINE_COMMENT, noBlocks);
    }

    private static String replaceKeepingLines(Pattern pattern, String code) {
        Matcher m = pattern.matcher(code);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, m.group().replaceAll("[^\\n]", ""));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
