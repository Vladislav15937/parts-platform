package ru.partsflow.inventory;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    private final CatalogService catalog;
    private final PhotoStorage storage;

    public CatalogController(CatalogService catalog,
                             PhotoStorage storage) {
        this.catalog = catalog;
        this.storage = storage;
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
                     @RequestParam(defaultValue = "code") String sort,
                     @RequestParam(defaultValue = "true") boolean desc,
                     @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "50") int size) {

        CatalogService.Page found = catalog.list(q, reserved, missing, warehouses,
                new CatalogService.Vehicle(brandId, modelId, body, engine),
                sort, desc, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));

        return new View(found.total(), catalog.warehouses(),
                found.rows().stream().map(this::rowOf).toList());
    }

    /**
     * Ссылка на превью подписывается на месте.
     *
     * <p>Фотографии лежат в S3 и отдаются по подписанной ссылке — постоянной
     * ссылки у них нет намеренно. Подписей на страницу пятьдесят, и это
     * счёт хеша, а не поход в хранилище.
     */
    private Row rowOf(CatalogService.Row row) {
        return new Row(row.id(), row.code(), row.title(), row.qualityGrade(), row.condition(),
                row.brand(), row.model(), row.generation(), row.yearFrom(), row.yearTo(),
                row.body(), row.engine(), row.year(), row.donorCode(),
                row.price(), row.installationPrice(), row.color(), row.description(), row.note(),
                row.manufacturer(), row.marking(), row.section(), row.sideLr(), row.sideFr(),
                row.qty(), row.oem(), row.crosses(),
                row.photoKey() == null ? null : storage.presignView(row.photoKey()),
                row.supply(), row.equipment(),
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
    @PreAuthorize("hasRole('OWNER')")
    public CatalogService.Parsed applicabilityFromTitles() {
        return catalog.applyFromTitles();
    }

    /** Добавляет машину в применимость позиции — из карточки. */
    @PostMapping("/{id}/applicability")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<CatalogService.Applicability> addApplicability(
            @PathVariable Long id, @RequestBody VehicleRequest request) {
        catalog.addApplicability(id, request.brandId(), request.modelId());
        return catalog.applicabilityOf(id);
    }

    @DeleteMapping("/{id}/applicability/{applicabilityId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<CatalogService.Applicability> removeApplicability(
            @PathVariable Long id, @PathVariable Long applicabilityId) {
        catalog.removeApplicability(id, applicabilityId);
        return catalog.applicabilityOf(id);
    }

    /** @param modelId пусто — «любая модель этой марки» */
    public record VehicleRequest(Long brandId, Long modelId) {
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

    @GetMapping("/export")
    public void export(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "true") boolean reserved,
                       @RequestParam(defaultValue = "false") boolean missing,
                       @RequestParam(required = false) List<Long> warehouses,
                       @RequestParam(required = false) Long brandId,
                       @RequestParam(required = false) Long modelId,
                       @RequestParam(required = false) String body,
                       @RequestParam(required = false) String engine,
                       @RequestParam(defaultValue = "code") String sort,
                       @RequestParam(defaultValue = "true") boolean desc,
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
                new CatalogService.Vehicle(brandId, modelId, body, engine), sort, desc, found,
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

    public record View(long total, List<CatalogService.Warehouse> warehouses, List<Row> rows) {
    }

    public record Row(Long id, String code, String title, String qualityGrade, String condition,
                      String brand, String model, String generation,
                      Integer yearFrom, Integer yearTo, String body, String engine,
                      Integer year, String donorCode,
                      java.math.BigDecimal price, java.math.BigDecimal installationPrice,
                      String color, String description, String note,
                      String manufacturer, String marking, String section,
                      String sideLr, String sideFr, java.math.BigDecimal qty,
                      String oem, String crosses, String photoUrl,
                      String supply, String equipment,
                      Map<Long, java.math.BigDecimal> stock) {
    }
}
