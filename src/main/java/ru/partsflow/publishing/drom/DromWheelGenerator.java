package ru.partsflow.publishing.drom;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.stream.XMLStreamException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * Глубина протектора новой шины, от которой считается износ в процентах.
     *
     * <p><b>Здесь мы производим число, которого у нас нет, и это надо
     * понимать.</b> Мы храним остаток протектора в миллиметрах — так его
     * мерил приёмщик глубиномером, и так его называет покупатель. Площадка
     * же фильтрует б/у шины по износу в процентах и другого поля не знает.
     *
     * <p>Не отдавать износ вовсе — значит выпасть из фильтра «износ до 20 %»,
     * то есть из основного способа искать б/у шину. Отдавать пересчёт —
     * значит опираться на глубину новой шины, которой в карточке нет.
     * Выбрано второе: восемь миллиметров — типовая глубина легковой шины,
     * и ошибка в один миллиметр даёт двенадцать процентов, а отсутствие
     * поля даёт ноль показов.
     *
     * <p>Настоящий остаток при этом уезжает в наименовании: человек, открывший
     * объявление, видит «остаток протектора 6 мм», а не только процент.
     */
    private static final BigDecimal NEW_TREAD_MM = new BigDecimal("8");

    /**
     * В прайс идёт то, что имеет смысл показывать: колесо с остатком либо уже
     * проданное. Проданное — недоступным, чтобы не потерять объявление вместе
     * с просмотрами; то же соображение, что и в прайсе запчастей.
     */
    private static final String SQL = """
            SELECT p.public_code,
                   p.title,
                   p.price,
                   p.condition,
                   COALESCE(s.qty_available, 0) AS qty_available,
                   w.kind,
                   w.brand, w.model,
                   w.disc_brand, w.disc_model,
                   w.tyre_width, w.tyre_height, w.diameter,
                   w.speed_index, w.load_index, w.run_flat, w.light_truck,
                   w.disc_width, w.offset_mm, w.bolt_pattern,
                   w.season, w.wear_mm, w.made_year,
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

        String sql = SQL.replace("ORDER BY p.id",
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
            } catch (XMLStreamException e) {
                throw new SQLException("Не удалось записать прайс колёс", e);
            }
        });
    }

    /**
     * Дельта колёс: те же позиции в том же формате, только выбранные.
     *
     * <p>Формат обязан совпадать с полным прайсом — площадка разбирает дельту
     * той же настройкой. Ссылок на снимки в ней нет по той же причине, что
     * и в дельте запчастей: объявление и его фотографии у Дрома уже есть.
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
        if (partIds == null || partIds.isEmpty()) {
            return 0;
        }
        return write(out, partIds, filter, null, settings);
    }

    /** Итератор поверх курсора: строки не накапливаются. */
    private static final class OfferCursor implements Iterator<DromWheelOffer> {

        private static final int MAX_PHOTOS = 10;

        private final ResultSet resultSet;
        private final String photoBase;

        /** Как собирается файл: наценка на прайс-лист и округление. */
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
            boolean used = "USED".equals(rs.getString("condition"));

            return new DromWheelOffer(
                    rs.getString("public_code"),
                    rs.getString("title"),
                    model(rs, kind),
                    marking(rs, kind),
                    // Колесо у нас продаётся поштучно — так его и заводят
                    // в карточку, и так называется цена. Площадка требует,
                    // чтобы цена соответствовала числу шин в комплекте
                    // (п. 6.4.3), поэтому здесь единица, а не размер набора:
                    // покупатель берёт и одну запаску.
                    1,
                    rs.getBigDecimal("qty_available"),
                    // Цена объявления, а не складская: наценка принадлежит
                    // прайс-листу, а колесо на складе стоит по-прежнему.
                    settings.priceFor(rs.getBigDecimal("price")),
                    used ? "Б/у" : "Новая",
                    season(rs.getString("season")),
                    rs.getBoolean("light_truck") ? "Грузовая" : "Легковая",
                    spike(rs.getString("season")),
                    year(rs),
                    used ? wearPercent(rs.getBigDecimal("wear_mm")) : null,
                    photoLinks(rs.getString("photo_ids"), photoBase));
        }

        /**
         * Производитель и модель. У колеса в сборе их два — шинный и дисковый,
         * — и площадке нужен тот, по которому ищут шину.
         */
        private static String model(ResultSet rs, String kind) throws SQLException {
            String brand = "DISC".equals(kind) ? rs.getString("disc_brand") : rs.getString("brand");
            String model = "DISC".equals(kind) ? rs.getString("disc_model") : rs.getString("model");
            return join(brand, model);
        }

        /**
         * Маркировка по правилам площадки: типоразмер, потом индексы.
         *
         * <p>Порядок и слитность взяты из её требований (п. 3.3): «195/65R15
         * 100Q». Написание «R15 195/65» она честно предупреждает, что
         * распознает неверно, и покупатель шину не найдёт.
         *
         * <p>У диска типоразмера в этом смысле нет; ему отдаётся привычная
         * запись «7x18 5x114.3 ET38» — та же, что видит человек на самом диске.
         */
        private static String marking(ResultSet rs, String kind) throws SQLException {
            if ("DISC".equals(kind)) {
                return discMarking(rs);
            }
            BigDecimal diameter = rs.getBigDecimal("diameter");
            int width = rs.getInt("tyre_width");
            int height = rs.getInt("tyre_height");
            if (width == 0 || diameter == null) {
                return null;
            }

            StringBuilder marking = new StringBuilder();
            marking.append(width);
            if (height > 0) {
                marking.append('/').append(height);
            }
            marking.append('R').append(diameter.stripTrailingZeros().toPlainString());

            // Индекс нагрузки и скорости — слитно, как требует площадка
            // (п. 3.6.1): «100Q», а не «100 Q».
            int load = rs.getInt("load_index");
            String speed = rs.getString("speed_index");
            if (load > 0 || speed != null) {
                marking.append(' ');
                if (load > 0) {
                    marking.append(load);
                }
                if (speed != null) {
                    marking.append(speed);
                }
            }
            return marking.toString();
        }

        private static String discMarking(ResultSet rs) throws SQLException {
            BigDecimal width = rs.getBigDecimal("disc_width");
            BigDecimal diameter = rs.getBigDecimal("diameter");
            if (width == null || diameter == null) {
                return null;
            }
            StringBuilder marking = new StringBuilder()
                    .append(width.stripTrailingZeros().toPlainString())
                    .append('x')
                    .append(diameter.stripTrailingZeros().toPlainString());

            String bolts = rs.getString("bolt_pattern");
            if (bolts != null) {
                marking.append(' ').append(bolts);
            }
            int offset = rs.getInt("offset_mm");
            if (!rs.wasNull()) {
                marking.append(" ET").append(offset);
            }
            return marking.toString();
        }

        /**
         * Сезон словами площадки. Шипы и липучка у неё различаются не сезоном,
         * а отдельным полем — оба «Зимняя».
         */
        private static String season(String season) {
            return season == null ? null : switch (season) {
                case "SUMMER" -> "Летняя";
                case "WINTER", "WINTER_STUDDED", "WINTER_FRICTION" -> "Зимняя";
                case "ALL_SEASON" -> "Всесезонная";
                default -> null;
            };
        }

        /**
         * Шиповка.
         *
         * <p>Отвечаем только там, где знаем: у шины, заведённой как просто
         * «зимняя», шипы неизвестны, и сказать «нешипуемая» значит соврать
         * покупателю, который ищет шипы. Летняя и всесезонная нешипуемы
         * по природе.
         */
        private static String spike(String season) {
            return season == null ? null : switch (season) {
                case "WINTER_STUDDED" -> "Шипованная";
                case "WINTER_FRICTION", "SUMMER", "ALL_SEASON" -> "Нешипуемая";
                default -> null;
            };
        }

        private static Integer year(ResultSet rs) throws SQLException {
            int value = rs.getInt("made_year");
            return rs.wasNull() ? null : value;
        }

        /** Остаток протектора в миллиметрах → износ в процентах. */
        private static Integer wearPercent(BigDecimal remainingMm) {
            if (remainingMm == null) {
                return null;
            }
            BigDecimal worn = NEW_TREAD_MM.subtract(remainingMm);
            if (worn.signum() <= 0) {
                return 0;
            }
            int percent = worn.multiply(new BigDecimal("100"))
                    .divide(NEW_TREAD_MM, 0, RoundingMode.HALF_UP)
                    .intValue();
            return Math.min(percent, 100);
        }

        private static List<String> photoLinks(String ids, String base) {
            if (base == null || ids == null || ids.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(ids.split(","))
                    .limit(MAX_PHOTOS)
                    .map(id -> base + id + ".jpg")
                    .toList();
        }

        private static String join(String first, String second) {
            if (first == null || first.isBlank()) {
                return second;
            }
            if (second == null || second.isBlank()) {
                return first;
            }
            return first + " " + second;
        }
    }
}
