package ru.partsflow.migration.bazon;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
import ru.partsflow.platform.security.CurrentUser;
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

    /**
     * Предел пачки снимков. Двести файлов — это минуты, то есть запрос,
     * который доживает до ответа; тысяча уже упирается в таймаут терминатора,
     * и владелец видит ошибку там, где перенос на самом деле идёт.
     */
    private static final int MAX_PHOTO_BATCH = 500;

    private final DataSource dataSource;
    private final PartNameService partNames;
    private final PartService parts;
    private final PhotoMigration photoMigration;
    private final ru.partsflow.intake.DonorVehicleResolver vehicles;
    private final BazonWheelImporter wheelImporter;

    public BazonImportController(DataSource dataSource, PartNameService partNames,
                                 PartService parts, PhotoMigration photoMigration,
                                 ru.partsflow.intake.DonorVehicleResolver vehicles,
                                 ru.partsflow.inventory.StockLedger stock,
                                 BazonWheelImporter wheelImporter) {
        this.dataSource = dataSource;
        this.partNames = partNames;
        this.parts = parts;
        this.photoMigration = photoMigration;
        this.vehicles = vehicles;
        this.wheelImporter = wheelImporter;
    }

    /**
     * Переносит очередную пачку фотографий.
     *
     * <p><b>Пачками, а не одним запросом.</b> Сто тысяч файлов с чужого CDN —
     * это часы; запрос столько не живёт. Владелец нажимает, видит число
     * и нажимает снова, пока в очереди не станет пусто. Прервать можно
     * в любой момент: перенесённое остаётся перенесённым.
     */
    @PostMapping("/photos")
    @PreAuthorize("hasRole('OWNER')")
    public PhotoMigration.Progress photos(
            @RequestParam(defaultValue = "200") int limit) {
        return photoMigration.migrateBatch(Math.min(Math.max(limit, 1), MAX_PHOTO_BATCH));
    }

    /** Сколько осталось и сколько не вышло — для экрана переноса. */
    @GetMapping("/photos")
    @PreAuthorize("hasRole('OWNER')")
    public PhotoMigration.Progress photoStatus() {
        return photoMigration.status();
    }

    /**
     * Возвращает неудачные снимки в очередь.
     *
     * <p>Отдельным действием: CDN, лежавший десять минут, чинится повтором,
     * а удалённый файл — нет, и вечный повтор скрывал бы второе за первым.
     */
    @PostMapping("/photos/retry")
    @PreAuthorize("hasRole('OWNER')")
    public PhotoMigration.Progress retryPhotos() {
        photoMigration.retryFailed();
        return photoMigration.status();
    }

    /**
     * Перенос шин и дисков — отдельным файлом и отдельным действием.
     *
     * <p>Колёса лежат у Bazon на своей вкладке и в выгрузку товаров
     * не попадают: проверено на выгрузке живого клиента, где в сорока
     * восьми колонках нет ни ширины, ни профиля, ни сезона. Пока этого
     * прохода не было, переехавший клиент терял весь колёсный склад —
     * 65 позиций, 221 карточку с учётом комплектов.
     *
     * <p>Склад спрашивается, а не подставляется: какой из них правильный,
     * знает только владелец, а тихо уехавший не туда товар ищут глазами.
     */
    @PostMapping("/wheels")
    @PreAuthorize("hasRole('OWNER')")
    public WheelResult loadWheels(@RequestParam("wheels") MultipartFile wheels,
                                  @RequestParam("warehouseId") Long warehouseId)
            throws IOException {

        requireFile(wheels, "выгрузка шин и дисков");
        requireBazonExport(wheels, "выгрузка шин и дисков", BazonWheelImporter.ANCHOR);

        try (InputStream in = wheels.getInputStream()) {
            BazonWheelImporter.Report report =
                    wheelImporter.load(in, warehouseId, CurrentUser.memberId());
            return new WheelResult(report.created(), report.sets(), report.skipped(),
                    report.photos(), report.problems());
        }
    }

    /**
     * @param created карточек заведено: комплект из четырёх — это четыре
     * @param sets    строк файла, ставших комплектами
     * @param skipped уже перенесённых раньше: повтор безопасен
     */
    public record WheelResult(int created, int sets, int skipped, int photos,
                              List<String> problems) {
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public Result load(@RequestParam("donors") MultipartFile donors,
                       @RequestParam("catalog") MultipartFile catalog) throws Exception {

        requireFile(donors, "выгрузка машин");
        requireFile(catalog, "выгрузка товаров");
        requireBazonExport(donors, "выгрузка машин", "Номер донора");
        requireBazonExport(catalog, "выгрузка товаров", "Номер товара");

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
            // Поколение подбирается по году — тем же методом, что и у машины,
            // заведённой руками. Импортёр пишет доноров своим SQL, мимо
            // registerDonor, и без этого шага поколения у переехавшего клиента
            // не появлялось никогда: ни кузова в заголовке, ни подбора детали
            // по машине с поколением.
            loaded.put("машин получили поколение", vehicles.backfillGenerations());

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


    /**
     * Узнаёт свою выгрузку по опорной колонке.
     *
     * <p><b>Чужой формат обязан быть отвергнут, а не «частично загружен».</b>
     * Выгрузка Bazon — это CSV в windows-1251, и таблица .xlsx, поданная сюда
     * по ошибке, читается как текст: заголовка нет, строки разбираются
     * как попало, и часть из них доезжает до склада призрачными карточками
     * «Без наименования». Поймано прогоном инструкции по подключению —
     * пятнадцать таких карточек на двухстах настоящих.
     *
     * <p>Проверяется одна колонка, а не весь заголовок: набор колонок
     * у клиента настраивается в кабинете площадки, и требовать их все значило
     * бы отбивать законные выгрузки. Опорная есть всегда — по ней товар
     * и машина узнаются при повторе.
     */
    private static void requireBazonExport(MultipartFile file, String what, String column) {
        boolean found;
        try (var in = file.getInputStream()) {
            found = new BazonCsvReader(in).has(column);
        } catch (java.io.IOException | IllegalArgumentException e) {
            // Пустой файл и нечитаемый заголовок приходят отсюда же — и это
            // тот же ответ: файл не похож на выгрузку.
            found = false;
        }
        if (!found) {
            throw new IllegalArgumentException(
                    "Это не " + what + ": в заголовке нет колонки «" + column
                            + "». Выгрузка прежней системы — это CSV в windows-1251,"
                            + " а не таблица Excel");
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
