package ru.partsflow.platform.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.shared.AuditedColumns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Новый путь записи мимо журнала нельзя завести молча (задача 0325, требование 4).
 *
 * <p>{@code AuditLogListener} видит только сущности, а прямой SQL — нет: забытый
 * вызов {@code AuditJournal} рядом с новым {@code UPDATE} не пишет ничего, и узнают
 * об этом в день разбирательства. Перебор идёт по {@code src/main/java}, находит
 * каждую инструкцию {@code INSERT INTO / UPDATE / DELETE FROM} и требует от неё
 * одного из трёх:
 * <ul>
 *   <li>таблица журналом не названа ({@link AuditedColumns#of} пуст) — тогда она
 *       обязана стоять в {@link #NOT_JOURNALED} с причиной: новая таблица, которую
 *       правят из кода, не заводится без решения, нужен ли ей журнал;</li>
 *   <li>в том же методе стоит запись в журнал (вызов, в имени которого есть
 *       «journal»: {@code journal.changed}, {@code feedJournal.created},
 *       {@code journalKinds});</li>
 *   <li>метод назван в {@link #NOT_UI} с причиной «не правка через экран».</li>
 * </ul>
 *
 * <p><b>Чего он не видит, и это названо.</b> Метод, где журнал пишется в одной
 * ветке, а инструкция стоит в другой, проходит: перебор знает метод, а не путь
 * исполнения, — путь стережёт направленный тест ({@code ScreenEditsJournalTest}).
 * Правку сущностью вне {@code AUDITED} он не видит вовсе: это не инструкция SQL.
 * Метод ищется по отступу (четыре пробела у объявления и у закрывающей скобки) —
 * так оформлен весь проект; инструкция в поле класса проверяется как метод
 * с именем поля. Таблица со схемой, подставленной в текст ({@code %s.tenant_member}
 * у провижининга, входов и визита администратора), не разбирается вовсе: эти пути
 * идут вне транзакции арендатора и вне экрана по устройству.
 */
class ScreenWritesJournaledTest {

    /**
     * Таблицы без журнала — и почему. Ключ — имя таблицы, как в инструкции.
     */
    static final Map<String, String> NOT_JOURNALED = Map.ofEntries(
            Map.entry("audit_log", "сам журнал"),
            Map.entry("part_stock", "остаток — производное журнала движений (StockLedger)"),
            Map.entry("part_change", "отметка для площадки, не правка"),
            Map.entry("outbox", "транспорт событий"),
            Map.entry("processed_event", "транспорт событий"),
            Map.entry("event_dead_letter", "транспорт событий; разбор помечает resolved_by сам"),
            Map.entry("publication_log", "журнал отправки дельт площадке"),
            Map.entry("listing", "состояние объявления у площадки, пишет отправка"),
            Map.entry("member_setting", "раскладка экрана у сотрудника, не данные компании"),
            Map.entry("inventory_note_request", "ключи повтора офлайн-очереди"),
            Map.entry("part_oem", "номера пишутся записью таблицы part (oem, crosses) — PartIdentity"),
            Map.entry("part_wheel", "свойства колеса при заведении; заведение — запись part"),
            Map.entry("public.tenant_registry", "реестр ячейки, ведёт владелец схем"),
            Map.entry("public.admin_access_grant", "свой журнал допуска администратора (closed_by)"),
            Map.entry("public.admin_access_request", "свой журнал допуска администратора"),
            Map.entry("public.service_admin", "управляющий контур, не арендатор"),
            Map.entry("public.mail_message", "почта, не правка"),
            Map.entry("public.mail_event_pending", "почта, не правка"),
            Map.entry("public.shedlock", "блокировка расписания"),
            Map.entry("access_role_permission", "набор роли — в снимке записи access_role"
                    + " (поле «Права» словами), AccessRoleService"),
            Map.entry("member_permission_override", "права поверх роли — в снимке записи"
                    + " tenant_member («Добавленные права», «Снятые права»), AccessRoleService"));

    /**
     * Методы, пишущие таблицы журнала мимо него, — и почему это не правка
     * через экран. Ключ — {@code Файл#метод}.
     */
    static final Map<String, String> NOT_UI = Map.ofEntries(
            Map.entry("StockLedger#*", "остаток, статус и адрес полки — журнал движений, не правка полей"),
            Map.entry("DromApplicabilityService#*", "массовый разбор блока «Подходит»; решение 0325 —"
                    + " не правка полей, а разложение уже записанного текста"),
            Map.entry("WheelService#attachWheel", "зовёт только перенос (0291), не экран"),
            Map.entry("DonorVehicleResolver#backfillGenerations", "доводка после переноса Bazon"),
            Map.entry("OwnVehicleCodes#*", "пересчёт хранимого производного part.own_body_codes"
                    + " (0296) из строк применимости и наименования; колонки нет в AuditedColumns,"
                    + " правка человека — это правка источника, и журналируется она там"),
            Map.entry("CatalogService#applyFromTitles","массовый разбор наименований кнопкой импорта;"
                    + " решение 0325 — как DromApplicabilityService"),
            Map.entry("CustomerService#retail", "контрагент розничной продажи заводится сам, без человека"),
            Map.entry("MarketplaceAccountService#forgetLastOutcome", "служебные отметки отправки"),
            Map.entry("DromDeltaSender#*", "отметки отправки площадке"),
            Map.entry("PartNameRepository#*", "счётчик употреблений написания, не правка человека"),
            Map.entry("MemberService#createFirstOwnerIfEmpty", "в src/main не зовёт никто: первого"
                    + " владельца заводит провижининг"));

    /** Переносы — мимо интерфейса по решению владельца (0282): каталог целиком. */
    private static final Set<String> NOT_UI_DIRS = Set.of("migration");

    private static final Pattern WRITE = Pattern.compile(
            "\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+((?:public\\.)?[a-z_]+)\\b"
                    + "(?=[\\s\\S]{0,40}?(?:\\bSET\\b|\\(|\\bVALUES\\b|\\bWHERE\\b|\\bSELECT\\b|\\bFROM\\b|"
                    + "\\bRETURNING\\b|\"))");

    private static final Pattern JOURNAL_CALL = Pattern.compile("\\w*[jJ]ournal\\w*\\s*[.(]");

    private static final Pattern MEMBER = Pattern.compile(
            "^    (?![ *])(?:@\\w+(?:\\([^)]*\\))?\\s+)*[\\w<>\\[\\],.? ]*?\\b(\\w+)\\s*(?:\\(|=)", Pattern.MULTILINE);

    @Test
    @DisplayName("Каждая инструкция записи по таблице журнала стоит рядом с записью в журнал"
            + " либо названа с причиной")
    void everyWriteIsJournaledOrExplained() throws IOException {
        List<String> found = new ArrayList<>();
        Set<String> usedMethods = new TreeSet<>();
        Set<String> usedTables = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (NOT_UI_DIRS.stream().anyMatch(dir -> file.toString().contains("/" + dir + "/"))) {
                    continue;
                }
                found.addAll(check(file.getFileName().toString().replace(".java", ""),
                        Files.readString(file), usedMethods, usedTables));
            }
        }
        assertThat(found).as("запись мимо журнала действий без решения").isEmpty();
        // Пометка, которой больше не пользуется ни одна инструкция, — это
        // разрешение, оставшееся висеть: следующий прочтёт его как действующее.
        Set<String> stale = new TreeSet<>(NOT_UI.keySet());
        stale.removeAll(usedMethods);
        assertThat(stale).as("пометки NOT_UI без инструкции — уберите").isEmpty();
        Set<String> staleTables = new TreeSet<>(NOT_JOURNALED.keySet());
        staleTables.removeAll(usedTables);
        assertThat(staleTables).as("пометки NOT_JOURNALED без инструкции — уберите").isEmpty();
    }

    @Test
    @DisplayName("Сторож краснеет на подложенной записи без журнала и молчит на записи с ним")
    void guardSeesAPlantedDefect() {
        String bare = """
                class Planted {
                    void rename(long id) {
                        jdbc.update("UPDATE customer SET name = ? WHERE id = ?", "x", id);
                    }
                }
                """;
        String journaled = """
                class Planted {
                    void rename(long id) {
                        jdbc.update("UPDATE customer SET name = ? WHERE id = ?", "x", id);
                        journal.changed("customer", id, Map.of(), Map.of());
                    }
                }
                """;
        String unknownTable = """
                class Planted {
                    void touch() {
                        jdbc.update("INSERT INTO brand_new_table (x) VALUES (1)");
                    }
                }
                """;
        assertThat(check("Planted", bare, new TreeSet<>(), new TreeSet<>()))
                .as("правка клиента без журнала прошла").hasSize(1);
        assertThat(check("Planted", journaled, new TreeSet<>(), new TreeSet<>()))
                .as("правка с журналом отбита").isEmpty();
        assertThat(check("Planted", unknownTable, new TreeSet<>(), new TreeSet<>()))
                .as("новая таблица без решения прошла").hasSize(1);
    }

    static List<String> check(String file, String text, Set<String> usedMethods, Set<String> usedTables) {
        List<String> found = new ArrayList<>();
        Matcher write = WRITE.matcher(text);
        while (write.find()) {
            String table = write.group(1);
            if (isComment(text, write.start())) {
                continue;
            }
            Member member = memberAt(text, write.start());
            String key = file + "#" + member.name();
            if (AuditedColumns.of(table).isEmpty()) {
                if (NOT_JOURNALED.containsKey(table)) {
                    usedTables.add(table);
                } else if (NOT_UI.containsKey(key)) {
                    usedMethods.add(key);
                } else if (NOT_UI.containsKey(file + "#*")) {
                    usedMethods.add(file + "#*");
                } else {
                    found.add(key + ": таблица «" + table + "» журналом не названа и в"
                            + " NOT_JOURNALED не внесена — нужен ли ей журнал?");
                }
                continue;
            }
            if (JOURNAL_CALL.matcher(member.body()).find()) {
                continue;
            }
            if (NOT_UI.containsKey(key)) {
                usedMethods.add(key);
            } else if (NOT_UI.containsKey(file + "#*")) {
                usedMethods.add(file + "#*");
            } else {
                found.add(key + ": " + write.group().strip() + " — правка таблицы журнала без"
                        + " записи в журнал (AuditJournal) и без причины в NOT_UI");
            }
        }
        return found;
    }

    private static boolean isComment(String text, int at) {
        int lineStart = text.lastIndexOf('\n', at) + 1;
        String before = text.substring(lineStart, at).strip();
        return before.startsWith("//") || before.startsWith("*") || before.startsWith("/*");
    }

    private record Member(String name, String body) {
    }

    /** Член класса, внутри которого стоит позиция: от объявления до «    }» или «;». */
    private static Member memberAt(String text, int at) {
        Matcher member = MEMBER.matcher(text);
        int start = -1;
        String name = "?";
        while (member.find() && member.start() < at) {
            start = member.start();
            name = member.group(1);
        }
        if (start < 0) {
            return new Member(name, text);
        }
        int end = text.indexOf("\n    }\n", at);
        int field = text.indexOf(";\n\n", at);
        int stop = end < 0 ? text.length() : end;
        if (field >= 0 && field < stop && !text.substring(start, at).contains("{\n")) {
            stop = field;
        }
        return new Member(name, text.substring(start, stop));
    }
}
