package ru.partsflow.publishing.drom;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Прайс шин и дисков — отдельная выгрузка со своим форматом.
 *
 * <p><b>Почему отдельно от прайса запчастей.</b> Площадка требует этого
 * прямо (п. 1.2 её требований), и требование по делу: у шины пятнадцать своих
 * полей, которых у фары нет, а прайс запчастей разбирают отдельной настройкой
 * на её стороне. Объявление «Шина 195/65 R15» среди запчастей уезжает в чужую
 * категорию, откуда его снимают.
 *
 * <p>Соединение берётся из сессии Hibernate и только в транзакции — по тем же
 * двум причинам, что и в {@link DromPriceGenerator}: маршрутизация арендатора
 * и работающий курсор.
 */
@Service
public class DromWheelGenerator {

    private static final int FETCH_SIZE = 500;

    /**
     * В прайс идёт то, что имеет смысл показывать: колесо с остатком либо уже
     * проданное. Проданное — недоступным, чтобы не потерять объявление вместе
     * с просмотрами; то же соображение, что и в прайсе запчастей.
     */
    private static final String SQL = """
            SELECT -- Ключ позиции в файле — её номер (0334, 0340), не публичный код.
                   p.number,
                   p.description,
                   p.price,
                   p.condition,
                   COALESCE(s.qty_available, 0) AS qty_available,
                   w.kind,
                   w.brand, w.model,
                   w.disc_brand, w.disc_model,
                   w.tyre_width, w.tyre_height, w.diameter,
                   w.speed_index, w.load_index, w.run_flat, w.light_truck,
                   w.disc_width, w.offset_mm, w.bolt_pattern, w.disc_type, w.hub_bore,
                   w.season, w.wear_percent, w.made_year, w.set_pieces,
                   photos.ids AS photo_ids
              FROM part p
              JOIN part_wheel w ON w.part_id = p.id
              LEFT JOIN (
                  SELECT part_id, sum(qty - qty_reserved) AS qty_available
                    FROM part_stock
                   WHERE (?::bigint[] IS NULL OR warehouse_id = ANY (?::bigint[]))
                   GROUP BY part_id
              ) s ON s.part_id = p.id
              LEFT JOIN (
                  SELECT part_id,
                         string_agg(id::text, ',' ORDER BY is_main DESC, sort_order, id) AS ids
                    FROM part_photo
                   WHERE status = 'PROCESSED'
                   GROUP BY part_id
              ) photos ON photos.part_id = p.id
             WHERE p.is_published
               AND p.product_line = 'WHEEL'
               AND p.status IN ('IN_STOCK', 'SOLD')
               AND p.price > 0
               -- Тем же условием, что прайс запчастей и счётчик (задача 0322):
               -- без состояния колесо ушло бы «Новой» без износа.
               AND ${hasCondition}
               AND (?::numeric IS NULL OR p.price >= ?::numeric)
               AND (?::numeric IS NULL OR p.price <= ?::numeric)
               AND (?::text[]   IS NULL OR p.condition = ANY (?::text[]))
             ORDER BY p.id
            """;

    private final EntityManager entityManager;
    private final DromWheelWriter writer;

    private final ru.partsflow.inventory.WheelService wheels;

    public DromWheelGenerator(EntityManager entityManager, DromWheelWriter writer,
                              ru.partsflow.inventory.WheelService wheels) {
        this.entityManager = entityManager;
        this.writer = writer;
        // Отбор по колонкам собирает сама вкладка колёс: список колонок
        // и выражения обязаны быть одни на экран и на выгрузку.
        this.wheels = wheels;
    }

    /** Разбирает отбор, ничего не записывая, — до открытия потока ответа. */
    @Transactional(readOnly = true)
    public void checkFilter(DromPriceGenerator.FeedFilter filter) {
        wheels.columnFilter(filter.columns(), filter.words());
    }

    /**
     * Пишет прайс колёс в поток.
     *
     * <p>Из отбора выгрузки применимы цена, состояние и склады. Виды деталей
     * и марки машин к колесу не относятся вовсе: шина подходит по размеру,
     * а не по модели машины — по той же причине у колеса нет и вкладки
     * применимости.
     */
    @Transactional(readOnly = true)
    public int writeTo(OutputStream out, DromPriceGenerator.FeedFilter filter, String photoBase) {
        return write(out, null, filter, photoBase, ru.partsflow.publishing.FeedSettings.none());
    }

    /**
     * Прайс колёс с настройками сборки выгрузки.
     *
     * <p>Наценка на прайс-лист принадлежит выгрузке, а не товару, и колёсная
     * выгрузка тут ничем не отличается от выгрузки запчастей: у неё своя цена
     * размещения на площадке и своя комиссия. Поле, показанное на экране, но
     * не применённое к колёсам, было бы обещанием, которого нет.
     *
     * <p>Аннотация повторена: {@code @Transactional} перегрузкой
     * не наследуется.
     */
    @Transactional(readOnly = true)
    public int writeTo(OutputStream out, DromPriceGenerator.FeedFilter filter, String photoBase,
                       ru.partsflow.publishing.FeedSettings settings) {
        return write(out, null, filter, photoBase, settings);
    }

    private int write(OutputStream out, List<Long> partIds,
                      DromPriceGenerator.FeedFilter filter, String photoBase,
                      ru.partsflow.publishing.FeedSettings settings) {
        Session session = entityManager.unwrap(Session.class);

        // Условия по колонкам вкладки — в конец, перед отбором дельты:
        // иначе сдвинулись бы номера параметров у всех условий выше.
        var byColumns = wheels.columnFilter(filter.columns(), filter.words());
        String columnsSql = byColumns.map(f -> " AND " + f.sql()).orElse("");
        List<Object> columnArgs = byColumns
                .map(ru.partsflow.inventory.WheelService.ColumnFilter::args)
                .orElseGet(List::of);

        String sql = SQL.replace("${hasCondition}", DromPriceGenerator.HAS_CONDITION)
                .replace("ORDER BY p.id",
                columnsSql + (partIds == null ? " ORDER BY p.id"
                        : " AND p.id = ANY (?) ORDER BY p.id"));

        return session.doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setFetchSize(FETCH_SIZE);

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
                int next = 9;
                for (Object arg : columnArgs) {
                    statement.setObject(next++, arg);
                }
                if (partIds != null) {
                    statement.setArray(next, connection.createArrayOf("bigint", partIds.toArray()));
                }

                try (ResultSet rs = statement.executeQuery()) {
                    return writer.write(out, new OfferCursor(rs, photoBase, settings));
                }
            } catch (java.io.IOException e) {
                throw new SQLException("Не удалось записать прайс колёс", e);
            }
        });
    }

    /**
     * Дельта колёс: те же позиции в том же формате, только выбранные.
     *
     * <p>Формат обязан совпадать с полным файлом — площадка разбирает дельту
     * той же настройкой («смена формата — ошибка», {@code docs/drom-integration.md}).
     */
    @Transactional(readOnly = true)
    public int writeDelta(OutputStream out, List<Long> partIds,
                          DromPriceGenerator.FeedFilter filter) {
        return writeDelta(out, partIds, filter, ru.partsflow.publishing.FeedSettings.none());
    }

    /**
     * Дельта колёс — отбором и настройками своей выгрузки.
     *
     * <p>Настройки тут так же обязательны, как отбор: дельта несёт текущее
     * состояние и перебивает то, что стоит на площадке. Уйдя без наценки,
     * она вернула бы объявлению складскую цену через несколько секунд после
     * того, как полный прайс поставил цену с наценкой.
     */
    @Transactional(readOnly = true)
    public int writeDelta(OutputStream out, List<Long> partIds,
                          DromPriceGenerator.FeedFilter filter,
                          ru.partsflow.publishing.FeedSettings settings) {
        return writeDelta(out, partIds, filter, settings, null);
    }

    /**
     * Дельта со ссылками на снимки — тем же адресом выдачи, что и полный файл.
     *
     * <p>Пустая колонка «Фото» в файле Bazon значит «снимков нет», а не «не
     * трогать»: дельта без ссылок снимала бы фотографии с объявления
     * (так же решено для прайса запчастей, 0333). Аннотация повторена:
     * {@code @Transactional} перегрузкой не наследуется.
     */
    @Transactional(readOnly = true)
    public int writeDelta(OutputStream out, List<Long> partIds,
                          DromPriceGenerator.FeedFilter filter,
                          ru.partsflow.publishing.FeedSettings settings, String photoBase) {
        if (partIds == null || partIds.isEmpty()) {
            return 0;
        }
        return write(out, partIds, filter, photoBase, settings);
    }

    /** Итератор поверх курсора: строки не накапливаются. */
    private static final class OfferCursor implements Iterator<DromWheelOffer> {

        private final ResultSet resultSet;
        private final String photoBase;

        /**
         * Как собирается файл: наценка на прайс-лист, округление и число
         * снимков в объявлении.
         *
         * <p>Настройка, доехавшая до прайса запчастей и не доехавшая сюда,
         * даёт два файла одного клиента, собранные по разным правилам, —
         * и увидеть это можно будет только на чужом сайте.
         */
        private final ru.partsflow.publishing.FeedSettings settings;

        private Boolean hasNext;

        private OfferCursor(ResultSet resultSet, String photoBase,
                            ru.partsflow.publishing.FeedSettings settings) {
            this.resultSet = resultSet;
            this.photoBase = photoBase;
            this.settings = settings;
        }

        @Override
        public boolean hasNext() {
            if (hasNext == null) {
                try {
                    hasNext = resultSet.next();
                } catch (SQLException e) {
                    throw new IllegalStateException("Обход прайса колёс прервался", e);
                }
            }
            return hasNext;
        }

        @Override
        public DromWheelOffer next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            hasNext = null;
            try {
                return map(resultSet, photoBase, settings);
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось прочитать позицию прайса колёс", e);
            }
        }

        private static DromWheelOffer map(ResultSet rs, String photoBase,
                                          ru.partsflow.publishing.FeedSettings settings)
                throws SQLException {
            String kind = rs.getString("kind");
            boolean disc = !"TYRE".equals(kind);
            boolean tyre = !"DISC".equals(kind);
            String condition = rs.getString("condition");

            return new DromWheelOffer(
                    // Ключ позиции в файле — «К» и номер позиции (0334, 0340):
                    // у перенесённой с Bazon это её «Номер товара». Букву надо
                    // проверять кодом знака, не глазом: латинская K выглядит так же.
                    DromWheelWriter.ARTICLE_PREFIX + rs.getLong("number"),
                    switch (kind) {
                        case "TYRE" -> DromWheelWriter.TYPE_TYRE;
                        case "DISC" -> DromWheelWriter.TYPE_DISC;
                        default -> DromWheelWriter.TYPE_ASSEMBLY;
                    },
                    // Колёс, за которые названа цена: комплект одной позицией
                    // (перенос с Дрома, 0291) — его число, иначе одно. Цена
                    // не пересчитывается на штуку никогда (решение 2 задачи 0340):
                    // она уходит как в карточке, число штук — своей колонкой.
                    inSet(rs),
                    disc ? firstNonBlank(rs.getString("disc_brand"),
                            "DISC".equals(kind) ? rs.getString("brand") : null) : null,
                    disc ? firstNonBlank(rs.getString("disc_model"),
                            "DISC".equals(kind) ? rs.getString("model") : null) : null,
                    disc ? number(rs.getBigDecimal("diameter")) : null,
                    disc ? rs.getString("disc_type") : null,
                    disc ? number(rs.getBigDecimal("disc_width")) : null,
                    disc ? integer(rs, "offset_mm") : null,
                    disc ? rs.getString("bolt_pattern") : null,
                    disc ? number(rs.getBigDecimal("hub_bore")) : null,
                    tyre ? rs.getString("brand") : null,
                    tyre ? rs.getString("model") : null,
                    tyre ? integer(rs, "tyre_width") : null,
                    tyre ? integer(rs, "tyre_height") : null,
                    tyre ? number(rs.getBigDecimal("diameter")) : null,
                    tyre ? season(rs.getString("season")) : null,
                    tyre ? integer(rs, "made_year") : null,
                    // Износ хранится процентами, как у клиента (0340): «30%»
                    // вписано — «30%» уходит. У новой шины его нет.
                    tyre && !"NEW".equals(condition) && rs.getObject("wear_percent") != null
                            ? rs.getInt("wear_percent") + "%" : null,
                    condition(condition),
                    // Комментарий — текст клиента как набран (описание позиции).
                    rs.getString("description"),
                    price(settings.priceFor(rs.getBigDecimal("price"))),
                    photoLinks(rs.getString("photo_ids"), photoBase, settings),
                    rs.getBigDecimal("qty_available").signum() > 0);
        }

        /** Цена целым, копейки вверх — так же, как в файле запчастей (0333). */
        private static String price(BigDecimal price) {
            return price == null ? null
                    : price.setScale(0, java.math.RoundingMode.CEILING).toPlainString();
        }

        private static String number(BigDecimal value) {
            return value == null ? null : value.stripTrailingZeros().toPlainString();
        }

        private static String integer(ResultSet rs, String column) throws SQLException {
            int value = rs.getInt(column);
            return rs.wasNull() ? null : String.valueOf(value);
        }

        private static String firstNonBlank(String first, String second) {
            return first != null && !first.isBlank() ? first : second;
        }

        /**
         * Сезон словами Bazon. Шипы и липучка различаются здесь самим словом
         * сезона (у площадки XML они были отдельным полем).
         */
        static String season(String season) {
            return season == null ? null : switch (season) {
                case "SUMMER" -> DromWheelWriter.SEASON_SUMMER;
                case "WINTER" -> DromWheelWriter.SEASON_WINTER;
                case "WINTER_FRICTION" -> DromWheelWriter.SEASON_FRICTION;
                case "WINTER_STUDDED" -> DromWheelWriter.SEASON_STUDDED;
                case "ALL_SEASON" -> DromWheelWriter.SEASON_ALL;
                default -> null;
            };
        }

        /** Состояние словами Bazon; восстановленная — «БУ», как в прайсе запчастей. */
        static String condition(String condition) {
            return condition == null ? null : switch (condition) {
                case "NEW" -> DromWheelWriter.CONDITION_NEW;
                case "CONTRACT" -> DromWheelWriter.CONDITION_CONTRACT;
                default -> DromWheelWriter.CONDITION_USED;
            };
        }

        /** Колёс, за которые названа цена: комплект одной позицией — его число, иначе одно. */
        private static int inSet(ResultSet rs) throws SQLException {
            int pieces = rs.getInt("set_pieces");
            return rs.wasNull() ? 1 : pieces;
        }

        /** Число снимков задаёт выгрузка; ноль у неё — «без ограничения». */
        private static List<String> photoLinks(String ids, String base,
                                               ru.partsflow.publishing.FeedSettings settings) {
            if (base == null || ids == null || ids.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(ids.split(","))
                    .limit(settings.photosPerOffer())
                    .map(id -> base + id + ".jpg")
                    .toList();
        }
    }
}
