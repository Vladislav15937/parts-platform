package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path files;

    @BeforeAll
    static void migrate() {
        provisionTenants(IMPORT, REPEAT, HEADER, NAMES, BAD_ROW, UNKNOWN, BACKFILL,
                PARTS_BACKFILL, JUNK_OEM, BROKEN_ROW);
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
        assertThat(qtyOf(IMPORT, "A-100")).isEqualByComparingTo("3");
        assertThat(qtyOf(IMPORT, "A-200")).isEqualByComparingTo("1");

        // Резерв из выгрузки — это обещание другому клиенту, и терять его
        // нельзя: продавец пообещает деталь второму.
        assertThat(reservedOf(IMPORT, "A-100")).isEqualByComparingTo("1");

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

        assertThat(qtyOf(IMPORT, "A-400")).isEqualByComparingTo("0");
        assertThat(statusOf(IMPORT, "A-400")).isEqualTo("DRAFT");
        // А у позиции с остатком статус ставит триггер прихода.
        assertThat(statusOf(IMPORT, "A-100")).isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("Нулевая цена установки — это незаполненное поле, а не бесплатно")
    void zeroInstallationPriceIsNotAPromise() throws Exception {
        importFixture(IMPORT);

        // В карточке товара «Цена установки 0 ₽» — обещание покупателю,
        // а в выгрузке прежней системы ноль стоит там, где поле не заполняли:
        // у живого клиента таких строк 367 из 381.
        assertThat(installationPriceOf(IMPORT, "A-200")).isNull();
        assertThat(installationPriceOf(IMPORT, "A-400")).isNull();
        assertThat(installationPriceOf(IMPORT, "A-100")).isEqualByComparingTo("1500");
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
        assertThat(qtyOf(REPEAT, "A-100"))
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
                "A-500";"Стартер";"";"";"";"";"";"";"";"";"";"";\
                "";"";"";"7000";"";"1";"0";"0";"0";"0";"0";"да";""
                """);
        new BazonImporter(dataSource, PARTS_BACKFILL).importAll(donorsFixture(), first);

        assertThat(photoQueueOf(PARTS_BACKFILL)).isZero();

        // Цену владелец подвинул руками — выгрузка старше этой правки.
        jdbc.update("UPDATE %s.part SET price = 6000 WHERE legacy_code = 'A-500'"
                .formatted(PARTS_BACKFILL));

        Path second = write("catalog-new.csv", """
                "Номер товара";"Превью";"Запчасть";"Номер донора";"Марка";"Модель";\
                "Год выпуска";"Кузов";"Двигатель";"Комментарий";"Заметка";"Левый / Правый";\
                "Передний / Задний";"Оценка состояния";"Маркировка";"Производитель";"Цена";\
                "Секция";"Номер производителя";"Ткацкая (свободно)";"Ткацкая (резерв)";\
                "Ткацкая (ожидается)";"Ангар (свободно)";"Ангар (резерв)";\
                "Ангар (ожидается)";"Выгружать";"Установка"
                "A-500";"http://cdn/1.jpg,http://cdn/2.jpg";"Стартер";"";"";"";"";"";"";\
                "Контракт";"";"";"";"";"";"Denso";"7000";"01-02-03";"28100-0D030";\
                "1";"0";"0";"0";"0";"0";"да";""
                """);
        ImportReport report = new BazonImporter(dataSource, PARTS_BACKFILL)
                .importAll(donorsFixture(), second);

        assertThat(report.loaded("товаров дополнено")).isEqualTo(1);
        assertThat(textOf(PARTS_BACKFILL, "A-500", "section"))
                .as("пустая секция так и не заполнилась")
                .isEqualTo("01-02-03");
        assertThat(textOf(PARTS_BACKFILL, "A-500", "manufacturer")).isEqualTo("Denso");
        assertThat(textOf(PARTS_BACKFILL, "A-500", "description")).isEqualTo("Контракт");
        assertThat(jdbc.queryForObject(
                "SELECT price FROM %s.part WHERE legacy_code = 'A-500'".formatted(PARTS_BACKFILL),
                BigDecimal.class))
                .as("выгрузка вернула прежнюю цену поверх правки владельца")
                .isEqualByComparingTo("6000");
        assertThat(photoQueueOf(PARTS_BACKFILL))
                .as("снимки не поставились в очередь: у клиента, загруженного до чтения "
                        + "«Превью», они не появятся никогда")
                .isEqualTo(2);
        // Самое опасное в дозаполнении: движения повторить нельзя.
        assertThat(qtyOf(PARTS_BACKFILL, "A-500"))
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
        assertThat(reservedOf(IMPORT, "A-100")).isEqualByComparingTo("1");

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
                "A-100";"Фара левая";"Д-1";"";"";"";"";"";"";"";"";"";"";"";"";"9500";"";"2";"1";"0";"0";"0";"0";"да";""
                "A-300";"Дверь";"";"";"";"";"";"";"";"";"";"";"";"";"";"-500";"";"1";"0";"0";"0";"0";"0";"да";""
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
                "A-500";"Фара левая";"";"";"";"";"";"";"";"";"";"";"";"";"";"9500";"";\
                "1";"0";"0";"0";"0";"0";"да";"0"
                "A-501";"обрезанная
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
                "A-900";"Фара левая";"";"";"";"";"";"";"";"";"";"";"";"";"";"9500";\
                "81150-33670";"1";"0";"0";"0";"0";"0";"да";"0";"АНАЛОГ, 8115033671"
                """);

        new BazonImporter(dataSource, JUNK_OEM).importAll(donorsFixture(), catalog);

        // В выгрузке живого клиента в кросс-номерах стоит слово «АНАЛОГ»:
        // после приведения от него не остаётся ничего, а колонка normalized
        // объявлена NOT NULL. Пока номер писали не глядя, отказ базы уносил
        // карточку целиком — вместе с остатком, снимками и деньгами.
        assertThat(count(JUNK_OEM, "part")).isEqualTo(1);
        assertThat(qtyOf(JUNK_OEM, "A-900")).isEqualByComparingTo("1");
        assertThat(jdbc.queryForList(
                "SELECT normalized FROM " + JUNK_OEM + ".part_oem ORDER BY normalized",
                String.class))
                .containsExactly("8115033670", "8115033671");
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
                "A-100";"Фара левая";"Д-1";"Toyota";"Camry";"2006";"";"";"";"";"Левый";"Передний";\
                "Хорошее";"";"";"9500";"81150-33670";"2";"1";"0";"0";"0";"0";"да";"1500"
                "A-200";"Бампер передний";"";"";"";"";"";"";"";"";"";"";"";"";"";"12000";"";\
                "0";"0";"0";"1";"0";"0";"да";"0"
                "A-400";"Стартер";"";"";"";"";"";"";"";"";"";"";"";"";"";"3500";"";\
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
