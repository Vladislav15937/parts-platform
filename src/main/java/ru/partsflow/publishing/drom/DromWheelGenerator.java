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
import java.util.Set;

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
            SELECT -- Ключ строки в файле — «К» и номер (0334, 0340), не публичный код.
                   p.id, p.number, p.legacy_code, w.set_no,
                   -- Номер комплекта: самый малый номер карточки комплекта по всему
                   -- складу, а не по отбору — иначе артикул менялся бы от фильтра.
                   (SELECT min(p2.number) FROM part_wheel w2 JOIN part p2 ON p2.id = w2.part_id
                     WHERE w2.set_no = w.set_no) AS set_number,
                   -- Строка файла — комплект: карточки одного комплекта идут подряд.
                   CASE WHEN w.set_no IS NULL THEN p.id
                        ELSE MIN(p.id) OVER (PARTITION BY w.set_no) END AS grp,
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
             ORDER BY grp, p.id
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
                .replace("ORDER BY grp, p.id",
                columnsSql + (partIds == null ? " ORDER BY grp, p.id"
                        // Изменилась одна карточка комплекта — в дельту идёт весь
                        // комплект: строка файла одна на комплект.
                        : " AND (p.id = ANY (?) OR w.set_no IN (SELECT set_no FROM part_wheel"
                        + " WHERE part_id = ANY (?) AND set_no IS NOT NULL)) ORDER BY grp, p.id"));

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
                    java.sql.Array ids = connection.createArrayOf("bigint", partIds.toArray());
                    statement.setArray(next++, ids);
                    statement.setArray(next, ids);
                }

                Set<String> taken = migratedArticles(connection);
                try (ResultSet rs = statement.executeQuery()) {
                    return writer.write(out, new OfferCursor(rs, photoBase, settings, taken));
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

    /**
     * Артикулы, уже занятые перенесёнными колёсами: «К30» из «К30» и «К30-1»…«К30-4».
     *
     * <p>Номер у перенесённых — чужой счётчик комплектов Bazon, а у наших новых
     * позиций — {@code part.number}; один и тот же номер на двух строках дал бы
     * Дрому дубль артикула (обход 19.10.2026).
     */
    private static Set<String> migratedArticles(java.sql.Connection connection) throws SQLException {
        Set<String> taken = new java.util.HashSet<>();
        try (java.sql.Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT regexp_replace(legacy_code, '-[0-9]+$', '') FROM part"
                             + " WHERE product_line = 'WHEEL' AND legacy_code ~ '^К[0-9]+(-[0-9]+)?$'")) {
            while (rs.next()) {
                taken.add(rs.getString(1));
            }
        }
        return taken;
    }

    /** Корень артикула перенесённого колеса: «К30-2» → «К30»; не перенесённое — {@code null}. */
    static String legacyRoot(String legacy) {
        if (legacy == null || !legacy.matches("К[0-9]+(-[0-9]+)?")) {
            return null;
        }
        int dash = legacy.indexOf('-');
        return dash < 0 ? legacy : legacy.substring(0, dash);
    }

    /** Суффикс артикула новой позиции, чей номер занят перенесённым комплектом. */
    static final String COLLISION_SUFFIX = "н";

    /**
     * Артикул строки файла: «К» и номер комплекта.
     *
     * <p>Перенесённое колесо несёт свой прежний номер, и Дром узнаёт то же объявление.
     * У нового — номер самой ранней карточки комплекта (у одиночного — её собственный).
     * Занятый перенесённым — выводится со {@link #COLLISION_SUFFIX суффиксом}, а не дублем.
     */
    static String article(String legacy, Long setNumber, long number, Set<String> taken) {
        String root = legacyRoot(legacy);
        if (root != null) {
            return root;
        }
        String candidate = DromWheelWriter.ARTICLE_PREFIX + (setNumber != null ? setNumber : number);
        return taken.contains(candidate) ? candidate + COLLISION_SUFFIX : candidate;
    }

    /** Одна карточка из курсора — до склейки в строку файла. */
    private record Card(long grp, Long setNo, String legacy, long number, Long setNumber,
                        DromWheelOffer offer, BigDecimal price, boolean available,
                        String noun, String adjective, String spec, String comment) {

        String signature() {
            return noun + "|" + adjective + "|" + spec;
        }
    }

    /** Итератор поверх курсора: строки склеиваются в комплекты, но не накапливаются. */
    private static final class OfferCursor implements Iterator<DromWheelOffer> {

        private final ResultSet resultSet;
        private final String photoBase;
        private final Set<String> taken;

        /**
         * Как собирается файл: наценка на прайс-лист, округление и число
         * снимков в объявлении.
         *
         * <p>Настройка, доехавшая до прайса запчастей и не доехавшая сюда,
         * даёт два файла одного клиента, собранные по разным правилам, —
         * и увидеть это можно будет только на чужом сайте.
         */
        private final ru.partsflow.publishing.FeedSettings settings;

        /** Курсор стоит на непрочитанной строке. */
        private boolean positioned;
        private boolean primed;

        private OfferCursor(ResultSet resultSet, String photoBase,
                            ru.partsflow.publishing.FeedSettings settings, Set<String> taken) {
            this.resultSet = resultSet;
            this.photoBase = photoBase;
            this.settings = settings;
            this.taken = taken;
        }

        @Override
        public boolean hasNext() {
            if (!primed) {
                advance();
                primed = true;
            }
            return positioned;
        }

        private void advance() {
            try {
                positioned = resultSet.next();
            } catch (SQLException e) {
                throw new IllegalStateException("Обход прайса колёс прервался", e);
            }
        }

        @Override
        public DromWheelOffer next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                List<Card> cards = new java.util.ArrayList<>();
                List<List<String>> photos = new java.util.ArrayList<>();
                Card first = card(resultSet);
                cards.add(first);
                photos.add(photoLinks(resultSet.getString("photo_ids"), photoBase, settings));
                advance();
                // Карточки одного комплекта идут подряд (ORDER BY grp): строка файла — комплект.
                while (positioned && first.setNo() != null && resultSet.getLong("grp") == first.grp()) {
                    cards.add(card(resultSet));
                    photos.add(photoLinks(resultSet.getString("photo_ids"), photoBase, settings));
                    advance();
                }
                return assemble(cards, photos);
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось прочитать позицию прайса колёс", e);
            }
        }

        private DromWheelOffer assemble(List<Card> cards, List<List<String>> photos) {
            // Считаются карточки, которые можно купить; если их нет вовсе — все
            // (проданный комплект остаётся в файле недоступным).
            List<Card> counted = cards.stream().filter(Card::available).toList();
            boolean available = !counted.isEmpty();
            if (!available) {
                counted = cards;
            }
            DromWheelOffer base = counted.get(0).offer();

            // Цена комплекта — сумма цен штук (обход 19.10.2026): у Bazon у каждой
            // штуки своя цена, в файле одна строка с суммой.
            BigDecimal sum = BigDecimal.ZERO;
            for (Card card : counted) {
                BigDecimal each = settings.priceFor(card.price());
                if (each != null) {
                    sum = sum.add(each);
                }
            }
            // Одна карточка с «штук в комплекте» (перенос с Дрома, 0291): цена в ней
            // уже за весь комплект, штук — её число.
            int inSet = cards.size() == 1 ? base.inSet() : counted.size();

            List<String> links = new java.util.ArrayList<>();
            for (List<String> own : photos) {
                for (String link : own) {
                    if (settings.photosPerOffer() <= 0 || links.size() < settings.photosPerOffer()) {
                        links.add(link);
                    }
                }
            }

            Card byId = cards.get(0);
            String legacy = cards.stream().map(Card::legacy)
                    .filter(l -> legacyRoot(l) != null).findFirst().orElse(null);
            return new DromWheelOffer(
                    article(legacy, byId.setNumber(), byId.number(), taken),
                    base.type(), inSet,
                    base.discBrand(), base.discModel(), base.discDiameter(), base.discType(),
                    base.discWidth(), base.offset(), base.boltPattern(), base.hubBore(),
                    base.tyreBrand(), base.tyreModel(), base.tyreWidth(), base.tyreHeight(),
                    base.tyreDiameter(), base.season(), base.year(), base.wear(), base.condition(),
                    comment(counted),
                    price(sum),
                    links,
                    available);
        }

        /**
         * Комментарий строки: генерируемое Bazon'ом, затем слова клиента.
         *
         * <p>«Шина #1: » стоит перед комментарием всегда; у разнородного комплекта
         * (диски разной ширины, шины разных размеров) перед этим идёт блок
         * «Продаётся комплект:» — по строке на группу одинаковых. Дословно по
         * обходу 19.10.2026.
         */
        static String comment(List<Card> cards) {
            java.util.LinkedHashMap<String, List<Card>> groups = new java.util.LinkedHashMap<>();
            for (Card card : cards) {
                groups.computeIfAbsent(card.signature(), k -> new java.util.ArrayList<>()).add(card);
            }
            boolean mixed = groups.size() > 1;
            StringBuilder out = new StringBuilder();
            if (mixed) {
                out.append("Продаётся комплект:");
                for (List<Card> group : groups.values()) {
                    out.append('\n').append(line(group.get(0), group.size()));
                }
                out.append("\n\n");
            }
            int index = 1;
            List<String> parts = new java.util.ArrayList<>();
            for (List<Card> group : groups.values()) {
                Card c = group.get(0);
                String text = c.comment() == null ? "" : c.comment();
                if (!mixed || !text.isBlank()) {
                    parts.add(capital(c.noun()) + " #" + index + ": " + text);
                }
                index++;
            }
            out.append(String.join("\n", parts));
            return mixed && parts.isEmpty() ? out.toString().stripTrailing() : out.toString();
        }

        private static String capital(String word) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }

        /** «3 диска литых R16 / …» — число, существительное и прилагательное в нужной форме. */
        static String line(Card card, int count) {
            int form = count % 10 == 1 && count % 100 != 11 ? 0
                    : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14) ? 1 : 2;
            String noun;
            String[] adjective;
            switch (card.noun()) {
                case "шина" -> {
                    noun = new String[] {"шина", "шины", "шин"}[form];
                    adjective = seasonForms(card.adjective());
                }
                case "диск" -> {
                    noun = new String[] {"диск", "диска", "дисков"}[form];
                    adjective = discTypeForms(card.adjective());
                }
                default -> {
                    noun = new String[] {"колесо", "колеса", "колёс"}[form];
                    adjective = new String[] {"", "", ""};
                }
            }
            StringBuilder out = new StringBuilder().append(count).append(' ').append(noun);
            if (!adjective[form].isBlank()) {
                out.append(' ').append(adjective[form]);
            }
            if (card.spec() != null && !card.spec().isBlank()) {
                out.append(' ').append(card.spec());
            }
            return out.toString();
        }

        /** Сезон по числам: «летняя / летние / летних» (женский род: 1, 2–4, 5+). */
        static String[] seasonForms(String season) {
            if (season == null || season.isBlank()) {
                return new String[] {"", "", ""};
            }
            String lower = season.toLowerCase(java.util.Locale.ROOT);
            int space = lower.indexOf(' ');
            String first = space < 0 ? lower : lower.substring(0, space);
            String rest = space < 0 ? "" : lower.substring(space);
            String[] forms = switch (first) {
                case "летняя" -> new String[] {"летняя", "летние", "летних"};
                case "зимняя" -> new String[] {"зимняя", "зимние", "зимних"};
                case "всесезонная" -> new String[] {"всесезонная", "всесезонные", "всесезонных"};
                default -> new String[] {first, first, first};
            };
            return new String[] {forms[0] + rest, forms[1] + rest, forms[2] + rest};
        }

        /** Тип диска: «литой / литых / литых» (мужской род: после 2–4 — родительный падеж). */
        static String[] discTypeForms(String type) {
            if (type == null || type.isBlank()) {
                return new String[] {"", "", ""};
            }
            String lower = type.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith("ой") || lower.endsWith("ый")) {
                String plural = lower.substring(0, lower.length() - 2) + "ых";
                return new String[] {lower, plural, plural};
            }
            return new String[] {lower, lower, lower};
        }

        private Card card(ResultSet rs) throws SQLException {
            Long setNo = rs.getObject("set_no") == null ? null : rs.getLong("set_no");
            Long setNumber = rs.getObject("set_number") == null ? null : rs.getLong("set_number");
            DromWheelOffer offer = map(rs, photoBase, settings);
            String kind = rs.getString("kind");
            String noun = switch (kind) {
                case "TYRE" -> "шина";
                case "DISC" -> "диск";
                default -> "колесо";
            };
            String tyreSpec = join(
                    offer.tyreWidth() == null ? null : offer.tyreWidth()
                            + (offer.tyreHeight() == null ? "" : "/" + offer.tyreHeight())
                            + (offer.tyreDiameter() == null ? "" : "R" + offer.tyreDiameter()),
                    offer.tyreBrand(), offer.tyreModel());
            String discSpec = join(
                    offer.discDiameter() == null ? null : "R" + offer.discDiameter(),
                    offer.boltPattern() == null ? null : "/ " + offer.boltPattern(),
                    offer.discWidth() == null ? null : "J" + oneDecimal(offer.discWidth()),
                    offer.offset() == null ? null : offer.offset() + "ET",
                    offer.hubBore() == null ? null : "DIA" + offer.hubBore());
            String spec = switch (kind) {
                case "TYRE" -> tyreSpec;
                case "DISC" -> discSpec;
                default -> join(discSpec, tyreSpec);
            };
            String adjective = switch (kind) {
                case "TYRE" -> offer.season();
                case "DISC" -> offer.discType();
                default -> null;
            };
            return new Card(rs.getLong("grp"), setNo, rs.getString("legacy_code"), rs.getLong("number"),
                    setNumber, offer, rs.getBigDecimal("price"),
                    rs.getBigDecimal("qty_available").signum() > 0,
                    noun, adjective, spec, offer.comment());
        }

        private static String join(String... parts) {
            return java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank())
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        /** «6» → «6.0», «6.5» → «6.5»: ширина диска в строке комплекта с десятой, как у Bazon. */
        private static String oneDecimal(String width) {
            return width.contains(".") ? width : width + ".0";
        }

        private static DromWheelOffer map(ResultSet rs, String photoBase,
                                          ru.partsflow.publishing.FeedSettings settings)
                throws SQLException {
            String kind = rs.getString("kind");
            boolean disc = !"TYRE".equals(kind);
            boolean tyre = !"DISC".equals(kind);
            String condition = rs.getString("condition");

            return new DromWheelOffer(
                    // Артикул строки ставит assemble(): он знает комплект целиком.
                    null,
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
