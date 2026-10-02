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
 * Номер строки базы не уезжает человеку в тексте отказа (задача 0067).
 *
 * <p><b>Как это выглядело.</b> «Сделка не найдена: 118», «Склад не найден: 7»,
 * «Услуга не найдена: 3» — тридцать с лишним мест в девяти файлах. Номер
 * строки человеку не говорит ничего: по нему не найти ни поиском, ни
 * в разговоре, ни в бумаге, он меняется при переносе и не переживает
 * восстановления в другую схему, — а видит его владелец ровно тогда, когда
 * что-то пошло не так и надо понять, что делать. Класс 3
 * {@code docs/defect-classes.md}: внутреннего представления на экране нет.
 *
 * <p><b>Почему перебор, а не проверка одного места.</b> Та же причина, что
 * у {@code dealStatus.test.ts} из задач 0062 и 0065: сведённая функция
 * проверяет сама себя — она возвращает то, что в ней написано, — а
 * расходятся **вызывающие**, каждый своей склейкой. Следующее «не найдено:
 * » заведётся снова на месте, и общий {@link NotFound} при этом останется
 * зелёным.
 *
 * <p><b>Ищется склейка, а не слово.</b> Текст в кавычках, за которым
 * приклеивается значение с именем, кончающимся на {@code Id}, —
 * и {@code "%d"} в сообщении, потому что вторым способом написано ровно
 * то же («Ячейка %d не найдена на складе %d»). Оба шаблона нужны: перебор
 * с одним лишь плюсом пропустил бы четыре места из тридцати четырёх, и это
 * не догадка — ровно так считалось при разборе задачи.
 *
 * <p><b>Чего перебор не ловит и почему это сказано вслух.</b> Он читает
 * исходники, а не экраны: номер, доехавший до человека через поле ответа
 * (как было у {@code сделка ${p.dealId}} в отчёте), виден только на стороне
 * клиента — там стоит свой перебор, {@code internalIdsNotShown.test.ts}.
 * И он не отличает лога от ответа наружу: {@code log.warn("… id={}", id)}
 * законен и под шаблон не подпадает, потому что значение там идёт
 * параметром, а не склейкой.
 */
class InternalIdsNotShownTest {

    private static final Path SRC = Path.of("src/main/java/ru/partsflow");

    /** Склейка номера в текст: {@code "… не найден: " + partId}. */
    private static final Pattern GLUED = Pattern.compile(
            "\"[^\"\\n]*[А-Яа-яЁё][^\"\\n]*\"\\s*\\+\\s*[A-Za-z_][A-Za-z0-9_.()]*(?:[Ii]d|ID)\\b");

    /** Тот же номер, вписанный через {@code formatted}: {@code "Ячейка %d …"}. */
    private static final Pattern FORMATTED = Pattern.compile(
            "\"[^\"\\n]*[А-Яа-яЁё][^\"\\n]*%d[^\"\\n]*\"");

    /**
     * Места, где число в тексте — не номер строки базы, а величина, которую
     * человек и спрашивает. Каждое с причиной: пометка без причины
     * не принимается, иначе список превращается в способ обойти перебор.
     */
    private static final List<Allowed> ALLOWED = List.of(
            new Allowed("platform/tenant/ProvisioningLimits.java",
                    "предел частоты и потолок схем — сами числа и есть ответ оператору"),
            new Allowed("platform/tenant/TenantShare.java",
                    "номер экземпляра и их число: это аргументы запуска, а не строки базы"),
            new Allowed("platform/tenant/TenantProvisioning.java",
                    "число попыток занять номер — величина, названная в отказе"),
            new Allowed("platform/health/ReadinessEndpoint.java",
                    "счёт отставших схем и changeset'ов: читает дежурный по ячейке"),
            new Allowed("platform/settings/CompanySettingsService.java",
                    "границы срока резервирования в днях — их владелец и вводит"),
            new Allowed("platform/outbox/EventPayloads.java",
                    "номер события в логе разбора недоставленного, не ответ человеку"),
            new Allowed("publishing/drom/DromSyncClient.java",
                    "размер дельты в байтах против предела площадки"),
            new Allowed("inventory/PartService.java",
                    "«Ни одна из %d позиций не прошла операцию» — число позиций правки списком"),
            new Allowed("sales/Deal.java",
                    "«запрошено %d, найдено %d» — сколько позиций просили и нашли"),
            new Allowed("migration/bazon/BazonWheelImporter.java",
                    "номер строки файла переноса: человек ищет её в своём файле"),
            new Allowed("migration/bazon/ImportReport.java",
                    "номера строк файла и счётчики отчёта о переносе"),
            new Allowed("migration/bazon/BazonImporter.java",
                    "число позиций без колонки «Выгружать» в отчёте о переносе"),
            new Allowed("migration/excel/ExcelWarehouseImporter.java",
                    "номер строки таблицы, которую владелец загрузил сам"),
            new Allowed("platform/tenant/TenantConnectionProvider.java",
                    "это SQL настройки соединения, а не текст человеку"),
            new Allowed("publishing/drom/DromDeltaSender.java",
                    "ключ пачки дельт для лога площадки"),
            new Allowed("publishing/drom/FeedDeltaRelay.java",
                    "ключ пачки дельт для лога площадки"),
            new Allowed("platform/outbox/DomainEvent.java",
                    "ключ идемпотентности события, наружу не идёт"),
            new Allowed("inventory/PhotoStorage.java",
                    "ключ объекта в хранилище, наружу не идёт"),
            new Allowed("reports/SoldItemsReportService.java",
                    "метка продолжения страницы, наружу идёт как курсор"),
            new Allowed("platform/audit/OrganizationAuditService.java",
                    "«запись №N» у удалённой вещи: названия больше нет, и прочерк"
                            + " не дал бы даже зацепки для запроса в базу —"
                            + " решение названо в javadoc самого метода"));

    /**
     * Очередь работы, а не разрешение: у этих мест номер заменить **нечем**.
     *
     * <p>У `inventory_session` нет колонки `number` — своего номера
     * у документа пересчёта не существует, а завести её может только агент
     * {@code migrator} ({@code db/changelog} правит он, по одному за раз).
     * Поэтому список меряется наоборот ({@link #queueIsStillOpen()}): пока
     * колонки нет, место <b>обязано</b> находиться, и починка валит проверку
     * с требованием убрать строку. Список, зеленеющий и после починки, через
     * месяц перестают читать — та же природа, что у {@code KNOWN_WIDE}
     * в {@code narrowViewport.test.tsx} и пометки {@code ПРОБЕЛ}
     * в {@code tools/endpoint-coverage.py}.
     */
    private static final List<Allowed> QUEUE = List.of(
            new Allowed("inventory/PartHistoryService.java",
                    "«Пересчёт №N» в истории карточки: колонки number"
                            + " у inventory_session нет, нужен migrator"));

    @Test
    @DisplayName("Отказ не называет человеку номер строки базы")
    void refusalsDoNotNameRowIds() throws IOException {
        List<String> guilty = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = SRC.relativize(file).toString();
                if (allowed(rel) || inQueue(rel)) {
                    continue;
                }
                String text = Files.readString(file);
                for (Pattern pattern : List.of(GLUED, FORMATTED)) {
                    Matcher m = pattern.matcher(text);
                    while (m.find()) {
                        guilty.add(rel + ": " + m.group().replace('\n', ' '));
                    }
                }
            }
        }

        // Перебор без файлов — зелёный тест ни о чём: ошибись путь, и он
        // молчал бы при любом коде. Классов в src/main/java почти две сотни.
        assertThat(count())
                .as("перебор не нашёл исходников — проверьте путь %s", SRC)
                .isGreaterThan(100);

        assertThat(guilty)
                .as("здесь внутренний номер приклеен к тексту, который читает человек."
                        + " По номеру строки базы нельзя ни найти, ни спросить, ни позвонить."
                        + " Зовите ru.partsflow.shared.NotFound (вещь словами, номер в лог);"
                        + " если число в этой строке — величина, которую человек и спрашивает,"
                        + " внесите файл в ALLOWED с причиной")
                .isEmpty();
    }

    /**
     * Совет «что делать» живёт в одном месте.
     *
     * <p>Первая проверка ловит <b>номер</b> в тексте; эта — <b>второй
     * источник тех же слов</b>. До задачи 0067 канонический хвост
     * «обновите страницу, список устарел» был выписан руками в шести
     * файлах: {@code CustomerService} (дважды), {@code PaymentSourceService},
     * {@code DealSourceService}, {@code SalesService},
     * {@code PrintSettingsService}. Шесть копий одной фразы расходятся
     * молча — ровно так разъехались четыре написания пустого клиента
     * и пять копий словаря состояний сделки.
     *
     * <p><b>Чего эта проверка намеренно не требует.</b> Отказ «не найдено»
     * со <b>своим</b> советом законен: {@code DealPrintService} говорит
     * «Склад выдачи не найден — возможно, его убрали. Реквизиты продавца…»,
     * потому что действие там другое. Запрещена копия <b>этого</b> совета,
     * а не всякое слово «не найден»: проверка, краснеющая на законном,
     * была бы выключена первой.
     */
    @Test
    @DisplayName("Совет «обновите страницу» выписан в одном месте")
    void adviceComesFromOnePlace() throws IOException {
        List<String> copies = new ArrayList<>();
        String advice = "обновите страницу, список устарел";

        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = SRC.relativize(file).toString();
                if (rel.equals("shared/NotFound.java")) {
                    continue;
                }
                if (Files.readString(file).contains(advice)) {
                    copies.add(rel);
                }
            }
        }

        assertThat(copies)
                .as("здесь вторая копия совета «%s» — а он один на проект"
                        + " (ru.partsflow.shared.NotFound): копии расходятся молча."
                        + " Зовите NotFound.<вещь>.error(id)", advice)
                .isEmpty();

        assertThat(Files.readString(SRC.resolve("shared/NotFound.java")))
                .as("словарь отказов обязан называть, что делать, а не только вещь")
                .contains(advice);
    }

    /** Очередь работы меряется наоборот — см. {@link #QUEUE}. */
    @Test
    @DisplayName("Очередь: номер пересчёта ещё показывается, заменить его нечем")
    void queueIsStillOpen() throws IOException {
        for (Allowed queued : QUEUE) {
            Path file = SRC.resolve(queued.file());
            assertThat(file).as("файл из QUEUE исчез — поправьте список").exists();

            Matcher m = GLUED.matcher(Files.readString(file));
            assertThat(m.find())
                    .as("%s больше не склеивает внутренний номер — уберите его строку"
                            + " из QUEUE: список, зеленеющий и после починки, через месяц"
                            + " перестают читать", queued.file())
                    .isTrue();
        }
    }

    private static long count() throws IOException {
        try (Stream<Path> files = Files.walk(SRC)) {
            return files.filter(p -> p.toString().endsWith(".java")).count();
        }
    }

    private static boolean inQueue(String rel) {
        String unix = rel.replace('\\', '/');
        return QUEUE.stream().anyMatch(a -> unix.equals(a.file()));
    }

    private static boolean allowed(String rel) {
        String unix = rel.replace('\\', '/');
        return ALLOWED.stream().anyMatch(a -> unix.equals(a.file()));
    }

    private record Allowed(String file, String why) {
    }
}
