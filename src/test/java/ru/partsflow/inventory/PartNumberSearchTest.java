package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Поиск по номеру позиции — задача 0064.
 *
 * <p><b>Что было для человека.</b> Задача 0060 завела позиции собственный
 * номер ровно ради разговора: «порядковый номер также должен отображаться,
 * чтобы человек мог применять человекочитаемые цифры для точной
 * идентификации конкретной запчасти при общении с другими работниками».
 * Номер показывался, сортировка по нему работала — а <b>найти по нему было
 * нельзя</b>: ни витрина, ни продавец, ни вкладка колёс в `part.number`
 * не смотрели. Сказанное вслух «посмотри позицию 347» собеседник мог только
 * долистать, а на складе в 35 841 позицию это не ответ.
 *
 * <p><b>Что проверяется.</b> Три поверхности, на которых ищут позицию, —
 * витрина склада, выдача продавца и вкладка «Шины и диски», — и главное
 * правило модуля: <b>два поиска по одному складу обязаны находить одно
 * и то же</b>. Оно уже нарушалось дважды (521 позиция против 739 и «140125»
 * у владельца против нуля у продавца), и добавить номер в одну поверхность,
 * забыв другую, значит повторить то же самое третий раз. Поэтому проверки
 * идут парами и сравнивают <b>одну и ту же позицию</b>, а не «что-то
 * нашлось».
 *
 * <p><b>Номера в фикстуре настоящие, а не совпадающие с `id`.</b> В свежей
 * схеме обе последовательности начинаются с единицы, и подмена `p.number`
 * на `p.id` прошла бы зелёной. Поэтому последовательность сдвигается
 * до 346, искомая позиция получает номер 347 — тот самый из разговора, —
 * и тест это условие проверяет.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class PartNumberSearchTest extends PostgresTestBase {

    private static final String TENANT = "t_000153";

    /** Номер, который «называют вслух», — он же в задаче и в памятке. */
    private static final long SPOKEN = 347;

    /** Номер колеса: нумерация общая с запчастями, поэтому просто следующий. */
    private static final long WHEEL_NUMBER = 348;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private PartService parts;

    @Autowired
    private WheelService wheels;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouseId;

    /** Позиция, которую называют вслух: у неё номер 347. */
    private Long spokenPart;

    /** Шумная запчасть: «347» стоит у неё в заголовке и в кросс-номере. */
    private Long noisyPart;

    /** Колесо с номером 348 и шумное колесо с «348» в заголовке. */
    private Long spokenWheel;
    private Long noisyWheel;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part_wheel");
            jdbc.update("DELETE FROM part");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouseId = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);

            // Шумные заводятся первыми: без ключа «точное совпадение сначала»
            // они встают выше искомой (у них «347» есть в тексте, то есть
            // ts_rank больше нуля, и id меньше). Проверка порядка иначе
            // проходила бы сама собой.
            noisyPart = part("Фара Nissan Almera 347 прав.", "AAAAAAAAAAAA");
            // Кросс-номер, в который «347» входит куском: подстрочное
            // сравнение номера позиции притащило бы эту строку как «347».
            jdbc.update("""
                    INSERT INTO part_oem (part_id, raw_number, normalized)
                    VALUES (?, '13470', '13470')""", noisyPart);
            noisyWheel = wheel("Шина 195/65 R15 Dunlop 348 летняя", "BBBBBBBBBBBB");

            // Дальше — номер из разговора. Сдвиг последовательности нужен
            // ещё и затем, чтобы номер не совпал с `id`.
            jdbc.execute("SELECT setval('part_number_seq', " + (SPOKEN - 1) + ")");
            spokenPart = part("Фара Toyota Camry 2007 лев.", "CCCCCCCCCCCC");
            spokenWheel = wheel("Шина 195/65 R15 Bridgestone зимняя", "DDDDDDDDDDDD");
            return null;
        });

        // Номера обязаны быть теми, на которые опираются проверки, а `id`
        // обязан от них отличаться — иначе тест не поймает подмену
        // `p.number` на `p.id`.
        assertThat(numberOf(spokenPart))
                .as("фикстура не раздала номера так, как ожидает тест")
                .isEqualTo(SPOKEN);
        assertThat(numberOf(spokenWheel)).isEqualTo(WHEEL_NUMBER);
        assertThat(spokenPart)
                .as("номер позиции совпал с её id — проверка перестала ловить подмену")
                .isNotEqualTo(SPOKEN);
    }

    /**
     * Главная проверка: названный вслух номер находит одну и ту же позицию
     * и у владельца, и у продавца.
     *
     * <p>До правки обе поверхности отвечали на «347» шумной позицией —
     * той, у которой эти цифры в заголовке, — а самой позиции 347 в выдаче
     * не было вовсе. Поэтому сообщение говорит про <b>ненайденную позицию</b>,
     * а не про пустую выдачу: выдача как раз непустая, и именно этим
     * дефект и был незаметен.
     */
    @Test
    @DisplayName("Номер позиции находит одну и ту же деталь на витрине и у продавца")
    void bothSurfacesFindTheSpokenPosition() {
        List<Long> storefront = storefront(String.valueOf(SPOKEN));
        List<Long> seller = seller(String.valueOf(SPOKEN));

        assertThat(storefront)
                .as("витрина не нашла позицию %s по её номеру: нашлось %s — "
                                + "владельцу остаётся листать склад",
                        SPOKEN, storefront)
                .contains(spokenPart);
        assertThat(seller)
                .as("продавец не нашёл позицию %s по её номеру: нашлось %s — "
                                + "сказанное вслух «посмотри позицию %s» не работает",
                        SPOKEN, seller, SPOKEN)
                .contains(spokenPart);
    }

    /**
     * Решение о продукте, записанное проверкой: номер <b>подмешивается</b>
     * в общую выдачу, а не заменяет её.
     *
     * <p>Отдельный режим «искать только по номеру» отобрал бы у запроса
     * из одних цифр всё остальное — а цифрами задают и номер производителя
     * («140125» на живом складе находит три позиции), и маркировку. Поэтому
     * «347» отдаёт и позицию 347, и всё, где эти цифры встретились.
     */
    @Test
    @DisplayName("Номер добавляется к текстовой выдаче, а не заменяет её")
    void numberIsMixedIntoTheUsualResults() {
        assertThat(storefront(String.valueOf(SPOKEN)))
                .as("поиск по номеру отобрал у владельца текстовые совпадения")
                .contains(spokenPart, noisyPart);
        assertThat(seller(String.valueOf(SPOKEN)))
                .as("поиск по номеру отобрал у продавца совпадения по кросс-номеру")
                .contains(spokenPart, noisyPart);
    }

    /**
     * Вторая половина того же решения: сравнение <b>точное</b>.
     *
     * <p>Подстрочное сравнение номера («347» находит и 1347, и 3470)
     * добавило бы к выдаче шум того же рода, от которого номер и должен
     * спасать. Публичные коды у позиций фикстуры заданы буквами именно
     * поэтому: случайные шесть байт сами по себе содержат цифры,
     * и проверка иначе зависела бы от жребия.
     */
    @Test
    @DisplayName("Кусок номера позицию не находит")
    void numberIsMatchedExactly() {
        assertThat(storefront("34"))
                .as("«34» нашло позицию 347: номер сравнивается подстрокой, "
                        + "и в выдаче окажутся 1347, 3470 и 2347")
                .doesNotContain(spokenPart);
        assertThat(seller("34"))
                .as("«34» нашло позицию 347 у продавца")
                .doesNotContain(spokenPart);
    }

    /**
     * Цифры в середине запроса — это не номер позиции.
     *
     * <p>«фара 347» спрашивают про маркировку или номер, и подставлять туда
     * позицию 347 значило бы отвечать не на заданный вопрос. Условие одно
     * на все три поверхности: номером считается запрос целиком.
     */
    @Test
    @DisplayName("Цифры внутри фразы номером позиции не считаются")
    void digitsInsideAPhraseAreNotANumber() {
        assertThat(seller("камри 347"))
                .as("«камри 347» подставило позицию 347 — поиск отвечает "
                        + "не на тот вопрос, который задали")
                .doesNotContain(spokenPart);
    }

    /**
     * Названная позиция стоит первой у продавца — и это не украшение.
     *
     * <p>Выдача обрезана полусотней, а «347» попадает в куски публичных
     * кодов и номеров производителя: на живом складе таких десятки.
     * Позиция, оказавшаяся пятьдесят первой, для продавца не существует —
     * он отвечает покупателю «нет такого», глядя на полный экран находок.
     */
    @Test
    @DisplayName("Позиция, названная номером, стоит первой в выдаче продавца")
    void exactNumberComesFirst() {
        List<Long> found = seller(String.valueOf(SPOKEN));

        assertThat(found)
                .as("проверка бессмысленна, если в выдаче одна строка")
                .hasSizeGreaterThan(1);
        assertThat(found.get(0))
                .as("первой стоит не позиция %s, а совпадение по тексту: "
                        + "в обрезанной выдаче названная вслух позиция утонет", SPOKEN)
                .isEqualTo(spokenPart);
    }

    /**
     * Колесо — та же `part` с тем же номером, и вкладка «Шины и диски»
     * обязана находить его так же, как витрина находит запчасть.
     *
     * <p>Вкладка — третья поверхность, на которой ищут позицию, и забыть
     * её проще всего: у неё свой запрос, свой отбор и своя выгрузка.
     * Проверяется парой с продавцом: колесо видно и там, и там.
     */
    @Test
    @DisplayName("Номер позиции находит колесо на вкладке и у продавца")
    void wheelsTabFindsTheWheelByItsNumber() {
        List<Long> tab = wheelsTab(String.valueOf(WHEEL_NUMBER));
        List<Long> seller = seller(String.valueOf(WHEEL_NUMBER));

        assertThat(tab)
                .as("вкладка «Шины и диски» не нашла колесо %s по его номеру: "
                        + "нашлось %s", WHEEL_NUMBER, tab)
                .contains(spokenWheel);
        assertThat(seller)
                .as("продавец не нашёл колесо %s по его номеру: нашлось %s",
                        WHEEL_NUMBER, seller)
                .contains(spokenWheel);
        // Подмешивание и здесь: «348» есть в заголовке соседней шины.
        assertThat(tab)
                .as("поиск по номеру отобрал у вкладки текстовые совпадения")
                .contains(noisyWheel);
    }

    /** Витрина склада: `product_line = 'PART'`, колесо в неё не попадает. */
    private List<Long> storefront(String query) {
        return inTenant(() -> catalog.list(query, true, true, List.of(), null,
                        Map.of(), Map.of(), null, false, 0, 50)).rows().stream()
                .map(CatalogService.Row::id)
                .toList();
    }

    /** Выдача продавца: и запчасти, и колёса, по свободному остатку. */
    private List<Long> seller(String query) {
        return inTenant(() -> parts.searchAvailable(query, 50)).rows().stream()
                .map(PartService.StockRow::partId)
                .toList();
    }

    /**
     * Вкладка «Шины и диски»: `product_line = 'WHEEL'`.
     *
     * <p>Сортировка названа «number» — тем же именем, каким её подставляет
     * {@code WheelController} по умолчанию: вкладка открывается именно так,
     * и спрашивать у сервиса то, чего экран не спрашивает, значит проверять
     * не тот путь.
     */
    private List<Long> wheelsTab(String query) {
        return inTenant(() -> wheels.list(query, null, true, Map.of(), Map.of(),
                        "number", false, 0, 50)).rows().stream()
                .map(WheelService.WheelRow::id)
                .toList();
    }

    private Long numberOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT number FROM part WHERE id = ?", Long.class, partId));
    }

    /**
     * Позиция с заданным публичным кодом: настоящий код — шесть случайных
     * байт, а в нём сами собой встречаются цифры, и проверка точности
     * сравнения зависела бы от жребия.
     */
    private Long part(String title, String code) {
        Long id = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published)
                VALUES (1, ?, 5000, true) RETURNING id""", Long.class, title);
        jdbc.update("UPDATE part SET public_code = ? WHERE id = ?", code, id);
        ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouseId, null));
        return id;
    }

    private Long wheel(String title, String code) {
        Long id = jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published, product_line)
                VALUES (1, ?, 9000, true, 'WHEEL') RETURNING id""", Long.class, title);
        jdbc.update("UPDATE part SET public_code = ? WHERE id = ?", code, id);
        jdbc.update("""
                INSERT INTO part_wheel (part_id, kind, tyre_width, tyre_height, diameter)
                VALUES (?, 'TYRE', 195, 65, 15)""", id);
        ledger.record(StockMovement.intake(id, BigDecimal.ONE, warehouseId, null));
        return id;
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
