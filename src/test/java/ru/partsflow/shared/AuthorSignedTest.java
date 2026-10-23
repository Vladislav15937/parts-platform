package ru.partsflow.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сотрудник назван человеку подписью «Имя (логин, ID N)», а не одним именем
 * (задача 0373).
 *
 * <p><b>Решение владельца.</b> 23.10.2026, `tasks/0373`, раздел «Ответ
 * владельца — 23 октября 2026 (после слияния PR 499, 503)», пункт 5а: автор
 * подписан «Имя (логин, ID N)» <b>везде</b>, где назван, — а не только
 * в «Истории изменений» карточки. Раньше имя сотрудника уезжало наружу
 * колонкой {@code tenant_member.display_name} из четырнадцати
 * файлов, и каждый писал его сам.
 *
 * <p><b>Почему перебор, а не проверка одного экрана.</b> Подпись собирают
 * {@code MemberSignature.of}/{@code .sql} и {@code MemberSignatures} — они
 * проверяют сами себя ({@code MemberSignatureTest}, {@code MemberSignatureSqlTest}).
 * Расходятся <b>вызывающие</b>: следующий запрос, написанный по образцу
 * «{@code m.display_name AS author}», покажет человека одним именем, и общий
 * класс при этом останется зелёным. Поэтому перебираются исходники:
 * колонка имени и прежний поиск имён ({@code MemberService.namesOf}) живут
 * только там, где разобраны, и каждое такое место названо ниже с причиной.
 * Новое упоминание краснеет, называя файл: либо подпись, либо строка сюда.
 *
 * <p><b>Чего перебор не ловит — и это сказано вслух.</b> Имя, взятое мимо
 * колонки: {@code TenantPrincipal.displayName()} (вошедший о себе — шапка
 * экрана) и имя администратора сервиса из {@code public.service_admin}.
 * Он считает упоминания в файле, а не их смысл: новое место в разобранном
 * файле краснеет числом, но заменённое одно на другое (убрали одно чтение,
 * добавили другое) пройдёт молча. Число закреплено у всех файлов, и правка
 * реестра сотрудников (задача 0044) тоже обязана пройти этот разбор.
 */
class AuthorSignedTest {

    private static final Path SRC = Path.of("src/main/java/ru/partsflow");

    /** Колонка имени сотрудника и прежний поиск имён по номерам. */
    private static final Pattern NAME = Pattern.compile("display_name|\\bnamesOf\\(");

    private record Allowed(int count, String reason) {
    }

    /**
     * Разобранные места. Число — сколько упоминаний в файле разобрано:
     * изменилось — значит появилось новое, и его надо разобрать заново.
     */
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(
            Map.entry("platform/security/MemberSignature.java", new Allowed(2,
                    "сама подпись: SQL-запись «Имя (логин, ID N)»")),
            Map.entry("platform/security/MemberSignatures.java", new Allowed(2,
                    "сама подпись по номерам строк: читает имя, логин и номер"
                            + " и собирает MemberSignature.of")),
            Map.entry("inventory/PartHistoryService.java", new Allowed(3,
                    "«Изменения» карточки (PR 499): имя читается парой с логином"
                            + " и номером и собирается MemberSignature.of в authorOf")),
            Map.entry("platform/security/MemberService.java", new Allowed(11,
                    "реестр сотрудников: заведение, экран «Сотрудники» (номер, логин"
                            + " и имя там отдельными колонками) и снимок журнала «Имя»."
                            + " namesOf снят вместе с последним вызывающим (задача 0373)")),
            Map.entry("platform/security/MemberAuthenticationProvider.java", new Allowed(2,
                    "вход: имя вошедшего о себе, для шапки экрана, а не автор записи")),
            Map.entry("platform/tenant/TenantProvisioning.java", new Allowed(1,
                    "заведение владельца при провижининге — запись, не показ")),
            Map.entry("platform/tenant/TenantMigrations.java", new Allowed(1,
                    "namesOf — имена схем, отставших от версии, а не сотрудников")),
            Map.entry("platform/tenant/SchemaVersionCheck.java", new Allowed(1,
                    "TenantMigrations.namesOf — имена схем в логе")),
            Map.entry("platform/health/ReadinessEndpoint.java", new Allowed(1,
                    "TenantMigrations.namesOf — имена схем в ответе готовности")),
            Map.entry("platform/admin/AdminVisits.java", new Allowed(5,
                    "участник администратора сервиса заводится именем его учётной"
                            + " записи; запись и чтение public.service_admin")),
            Map.entry("platform/admin/AdminAccess.java", new Allowed(2,
                    "кто из администраторов сервиса просит доступ: public.service_admin,"
                            + " у него нет ни логина разборки, ни номера сотрудника")),
            Map.entry("platform/admin/ServiceAdmins.java", new Allowed(1,
                    "заведение учётной записи администратора сервиса — запись")),
            Map.entry("platform/admin/ServiceAdminAuthenticationProvider.java", new Allowed(2,
                    "вход администратора сервиса: имя о себе")),
            Map.entry("platform/audit/OrganizationAuditService.java", new Allowed(1,
                    "ключ снимка журнала в поиске по вещи — по нему ищут, не показывают")),
            Map.entry("shared/AuditedColumns.java", new Allowed(1,
                    "слово колонки «Имя» в журнале правок сотрудника")));

    @Test
    @DisplayName("Имя сотрудника наружу — только подписью «Имя (логин, ID N)»: новое место краснеет")
    void everyEmployeeNameIsSigned() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher matcher = NAME.matcher(Files.readString(file));
                int count = 0;
                while (matcher.find()) {
                    count++;
                }
                if (count > 0) {
                    found.put(SRC.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        assertThat(found).as("перебор не нашёл ни одного упоминания — проверьте путь %s", SRC)
                .isNotEmpty();

        List<String> problems = new ArrayList<>();
        found.forEach((file, count) -> {
            Allowed allowed = ALLOWED.get(file);
            if (allowed == null) {
                problems.add(file + ": имя сотрудника одним именем (упоминаний: " + count + ")."
                        + " Подпишите — MemberSignature.sql(псевдоним) в запросе"
                        + " или MemberSignatures.of(номера строк) — либо разберите место"
                        + " и внесите файл в ALLOWED с причиной");
            } else if (allowed.count() != count) {
                problems.add(file + ": упоминаний " + count + ", разобрано " + allowed.count()
                        + " — новое место показывает сотрудника одним именем? Разберите заново");
            }
        });
        assertThat(problems).as(String.join("\n", problems)).isEmpty();
    }

    @Test
    @DisplayName("Разобранное место не остаётся в списке, когда его уже нет")
    void allowedEntriesStillExist() throws IOException {
        List<String> stale = new ArrayList<>();
        for (String file : ALLOWED.keySet()) {
            Path path = SRC.resolve(file);
            if (!Files.exists(path) || !NAME.matcher(Files.readString(path)).find()) {
                stale.add(file);
            }
        }
        assertThat(stale).as("в ALLOWED числятся места без упоминаний: %s",
                stale.stream().collect(Collectors.joining(", "))).isEmpty();
    }

    @Test
    @DisplayName("Каждое разобранное место названо причиной")
    void everyAllowedHasAReason() {
        assertThat(ALLOWED.values()).allSatisfy(a -> assertThat(a.reason()).isNotBlank());
    }
}
