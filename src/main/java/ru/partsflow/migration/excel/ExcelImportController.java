package ru.partsflow.migration.excel;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Перенос склада из таблицы клиента.
 *
 * <p><b>Файл идёт через приложение, в отличие от фотографий.</b> Там запрещено
 * намеренно: снимков сотни, делают их на плохой связи, и повторять пришлось бы
 * весь запрос. Здесь наоборот — файл один, загружают его с компьютера при
 * переходе на систему, и подписанная ссылка в хранилище добавила бы два шага
 * ради разовой операции.
 *
 * <p><b>Два шага, а не один.</b> Сначала предпросмотр: он показывает, как
 * распознались колонки, и человек это подтверждает. Ошибка сопоставления
 * тихая — перепутанные цена и количество дают склад, где всё по три рубля,
 * и замечают это на первой продаже.
 *
 * <p>Только владелец: импорт заливает склад целиком, и отменить его можно
 * только восстановлением из бэкапа.
 */
@RestController
@RequestMapping("/api/import/excel")
public class ExcelImportController {

    private final ExcelWarehouseImporter importer;

    public ExcelImportController(ExcelWarehouseImporter importer) {
        this.importer = importer;
    }

    @PostMapping("/preview")
    @PreAuthorize("hasRole('OWNER')")
    public ExcelWarehouseImporter.Preview preview(@RequestParam("file") MultipartFile file)
            throws Exception {

        requireFile(file);
        try (var in = file.getInputStream()) {
            return importer.preview(in);
        }
    }

    /**
     * @param requestId ключ клиента, генерируется при выборе файла. Повтор
     *                  с тем же ключом отдаёт прежний итог, а не заводит
     *                  второй склад
     * @param columns подтверждённое сопоставление: имя поля → номер колонки.
     *                Присылается целиком, а не поправками к догадке: иначе
     *                непонятно, что человек подтвердил, а что не заметил
     */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ExcelWarehouseImporter.Report load(@RequestParam("file") MultipartFile file,
                                              @RequestParam("warehouseId") long warehouseId,
                                              @RequestParam("requestId") String requestId,
                                              @RequestParam Map<String, String> columns)
            throws Exception {

        requireFile(file);
        Map<ColumnMapping.Field, Integer> mapped = new EnumMap<>(ColumnMapping.Field.class);
        for (ColumnMapping.Field field : ColumnMapping.Field.values()) {
            String index = columns.get(field.name());
            if (index != null && !index.isBlank()) {
                mapped.put(field, Integer.parseInt(index));
            }
        }

        try (var in = file.getInputStream()) {
            return importer.importInto(in, ColumnMapping.of(java.util.List.of(), mapped),
                    warehouseId, requestId);
        }
    }

    private static void requireFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл не приложен");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase().endsWith(".xlsx")) {
            // xls старого формата не читаем: это другой контейнер, и тянуть
            // ради него вторую половину POI незачем — Excel сохраняет в xlsx
            // одним действием.
            throw new IllegalArgumentException(
                    "Нужен файл .xlsx. Старый .xls пересохраните в Excel как .xlsx");
        }
    }
}
