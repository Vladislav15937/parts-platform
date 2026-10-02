package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.publishing.FeedSettings;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Снимки машины-донора в объявлении выбранных наименований (задача 0009).
 *
 * <p><b>Что здесь проверяется и почему именно так.</b> Для двигателя
 * и коробки состояние машины — половина объявления: покупатель смотрит,
 * откуда снято и в каком была машина. У фары те же фотографии лишние,
 * и выборочность — это и есть предмет задачи: настройка, дописывающая
 * снимки донора всем, была бы хуже отсутствующей.
 *
 * <p>Своя схема, а не общая с {@code DromPriceGeneratorTest}: тот заводит
 * позиции без донора и считает их прайсом целиком, а здесь к каждой машине
 * привязаны снимки — чужая строка в общей схеме меняла бы число ссылок
 * в чужом объявлении. Схема стоит дёшево, поиск причины — нет.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class DonorPhotoFeedTest extends PostgresTestBase {

    private static final String TENANT = "t_000930";

    /**
     * Адреса выдачи: те же постоянные ссылки нашего домена, что в бою.
     *
     * <p>Разные у позиции и у машины не для порядка: номера в
     * {@code part_photo} и {@code donor_photo} свои и пересекаются, поэтому
     * по одному адресу площадка получала бы снимок не того, о чём объявление.
     * Тест на этом и стоит — он считает ссылки каждого вида отдельно.
     */
    private static final String PHOTO_BASE = "https://parts.example.org/feeds/drom/c/t/photo/";
    private static final String DONOR_BASE =
            "https://parts.example.org/feeds/drom/c/t/donor-photo/";

    @Autowired
    private DromPriceGenerator generator;

    @Autowired
    private ru.partsflow.inventory.StockLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;
    private Long engineKind;
    private Long headlightKind;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });

        // Эталоны берутся из поставляемого справочника, а не заводятся свои:
        // catalog — общая схема ячейки, и своя копия «Двигателя» рядом
        // с настоящим ломала бы сопоставление соседним тестам.
        engineKind = kindNamed("Двигатель");
        headlightKind = kindNamed("Фара");
    }

    @Test
    @DisplayName("Снимки донора уходят в объявление отмеченного наименования — вслед за своими")
    void donorPhotosFollowTheMarkedKind() {
        String name = "Донор-фото: двигатель 1NZ-FE";
        Long donor = donor();
        Long partId = part(name, engineKind, donor);
        photo(partId, "svoy-1.jpg", true, 0);
        donorPhoto(donor, "mashina-1.jpg", true, 0);
        donorPhoto(donor, "mashina-2.jpg", false, 1);

        String offer = offerOf(name, marked(engineKind));

        assertThat(linksTo(offer, PHOTO_BASE))
                .as("свой снимок позиции пропал из объявления")
                .hasSize(1);
        assertThat(linksTo(offer, DONOR_BASE))
                .as("снимки машины не доехали до объявления двигателя, "
                        + "а состояние машины для него — половина объявления")
                .hasSize(2);

        // Порядок — это решение, а не случайность: предел снимков обрезает
        // первые, и свои обязаны стоять раньше донорских.
        assertThat(offer.indexOf(PHOTO_BASE))
                .as("снимок машины встал раньше снимка самой детали")
                .isLessThan(offer.indexOf(DONOR_BASE));
    }

    @Test
    @DisplayName("У фары с того же донора снимков машины нет")
    void unmarkedKindGetsNoDonorPhotos() {
        String name = "Донор-фото: фара левая";
        Long donor = donor();
        Long partId = part(name, headlightKind, donor);
        photo(partId, "fara-1.jpg", true, 0);
        donorPhoto(donor, "mashina-fary.jpg", true, 0);

        // Отмечен двигатель — фара обязана уехать как раньше.
        String offer = offerOf(name, marked(engineKind));

        assertThat(linksTo(offer, DONOR_BASE))
                .as("снимки машины уехали в объявление фары, хотя её наименование "
                        + "не отмечено: в объявлении о фаре виден чужой капот")
                .isEmpty();
        assertThat(linksTo(offer, PHOTO_BASE))
                .as("свой снимок фары пропал")
                .hasSize(1);
    }

    @Test
    @DisplayName("Предел числа снимков донорские не выталкивают: обрезаются они, а не свои")
    void donorPhotosNeverPushOutOwn() {
        String name = "Донор-фото: АКПП A541E";
        Long donor = donor();
        Long partId = part(name, engineKind, donor);
        photo(partId, "akpp-1.jpg", true, 0);
        photo(partId, "akpp-2.jpg", false, 1);
        donorPhoto(donor, "mashina-akpp-1.jpg", true, 0);
        donorPhoto(donor, "mashina-akpp-2.jpg", false, 1);

        FeedSettings twoPhotos = new FeedSettings(null, null, 2, null, null, null, null,
                List.of(engineKind));
        String offer = offerOf(name, twoPhotos);

        assertThat(linksTo(offer, PHOTO_BASE))
                .as("предел выгрузки съел собственные снимки позиции, "
                        + "а донорские оставил")
                .hasSize(2);
        assertThat(linksTo(offer, DONOR_BASE))
                .as("снимков в объявлении больше, чем разрешила выгрузка")
                .isEmpty();
    }

    @Test
    @DisplayName("У контрактной детали без донора ничего не ломается и не пустует")
    void contractPartWithoutDonorIsUnchanged() {
        String name = "Донор-фото: контрактный двигатель из контейнера";
        Long partId = part(name, engineKind, null);
        photo(partId, "kontrakt-1.jpg", true, 0);

        String marked = offerOf(name, marked(engineKind));

        assertThat(linksTo(marked, DONOR_BASE))
                .as("у детали без машины взялись снимки машины")
                .isEmpty();
        assertThat(linksTo(marked, PHOTO_BASE))
                .as("свой снимок контрактной детали пропал")
                .hasSize(1);
        // Пустой элемент хуже отсутствующего: площадка снимает объявление
        // за мёртвую картинку, а «<photo></photo>» — ровно она.
        assertThat(marked)
                .as("в объявление уехала пустая ссылка на снимок")
                .doesNotContain("<photo></photo>")
                .doesNotContain("<photo/>");

        // И то же объявление без настройки обязано быть ровно таким же:
        // появление настройки не меняет прайс тем, кто её не трогал.
        assertThat(offerOf(name, FeedSettings.none()))
                .as("объявление контрактной детали изменилось от одной настройки")
                .isEqualTo(marked);
    }

    /** Настройки выгрузки, отмечающей эти наименования снимками донора. */
    private static FeedSettings marked(Long... kindIds) {
        return new FeedSettings(null, null, null, null, null, null, null, List.of(kindIds));
    }

    /** Ссылки на снимки из объявления, у которых этот адрес. */
    private static List<String> linksTo(String offer, String base) {
        java.util.regex.Matcher found = java.util.regex.Pattern
                .compile("<photo>(" + java.util.regex.Pattern.quote(base) + "[^<]*)</photo>")
                .matcher(offer);
        List<String> links = new java.util.ArrayList<>();
        while (found.find()) {
            links.add(found.group(1));
        }
        return links;
    }

    private Long kindNamed(String name) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM catalog.part_kind WHERE name = ?", Long.class, name);
        assertThat(id)
                .as("в поставляемом справочнике нет эталона «%s» — фикстуру надо "
                        + "переписать на существующий, а не заводить свой", name)
                .isNotNull();
        return id;
    }

    private Long donor() {
        return inTenant(() -> jdbc.queryForObject(
                "INSERT INTO donor (note) VALUES ('Машина под снимки') RETURNING id", Long.class));
    }

    /** Позиция с остатком: без него она уедет недоступной, а снимки — те же. */
    private Long part(String title, Long kindId, Long donorId) {
        Long partId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published,
                                  part_kind_id, donor_id)
                VALUES (1, ?, 5000, 1000, true, ?, ?) RETURNING id""",
                Long.class, title, kindId, donorId));
        inTenant(() -> ledger.record(
                StockMovement.intake(partId, BigDecimal.ONE, warehouse, null)));
        return partId;
    }

    private void photo(Long partId, String file, boolean main, int order) {
        inTenant(() -> jdbc.update("""
                INSERT INTO part_photo (part_id, s3_key, is_main, sort_order, status)
                VALUES (?, ?, ?, ?, 'PROCESSED')""",
                partId, TENANT + "/parts/" + partId + "/" + file, main, order));
    }

    private void donorPhoto(Long donorId, String file, boolean main, int order) {
        inTenant(() -> jdbc.update("""
                INSERT INTO donor_photo (donor_id, s3_key, is_main, sort_order, status)
                VALUES (?, ?, ?, ?, 'PROCESSED')""",
                donorId, TENANT + "/donors/" + donorId + "/" + file, main, order));
    }

    /** Один {@code <offer>} из прайса, собранного с этими настройками. */
    private String offerOf(String name, FeedSettings settings) {
        String xml = inTenant(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            generator.writeTo(out, DromPriceGenerator.FeedFilter.everything(),
                    PHOTO_BASE, DONOR_BASE, settings);
            return out.toString(StandardCharsets.UTF_8);
        });

        int nameAt = xml.indexOf("<name>" + name + "</name>");
        assertThat(nameAt).as("позиции «%s» нет в прайсе вовсе", name).isNotNegative();
        return xml.substring(xml.lastIndexOf("<offer>", nameAt), xml.indexOf("</offer>", nameAt));
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
