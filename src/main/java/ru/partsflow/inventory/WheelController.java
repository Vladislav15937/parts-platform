package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.util.List;

/**
 * Шины и диски: своя вкладка, свои свойства, общий склад с запчастями.
 *
 * <p>Заводятся комплектом — на разборке снимают четыре колеса разом,
 * и повторять двенадцать полей четырежды никто не станет. Продаются
 * поштучно: запаску берут по одной.
 */
@RestController
@RequestMapping("/api/wheels")
public class WheelController {

    private static final String INTAKES = "hasAnyRole('OWNER','MANAGER','STOREKEEPER')";

    private final WheelService wheels;
    private final CatalogService catalog;
    private final PhotoStorage storage;

    public WheelController(WheelService wheels, CatalogService catalog,
                           PhotoStorage storage) {
        this.wheels = wheels;
        this.catalog = catalog;
        this.storage = storage;
    }

    /**
     * Список колёс со складами страницы.
     *
     * <p>Склады едут вместе со списком, а не отдельным запросом: колонок
     * складов у клиента две, у другого будет пять, и остаток по каждому —
     * своя колонка. Та же причина, что и на витрине запчастей.
     */
    @GetMapping
    public View list(@RequestParam(required = false) String q,
                     @RequestParam(required = false) String kind,
                     @RequestParam(defaultValue = "false") boolean missing,
                     @RequestParam(required = false) List<String> filter,
                     @RequestParam(defaultValue = "set") String sort,
                     @RequestParam(defaultValue = "true") boolean desc,
                     @RequestParam(defaultValue = "200") int limit) {

        return new View(catalog.warehouses(),
                wheels.list(q, kind, missing, columnsOf(filter), sort, desc, Math.min(limit, 500))
                        .stream().map(this::rowOf).toList());
    }

    /**
     * Выгрузка вкладки в таблицу.
     *
     * <p>Ссылкой, а не запросом из скрипта: файл качает браузер, показывая
     * ход, и вкладка при этом жива. Отбор тот же, что у страницы — скачанный
     * файл обязан совпасть с тем, что владелец видел на экране.
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String kind,
                       @RequestParam(defaultValue = "false") boolean missing,
                       @RequestParam(required = false) List<String> filter,
                       @RequestParam(defaultValue = "set") String sort,
                       @RequestParam(defaultValue = "true") boolean desc,
                       jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        List<CatalogService.Warehouse> found = catalog.warehouses();

        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"kolesa.csv\"");

        var out = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(response.getOutputStream(),
                        java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
        // Метка порядка байтов: без неё Excel открывает файл в кодировке
        // системы и показывает кракозябры.
        out.write('\uFEFF');
        writeRow(out, WheelService.exportHeader(found));

        wheels.export(q, kind, missing, columnsOf(filter), sort, desc, found,
                cells -> writeRow(out, cells));
        out.flush();
    }

    /**
     * Разделитель — точка с запятой: Excel в русской локали разбирает запятую
     * как десятичный знак. Кавычки вокруг всего: в наименованиях встречается
     * и точка с запятой, и перенос строки, и сами кавычки.
     */
    private static void writeRow(java.io.Writer out, List<String> cells) {
        try {
            for (int at = 0; at < cells.size(); at++) {
                if (at > 0) {
                    out.write(';');
                }
                out.write('"');
                out.write(cells.get(at).replace("\"", "\"\""));
                out.write('"');
            }
            out.write('\n');
        } catch (java.io.IOException e) {
            // Обрыв на середине выгрузки — обычное дело: скачивающий закрыл
            // вкладку. Заворачиваем, чтобы не тащить проверяемое исключение
            // через обработчик строки.
            throw new java.io.UncheckedIOException(e);
        }
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
        return wheels.values(column);
    }

    /**
     * Отбор приходит парами «колонка:значение» — по одной на каждый
     * поставленный фильтр.
     *
     * <p>Разделитель ищется первым: в значении двоеточие встречается
     * («Контейнер №7 | 19.06.2024» его не содержит, а вот заметка может),
     * и разбор по последнему разрезал бы значение пополам.
     */
    private static java.util.Map<String, String> columnsOf(List<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return java.util.Map.of();
        }
        var columns = new java.util.LinkedHashMap<String, String>();
        for (String pair : filter) {
            int at = pair.indexOf(':');
            if (at <= 0) {
                throw new IllegalArgumentException("Отбор задаётся как «колонка:значение»: " + pair);
            }
            columns.put(pair.substring(0, at), pair.substring(at + 1));
        }
        return columns;
    }

    /**
     * Ссылка на превью подписывается на месте: постоянной ссылки
     * у фотографий нет намеренно, они короткоживущие.
     */
    private Row rowOf(WheelService.WheelRow row) {
        return new Row(row, row.photoKey() == null ? null : storage.presignView(row.photoKey()));
    }

    /** @param warehouses колонки складов: у каждого клиента свои */
    public record View(List<CatalogService.Warehouse> warehouses, List<Row> rows) {
    }

    /**
     * Строка витрины колёс: свойства плюс подписанная ссылка на превью.
     *
     * <p>Свойства отдаются вложенным объектом, а не расплющенными в строку:
     * их сорок, и половина относится только к шине или только к диску.
     */
    public record Row(WheelService.WheelRow wheel, String photoUrl) {
    }

    @PostMapping("/sets")
    @PreAuthorize(INTAKES)
    public ResponseEntity<WheelService.Created> createSet(@Valid @RequestBody SetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wheels.createSet(
                new WheelService.WheelRequest(
                        request.kind(), request.diameter(),
                        request.tyreWidth(), request.tyreHeight(), request.construction(),
                        request.tyreType(), request.season(), request.wearMm(),
                        request.madeYear(),
                        request.discType(), request.discWidth(), request.offsetMm(),
                        request.boltPattern(), request.hubBore(),
                        request.brand(), request.model(),
                        request.discBrand(), request.discModel(),
                        request.markingType(), request.treadType(), request.runFlat(),
                        request.lightTruck(), request.speedIndex(), request.loadIndex(),
                        request.price(), request.costPrice(), request.condition()),
                request.quantity(), request.warehouseId(), CurrentUser.memberId()));
    }

    /**
     * @param quantity сколько колёс в комплекте: обычно четыре, но продают
     *                 и одну запаску
     * @param wearMm   остаток протектора в миллиметрах
     */
    public record SetRequest(@NotBlank String kind,
                             @NotNull Long warehouseId,
                             int quantity,
                             BigDecimal diameter,
                             Integer tyreWidth, Integer tyreHeight, String construction,
                             String tyreType, String season, BigDecimal wearMm,
                             Integer madeYear,
                             String discType, BigDecimal discWidth, Integer offsetMm,
                             String boltPattern, BigDecimal hubBore,
                             String brand, String model,
                             String discBrand, String discModel,
                             String markingType, String treadType,
                             Boolean runFlat, Boolean lightTruck,
                             String speedIndex, Integer loadIndex,
                             BigDecimal price, BigDecimal costPrice, String condition) {
    }
}
