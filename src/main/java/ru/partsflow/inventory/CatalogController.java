package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.util.List;
import java.util.Map;

/**
 * Витрина склада: таблица товаров.
 *
 * <p>Постранично намеренно: у переехавшего клиента тридцать пять тысяч
 * позиций, и отдать их одним ответом значит собрать в памяти несколько
 * десятков мегабайт на каждого смотрящего.
 *
 * <p>Колонки складов приходят вместе со страницей: их столько, сколько складов
 * у клиента, и знать это заранее экран не может.
 */
@RestController
@RequestMapping("/api/parts/catalog")
public class CatalogController {

    /** Больше двухсот строк за раз не отдаём: экран столько всё равно не покажет. */
    private static final int MAX_SIZE = 200;

    /**
     * Порядок по умолчанию — номер позиции по убыванию, новые сверху.
     *
     * <p>Решение владельца продукта 19.10.2026 «всё как у bazon» (задача 0372):
     * прежнее «по возрастанию» (0060, 11.09.2026) отменено. До 0060 здесь стоял
     * {@code code} — шесть случайных байт, то есть склад открывался случайной
     * полусотней. Умолчание одно у страницы, у выгрузки и у экрана
     * ({@code DEFAULT_SORT}/{@code DEFAULT_DESC} в {@code inventory/catalog.ts}).
     *
     * <p>С задачи 0379 то же правило читает и вкладка «Шины и диски»
     * ({@code WheelController}): вторая копия разошлась бы с этой на первой
     * правке, и соседние экраны снова открывались бы по-разному.
     */
    static final String DEFAULT_SORT = "number";

    static String sortOf(String sort) {
        return sort == null ? DEFAULT_SORT : sort;
    }

    /**
     * «По убыванию» — направление умолчания целиком, а не любой колонки:
     * названная без направления сортировка идёт по возрастанию, как нажатие
     * на заголовок. Иначе {@code sort=brand} без {@code desc} начинался бы с «Я».
     */
    static boolean descOf(String sort, Boolean desc) {
        return desc != null ? desc : sort == null;
    }

    private final CatalogService catalog;
    private final PhotoStorage storage;
    private final PartService parts;
    private final DromApplicabilityService dromApplicability;
    private final ApplicabilityDetails details;

    public CatalogController(CatalogService catalog,
                             PhotoStorage storage,
                             PartService parts,
                             DromApplicabilityService dromApplicability,
                             ApplicabilityDetails details) {
        this.details = details;
        this.catalog = catalog;
        this.storage = storage;
        this.parts = parts;
        this.dromApplicability = dromApplicability;
    }

    @GetMapping
    public View list(@RequestParam(required = false) String q,
                     @RequestParam(defaultValue = "true") boolean reserved,
                     @RequestParam(defaultValue = "false") boolean missing,
                     @RequestParam(required = false) List<Long> warehouses,
                     @RequestParam(required = false) Long brandId,
                     @RequestParam(required = false) Long modelId,
                     @RequestParam(required = false) String body,
                     @RequestParam(required = false) String engine,
                     jakarta.servlet.http.HttpServletRequest request,
                     /* Порядок по умолчанию — см. DEFAULT_SORT. */
                     @RequestParam(required = false) String sort,
                     @RequestParam(required = false) Boolean desc,
                     @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "50") int size,
                     /* Номер товара последней строки предыдущей страницы:
                        тогда следующая берётся от него, а не отступом. */
                     @RequestParam(required = false) String after) {

        CatalogService.Page found = catalog.list(q, reserved, missing, warehouses,
                new CatalogService.Vehicle(brandId, modelId, body, engine),
                columnsOf(request, "filter"), columnsOf(request, "find"),
                sortOf(sort), descOf(sort, desc), Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_SIZE), after);

        return new View(found.total(), catalog.warehouses(),
                found.rows().stream().map(this::rowOf).toList(),
                CatalogService.filterableColumns());
    }

    /**
     * Ссылка на превью подписывается на месте.
     *
     * <p>Фотографии лежат в S3 и отдаются по подписанной ссылке — постоянной
     * ссылки у них нет намеренно. Подписей на страницу пятьдесят, и это
     * счёт хеша, а не поход в хранилище.
     */
    private Row rowOf(CatalogService.Row row) {
        return new Row(row.id(), row.number(), row.code(), row.title(), row.qualityGrade(),
                row.condition(),
                row.brand(), row.model(), row.generation(), row.yearFrom(), row.yearTo(),
                row.body(), row.engine(), row.year(), row.donorCode(),
                row.price(), row.installationPrice(), row.color(), row.description(), row.note(),
                row.manufacturer(), row.marking(), row.section(), row.cellCode(),
                row.sideLr(), row.sideFr(),
                row.qty(), row.oem(), row.crosses(),
                row.photoKey() == null ? null : storage.presignView(row.photoKey()),
                row.supply(), row.equipment(),
                row.partName(), row.published(), row.barcode(),
                row.legacyCode(), row.dromAdNumber(), row.authenticity(),
                row.videoUrl(), row.textBlock(),
                row.weightKg(), row.dimensions(), row.packageDimensions(),
                row.packageWeightKg(),
                row.createdAt(), row.updatedAt(), row.updatedByName(),
                row.priceChangedAt(), row.priceChangedByName(), row.photoCount(),
                row.vehicleSource(), row.vehicleSpans(),
                row.vehicleTitleBody(), row.vehicleBlockBodies(),
                row.vehicleGenerationBodies(),
                row.vehicleGenerationMerged(),
                row.vehicleBodySource(), row.vehicleEngineSource(), row.vehicleBodySeries(),
                row.vehicleEnginesForBlock(),
                row.stock());
    }

    /**
     * Скачать таблицу — то же, что на экране, только целиком.
     *
     * <p>Пишется прямо в ответ по мере чтения из базы: тридцать пять тысяч
     * строк, собранные в памяти перед отправкой, — сотни мегабайт на каждого
     * скачивающего.
     *
     * <p><b>UTF-8 с меткой, а не windows-1251, как у прежней системы.</b>
     * Метка нужна Excel: без неё он читает файл однобайтовой кодировкой
     * и показывает кракозябры вместо наименований. А 1251 не умеет всего,
     * что встречается в наименованиях, и потерянный символ в выгрузке
     * замечают не сразу — это единственное осознанное расхождение
     * с прежним форматом.
     *
     * <p>Разделитель — точка с запятой: Excel в русской локали разбирает
     * запятую как десятичный знак, и файл раскладывается по колонкам неверно.
     */
    /** Машины, к которым на складе что-то есть, — для подбора по применимости. */
    /**
     * Проставляет применимость по машинам из заголовков — всему складу разом.
     *
     * <p>Руками для девяти тысяч позиций это работа на месяцы. Повтор
     * безопасен: строки не дублируются, а подтверждённое человеком
     * не перезаписывается.
     */
    @PostMapping("/applicability/from-titles")
    @PreAuthorize("hasAuthority('applicability.bulk')")
    public CatalogService.Parsed applicabilityFromTitles() {
        return catalog.applyFromTitles();
    }

    /**
     * Раскладывает блок «Подходит» карточек Дрома в справочную применимость —
     * по уже загруженному складу (задача 0289).
     *
     * <p>Тем же, кому разбор наименований: проход по всему складу, и отменить
     * его можно только правкой каждой карточки. Повтор безопасен: та же машина
     * второй строкой не заводится, подтверждённое человеком не трогается,
     * текст клиента не меняется.
     */
    @PostMapping("/applicability/from-drom")
    @PreAuthorize("hasAuthority('applicability.bulk')")
    public DromApplicabilityService.Report applicabilityFromDrom() {
        return dromApplicability.applyAll();
    }

    /** Добавляет машину в применимость позиции — из карточки. */
    @PostMapping("/{id}/applicability")
    @PreAuthorize("hasAuthority('part.edit')")
    public List<CatalogService.Applicability> addApplicability(
            @PathVariable Long id, @RequestBody VehicleRequest request) {
        catalog.addApplicability(id, request.brandId(), request.modelId(), CurrentUser.memberId());
        return catalog.applicabilityOf(id);
    }

    @DeleteMapping("/{id}/applicability/{applicabilityId}")
    @PreAuthorize("hasAuthority('part.edit')")
    public List<CatalogService.Applicability> removeApplicability(
            @PathVariable Long id, @PathVariable Long applicabilityId) {
        catalog.removeApplicability(id, applicabilityId);
        return catalog.applicabilityOf(id);
    }

    /** @param modelId пусто — «любая модель этой марки» */
    public record VehicleRequest(Long brandId, Long modelId) {
    }

    /**
     * «Двигатель важен» и пояснение, почему применимость не поставилась сама
     * (задача 0363). Читают все вошедшие, как и саму применимость.
     */
    @GetMapping("/{id}/applicability/state")
    public ApplicabilityDetails.State applicabilityState(@PathVariable Long id) {
        return details.stateOf(id);
    }

    /** Ставит «Двигатель важен / не важен». Правит владелец и менеджер, как остальную применимость. */
    @PutMapping("/{id}/engine-matters")
    @PreAuthorize("hasAuthority('part.edit')")
    public ApplicabilityDetails.State setEngineMatters(
            @PathVariable Long id, @RequestBody EngineMattersRequest request) {
        return details.setEngineMatters(id, request.matters());
    }

    /** @param matters {@code true} — «Двигатель важен», {@code false} — «не важен» */
    public record EngineMattersRequest(boolean matters) {
    }

    /** «Применимость подробно»: позиции склада с автором и временем установки. */
    @GetMapping("/applicability-list")
    public ApplicabilityDetails.Page applicabilityList(
            @RequestParam(defaultValue = "0") int page) {
        return details.list(page);
    }

    /** Заявленная применимость позиции — для карточки. */
    @GetMapping("/{id}/applicability")
    public List<CatalogService.Applicability> applicability(@PathVariable Long id) {
        return catalog.applicabilityOf(id);
    }

    @GetMapping("/vehicles")
    public List<CatalogService.VehicleOption> vehicles() {
        return catalog.vehicles();
    }

    /**
     * Различные значения колонки — то, из чего владелец выбирает отбор.
     *
     * <p>Отдельным запросом по нажатию на заголовок, а не вместе со страницей:
     * колонок сорок с лишним, и считать все списки на каждую страницу значит
     * сорок запросов там, где нужен один, и то не всегда.
     */
    @GetMapping("/values")
    public List<String> values(@RequestParam String column) {
        return catalog.values(column);
    }

    /**
     * Правка всего, что попало в отбор, — а не одной отмеченной страницы.
     *
     * <p><b>Зачем.</b> Отметить можно только то, что на экране, а на экране
     * пятьдесят строк. После переезда без колонки «Выгружать» включить
     * публикацию надо всему складу: у живого клиента это 35 841 позиция,
     * то есть семьсот семнадцать страниц, причём выделение сбрасывается
     * на каждой. До тех пор прайс уезжает пустым — 55 байт вместо
     * двадцати мегабайт, — и площадка молча не заводит ни одного
     * объявления. Сама возможность была написана с самого начала: правка
     * списком принимает хоть весь склад и проходит его за двадцать секунд.
     * Не было только способа дотянуться до неё с экрана — та же порода
     * ошибки, что с экранами сотрудников, складов, кабинета площадки
     * и ссылки на прайс.
     *
     * <p><b>Отбор описан теми же параметрами, что страница и выгрузка.</b>
     * Не ради краткости: правка обязана тронуть ровно то, что владелец
     * видел на экране. Свой набор параметров разошёлся бы с витриной
     * на первой же новой колонке, и владелец правил бы одно, а менялось
     * бы другое — заметить это можно только пересчётом склада.
     *
     * <p>Набор полей тот же закрытый {@code BULK_FIELDS}: шире становится
     * не то, <i>что</i> можно изменить, а только <i>скольким</i> позициям.
     * Сколько именно, экран показывает до нажатия — это и есть защита,
     * потому что отменить правку нечем, кроме восстановления из бэкапа.
     *
     * <p>Пустой отбор — это весь склад, и так и задумано: именно он нужен
     * после переезда. А вот отбор, не нашедший ничего, отвергается: «изменено
     * 0» владелец прочитает как «сделано», и ошибку в отборе он не заметит.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('part.edit')")
    public PartController.BulkResult bulk(@RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "true") boolean reserved,
                           @RequestParam(defaultValue = "false") boolean missing,
                           @RequestParam(required = false) List<Long> warehouses,
                           @RequestParam(required = false) Long brandId,
                           @RequestParam(required = false) Long modelId,
                           @RequestParam(required = false) String body,
                           @RequestParam(required = false) String engine,
                           jakarta.servlet.http.HttpServletRequest http,
                           @Valid @RequestBody BulkByFilter request) {

        List<Long> ids = catalog.idsMatching(q, reserved, missing, warehouses,
                new CatalogService.Vehicle(brandId, modelId, body, engine),
                columnsOf(http, "filter"), columnsOf(http, "find"));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Отбор не нашёл ни одной позиции");
        }
        PartService.BulkOutcome outcome = parts.updateAll(ids, request.changes(),
                request.operations(), CurrentUser.memberId(),
                ru.partsflow.platform.audit.AuditBatch.Scope.FILTER);
        // Ответ тот же, что у правки отмеченного (POST /api/parts/bulk),
        // и запись одна на два пути: экран у них общий, а разойдясь, они дали
        // бы форму, которая после одного нажатия говорит о непрошедших,
        // а после другого молчит.
        return PartController.BulkResult.of(outcome);
    }

    /**
     * Только изменения: что править, сказано параметрами отбора.
     *
     * @param operations что сделать с денежным полем — процент, сумма,
     *                   округление. Считается от прежней цены каждой позиции,
     *                   а не сводит весь отбор к одному числу
     */
    public record BulkByFilter(@NotEmpty Map<String, Object> changes,
                               Map<String, PriceOperation> operations) {
    }

    /**
     * Пары отбора — сырыми значениями параметра, а не {@code List<String>}
     * от Spring (задача 0373).
     *
     * <p><b>Почему не параметром метода.</b> Один параметр Spring приводит
     * к списку, <b>разрезая по запятой</b>: «updatedBy:Пётр Массов (vladelec,
     * ID 4207)» приезжал двумя половинами, вторая — без двоеточия, и отбор
     * отвечал 400 «задаётся как колонка:значение». С подписью сотрудника
     * «Имя (логин, ID N)» запятая стоит в каждом значении «Кто изменил»;
     * раньше так же ломалось любое одиночное значение с запятой (заметка,
     * описание). Два и больше параметров Spring не режет — потому и
     * не замечали. {@code getParameterValues} отдаёт ровно то, что прислал
     * экран.
     */
    private static java.util.Map<String, String> columnsOf(
            jakarta.servlet.http.HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return columnsOf(values == null ? null : List.of(values));
    }

    /**
     * Отбор приходит парами «колонка:значение» — по одной на каждый фильтр.
     *
     * <p>Разделитель ищется первым: в значении двоеточие встречается,
     * и разбор по последнему разрезал бы значение пополам.
     */
    private static java.util.Map<String, String> columnsOf(List<String> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return java.util.Map.of();
        }
        var columns = new java.util.LinkedHashMap<String, String>();
        for (String pair : pairs) {
            int at = pair.indexOf(':');
            if (at <= 0) {
                throw new IllegalArgumentException("Отбор задаётся как «колонка:значение»: " + pair);
            }
            columns.put(pair.substring(0, at), pair.substring(at + 1));
        }
        return columns;
    }

    /**
     * Выгрузка витрины таблицей — владельцу и менеджеру.
     *
     * <p>Экран «Склад» открыт всем вошедшим, и это правильно: цену и наличие
     * смотрит и кладовщик, и «Просмотр». А вот один файл со всей номенклатурой
     * — это опись имущества: номера товаров, цены, поставки, заметки, кросс-номера
     * и остатки по каждому складу разом. Первые десять клиентов — компании
     * из одного города; ушедший кладовщик с таким файлом уносит готовый прайс
     * конкурента. Себестоимости в файле нет (см. {@link CatalogService#exportHeader}),
     * поэтому право дано тем же, кому открыты отчёты, а не только владельцу.
     *
     * <p>Право записано одним местом — {@link TableExport#ALLOWED}, общим
     * с вкладкой колёс; там же оговорка, что это заплатка до полномочия
     * «скачивать таблицу» (задача 0044).
     *
     * <p>Проверка стоит до {@code getOutputStream()} не случайно: отдав первый
     * байт, статус ответа уже не сменить, и отказ уехал бы двухсотым с телом
     * склада внутри.
     */
    @PreAuthorize("hasAuthority('export.table')")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "true") boolean reserved,
                       @RequestParam(defaultValue = "false") boolean missing,
                       @RequestParam(required = false) List<Long> warehouses,
                       @RequestParam(required = false) Long brandId,
                       @RequestParam(required = false) Long modelId,
                       @RequestParam(required = false) String body,
                       @RequestParam(required = false) String engine,
                       jakarta.servlet.http.HttpServletRequest request,
                       // Тот же порядок по умолчанию, что и у страницы:
                       // скачанный файл обязан совпасть с экраном.
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) Boolean desc,
                       jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        List<CatalogService.Warehouse> found = catalog.warehouses();

        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"sklad.csv\"");

        var out = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(response.getOutputStream(),
                        java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
        // Метка порядка байтов: без неё Excel открывает файл в кодировке
        // системы и показывает кракозябры.
        out.write('\uFEFF');
        writeRow(out, CatalogService.exportHeader(found));

        catalog.export(q, reserved, missing, warehouses,
                new CatalogService.Vehicle(brandId, modelId, body, engine),
                columnsOf(request, "filter"), columnsOf(request, "find"), sortOf(sort), descOf(sort, desc), found,
                cells -> writeRow(out, cells));
        out.flush();
    }

    private static void writeRow(java.io.Writer out, List<String> cells) {
        try {
            for (int at = 0; at < cells.size(); at++) {
                if (at > 0) {
                    out.write(';');
                }
                // Кавычки вокруг всего: в наименованиях встречаются и точка
                // с запятой, и перенос строки, и сами кавычки — «фара 5"».
                out.write('"');
                out.write(cells.get(at).replace("\"", "\"\""));
                out.write('"');
            }
            out.write('\n');
        } catch (java.io.IOException e) {
            // Обрыв на середине выгрузки — обычное дело: скачивающий закрыл
            // вкладку. Заворачиваем, чтобы не тащить проверяемое исключение
            // через обход курсора.
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * @param filterable по каким колонкам отбор делается. Экран открывает меню
     *                   только у них: у остальных сервер отвечает отказом,
     *                   а отказ, проглоченный молча, выглядит как «отбор
     *                   не сработал» на целом складе
     */
    public record View(long total, List<CatalogService.Warehouse> warehouses, List<Row> rows,
                       java.util.Set<String> filterable) {
    }

    public record Row(Long id,
                      /**
                       * Порядковый номер позиции — его показывают и называют
                       * вслух. {@code id} наружу не идёт: он внутренний.
                       */
                      Long number,
                      String code, String title, String qualityGrade, String condition,
                      String brand, String model, String generation,
                      Integer yearFrom, Integer yearTo, String body, String engine,
                      Integer year, String donorCode,
                      java.math.BigDecimal price, java.math.BigDecimal installationPrice,
                      String color, String description, String note,
                      String manufacturer, String marking, String section,
                      /**
                       * Код ячейки хранения — не то же, что {@code section}:
                       * ту клиент пишет руками своей нумерацией полок,
                       * а ячейку заводят складом и печатают на этикетке.
                       */
                      String cellCode,
                      String sideLr, String sideFr, java.math.BigDecimal qty,
                      String oem, String crosses, String photoUrl,
                      String supply, String equipment,
                      // Паритет с таблицей товаров прежней системы: сорок две
                      // колонки против наших двадцати четырёх.
                      String partName, Boolean published, String barcode,
                      String legacyCode, String dromAdNumber, String authenticity,
                      String videoUrl, String textBlock,
                      java.math.BigDecimal weightKg, String dimensions,
                      String packageDimensions, java.math.BigDecimal packageWeightKg,
                      java.time.Instant createdAt, java.time.Instant updatedAt,
                      String updatedByName,
                      java.time.Instant priceChangedAt, String priceChangedByName,
                      int photoCount,
                      /** Откуда машина в колонках — для подсказки клетки (задача 0290). */
                      String vehicleSource,
                      /** Поколений у своей машины в применимости. */
                      Integer vehicleSpans,
                      /**
                       * Номер кузова в наименовании и номера кузовов блока, когда
                       * поколение не показано, потому что номер ими не подтверждён
                       * (задачи 0294, 0299); кузовов нет — второе поле пусто.
                       */
                      String vehicleTitleBody, String vehicleBlockBodies,
                      /** Коды кузова, по которым поколение определено нами (задача 0295). */
                      String vehicleGenerationBodies,
                      /**
                       * Поколение Дрома целиком из нескольких строк справочника
                       * (рестайлинги, задача 0297) — подсказка клетки так и говорит.
                       */
                      boolean vehicleGenerationMerged,
                      /** Откуда «Кузов» и «Двигатель» — для подсказки клетки (задача 0296). */
                      String vehicleBodySource, String vehicleEngineSource,
                      /** Номер кузова из наименования, до ряда которого сужены кузова своей машины. */
                      String vehicleBodySeries,
                      /** Двигатели названы для всего блока «Подходит», а не для этой машины. */
                      boolean vehicleEnginesForBlock,
                      Map<Long, java.math.BigDecimal> stock) {
    }
}
