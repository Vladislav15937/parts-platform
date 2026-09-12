package ru.partsflow.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import ru.partsflow.inventory.QualityGrade;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Как называются человеку колонки, за которыми следит журнал изменений.
 *
 * <p><b>Почему словарь один на весь проект.</b> Его читают две поверхности:
 * история одной позиции ({@code inventory.PartHistoryService}, карточка товара)
 * и журнал действий организации ({@code platform.audit.OrganizationAuditService},
 * экран владельца). Обе отвечают на один и тот же вопрос — «что именно
 * поменялось» — и обязаны отвечать одинаково: владелец, увидевший в журнале
 * «Оценка состояния», открывает карточку и должен увидеть там то же слово,
 * а не «Качество».
 *
 * <p>Копия словаря здесь была бы не теоретическим риском, а повторением уже
 * оплаченной ошибки: в этом проекте копии словаря состояний сделки разошлись
 * вчетвером и довели сырой код {@code RETURNED} до экрана клиента
 * (задача 0048), а копии белых списков колонок разошлись до того. Расходятся
 * они не в момент копирования, а позже — когда правят одну из них.
 *
 * <p><b>Чего здесь нет.</b> Состояний сделки и её позиции: они названы словом
 * во фронтенде ({@code frontend/src/sales/dealStatus.ts}), одним файлом
 * на весь проект, и полноту того словаря стережёт
 * {@code DealStatusVocabularyTest} перебором по перечислению. Продублировать
 * его здесь значило бы завести пятую копию ровно там, где четыре только что
 * свели в одну. Поэтому журнал отдаёт код состояния как есть вместе с именем
 * таблицы и колонки, а слово подставляет экран — тем же словарём, которым
 * называет состояние во всех остальных местах.
 */
public final class AuditedColumns {

    private AuditedColumns() {
    }

    /**
     * Поля карточки товара, изменение которых видно человеку.
     *
     * <p>Порядок задаёт порядок вывода внутри одной правки: сначала то, ради
     * чего карточку чаще всего и открывают.
     */
    public static final Map<String, String> PART = new LinkedHashMap<>();

    static {
        PART.put("price", "Цена");
        PART.put("min_price", "Минимальная цена");
        PART.put("cost_price", "Себестоимость");
        PART.put("installation_price", "Стоимость установки");
        PART.put("title", "Наименование");
        PART.put("condition", "Состояние");
        PART.put("quality_grade", "Оценка состояния");
        PART.put("description", "Комментарий");
        PART.put("note", "Заметка");
        PART.put("section", "Секция");
        PART.put("is_published", "Выгружать");
        PART.put("storage_cell_id", "Ячейка");
        PART.put("donor_id", "Донор");
        PART.put("part_kind_id", "Вид детали");
        PART.put("supply_id", "Поставка");
        PART.put("manufacturer", "Производитель");
        PART.put("marking", "Маркировка");
        PART.put("color", "Цвет");
        PART.put("barcode", "Ст. баркод");
        PART.put("legacy_code", "Старые данные");
        PART.put("side_lr", "Левый / Правый");
        PART.put("side_fr", "Передний / Задний");
        PART.put("side_ud", "Верхний / Нижний");
        PART.put("video_url", "Видео");
        PART.put("text_block", "Текстовый блок");
        PART.put("weight_kg", "Вес, кг");
        PART.put("length_mm", "Длина, мм");
        PART.put("width_mm", "Ширина, мм");
        PART.put("height_mm", "Высота, мм");
        PART.put("package_weight_kg", "Вес в упаковке, кг");
        PART.put("package_length_mm", "Длина упаковки, мм");
        PART.put("package_width_mm", "Ширина упаковки, мм");
        PART.put("package_height_mm", "Высота упаковки, мм");
        PART.put("product_line", "Вид товара");
    }

    /**
     * Поля сделки.
     *
     * <p>Состояние первым: «кто отменил сделку» спрашивают тогда, когда клиент
     * приехал за деталью, которой нет, — и это первый вопрос к журналу, а не
     * один из.
     */
    public static final Map<String, String> DEAL = new LinkedHashMap<>();

    static {
        DEAL.put("status", "Состояние");
        DEAL.put("total_amount", "Сумма");
        DEAL.put("discount_amount", "Скидка");
        DEAL.put("customer_id", "Клиент");
        DEAL.put("manager_id", "Ответственный");
        DEAL.put("source", "Источник");
        DEAL.put("delivery_type", "Доставка");
        DEAL.put("delivery_address", "Адрес доставки");
        DEAL.put("note", "Примечание");
    }

    /** Поля позиции сделки. */
    public static final Map<String, String> DEAL_ITEM = new LinkedHashMap<>();

    static {
        DEAL_ITEM.put("status", "Состояние позиции");
        DEAL_ITEM.put("price", "Цена в сделке");
        DEAL_ITEM.put("quantity", "Количество");
        DEAL_ITEM.put("discount", "Скидка на позицию");
    }

    /** Поля платежа. */
    public static final Map<String, String> PAYMENT = new LinkedHashMap<>();

    static {
        PAYMENT.put("amount", "Сумма");
        PAYMENT.put("payment_source_id", "Способ оплаты");
        PAYMENT.put("payment_type", "Вид оплаты");
        PAYMENT.put("customer_id", "Клиент");
        PAYMENT.put("comment", "Примечание");
    }

    /** Поля затраты по машине. */
    public static final Map<String, String> DONOR_COST = new LinkedHashMap<>();

    static {
        DONOR_COST.put("amount", "Сумма");
        DONOR_COST.put("cost_type", "Вид затраты");
        DONOR_COST.put("incurred_on", "Дата");
        DONOR_COST.put("note", "Примечание");
    }

    /** Словарь колонок по имени таблицы; пустой — за таблицей не следим. */
    public static Map<String, String> of(String table) {
        return switch (table) {
            case "part" -> PART;
            case "deal" -> DEAL;
            case "deal_item" -> DEAL_ITEM;
            case "payment" -> PAYMENT;
            case "donor_cost" -> DONOR_COST;
            default -> Map.of();
        };
    }

    /**
     * Деньги владельца.
     *
     * <p>Себестоимость и минимальная цена не «скрываются на экране», а не
     * уезжают с сервера тому, кому не положено: карточку позиции открывает
     * и продавец.
     */
    public static final Set<String> MONEY = Set.of("cost_price", "min_price");

    /** В снимке лежит идентификатор, человеку нужно имя. */
    public static final Set<String> REFERENCES = Set.of(
            "storage_cell_id", "donor_id", "part_kind_id", "supply_id",
            "customer_id", "manager_id", "payment_source_id");

    private static final Map<String, String> CONDITIONS =
            Map.of("NEW", "Новая", "USED", "Б/у", "REFURBISHED", "Восстановленная");

    /**
     * Оценка состояния — словарём самого перечисления, а не копией здесь.
     *
     * <p>Копия тут была четвёртой из четырёх, и три остальные успели
     * разойтись: витрина переводила значения, которых в базе не бывает,
     * форма правки предлагала их же, а карточка печатала {@code NO_DEFECTS}
     * как есть (задача 0033). Эта случайно совпадала — совпадение и есть
     * худший вид связи: следующая правка слова его не переживёт.
     */
    private static final Map<String, String> GRADES = QualityGrade.titles();

    private static final Map<String, String> SIDES = Map.of(
            "LEFT", "Левый", "RIGHT", "Правый", "FRONT", "Передний",
            "REAR", "Задний", "UPPER", "Верхний", "LOWER", "Нижний");

    private static final Map<String, String> LINES = Map.of(
            "PART", "Запчасть", "TYRE", "Шина", "DISC", "Диск", "WHEEL", "Колесо");

    private static final Map<String, String> PAYMENT_TYPES = Map.of(
            "CASH", "Наличные", "CARD", "Карта", "ONLINE", "Онлайн", "TRANSFER", "Перевод");

    private static final Map<String, String> COST_TYPES = Map.of(
            "PURCHASE", "Покупка", "DELIVERY", "Доставка", "CUSTOMS", "Растаможка",
            "DISMANTLING", "Разбор", "STORAGE", "Хранение", "OTHER", "Прочее");

    /**
     * Значение снимка словом человека.
     *
     * <p>Состояния сделки и её позиции сюда не попадают намеренно — см.
     * заглавный комментарий класса: их называет экран, чтобы словарь остался
     * в одном месте. Незнакомое значение возвращается как есть: выдуманное
     * слово спрятало бы расхождение, а сырой код виден.
     *
     * @param lookup имена для ссылочных колонок; пустой — покажется номер
     */
    public static String display(String column, String raw, Map<Long, String> lookup) {
        if (raw == null) {
            return null;
        }
        if (REFERENCES.contains(column)) {
            try {
                // Ссылка на то, чего уже нет, — не повод показать пустоту:
                // номер говорит больше прочерка.
                return lookup.getOrDefault(Long.parseLong(raw), "№" + raw);
            } catch (NumberFormatException notAnId) {
                return raw;
            }
        }
        return switch (column) {
            case "condition" -> CONDITIONS.getOrDefault(raw, raw);
            case "quality_grade" -> GRADES.getOrDefault(raw, raw);
            case "side_lr", "side_fr", "side_ud" -> SIDES.getOrDefault(raw, raw);
            case "product_line" -> LINES.getOrDefault(raw, raw);
            case "payment_type" -> PAYMENT_TYPES.getOrDefault(raw, raw);
            case "cost_type" -> COST_TYPES.getOrDefault(raw, raw);
            case "is_published" -> "true".equals(raw) ? "да" : "нет";
            default -> raw;
        };
    }

    /**
     * Число приводится к общему виду — один раз и для сравнения, и для показа.
     *
     * <p>Снимки сравниваются текстом, а Postgres хранит {@code numeric} со своей
     * точностью: правка цены, не тронувшая себестоимость, оставляла в ленте
     * строку «Себестоимость: было 1200.00, стало 1200.0». Это одно и то же
     * число в разном написании — и правка денег, которой не было, у поля,
     * ради которого историю и открывают. Заодно уходит разнобой в показе:
     * «было 4700.00, стало 5200» читается как две разные величины.
     *
     * <p>Нечисловое сравнивается и показывается как есть: заметка «1 200»
     * с пробелом числом не является, и приводить её не к чему.
     */
    public static String normalized(String value) {
        return "CASE WHEN " + value + " ~ '^-?[0-9]+(\\.[0-9]+)?$'"
                + " THEN trim_scale((" + value + ")::numeric)::text"
                + " ELSE " + value + " END";
    }

    /**
     * Имена вместо идентификаторов — по одному запросу на вид ссылки,
     * а не по запросу на строку: правок у позиции бывают десятки.
     *
     * <p>Запросы здесь, а не у каждого читателя журнала, по той же причине,
     * что и словарь: два читателя, разрешающие ссылку по-своему, рано или
     * поздно назовут одну и ту же машину по-разному.
     */
    public static Map<Long, String> titlesOf(JdbcTemplate jdbc, String column,
                                             Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String sql = switch (column) {
            case "storage_cell_id" -> "SELECT id, code AS title FROM storage_cell WHERE id IN (%s)";
            case "donor_id" -> """
                    SELECT id, coalesce(legacy_code, public_code) AS title
                      FROM donor WHERE id IN (%s)""";
            case "part_kind_id" ->
                    "SELECT id, name AS title FROM catalog.part_kind WHERE id IN (%s)";
            case "supply_id" ->
                    "SELECT id, " + SupplyKinds.sqlLabel("supply")
                            + " AS title FROM supply WHERE id IN (%s)";
            case "customer_id" -> "SELECT id, name AS title FROM customer WHERE id IN (%s)";
            case "manager_id" ->
                    "SELECT id, display_name AS title FROM tenant_member WHERE id IN (%s)";
            case "payment_source_id" ->
                    "SELECT id, name AS title FROM payment_source WHERE id IN (%s)";
            default -> null;
        };
        if (sql == null) {
            return Map.of();
        }
        Map<Long, String> found = new HashMap<>();
        jdbc.query(sql.formatted(placeholders(ids)),
                (RowCallbackHandler) rs -> found.put(rs.getLong("id"), rs.getString("title")),
                ids.toArray());
        return found;
    }

    public static String placeholders(Collection<Long> ids) {
        return String.join(",", Collections.nCopies(ids.size(), "?"));
    }
}
