package ru.partsflow.publishing.drom;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.VerticalSide;

import javax.xml.stream.XMLStreamException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Сборка полного прайса Дрома: курсор по складу — сразу в поток.
 *
 * <p><b>Соединение берётся из сессии Hibernate, а не из {@code DataSource}.</b>
 * Маршрутизацию арендаторов делает {@code TenantConnectionProvider}, выставляя
 * {@code search_path} при выдаче соединения. Соединение, полученное напрямую из
 * пула, указывает на {@code public} — то есть прайс либо не соберётся вовсе,
 * либо, что хуже, соберётся не по тому арендатору.
 *
 * <p><b>Метод обязан выполняться в транзакции.</b> Postgres игнорирует
 * {@code fetchSize} при включённом autoCommit и вычитывает результат целиком —
 * ради чего тогда потоковая запись. Внутри транзакции autoCommit уже выключен,
 * поэтому курсор работает, а трогать его руками нельзя.
 *
 * <p>Полный прайс Дром забирает сам по постоянному URL; лимит 5 МБ относится
 * только к API-дельте и здесь не действует. Подробнее —
 * {@code docs/drom-integration.md} §1.
 */
@Service
public class DromPriceGenerator {

    /** Компромисс между числом round-trip'ов и памятью на пакет строк. */
    private static final int FETCH_SIZE = 500;

    /**
     * В прайс идёт то, что имеет смысл показывать: позиция с остатком либо уже
     * проданная. Проданные нужны с {@code available = false}: если просто убрать
     * позицию из прайса, объявление у Дрома исчезнет вместе с накопленными
     * просмотрами, а они и есть то, за что платят.
     *
     * <p>Свободный остаток суммируется по складам — покупателю неважно, где
     * лежит деталь, важно, сколько её можно продать.
     */
    private static final String SQL = """
            SELECT p.public_code,
                   p.title,
                   p.description,
                   p.price,
                   COALESCE(s.qty_available, 0) AS qty_available,
                   p.condition,
                   p.manufacturer,
                   p.color,
                   p.marking,
                   p.side_lr,
                   p.side_fr,
                   p.side_ud,
                   primary_oem.raw_number AS oem_number,
                   analogs.numbers        AS analog_numbers
              FROM part p
              LEFT JOIN (
                  SELECT part_id, sum(qty_available) AS qty_available
                    FROM part_stock
                   WHERE (?::bigint[] IS NULL OR warehouse_id = ANY (?::bigint[]))
                   GROUP BY part_id
              ) s ON s.part_id = p.id
              LEFT JOIN donor d ON d.id = p.donor_id
              LEFT JOIN part_oem primary_oem
                     ON primary_oem.part_id = p.id AND primary_oem.is_primary
              LEFT JOIN (
                  SELECT part_id, string_agg(raw_number, ',') AS numbers
                    FROM part_oem
                   WHERE NOT is_primary
                   GROUP BY part_id
              ) analogs ON analogs.part_id = p.id
             WHERE p.is_published
               AND p.status IN ('IN_STOCK', 'SOLD')
               AND p.price IS NOT NULL
               AND (?::numeric IS NULL OR p.price >= ?::numeric)
               AND (?::numeric IS NULL OR p.price <= ?::numeric)
               AND (?::text[]   IS NULL OR p.condition = ANY (?::text[]))
               -- Список в одну из двух сторон. COALESCE нужен для позиций
               -- без вида и без донора: при «только эти» они не проходят
               -- (мы не знаем, те ли они), при «кроме этих» — проходят,
               -- иначе исключение одной марки выкинуло бы и контрактные.
               AND (?::bigint[] IS NULL OR
                    CASE WHEN ?::boolean
                         THEN COALESCE(p.part_kind_id <> ALL (?::bigint[]), true)
                         ELSE p.part_kind_id = ANY (?::bigint[]) END)
               AND (?::bigint[] IS NULL OR
                    CASE WHEN ?::boolean
                         THEN COALESCE(d.brand_id <> ALL (?::bigint[]), true)
                         ELSE d.brand_id = ANY (?::bigint[]) END)
            """;

    /** Дельта — тот же запрос по списку позиций: формат обязан совпасть с прайсом. */
    private static final String DELTA_FILTER = " AND p.id = ANY (?)";

    private static final String ORDER = " ORDER BY p.id";

    private final EntityManager entityManager;
    private final DromPriceWriter writer;

    public DromPriceGenerator(EntityManager entityManager, DromPriceWriter writer) {
        this.entityManager = entityManager;
        this.writer = writer;
    }

    /**
     * Пишет прайс текущего арендатора в поток.
     *
     * @return сколько позиций записано — попадает в {@code publication_log},
     *         чтобы было с чем сверить «Замечания к товарам» в кабинете Дрома
     */
    @Transactional(readOnly = true)
    public int writeTo(OutputStream out) {
        return write(out, null, FeedFilter.everything());
    }

    /**
     * Пишет прайс одной выгрузки: только то, что проходит её отбор.
     *
     * <p>Выгрузок на одну площадку бывает несколько — у живого клиента пять
     * на Дром, разложенные по ценовым диапазонам, у каждой свой прайс-лист
     * в кабинете и своя цена размещения. Какая именно пришла за прайсом,
     * определяет токен в ссылке.
     */
    @Transactional(readOnly = true)
    public int writeTo(OutputStream out, FeedFilter filter) {
        return write(out, null, filter);
    }

    /**
     * Отбор товара в выгрузку.
     *
     * <p><b>Пусто в любом поле — «без ограничения», а не «ничего».</b>
     * Выгрузка, у которой не заполнили цену, обязана отдавать весь склад:
     * пустой прайс площадка примет молча, и объявления пропадут вместе
     * с накопленными просмотрами, за которые и платят.
     *
     * <p><b>Склад меняет и остаток, а не только состав.</b> Выгрузка филиала
     * должна показывать то, что лежит в этом филиале: иначе покупатель
     * приедет за деталью, которая числится на другом конце города.
     *
     * <p>Переключателя «выгружать резерв» здесь нет намеренно. У нас
     * отложенная деталь и так уезжает с {@code available = false}, а убрать
     * её из прайса значит потерять объявление вместе с просмотрами — по той же
     * причине, по которой в прайсе остаётся проданное.
     *
     * @param conditions   {@code NEW}, {@code USED}, {@code REFURBISHED};
     *                     пусто — любое
     * @param warehouseIds пусто — все склады
     */
    public record FeedFilter(BigDecimal priceFrom, BigDecimal priceTo,
                             List<String> conditions, List<Long> warehouseIds,
                             List<Long> kindIds, boolean kindsExcluded,
                             List<Long> brandIds, boolean brandsExcluded) {

        public static FeedFilter everything() {
            return new FeedFilter(null, null, List.of(), List.of(),
                    List.of(), false, List.of(), false);
        }
    }

    /**
     * Пишет дельту — те же позиции в том же формате, только выбранные.
     *
     * <p>Формат обязан совпадать с форматом исходного прайса: Дром разбирает
     * дельту той же настройкой, и смена формата — ошибка на их стороне.
     * Поэтому здесь тот же писатель и тот же запрос, отличается только фильтр.
     *
     * <p>Проданная позиция уезжает с {@code available = false} — это и есть
     * снятие с продажи. Отдельного метода удаления у Дрома нет.
     */
    @Transactional(readOnly = true)
    public int writeDelta(OutputStream out, List<Long> partIds) {
        if (partIds == null || partIds.isEmpty()) {
            return 0;
        }
        return write(out, partIds, FeedFilter.everything());
    }

    private int write(OutputStream out, List<Long> partIds, FeedFilter filter) {
        Session session = entityManager.unwrap(Session.class);
        String sql = SQL + (partIds == null ? "" : DELTA_FILTER) + ORDER;

        return session.doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(FETCH_SIZE);

                // Параметры идут в порядке появления в SQL: сначала склады
                // в подзапросе остатка, потом цена и состояние.
                java.sql.Array warehouses = filter.warehouseIds() == null
                        || filter.warehouseIds().isEmpty()
                        ? null
                        : connection.createArrayOf("bigint", filter.warehouseIds().toArray());
                java.sql.Array conditions = filter.conditions() == null
                        || filter.conditions().isEmpty()
                        ? null
                        : connection.createArrayOf("text", filter.conditions().toArray());

                statement.setArray(1, warehouses);
                statement.setArray(2, warehouses);
                statement.setBigDecimal(3, filter.priceFrom());
                statement.setBigDecimal(4, filter.priceFrom());
                statement.setBigDecimal(5, filter.priceTo());
                statement.setBigDecimal(6, filter.priceTo());
                statement.setArray(7, conditions);
                statement.setArray(8, conditions);

                java.sql.Array kinds = filter.kindIds() == null || filter.kindIds().isEmpty()
                        ? null
                        : connection.createArrayOf("bigint", filter.kindIds().toArray());
                java.sql.Array brands = filter.brandIds() == null || filter.brandIds().isEmpty()
                        ? null
                        : connection.createArrayOf("bigint", filter.brandIds().toArray());

                statement.setArray(9, kinds);
                statement.setBoolean(10, filter.kindsExcluded());
                statement.setArray(11, kinds);
                statement.setArray(12, kinds);
                statement.setArray(13, brands);
                statement.setBoolean(14, filter.brandsExcluded());
                statement.setArray(15, brands);
                statement.setArray(16, brands);

                if (partIds != null) {
                    statement.setArray(17, connection.createArrayOf("bigint", partIds.toArray()));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    return writer.write(out, new OfferCursor(rs));
                }
            } catch (XMLStreamException e) {
                // doReturningWork пропускает только SQLException; заворачиваем,
                // а разворачивает вызывающий по getCause.
                throw new SQLException("Не удалось записать прайс Дрома", e);
            }
        });
    }

    /** Итератор поверх курсора: строки не накапливаются. */
    private static final class OfferCursor implements Iterator<DromOffer> {

        private final ResultSet resultSet;
        private Boolean hasNext;

        private OfferCursor(ResultSet resultSet) {
            this.resultSet = resultSet;
        }

        @Override
        public boolean hasNext() {
            if (hasNext == null) {
                try {
                    hasNext = resultSet.next();
                } catch (SQLException e) {
                    throw new IllegalStateException("Обход прайса прервался", e);
                }
            }
            return hasNext;
        }

        @Override
        public DromOffer next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            hasNext = null;
            try {
                return map(resultSet);
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось прочитать позицию прайса", e);
            }
        }

        private static DromOffer map(ResultSet rs) throws SQLException {
            return new DromOffer(
                    rs.getString("public_code"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getBigDecimal("price"),
                    rs.getBigDecimal("qty_available"),
                    enumOf(PartCondition.class, rs.getString("condition")),
                    rs.getString("manufacturer"),
                    rs.getString("oem_number"),
                    splitAnalogs(rs.getString("analog_numbers")),
                    enumOf(LateralSide.class, rs.getString("side_lr")),
                    enumOf(LongitudinalSide.class, rs.getString("side_fr")),
                    enumOf(VerticalSide.class, rs.getString("side_ud")),
                    rs.getString("color"),
                    rs.getString("marking"));
        }

        private static List<String> splitAnalogs(String joined) {
            return joined == null || joined.isBlank()
                    ? List.of()
                    : List.copyOf(Arrays.asList(joined.split(",")));
        }

        private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
            return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
        }
    }
}
