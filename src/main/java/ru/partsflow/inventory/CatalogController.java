package ru.partsflow.inventory;

import org.springframework.web.bind.annotation.GetMapping;
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
                     @RequestParam(defaultValue = "code") String sort,
                     @RequestParam(defaultValue = "true") boolean desc,
                     @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "50") int size) {

        CatalogService.Page found = catalog.list(q, reserved, missing, warehouses,
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
                row.stock());
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
                      Map<Long, java.math.BigDecimal> stock) {
    }
}
