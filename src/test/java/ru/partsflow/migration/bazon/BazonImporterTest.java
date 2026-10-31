package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Перенос склада из выгрузки предыдущей системы.
 *
 * <p>Разбор выгрузки был закрыт тестами давно, а сам импорт — нет: его никто
 * не вызывал. Между «строка разобралась» и «склад перенесён» лежит всё, ради
 * чего клиент к нам и переходит.
 *
 * <p>Главная проверка — повторный запуск. Она же поймала на импорте из Excel
 * самую разрушительную ошибку в системе: ответ упал на сериализации, владелец
 * нажал ещё раз и получил вторую копию склада целиком.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class BazonImporterTest extends PostgresTestBase {

    /**
     * Своя схема на каждый тест.
     *
     * <p>Общую не почистить: журнал движений неизменяем по замыслу, его
     * запрещает удалять триггер. А импорт проверяется абсолютными числами —
     * «товаров ровно два», — и остатки соседнего теста их ломают.
     */
    private static final String IMPORT = "t_000077";
    private static final String REPEAT = "t_000078";
    private static final String HEADER = "t_000079";
    private static final String NAMES = "t_000080";
    private static final String BAD_ROW = "t_000081";
    private static final String UNKNOWN = "t_000082";
    private static final String BACKFILL = "t_000083";
    private static final String PARTS_BACKFILL = "t_000084";
    private static final String JUNK_OEM = "t_000097";
    private static final String BROKEN_ROW = "t_000100";
    private static final String NO_PUBLISH = "t_000105";
    private static final String CONDITION_COL = "t_000940";
    private static final String YEAR_TEXT = "t_000917";
    private static final String YEAR_REFILL = "t_000918";
    private static final String DONOR_NUMBER = "t_000365";
    private static final String DONOR_STATUS = "t_000391";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path files;

    @BeforeAll
    static void migrate() {
        provisionTenants(IMPORT, REPEAT, HEADER, NAMES, BAD_ROW, UNKNOWN, BACKFILL,
                PARTS_BACKFILL, JUNK_OEM, BROKEN_ROW, NO_PUBLISH, CONDITION_COL,
                YEAR_TEXT, YEAR_REFILL, DONOR_NUMBER, DONOR_STATUS);
    }

    @Test
    @DisplayName("Выгрузка переносится: доноры, наименования, склады, остаток")
    void importsWarehouse() throws Exception {
        ImportReport report = importFixture(IMPORT);

        assertThat(count(IMPORT, "donor")).isEqualTo(1);
        assertThat(count(IMPORT, "supply")).isEqualTo(1);
        assertThat(count(IMPORT, "part")).isEqualTo(3);

        // Остаток появляется движением, а не записью в part.qty_on_hand:
        // иначе кэш разъедется с журналом на первой же продаже.
        //
        // Свободное плюс резерв: отложенная деталь физически лежит на полке,
        // и остаток склада её включает. Взять только свободное значило бы
        // списать обещанное другому клиенту.
        assertThat(qtyOf(IMPORT, "100")).isEqualByComparingTo("3");
        assertThat(qtyOf(IMPORT, "200")).isEqualByComparingTo("1");

        // Резерв из выгрузки — это обещание другому клиенту, и терять его
        // нельзя: продавец пообещает деталь второму.
        assertThat(reservedOf(IMPORT, "100")).isEqualByComparingTo("1");

        assertThat(report.problems()).isEmpty();
    }

    /**
     * Позиция, которой в выгрузке нет ни на одном складе, не должна числиться
     * «в наличии»: за ней ничего не лежит и движения у неё нет вовсе.
     *
     * <p>Импортёр ставил статус при вставке, и у позиций с остатком его через
     * мгновение переписывал триггер прихода — а у этих переписывать было
     * нечему. У переехавшего клиента таких нашлось десять: карточка обещает
     * наличие, а склад пуст.
     */
    @Test
    @DisplayName("Позиция без остатка в выгрузке не числится «в наличии»")
    void itemWithoutStockIsNotInStock() throws Exception {
        importFixture(IMPORT);

        assertThat(qtyOf(IMPORT, "400")).isEqualByComparingTo("0");
        assertThat(statusOf(IMPORT, "400")).isEqualTo("DRAFT");
        // А у позиции с остатком статус ставит триггер прихода.
        assertThat(statusOf(IMPORT, "100")).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Нулевая цена установки — это незаполненное поле, а не бесплатно")
    void zeroInstallationPriceIsNotAPromise() throws Exception {
        importFixture(IMPORT);

        // В карточке товара «Цена установки 0 ₽» — обещание покупателю,
        // а в выгрузке прежней системы ноль стоит там, где поле не заполняли:
        // у живого клиента таких строк 367 из 381.
        assertThat(installationPriceOf(IMPORT, "200")).isNull();
        assertThat(installationPriceOf(IMPORT, "400")).isNull();
        assertThat(installationPriceOf(IMPORT, "100")).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("Марка и модель берутся из общего каталога, а не выдумываются")
    void vehicleComesFromCatalog() throws Exception {
        importFixture(IMPORT);

        // Раньше здесь стоял ноль — ссылка на марку, которой в каталоге нет
        // вовсе. Внешнего ключа не было, база пропускала, и у переехавшего
        // клиента не работали ни фильтр по марке, ни применимость.
        var vehicle = jdbc.queryForMap("""
                SELECT b.name AS brand, m.name AS model
                  FROM %s.donor d
                  JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                 WHERE d.legacy_code = 'Д-1'""".formatted(IMPORT));

        assertThat(vehicle.get("brand")).isEqualTo("Toyota");
        assertThat(vehicle.get("model")).isEqualTo("Camry");
    }

    @Test
    @DisplayName("Номер донора Bazon становится номером машины; занятый и нечисловой — следующий за наибольшим")
    void donorNumberIsCarriedOver() throws Exception {
        // Машина, заведённая до переноса, уже держит номер 5.
        jdbc.update("INSERT INTO %s.donor (status, number) VALUES ('DISMANTLED', 5)".formatted(DONOR_NUMBER));
        Path donors = write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
                "Д-9";"Toyota";"Camry";"2006";"";"";"Разбор";"";"";"";"";"";"";""
                "007";"Toyota";"Camry";"2006";"";"";"Разбор";"";"";"";"";"";"";""
                "5";"Toyota";"Camry";"2006";"";"";"Разбор";"";"";"";"";"";"";""
                "40";"Toyota";"Camry";"2006";"";"";"Разбор";"";"";"";"";"";"";""
                """);

        ImportReport report = new BazonImporter(dataSource, DONOR_NUMBER)
                .importAll(donors, write("catalog.csv", CATALOG_HEADER));

        assertThat(donorNumberOf(DONOR_NUMBER, "007")).isEqualTo(7L);
        assertThat(donorNumberOf(DONOR_NUMBER, "40")).isEqualTo(40L);
        // «Д-9» идёт первой строкой: без учёта наибольшего в файле она взяла бы
        // номер, который ниже принесёт другая машина.
        assertThat(donorNumberOf(DONOR_NUMBER, "Д-9")).isGreaterThan(40L);
        Long taken = donorNumberOf(DONOR_NUMBER, "5");
        assertThat(taken).isGreaterThan(40L).isNotEqualTo(donorNumberOf(DONOR_NUMBER, "Д-9"));
        assertThat(report.problems()).extracting(ImportReport.Problem::message)
                .contains("номер донора 5 уже у другой машины — перенесённая получила номер " + taken);
    }

    @Test
    @DisplayName("Статус машины Bazon ложится в список статусов клиента и выбран у машины")
    void donorStatusGoesToTheClientsList() throws Exception {
        // Ответ владельца 31.10.2026 (задача 0364): «статусы переносить» —
        // в список статусов машины, а не в «Где стоит».
        Path donors = write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
                "11";"Toyota";"Camry";"2006";"";"";"В Японии";"";"";"";"";"";"";""
                "12";"Toyota";"Camry";"2006";"";"";" в японии ";"";"";"";"";"";"";""
                "13";"Toyota";"Camry";"2006";"";"";"Разобрана";"";"";"";"";"";"";""
                "14";"Toyota";"Camry";"2006";"";"";"";"";"";"";"";"";"";""
                """);

        ImportReport report = new BazonImporter(dataSource, DONOR_STATUS)
                .importAll(donors, write("catalog.csv", CATALOG_HEADER));

        assertThat(report.problems()).as("перенос машин отказал").isEmpty();
        assertThat(donorStatusOf(DONOR_STATUS, "11")).isEqualTo("В Японии");
        assertThat(donorStatusOf(DONOR_STATUS, "12"))
                .as("то же слово другим регистром — тот же пункт списка, а не второй")
                .isEqualTo("В Японии");
        assertThat(donorStatusOf(DONOR_STATUS, "13"))
                .as("значение, которое в списке уже есть, не заводится вторым")
                .isEqualTo("Разобрана");
        assertThat(donorStatusOf(DONOR_STATUS, "14")).isNull();
        assertThat(jdbc.queryForList(
                "SELECT name FROM %s.donor_status ORDER BY sort_order, id".formatted(DONOR_STATUS),
                String.class))
                .containsExactly("Куплена", "В разборе", "Разобрана", "Списана", "В Японии");
        assertThat(jdbc.queryForList(
                "SELECT location FROM %s.donor WHERE location IS NOT NULL".formatted(DONOR_STATUS),
                String.class))
                .as("статус Bazon больше не пишется в «Где стоит»")
                .isEmpty();
    }

    @Test
    @DisplayName("Неизвестная марка оставляет поле пустым, а не выдумывает своё")
    void unknownBrandStaysEmpty() throws Exception {
        Path donors = write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
                "Д-9";"Марка-которой-нет";"Модель-которой-нет";"2006";"VIN9";"";"Разбор";\
                "";"";"";"";"";"";""
                """);

        new BazonImporter(dataSource, UNKNOWN).importAll(donors, catalogFixture());

        // Завести свою марку вместо ненайденной нельзя: через месяц
        // в справочнике будут «Тойота», «тойота» и «Toyota».
        assertThat(jdbc.queryForObject(
                "SELECT brand_id FROM %s.donor WHERE legacy_code = 'Д-9'".formatted(UNKNOWN),
                Long.class)).isNull();
    }

    @Test
    @DisplayName("Повторный запуск не удваивает склад")
    void secondRunIsIdempotent() throws Exception {
        importFixture(REPEAT);
        ImportReport second = importFixture(REPEAT);

        assertThat(count(REPEAT, "part"))
                .as("склад загрузился второй раз — отменяется это только "
                        + "восстановлением из бэкапа")
                .isEqualTo(3);
        assertThat(count(REPEAT, "donor")).isEqualTo(1);
        assertThat(count(REPEAT, "supply")).isEqualTo(1);
        assertThat(qtyOf(REPEAT, "100"))
                .as("остаток удвоился: движения записались повторно")
                .isEqualByComparingTo("3");
        assertThat(second.loaded("товаров пропущено (уже есть)"))
                .as("повтор не отчитался о пропуске — значит он что-то создал заново")
                .isEqualTo(3);
        assertThat(second.loaded("машин пропущено (уже есть)")).isEqualTo(1);

        // Без этой строки проверка сходится и по неверной причине: машину
        // не пускает уникальный индекс, импорт ловит отказ и пишет проблему.
        // Дубля нет, но повтор перестаёт быть штатным — а он штатный.
        assertThat(second.problems())
                .as("повтор прошёл с ошибками: он обязан быть обычным действием, "
                        + "а не аварией, которую спас индекс")
                .isEmpty();
    }

    /**
     * Про отсутствие колонки «Выгружать» предупреждаем один раз — про то,
     * что действительно завелось.
     *
     * <p>Выгрузка без этой колонки грузит весь склад без разрешения
     * публиковать, и предупреждение о пустом прайсе тут по делу. Но на
     * повторе все позиции пропускаются как уже существующие, публикацию
     * повтор не трогает вовсе — а предупреждение всё равно обещало пустой
     * прайс. Владелец, повторивший выгрузку ради дозаполнения полей, читал,
     * что остался без объявлений, при полностью целой публикации: тревога
     * на ровном месте ровно там, где он только что ждал минуту.
     *
     * <p>Поймано повтором переноса на живом складе — 35 841 пропущено,
     * ноль заведено, предупреждение на месте.
     */
    @Test
    @DisplayName("Про «Выгружать» предупреждает первый проход, а не повтор")
    void publishWarningOnlyWhenSomethingWasCreated() throws Exception {
        BazonImporter importer = new BazonImporter(dataSource, NO_PUBLISH);
        Path catalog = catalogWithoutPublishFlag();

        ImportReport first = importer.importAll(donorsFixture(), catalog);
        assertThat(first.problems())
                .as("склад завёлся без разрешения публиковать, а прайс уедет пустым — "
                        + "об этом обязаны сказать")
                .anyMatch(p -> p.message().contains("Выгружать"));

        ImportReport second = new BazonImporter(dataSource, NO_PUBLISH)
                .importAll(donorsFixture(), catalog);

        assertThat(second.loaded("товаров")).as("повтор что-то завёл заново").isZero();
        assertThat(second.problems())
                .as("повтор ничего не завёл, а пустым прайсом всё равно пугает")
                .noneMatch(p -> p.message().contains("Выгружать"));
    }

    /** Та же выгрузка, но без колонки «Выгружать» — так её отдаёт Bazon по умолчанию. */
    private Path catalogWithoutPublishFlag() throws Exception {
        return write("catalog-no-publish.csv", """
                "Номер товара";"Запчасть";"Номер донора";"Цена";"Ткацкая (свободно)";\
                "Ткацкая (резерв)";"Ткацкая (ожидается)"
                "B-100";"Фара левая";"Д-1";"9500";"2";"0";"0"
                "B-200";"Бампер передний";"";"12000";"1";"0";"0"
                """);
    }

    @Test
    @DisplayName("Повтор дозаполняет пустое, но не затирает заполненное")
    void repeatFillsWhatIsMissing() throws Exception {
        // Так выглядит клиент, загруженный до появления колонки: в его
        // выгрузке кузов с двигателем были, а перенос их ещё не читал.
        Path oldExport = write("donors-old.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
                "Д-7";"Toyota";"Camry";"2006";"VIN7";"";"Разбор";\
                "серебристый";"180000";"Левый";"Передний";"Автомат";"U151E";""
                """);
        new BazonImporter(dataSource, BACKFILL).importAll(oldExport, catalogFixture());

        assertThat(bodyOf(BACKFILL, "Д-7"))
                .as("кузов взялся неоткуда: выгрузка его не содержала")
                .isNull();

        // Человек поправил цвет руками — выгрузка старше этой правки.
        jdbc.update("UPDATE %s.donor SET color = 'синий металлик' WHERE legacy_code = 'Д-7'"
                .formatted(BACKFILL));

        Path fullExport = write("donors-new.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация";\
                "Кузов";"Двигатель"
                "Д-7";"Toyota";"Camry";"2006";"VIN7";"";"Разбор";\
                "серебристый";"180000";"Левый";"Передний";"Автомат";"U151E";"";"ACV40";"2AZFE"
                """);
        ImportReport second = new BazonImporter(dataSource, BACKFILL)
                .importAll(fullExport, catalogFixture());

        assertThat(bodyOf(BACKFILL, "Д-7"))
                .as("пустой кузов так и не заполнился — повтор снова пропустил машину, "
                        + "и колонка, добавленная в перенос позже, не появится никогда")
                .isEqualTo("ACV40");
        assertThat(jdbc.queryForObject(
                "SELECT engine_code FROM %s.donor WHERE legacy_code = 'Д-7'".formatted(BACKFILL),
                String.class)).isEqualTo("2AZFE");
        assertThat(jdbc.queryForObject(
                "SELECT color FROM %s.donor WHERE legacy_code = 'Д-7'".formatted(BACKFILL),
                String.class))
                .as("выгрузка затёрла правку человека: она бывает старше его работы")
                .isEqualTo("синий металлик");
        assertThat(second.loaded("машин дополнено")).isEqualTo(1);
        assertThat(count(BACKFILL, "donor"))
                .as("дозаполнение завело вторую машину вместо правки первой")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Повтор дозаполняет позицию и ставит недостающие снимки в очередь")
    void repeatFillsPartAndQueuesPhotos() throws Exception {
        // Выгрузка без «Превью» и без секции: так грузился клиент, у которого
        // колонку снимков не читали — в очереди у него восемь ссылок вместо
        // ста девяноста тысяч.
        String headerWithoutPhotos = CATALOG_HEADER;
        Path first = write("catalog-old.csv", headerWithoutPhotos + """
                "500";"Стартер";"";"";"";"";"";"";"";"";"";"";\
                "";"";"";"7000";"";"1";"0";"0";"0";"0";"0";"да";""
                """);
        new BazonImporter(dataSource, PARTS_BACKFILL).importAll(donorsFixture(), first);

        assertThat(photoQueueOf(PARTS_BACKFILL)).isZero();

        // Цену владелец подвинул руками — выгрузка старше этой правки.
        jdbc.update("UPDATE %s.part SET price = 6000 WHERE legacy_code = '500'"
                .formatted(PARTS_BACKFILL));

        Path second = write("catalog-new.csv", """
                "Номер товара";"Превью";"Запчасть";"Номер донора";"Марка";"Модель";\
                "Год выпуска";"Кузов";"Двигатель";"Комментарий";"Заметка";"Левый / Правый";\
                "Передний / Задний";"Оценка состояния";"Маркировка";"Производитель";"Цена";\
                "Секция";"Номер производителя";"Ткацкая (свободно)";"Ткацкая (резерв)";\
                "Ткацкая (ожидается)";"Ангар (свободно)";"Ангар (резерв)";\
                "Ангар (ожидается)";"Выгружать";"Установка"
                "500";"http://cdn/1.jpg,http://cdn/2.jpg";"Стартер";"";"";"";"";"";"";\
                "Контракт";"";"";"";"";"";"Denso";"7000";"01-02-03";"28100-0D030";\
                "1";"0";"0";"0";"0";"0";"да";""
                """);
        ImportReport report = new BazonImporter(dataSource, PARTS_BACKFILL)
                .importAll(donorsFixture(), second);

        assertThat(report.loaded("товаров дополнено")).isEqualTo(1);
        assertThat(textOf(PARTS_BACKFILL, "500", "section"))
                .as("пустая секция так и не заполнилась")
                .isEqualTo("01-02-03");
        assertThat(textOf(PARTS_BACKFILL, "500", "manufacturer")).isEqualTo("Denso");
        assertThat(textOf(PARTS_BACKFILL, "500", "description")).isEqualTo("Контракт");
        assertThat(jdbc.queryForObject(
                "SELECT price FROM %s.part WHERE legacy_code = '500'".formatted(PARTS_BACKFILL),
                BigDecimal.class))
                .as("выгрузка вернула прежнюю цену поверх правки владельца")
                .isEqualByComparingTo("6000");
        assertThat(photoQueueOf(PARTS_BACKFILL))
                .as("снимки не поставились в очередь: у клиента, загруженного до чтения "
                        + "«Превью», они не появятся никогда")
                .isEqualTo(2);
        // Самое опасное в дозаполнении: движения повторить нельзя.
        assertThat(qtyOf(PARTS_BACKFILL, "500"))
                .as("остаток удвоился — дозаполнение повторило движения")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Повтор без единого нового поля машину не трогает")
    void repeatWithoutChangesFillsNothing() throws Exception {
        importFixture(BACKFILL);
        ImportReport second = importFixture(BACKFILL);

        // Иначе число «дополнено» перестаёт что-либо значить: оно росло бы
        // на каждый повтор, ничего не изменив.
        assertThat(second.loaded("машин дополнено")).isZero();
        assertThat(second.loaded("машин пропущено (уже есть)")).isEqualTo(1);
    }

    @Test
    @DisplayName("Перенесённый резерв оформлен сделкой, и сверка сходится")
    void legacyReservationBecomesADeal() throws Exception {
        importFixture(IMPORT);

        // Резерв в part_stock остался — деталь обещана, и продать её нельзя.
        assertThat(reservedOf(IMPORT, "100")).isEqualByComparingTo("1");

        // Но теперь у обещания есть документ. Без него продавец видел
        // «отложено 1» и не мог выяснить, кому и до какого числа.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM %s.deal_item di
                  JOIN %s.deal d ON d.id = di.deal_id
                 WHERE d.status = 'RESERVED' AND di.status = 'RESERVED'"""
                .formatted(IMPORT, IMPORT), Integer.class))
                .isEqualTo(1);

        // И сверка резервов пуста. Пока резерв ставился мимо документа, она
        // у переехавшего клиента шумела с первого дня — то есть переставала
        // быть сигналом.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM %s.v_reservation_discrepancy".formatted(IMPORT),
                Integer.class))
                .as("сверка резервов непуста сразу после переезда")
                .isZero();
    }

    @Test
    @DisplayName("Склады берутся из заголовка, а не выдумываются")
    void warehousesComeFromHeader() throws Exception {
        importFixture(HEADER);

        // Имена складов у каждого клиента свои, и придумать их за него нельзя:
        // деталь ляжет на склад, которого у клиента нет.
        assertThat(jdbc.queryForList(
                "SELECT name FROM " + HEADER + ".warehouse ORDER BY name", String.class))
                .contains("Ткацкая", "Ангар");
    }

    @Test
    @DisplayName("Наименования заводятся в справочник, а не в заголовок карточки")
    void partNamesGoToDictionary() throws Exception {
        importFixture(NAMES);

        // Ради этого справочник и существует: «фара левая» из чужой системы
        // должна встать рядом с нашей приёмкой, а не остаться строкой.
        assertThat(jdbc.queryForList(
                "SELECT name FROM " + NAMES + ".part_name", String.class))
                .containsExactlyInAnyOrder("Фара левая", "Бампер передний", "Стартер");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + NAMES + ".part WHERE part_name_id IS NULL",
                Integer.class))
                .as("карточка без наименования не попадёт в разбор нераспознанных")
                .isZero();
    }

    @Test
    @DisplayName("Строка, которую не принимает база, не уносит остальной склад")
    void badRowDoesNotKillTheImport() throws Exception {
        // Отрицательная цена не проходит проверку part_price_ck. Мусор такого
        // рода в чужой выгрузке встречается, и одна такая строка не должна
        // оставить клиента без склада: без точки сохранения транзакция уходит
        // в aborted, и все последующие строки падают тоже.
        //
        // Цена буквами сюда не годится — разборщик приводит её к пустой,
        // и до базы такая строка доезжает целой.
        Path catalog = write("catalog.csv", CATALOG_HEADER + """
                "100";"Фара левая";"Д-1";"";"";"";"";"";"";"";"";"";"";"";"";"9500";"";"2";"1";"0";"0";"0";"0";"да";""
                "300";"Дверь";"";"";"";"";"";"";"";"";"";"";"";"";"";"-500";"";"1";"0";"0";"0";"0";"0";"да";""
                """);

        ImportReport report = new BazonImporter(dataSource, BAD_ROW)
                .importAll(donorsFixture(), catalog);

        assertThat(count(BAD_ROW, "part"))
                .as("плохая строка унесла с собой хорошие")
                .isEqualTo(1);
        assertThat(report.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("Битая строка называется один раз, а не по разу на проход")
    void brokenRowIsReportedOnce() throws Exception {
        Path catalog = write("catalog-broken.csv", CATALOG_HEADER + """
                "500";"Фара левая";"";"";"";"";"";"";"";"";"";"";"";"";"";"9500";"";\
                "1";"0";"0";"0";"0";"0";"да";"0"
                "501";"обрезанная
                """);

        ImportReport report = new BazonImporter(dataSource, BROKEN_ROW)
                .importAll(donorsFixture(), catalog);

        // Файл товаров читается двумя проходами — наименования и позиции, —
        // и каждый спотыкается об одну и ту же строку. Владелец видел бы её
        // дважды и решил, что файл испорчен сильнее, чем есть: по числу
        // проблем он и судит, доехало ли всё.
        long unparsed = report.problems().stream()
                .filter(p -> p.message().contains("не разобрана"))
                .count();
        assertThat(unparsed).as("битая строка названа %d раз", unparsed).isEqualTo(1);
    }

    @Test
    @DisplayName("Слово вместо кросс-номера не уносит карточку")
    void junkCrossNumberDoesNotLoseThePart() throws Exception {
        Path catalog = write("catalog-junk-oem.csv", CATALOG_HEADER.strip()
                + ";\"Кросс-номера\"\n" + """
                "900";"Фара левая";"";"";"";"";"";"";"";"";"";"";"";"";"";"9500";\
                "81150-33670";"1";"0";"0";"0";"0";"0";"да";"0";"АНАЛОГ, 8115033671"
                """);

        new BazonImporter(dataSource, JUNK_OEM).importAll(donorsFixture(), catalog);

        // В выгрузке живого клиента в кросс-номерах стоит слово «АНАЛОГ»:
        // после приведения от него не остаётся ничего, а колонка normalized
        // объявлена NOT NULL. Пока номер писали не глядя, отказ базы уносил
        // карточку целиком — вместе с остатком, снимками и деньгами.
        assertThat(count(JUNK_OEM, "part")).isEqualTo(1);
        assertThat(qtyOf(JUNK_OEM, "900")).isEqualByComparingTo("1");
        assertThat(jdbc.queryForList(
                "SELECT normalized FROM " + JUNK_OEM + ".part_oem ORDER BY normalized",
                String.class))
                .containsExactly("8115033670", "8115033671");
    }

    /**
     * «Контракт» прежней системы переезжает состоянием, а не теряется.
     *
     * <p>До задачи 0039 колонку «Состояние» не читал никто: в INSERT стоял
     * литерал {@code 'USED'}. У переехавшего клиента контрактных 9 417
     * позиций из 35 841 — они приходят контейнером без донора, — и все они
     * приезжали неотличимыми от б/у, хотя это другой товар, другая цена
     * и другая плашка для покупателя.
     *
     * <p>Колонку фикстура дописывает сама: в выгрузку по умолчанию она
     * не попадает (лежит в «Неактивных», как «Выгружать» и «Превью»),
     * и клиент включает её по инструкции перед боевой выгрузкой.
     */
    @Test
    @DisplayName("«Контракт» из выгрузки переезжает состоянием и пометкой «(б/у)», как у Bazon")
    void conditionColumnIsImported() throws Exception {
        Path catalog = write("catalog-condition.csv", CATALOG_HEADER.strip()
                + ";\"Состояние\"\n" + """
                "700";"Фара левая";"";"Toyota";"Camry";"";"";"";"";"";"";"";"";"";"";"9500";\
                "8414012530";"1";"0";"0";"0";"0";"0";"да";"0";"Контракт"
                "701";"Бампер передний";"";"";"";"";"";"";"";"";"";"";"";"";"";"7000";"";\
                "1";"0";"0";"0";"0";"0";"да";"0";"БУ"
                "702";"Стартер";"";"";"";"";"";"";"";"";"";"";"";"";"";"3500";"";\
                "1";"0";"0";"0";"0";"0";"да";"0";""
                "703";"Генератор";"";"";"";"";"";"";"";"";"";"";"";"";"";"4500";"27060-28110";\
                "1";"0";"0";"0";"0";"0";"да";"0";"Новый"
                """);

        new BazonImporter(dataSource, CONDITION_COL).importAll(donorsFixture(), catalog);

        assertThat(textOf(CONDITION_COL, "700", "condition"))
                .as("«Контракт» потерялся: такого товара у переехавшего клиента"
                        + " 9 417 позиций из 35 841")
                .isEqualTo("CONTRACT");
        assertThat(textOf(CONDITION_COL, "701", "condition")).isEqualTo("USED");
        assertThat(textOf(CONDITION_COL, "703", "condition")).isEqualTo("NEW");

        // Пустое значение — «в файле не сказано», а не выдуманное состояние:
        // остаётся прежнее умолчание.
        assertThat(textOf(CONDITION_COL, "702", "condition")).isEqualTo("USED");

        // Пометка состояния — как у Bazon (задача 0336, отменяет 0315): «(б/у)»
        // у контрактной и б/у, у новой нет. Номер производителя последним словом.
        assertThat(textOf(CONDITION_COL, "700", "title"))
                .isEqualTo("Фара левая Toyota Camry (б/у) 8414012530");
        assertThat(textOf(CONDITION_COL, "701", "title")).isEqualTo("Бампер передний (б/у)");
        assertThat(textOf(CONDITION_COL, "702", "title")).isEqualTo("Стартер (б/у)");
        assertThat(textOf(CONDITION_COL, "703", "title")).isEqualTo("Генератор 27060-28110");
    }

    /**
     * Повтор переноса заголовок существующей позиции не трогает — ни новый,
     * ни собранный прежней формулой: пересчёт хранимых названий — разовый
     * запуск с замером (0336, пункт 4), а не каждый повтор выгрузки молча.
     */
    @Test
    @DisplayName("Повтор переноса не меняет заголовок перенесённой позиции")
    void repeatKeepsTitles() throws Exception {
        Path catalog = write("catalog-condition-repeat.csv", CATALOG_HEADER.strip()
                + ";\"Состояние\"\n" + """
                "710";"Фара правая";"";"";"";"";"";"";"";"";"";"";"";"";"";"9500";\
                "8414012531";"1";"0";"0";"0";"0";"0";"да";"0";"БУ"
                "711";"Бампер задний";"";"";"";"";"";"";"";"";"";"";"";"";"";"7000";"";\
                "1";"0";"0";"0";"0";"0";"да";"0";"Контракт"
                """);
        new BazonImporter(dataSource, CONDITION_COL).importAll(donorsFixture(), catalog);
        jdbc.update("UPDATE " + CONDITION_COL + ".part SET title = 'Бампер задний (контракт)'"
                + " WHERE legacy_code = '711'");

        new BazonImporter(dataSource, CONDITION_COL).importAll(donorsFixture(), catalog);

        assertThat(textOf(CONDITION_COL, "710", "title")).isEqualTo("Фара правая (б/у) 8414012531");
        assertThat(textOf(CONDITION_COL, "711", "title"))
                .as("повтор переписал заголовок, собранный прежней формулой")
                .isEqualTo("Бампер задний (контракт)");
    }

    /**
     * «Год выпуска» доезжает до позиции текстом, как в файле (задача 0335).
     *
     * <p>Название в файле Дрома у Bazon несёт это поле целиком — «03-08»
     * у новых деталей, — а {@code parseYearRange} оставляет от него одно
     * «от». Строки взяты из образцов задачи: № 27 079 (новая, «03-08»),
     * № 88 487 («2010»), период месяц.год и позиция без года.
     */
    @Test
    @DisplayName("Текст года переносится как в файле, без разбора в число")
    void yearTextIsKeptAsInFile() throws Exception {
        new BazonImporter(dataSource, YEAR_TEXT).importAll(donorsFixture(), yearCatalog());

        assertThat(textOf(YEAR_TEXT, "A-910", "year_text"))
                .as("диапазон новой детали потерян: Bazon пишет в название «03-08»")
                .isEqualTo("03-08");
        assertThat(textOf(YEAR_TEXT, "A-911", "year_text")).isEqualTo("2010");
        assertThat(textOf(YEAR_TEXT, "A-912", "year_text")).isEqualTo("10.09-09.15");
        assertThat(textOf(YEAR_TEXT, "A-913", "year_text"))
                .as("пустой год — это «текста нет», а не пустая строка")
                .isNull();
    }

    /**
     * Позиции, перенесённые до колонки, получают текст года повтором того же
     * файла: ключ — {@code legacy_code}, номер, публичный код и остаток
     * не меняются, а уже стоящее значение не затирается.
     */
    @Test
    @DisplayName("Повтор переноса дописывает текст года уже перенесённым позициям")
    void repeatFillsYearTextOfTransferredParts() throws Exception {
        Path catalog = yearCatalog();
        new BazonImporter(dataSource, YEAR_REFILL).importAll(donorsFixture(), catalog);

        // Так лежат позиции клиента, перенесённого до tenant/092: текста нет.
        jdbc.update("UPDATE " + YEAR_REFILL + ".part SET year_text = NULL");
        // А это значение уже стоит — повтор выгрузки не имеет права его сменить.
        jdbc.update("UPDATE " + YEAR_REFILL + ".part SET year_text = '2009'"
                + " WHERE legacy_code = 'A-911'");
        List<String> before = identities(YEAR_REFILL);

        ImportReport repeat = new BazonImporter(dataSource, YEAR_REFILL)
                .importAll(donorsFixture(), catalog);

        assertThat(textOf(YEAR_REFILL, "A-910", "year_text"))
                .as("повтор не дописал текст года позиции, перенесённой до колонки")
                .isEqualTo("03-08");
        assertThat(textOf(YEAR_REFILL, "A-912", "year_text")).isEqualTo("10.09-09.15");
        assertThat(textOf(YEAR_REFILL, "A-911", "year_text"))
                .as("повтор затёр уже стоящий текст года")
                .isEqualTo("2009");
        assertThat(repeat.loaded("товаров дополнено")).isEqualTo(2);
        assertThat(identities(YEAR_REFILL))
                .as("повтор сменил номер, код или завёл позицию заново")
                .isEqualTo(before);
        assertThat(qtyOf(YEAR_REFILL, "A-910"))
                .as("остаток удвоился — дозаполнение повторило движения")
                .isEqualByComparingTo("1");
    }

    private Path yearCatalog() throws Exception {
        return write("catalog-year.csv", CATALOG_HEADER + """
                "A-910";"Бампер";"";"Lexus";"RX300";"03-08";"MCU31W";"1MZFE";"";"";"";\
                "Передний";"";"";"";"15000";"5211948910";"1";"0";"0";"0";"0";"0";"да";""
                "A-911";"Кнопка стеклоподъемника";"";"Toyota";"Harrier";"2010";"ACU30";\
                "2AZFE";"";"";"Левый";"Задний";"";"";"";"800";"7428048120";\
                "1";"0";"0";"0";"0";"0";"да";""
                "A-912";"Фара";"";"";"";"10.09-09.15";"";"";"";"";"";"";"";"";"";"5000";"";\
                "1";"0";"0";"0";"0";"0";"да";""
                "A-913";"Карман";"";"Mazda";"Millenia";"";"";"";"";"";"";"";"";"";"";"300";\
                "TA0164440C";"1";"0";"0";"0";"0";"0";"да";""
                """);
    }

    /** Номер базы, номер позиции и публичный код всех позиций — по порядку кода прежней системы. */
    private List<String> identities(String schema) {
        return jdbc.queryForList("SELECT legacy_code || ' ' || id || ' ' || number || ' '"
                + " || public_code FROM " + schema + ".part ORDER BY legacy_code", String.class);
    }

    private ImportReport importFixture(String schema) throws Exception {
        return new BazonImporter(dataSource, schema)
                .importAll(donorsFixture(), catalogFixture());
    }

    /**
     * Заголовок выгрузки товаров: колонки складов идут тройками
     * «свободно / резерв / ожидается» — так их отдаёт чужая система.
     */
    private static final String CATALOG_HEADER = """
            "Номер товара";"Запчасть";"Номер донора";"Марка";"Модель";"Год выпуска";\
            "Кузов";"Двигатель";"Комментарий";"Заметка";"Левый / Правый";\
            "Передний / Задний";"Оценка состояния";"Маркировка";"Производитель";"Цена";\
            "Номер производителя";"Ткацкая (свободно)";"Ткацкая (резерв)";\
            "Ткацкая (ожидается)";"Ангар (свободно)";"Ангар (резерв)";\
            "Ангар (ожидается)";"Выгружать";"Установка"
            """;

    private Path catalogFixture() throws Exception {
        return write("catalog.csv", CATALOG_HEADER + """
                "100";"Фара левая";"Д-1";"Toyota";"Camry";"2006";"";"";"";"";"Левый";"Передний";\
                "Хорошее";"";"";"9500";"81150-33670";"2";"1";"0";"0";"0";"0";"да";"1500"
                "200";"Бампер передний";"";"";"";"";"";"";"";"";"";"";"";"";"";"12000";"";\
                "0";"0";"0";"1";"0";"0";"да";"0"
                "400";"Стартер";"";"";"";"";"";"";"";"";"";"";"";"";"";"3500";"";\
                "0";"0";"0";"0";"0";"0";"да";""
                """);
    }

    private Path donorsFixture() throws Exception {
        return write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";\
                "Цвет";"Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
                "Д-1";"Toyota";"Camry";"2006";"JTDBR32E060012345";"Контейнер 12";"Разбор";\
                "серебристый";"180000";"Левый";"Передний";"Автомат";"U151E";""
                """);
    }

    /**
     * Файл пишется в windows-1251: выгрузка приходит именно в ней, и тест
     * на UTF-8 проверял бы не тот путь, которым файл попадает в систему.
     */
    private Path write(String name, String content) throws Exception {
        Path path = files.resolve(name);
        Files.write(path, content.getBytes(BazonCsvReader.CHARSET));
        return path;
    }

    private int count(String schema, String table) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + "." + table, Integer.class);
        return found == null ? 0 : found;
    }

    private int photoQueueOf(String schema) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".part_photo_import", Integer.class);
        return found == null ? 0 : found;
    }

    private String textOf(String schema, String legacyCode, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + schema + ".part WHERE legacy_code = ?",
                String.class, legacyCode);
    }

    private String donorStatusOf(String schema, String legacyCode) {
        return jdbc.queryForObject("""
                SELECT s.name FROM %1$s.donor d LEFT JOIN %1$s.donor_status s ON s.id = d.status_id
                 WHERE d.legacy_code = ?""".formatted(schema), String.class, legacyCode);
    }

    private Long donorNumberOf(String schema, String legacyCode) {
        return jdbc.queryForObject(
                "SELECT number FROM " + schema + ".donor WHERE legacy_code = ?",
                Long.class, legacyCode);
    }

    private String bodyOf(String schema, String legacyCode) {
        return jdbc.queryForObject(
                "SELECT body_code FROM " + schema + ".donor WHERE legacy_code = ?",
                String.class, legacyCode);
    }

    private String statusOf(String schema, String legacyCode) {
        return jdbc.queryForObject(
                "SELECT status FROM " + schema + ".part WHERE legacy_code = ?",
                String.class, legacyCode);
    }

    private BigDecimal installationPriceOf(String schema, String legacyCode) {
        return jdbc.queryForObject(
                "SELECT installation_price FROM " + schema + ".part WHERE legacy_code = ?",
                BigDecimal.class, legacyCode);
    }

    private BigDecimal qtyOf(String schema, String legacyCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(s.qty), 0) FROM %s.part_stock s
                  JOIN %s.part p ON p.id = s.part_id
                 WHERE p.legacy_code = ?""".formatted(schema, schema),
                BigDecimal.class, legacyCode);
    }

    private BigDecimal reservedOf(String schema, String legacyCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(s.qty_reserved), 0) FROM %s.part_stock s
                  JOIN %s.part p ON p.id = s.part_id
                 WHERE p.legacy_code = ?""".formatted(schema, schema),
                BigDecimal.class, legacyCode);
    }
}
