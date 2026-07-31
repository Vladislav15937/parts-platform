package ru.partsflow.migration.bazon;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.partsflow.catalog.PartNameService;
import ru.partsflow.inventory.PartService;
import ru.partsflow.platform.tenant.TenantContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Перенос склада из выгрузки предыдущей системы.
 *
 * <p>Импортёр был написан давно и не вызывался ниоткуда: между «строка
 * разобралась» и «склад перенесён» лежит всё, ради чего клиент к нам
 * и переходит, и до сих пор этот путь не проходил никто.
 *
 * <p><b>Два файла, а не один.</b> Выгрузка машин и выгрузка товаров — разные
 * таблицы в кабинете, и склеить их за клиента нельзя: деталь ссылается
 * на машину номером, а поставка приезжает только с машиной.
 *
 * <p><b>Файлы кладутся на диск, а не читаются потоком.</b> Импорт проходит
 * по каждому файлу несколько раз — сначала поставки, потом машины, потом
 * наименования, потом товары, — а поток читается один раз. Выгрузка
 * на пятьдесят тысяч позиций в память не помещается.
 *
 * <p>Только владелец: операция заливает склад целиком, и отменить её можно
 * лишь восстановлением из бэкапа.
 */
@RestController
@RequestMapping("/api/import/bazon")
public class BazonImportController {

    /**
     * Сколько наименований пересчитывать после переноса. Своих написаний
     * у клиента — тысячи, но не десятки тысяч: справочник склада конечен.
     */
    private static final int REMATCH_LIMIT = 10_000;

    private final DataSource dataSource;
    private final PartNameService partNames;
    private final PartService parts;

    public BazonImportController(DataSource dataSource, PartNameService partNames,
                                 PartService parts) {
        this.dataSource = dataSource;
        this.partNames = partNames;
        this.parts = parts;
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public Result load(@RequestParam("donors") MultipartFile donors,
                       @RequestParam("catalog") MultipartFile catalog) throws Exception {

        requireFile(donors, "выгрузка машин");
        requireFile(catalog, "выгрузка товаров");

        // Схема берётся из сессии, как и везде: имя арендатора в запросе
        // означало бы заливку чужого склада по подставленному номеру.
        String schema = TenantContext.getOrNull();
        if (schema == null) {
            throw new IllegalStateException("Арендатор не определён");
        }

        Path donorsFile = null;
        Path catalogFile = null;
        try {
            donorsFile = spill(donors, "donors");
            catalogFile = spill(catalog, "catalog");

            ImportReport report = new BazonImporter(dataSource, schema)
                    .importAll(donorsFile, catalogFile);

            Map<String, Integer> loaded = new java.util.LinkedHashMap<>(report.loaded());
            loaded.put("наименований сопоставлено", finishNames());
            // Сопоставить наименование и оставить склад в «Не разобрано» —
            // значит починить будущее и не починить прошлое. Ровно ради
            // этих карточек клиент и переезжает.
            loaded.put("карточек получили категорию", parts.applyMatchedNames());

            return new Result(loaded, report.problems(), report.problemCount());
        } finally {
            delete(donorsFile);
            delete(catalogFile);
        }
    }

    /**
     * Доводит справочник наименований после переноса.
     *
     * <p>Импортёр заводит написания как есть, а сопоставляет их с эталонами
     * тот же {@code PartNameService}, что и приёмка: второй путь означал бы
     * второй справочник, расходящийся с первым. Живой прогон показал, чем
     * это кончалось — «Фара», «Бампер», «Стартер» дословно совпадают
     * с эталонами и всё равно оставались нераспознанными, то есть весь склад
     * переехавшего клиента ложился в «Не разобрано».
     *
     * <p>Счётчик использований считается здесь же. Без него экран разбора
     * показывает «позиций пока нет» у написания, под которым висит две сотни
     * карточек, — и теряет единственный ориентир, ради которого он и нужен:
     * что чинить раньше.
     *
     * @return сколько наименований удалось сопоставить
     */
    private int finishNames() {
        // Через сервис, а не своим JdbcTemplate: search_path выставляет
        // провайдер соединений Hibernate внутри транзакции, и запрос отсюда
        // уходил в public — «relation part_name does not exist».
        partNames.recountUsage();
        return partNames.rematchUnmatched(REMATCH_LIMIT);
    }

    /**
     * Кладёт загруженный файл на диск.
     *
     * <p>Права на файл — только владельцу процесса: во временном каталоге
     * лежит весь склад клиента, включая закупочные цены.
     */
    private static Path spill(MultipartFile file, String name) throws IOException {
        Path path = Files.createTempFile("bazon-" + name + "-", ".csv");
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        path.toFile().setReadable(false, false);
        path.toFile().setReadable(true, true);
        return path;
    }

    private static void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Не роняем импорт из-за неудалённого временного файла: склад
            // уже перенесён, а каталог чистится системой.
            path.toFile().deleteOnExit();
        }
    }

    private static void requireFile(MultipartFile file, String what) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Не приложена " + what);
        }
    }

    /**
     * @param loaded что и сколько перенесено — первый вопрос клиента
     *               звучит как «а всё ли?»
     * @param problems строки, которые не поехали, с номерами: по ним открывают
     *                 исходный файл и смотрят
     */
    public record Result(Map<String, Integer> loaded,
                         List<ImportReport.Problem> problems,
                         int problemCount) {
    }
}
