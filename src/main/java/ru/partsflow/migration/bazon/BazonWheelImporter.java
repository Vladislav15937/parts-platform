package ru.partsflow.migration.bazon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.WheelService;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Перенос шин и дисков из предыдущей системы.
 *
 * <p><b>Зачем отдельный проход.</b> Колёса лежат у Bazon на своей вкладке
 * и в выгрузку товаров не попадают вовсе: в её сорока восьми колонках нет
 * ни ширины, ни профиля, ни сезона. Проверено на выгрузке живого клиента —
 * слово «шина» не встречается в наименованиях ни разу, а по артикулам
 * колёс в перенесённом складе нет ни одного. То есть переехавший клиент
 * терял весь колёсный склад: 65 позиций, а с учётом комплектов — 221
 * карточка, ровно столько, сколько он видит у себя в кабинете.
 *
 * <p><b>Читается прайс для площадки, а не выгрузка склада,</b> и это
 * не выбор: другого файла с шинными полями кабинет не отдаёт. Отсюда
 * и состав колонок — они дромовские.
 *
 * <p><b>Карточки заводит {@link WheelService#createSet},</b> а не свой
 * INSERT: там собирается заголовок, ставится движение склада и отметка
 * для площадки. Второй путь разошёлся бы с первым на первой же правке —
 * то же правило, по которому приёмка идёт только через
 * {@code IntakeService.receive}.
 */
@Component
public class BazonWheelImporter {

    private static final Logger log = LoggerFactory.getLogger(BazonWheelImporter.class);

    /** Опорная колонка: по ней отличается прайс колёс от выгрузки товаров. */
    static final String ANCHOR = "Тип (диск, шина, колесо)";

    /**
     * Глубина протектора новой легковой шины.
     *
     * <p>У клиента износ в процентах, у нас в миллиметрах — расхождение
     * записано и намеренно: покупатель мерил глубиномером, а «осталось
     * 25 %» он пересчитывать не станет. Обратный пересчёт при переезде
     * опирается на то же число, что и выгрузка на площадку: восемь
     * миллиметров. Иначе одна и та же шина проехала бы туда и обратно
     * с разной глубиной.
     */
    private static final BigDecimal NEW_TREAD_MM = new BigDecimal("8");

    private static final Map<String, String> KINDS = Map.of(
            "шина", "TYRE", "диск", "DISC", "колесо", "ASSEMBLY");

    private static final Map<String, String> SEASONS = Map.of(
            "летняя", "SUMMER",
            "зимняя", "WINTER",
            "зимняя (шипы)", "WINTER_STUDDED",
            "зимняя (шипованная)", "WINTER_STUDDED",
            "зимняя (липучка)", "WINTER_FRICTION",
            "всесезонная", "ALL_SEASON");

    private final WheelService wheels;
    private final JdbcTemplate jdbc;

    public BazonWheelImporter(WheelService wheels, JdbcTemplate jdbc) {
        this.wheels = wheels;
        this.jdbc = jdbc;
    }

    /**
     * @param warehouseId куда лечь остатку. Спрашивается у владельца, а не
     *                    подставляется: какой склад правильный, знает только
     *                    он, а тихо уехавший не туда товар ищут глазами
     */
    @Transactional
    public Report load(InputStream file, Long warehouseId, Long authorId) {
        if (warehouseId == null) {
            throw new IllegalArgumentException("Не указан склад");
        }
        Report report = new Report();
        try (BazonCsvReader reader = new BazonCsvReader(file)) {
            if (!reader.has(ANCHOR)) {
                throw new IllegalArgumentException(
                        "Это не выгрузка шин и дисков: нет колонки «%s»".formatted(ANCHOR));
            }
            reader.forEachRow(
                    row -> {
                        try {
                            load(row, warehouseId, authorId, report);
                        } catch (RuntimeException e) {
                            report.broken(row.lineNumber(), e.getMessage());
                        }
                    },
                    (line, values) -> report.broken(line, "битая строка выгрузки"));
        }
        log.info("Перенос колёс: заведено {}, пропущено {}, снимков в очередь {}",
                report.created, report.skipped, report.photos);
        return report;
    }

    private void load(BazonCsvReader.Row row, Long warehouseId, Long authorId, Report report) {
        String code = text(row.get("Артикул"));
        if (code == null) {
            // Без артикула повтор переноса завёл бы то же колесо второй раз:
            // узнавать его будет нечем.
            report.skipped++;
            return;
        }
        if (alreadyLoaded(code)) {
            report.skipped++;
            return;
        }

        String kind = lookup(KINDS, row.get(ANCHOR));
        if (kind == null) {
            throw new IllegalArgumentException(
                    "Неизвестный тип товара: " + row.get(ANCHOR));
        }
        int quantity = quantity(row.get("Количество в комплекте"));

        WheelService.Created created = wheels.createSet(
                new WheelService.WheelRequest(
                        kind,
                        decimal(row.get("Посадочный диаметр шины"), row.get("Диаметр диска")),
                        integer(row.get("Ширина профиля шины")),
                        integer(row.get("Высота профиля шины")),
                        null, null,
                        lookup(SEASONS, row.get("Сезон шины (лето, зима, шипы)")),
                        wearMm(row.get("Износ шин")),
                        integer(row.get("Дата производства")),
                        text(row.get("Тип диска")),
                        decimal(row.get("Ширина диска"), null),
                        integer(row.get("Вылет диска")),
                        text(row.get("PCD диска")),
                        decimal(row.get("Диаметр осевого отверстия диска"), null),
                        text(row.get("Производитель шины")),
                        text(row.get("Модель шины")),
                        text(row.get("Производитель диска")),
                        text(row.get("Модель диска")),
                        null, null, null, null, null, null,
                        decimal(row.get("Цена"), null), null, "USED"),
                quantity, warehouseId, authorId);

        stamp(created.partIds(), code, text(row.get("Комментарий")));
        queuePhotos(created.partIds(), row.get("Фото"), report);
        report.created += created.partIds().size();
        report.sets++;
    }

    /**
     * Номер из прежней системы и примечание.
     *
     * <p>Номер уникален, а карточек в комплекте четыре — поэтому к артикулу
     * приписывается порядковый: «К30-1»… «К30-4». Владелец узнаёт своё
     * колесо по «К30», а повтор переноса — по тому же префиксу.
     */
    private void stamp(List<Long> partIds, String code, String note) {
        for (int at = 0; at < partIds.size(); at++) {
            String legacy = partIds.size() > 1 ? code + "-" + (at + 1) : code;
            jdbc.update("UPDATE part SET legacy_code = ?, note = ? WHERE id = ?",
                    legacy, note, partIds.get(at));
        }
    }

    /**
     * Снимки ставятся в ту же очередь, что и у запчастей.
     *
     * <p>Скачивать их прямо здесь нельзя по той же причине, по которой это
     * вынесено у склада: сотня файлов с чужого CDN внутри запроса — это
     * оборванное соединение и непонятное состояние.
     */
    private void queuePhotos(List<Long> partIds, String urls, Report report) {
        if (urls == null || urls.isBlank()) {
            return;
        }
        // Ссылки разбирает тот же BazonValueParser, что и у запчастей:
        // он же убирает из адреса уменьшенную копию — перенести превью
        // значит навсегда оставить клиента с картинками, по которым
        // деталь не разглядеть.
        List<String> links = BazonValueParser.parsePhotoUrls(urls);
        // Снимки — на первую карточку комплекта: одна и та же фотография
        // у четырёх колёс означала бы четырёхкратную закачку одного файла.
        Long partId = partIds.get(0);
        int order = 0;
        for (String url : links) {
            report.photos += jdbc.update("""
                    INSERT INTO part_photo_import (part_id, url, sort_order)
                    VALUES (?, ?, ?)
                    ON CONFLICT (part_id, url) DO NOTHING""",
                    partId, url, order++);
        }
    }

    private boolean alreadyLoaded(String code) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM part
                 WHERE legacy_code = ? OR legacy_code LIKE ?""",
                Integer.class, code, code + "-%");
        return found != null && found > 0;
    }

    /**
     * Износ из процентов в миллиметры остатка протектора.
     *
     * <p>«10 %» у клиента значит «износилось на десятую», то есть осталось
     * девять десятых от новой шины.
     */
    private static BigDecimal wearMm(String value) {
        Integer percent = integer(value == null ? null : value.replace("%", ""));
        if (percent == null || percent < 0 || percent > 100) {
            return null;
        }
        return NEW_TREAD_MM
                .multiply(BigDecimal.valueOf(100 - percent))
                .divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);
    }

    private static int quantity(String value) {
        Integer parsed = integer(value);
        if (parsed == null || parsed < 1) {
            return 1;
        }
        // Восемь — предел комплекта у createSet; больше означает опечатку
        // в чужом файле, и упереться в неё лучше здесь.
        return Math.min(parsed, 8);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Значение словаря по написанию из файла.
     *
     * <p>Через отдельный метод, а не {@code map.get(...)} напрямую:
     * {@code Map.of} на {@code null}-ключе не отвечает «нет такого»,
     * а бросает {@code NullPointerException} — ловушка в проекте уже
     * записана, и наступить на неё удалось снова. Пустых полей в этом
     * файле много: у диска нет ни сезона, ни ширины профиля.
     */
    private static String lookup(Map<String, String> dictionary, String raw) {
        String text = text(raw);
        return text == null
                ? null
                : dictionary.get(text.toLowerCase(java.util.Locale.ROOT));
    }

    private static Integer integer(String value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.replace(",", ".").split("\\.")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Первое непустое из двух: у шины диаметр свой, у диска свой. */
    private static BigDecimal decimal(String first, String second) {
        String text = text(first) != null ? text(first) : text(second);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @param sets строк файла, ставших комплектами; created — карточек */
    public static final class Report {
        private int created;
        private int sets;
        private int skipped;
        private int photos;
        private final List<String> problems = new java.util.ArrayList<>();

        void broken(long line, String why) {
            if (problems.size() < 50) {
                problems.add("строка %d: %s".formatted(line, why));
            }
        }

        public int created() {
            return created;
        }

        public int sets() {
            return sets;
        }

        public int skipped() {
            return skipped;
        }

        public int photos() {
            return photos;
        }

        public List<String> problems() {
            return List.copyOf(problems);
        }
    }
}
