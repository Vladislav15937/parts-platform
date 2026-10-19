package ru.partsflow.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import ru.partsflow.inventory.PartCondition;
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
        PART.put("engine_matters", "Двигатель важен");
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
        // Задача 0302: поля, которые до неё стояли в форме с замком. Пишет их
        // inventory.PartIdentity через AuditJournal, мимо сущности: oem и crosses
        // не колонки part, а номера производителя из part_oem, названные так
        // в снимке правки.
        PART.put("number", "№ позиции");
        PART.put("public_code", "Номер товара");
        PART.put("oem", "Номер производителя");
        PART.put("crosses", "Кросс-номера");
        PART.put("authenticity", "Оригинальность");
        PART.put("drom_ad_number", "Номер объявления Дрома");
        PART.put("manual_donor_code", "Номер донора");
        PART.put("manual_brand", "Марка");
        PART.put("manual_model", "Модель");
        PART.put("manual_generation", "Поколение");
        PART.put("manual_body", "Модель кузова");
        PART.put("manual_engine", "Модель двигателя");
        PART.put("manual_year", "Год выпуска");
        PART.put("manual_equipment", "Комплектация");
    }

    /**
     * Блоки карточки Дрома у позиции (таблица {@code part_drom_card}, задача 0285).
     *
     * <p>Отдельным словарём, а не строками {@link #PART}: слушатель пишет их в
     * журнал отдельной таблицей, и в журнале действий организации ({@link #of})
     * её нет — пункт меню по полю, которого журнал организации не покажет
     * никогда, был бы отбором, не находящим ничего. Ленту правок одной позиции
     * ({@code inventory.PartHistoryService}) читает оба словаря: имена колонок
     * не пересекаются с {@link #PART}, и место ленты, откуда пришла правка,
     * человеку ни к чему.
     */
    public static final Map<String, String> PART_DROM_CARD = new LinkedHashMap<>();

    static {
        PART_DROM_CARD.put("warranty", "Гарантия и условия возврата");
        PART_DROM_CARD.put("pickup", "Самовывоз");
        PART_DROM_CARD.put("delivery_payment", "Доставка и оплата");
        // Задача 0286. В ленте правок подписи с «на Дроме»: «Вид детали» там уже
        // занят справочным видом (PART.part_kind_id), а «Раздел» читался бы как
        // «Секция» склада (PART.section). На карточке подписи короткие, слова Дрома.
        PART_DROM_CARD.put("drom_kind", "Вид детали на Дроме");
        PART_DROM_CARD.put("drom_section", "Раздел на Дроме");
        // Задача 0287: слово Дрома для блока применимости («Подходит»); справочная
        // применимость (part_applicability) журналом называется иначе.
        PART_DROM_CARD.put("applicability", "Подходит");
        // Задача 0292: «Прочие поля» объявления Дрома — подпись карточки.
        PART_DROM_CARD.put("characteristics", "Характеристики");
        // Задача 0293: слово Дрома для второго блока применимости — не «Подходит».
        PART_DROM_CARD.put("may_fit", "Может подойти");
        // Задача 0298: «Дата» объявления Дрома; не «Создан» позиции.
        PART_DROM_CARD.put("ad_date", "Дата (Дром)");
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

    /**
     * Поля выгрузки, правку которых пишет журнал (задача 0267).
     *
     * <p>Выгрузка не сущность Hibernate: слушатель её не видит, и запись
     * в журнал делает сам {@code MarketplaceAccountService} через
     * {@code AuditJournal}, передавая только эти колонки. Имя колонки
     * условное — номер лежит ключом {@code packetId} в {@code settings},
     * а не своей колонкой; журналу нужно имя, по которому назвать поле.
     *
     * <p><b>Ключа синхронизации здесь нет и быть не должно.</b> Его значение
     * в журнал не попадает ни в каком виде — ни целиком, ни началом:
     * журнал открыт ревизору, а ключ даёт доступ к кабинету клиента
     * на площадке.
     */
    public static final Map<String, String> MARKETPLACE_ACCOUNT = new LinkedHashMap<>();

    static {
        MARKETPLACE_ACCOUNT.put("packet_id", "Номер прайс-листа");
        // Остальное — задача 0270. Ключи с приставкой feed_ там, где короткое
        // имя уже занято другой таблицей и со своим смыслом (title, status):
        // display() выбирает слова по имени колонки, не по таблице.
        MARKETPLACE_ACCOUNT.put("feed_title", "Название выгрузки");
        MARKETPLACE_ACCOUNT.put("feed_status", "Состояние выгрузки");
        MARKETPLACE_ACCOUNT.put("feed_file_name", "Имя файла прайса");
        MARKETPLACE_ACCOUNT.put("price_percent", "Наценка, %");
        MARKETPLACE_ACCOUNT.put("price_rounding", "Округление цены, шаг ₽");
        MARKETPLACE_ACCOUNT.put("photo_limit", "Число снимков в объявлении");
        MARKETPLACE_ACCOUNT.put("installation_note", "Приписка о стоимости установки");
        MARKETPLACE_ACCOUNT.put("installation_template", "Текст приписки об установке");
        MARKETPLACE_ACCOUNT.put("expected_goods", "Выгружать ожидаемый товар");
        MARKETPLACE_ACCOUNT.put("expected_goods_note", "Текст приписки об ожидаемом товаре");
        MARKETPLACE_ACCOUNT.put("donor_photo_kinds", "Наименования со снимками машины");
        MARKETPLACE_ACCOUNT.put("filter_price_from", "Отбор: цена от");
        MARKETPLACE_ACCOUNT.put("filter_price_to", "Отбор: цена до");
        MARKETPLACE_ACCOUNT.put("filter_conditions", "Отбор: состояния");
        MARKETPLACE_ACCOUNT.put("filter_warehouses", "Отбор: склады");
        MARKETPLACE_ACCOUNT.put("filter_kinds", "Отбор: виды деталей");
        MARKETPLACE_ACCOUNT.put("filter_kinds_excluded", "Отбор: виды деталей — кроме выбранных");
        MARKETPLACE_ACCOUNT.put("filter_brands", "Отбор: марки");
        MARKETPLACE_ACCOUNT.put("filter_brands_excluded", "Отбор: марки — кроме выбранных");
        MARKETPLACE_ACCOUNT.put("filter_columns", "Отбор: условия по колонкам");
        MARKETPLACE_ACCOUNT.put("filter_words", "Отбор: слова в колонках");
        // Секреты — только фактом: значения нет ни в снимке, ни в таблице.
        MARKETPLACE_ACCOUNT.put("credentials", "Ключ синхронизации");
        MARKETPLACE_ACCOUNT.put("feed_token", "Ссылка на прайс");
    }

    /**
     * Словари задачи 0325: правки через экран, которых журнал до неё не видел.
     *
     * <p>Четыре таблицы — сущности Hibernate, и пишет их слушатель, как
     * карточку товара (машина, поставка, написание, снимки); остальные
     * правятся прямым SQL, и запись делает сам сервис через {@code AuditJournal}
     * теми же колонками. Состояние машины и поставки, роль сотрудника и тип
     * источника платежа здесь кодом: слово ставит экран своим словарём, как
     * у состояния сделки, — пятая копия словаря на сервере обошлась бы в то,
     * за что уже заплачено (задача 0048).
     */
    public static final Map<String, String> DONOR = new LinkedHashMap<>();

    static {
        DONOR.put("status", "Состояние машины");
        DONOR.put("location", "Место");
        DONOR.put("vin", "VIN");
        DONOR.put("plate_number", "Госномер");
        DONOR.put("year", "Год выпуска");
        DONOR.put("color", "Цвет");
        DONOR.put("mileage_km", "Пробег, км");
        DONOR.put("body_code", "Модель кузова");
        DONOR.put("engine_code", "Модель двигателя");
        DONOR.put("supply_id", "Поставка");
        DONOR.put("purchase_date", "Дата покупки");
        DONOR.put("supplier_name", "Продавец машины");
        DONOR.put("note", "Примечание");
    }

    public static final Map<String, String> SUPPLY = new LinkedHashMap<>();

    static {
        SUPPLY.put("status", "Состояние поставки");
        SUPPLY.put("kind", "Вид поставки");
        SUPPLY.put("number", "Номер поставки");
        SUPPLY.put("expected_on", "Ожидается");
        SUPPLY.put("arrived_on", "Пришла");
        SUPPLY.put("supplier_name", "Поставщик");
        SUPPLY.put("note", "Примечание");
    }

    /** Написание наименования и его сопоставление с видом детали (экран «Наименования»). */
    public static final Map<String, String> PART_NAME = new LinkedHashMap<>();

    static {
        PART_NAME.put("part_kind_id", "Вид детали");
        PART_NAME.put("match_status", "Сопоставление");
    }

    /** Снимок позиции и снимок машины — одни колонки. */
    public static final Map<String, String> PHOTO = new LinkedHashMap<>();

    static {
        PHOTO.put("is_main", "Главный снимок");
        PHOTO.put("status", "Состояние снимка");
    }

    /** Применимость позиции: строка заводится и снимается, правок полей у неё нет. */
    public static final Map<String, String> PART_APPLICABILITY = new LinkedHashMap<>();

    static {
        PART_APPLICABILITY.put("vehicle", "Машина");
    }

    public static final Map<String, String> CUSTOMER = new LinkedHashMap<>();

    static {
        CUSTOMER.put("name", "Имя или название");
        CUSTOMER.put("phone", "Телефон");
        CUSTOMER.put("email", "Почта");
        CUSTOMER.put("customer_type", "Данные юридического лица");
        CUSTOMER.put("company_name", "Название организации");
        CUSTOMER.put("inn", "ИНН");
        CUSTOMER.put("public_note", "Примечание (видно клиентам)");
        CUSTOMER.put("note", "Заметка (видно только вам)");
    }

    /** Источник платежа и источник сделки — справочники «Настроек». */
    public static final Map<String, String> SOURCE = new LinkedHashMap<>();

    static {
        SOURCE.put("name", "Название");
        SOURCE.put("source_type", "Тип");
        SOURCE.put("is_archived", "В архиве");
    }

    public static final Map<String, String> BRANCH = new LinkedHashMap<>();

    static {
        BRANCH.put("name", "Название филиала");
    }

    public static final Map<String, String> WAREHOUSE = new LinkedHashMap<>();

    static {
        WAREHOUSE.put("name", "Название склада");
        WAREHOUSE.put("availability_note", "Наличие под заказ");
        WAREHOUSE.put("order_days_from", "Дней заказа от");
        WAREHOUSE.put("order_days_to", "Дней заказа до");
        WAREHOUSE.put("print_details", "Реквизиты продавца для печати");
    }

    public static final Map<String, String> STORAGE_CELL = new LinkedHashMap<>();

    static {
        STORAGE_CELL.put("code", "Ячейка");
    }

    /**
     * Сотрудник. Пароля здесь нет значением и не будет: в снимке лежит факт
     * («задан» → «заменён»), а не хэш — журнал открыт ревизору.
     */
    public static final Map<String, String> TENANT_MEMBER = new LinkedHashMap<>();

    static {
        TENANT_MEMBER.put("display_name", "Имя");
        TENANT_MEMBER.put("login", "Логин");
        TENANT_MEMBER.put("role", "Роль");
        TENANT_MEMBER.put("is_active", "Работает");
        TENANT_MEMBER.put("password", "Пароль");
    }

    /** Настройки компании: срок резерва и печатные формы. */
    public static final Map<String, String> COMPANY_SETTING = new LinkedHashMap<>();

    static {
        COMPANY_SETTING.put("reservation_days", "Срок резервирования, дней");
        COMPANY_SETTING.put("print_extra_text", "Текст про гарантию и возврат");
        COMPANY_SETTING.put("print_vat_note", "Пометка об НДС");
        COMPANY_SETTING.put("print_client_signature", "Подпись покупателя");
        COMPANY_SETTING.put("print_issuer_signature", "Подпись выдавшего");
        COMPANY_SETTING.put("legal_name", "Организация");
        COMPANY_SETTING.put("legal_address", "Адрес организации");
        COMPANY_SETTING.put("legal_inn", "ИНН организации");
        COMPANY_SETTING.put("legal_kpp", "КПП");
        COMPANY_SETTING.put("legal_bank_name", "Банк");
        COMPANY_SETTING.put("legal_bank_bic", "БИК");
        COMPANY_SETTING.put("legal_bank_account", "Расчётный счёт");
        COMPANY_SETTING.put("legal_bank_corr_account", "Корр. счёт");
        COMPANY_SETTING.put("legal_director", "Руководитель");
        COMPANY_SETTING.put("legal_chief_accountant", "Главный бухгалтер");
    }

    /** Логические колонки журнала выгрузки: в снимке true/false, на экране да/нет. */
    private static final Set<String> FLAGS = Set.of(
            "is_published", "engine_matters", "installation_note", "expected_goods",
            "filter_kinds_excluded", "filter_brands_excluded",
            "is_main", "is_archived", "is_active",
            "print_client_signature", "print_issuer_signature");

    private static final Map<String, String> MATCH_STATUSES = Map.of(
            "UNMATCHED", "не сопоставлено", "AUTO", "по справочнику", "MANUAL", "вручную");

    private static final Map<String, String> PHOTO_STATUSES = Map.of(
            "UPLOADED", "загружается", "PROCESSED", "загружен", "FAILED", "не загрузился");

    /**
     * Значение словом с учётом таблицы — там, где одно имя колонки у разных
     * таблиц значит разное: {@code status} у снимка — своё, у сделки, машины
     * и поставки слово ставит экран.
     */
    public static String displayIn(String table, String column, String raw,
                                   Map<Long, String> lookup) {
        if (raw != null && "status".equals(column)
                && ("part_photo".equals(table) || "donor_photo".equals(table))) {
            return PHOTO_STATUSES.getOrDefault(raw, raw);
        }
        return display(column, raw, lookup);
    }

    private static final Map<String, String> FEED_STATUSES = Map.of(
            "ACTIVE", "Включена", "PAUSED", "Выключена", "ERROR", "Ошибка отправки");

    /** Словарь колонок по имени таблицы; пустой — за таблицей не следим. */
    public static Map<String, String> of(String table) {
        return switch (table) {
            case "part" -> PART;
            case "deal" -> DEAL;
            case "deal_item" -> DEAL_ITEM;
            case "payment" -> PAYMENT;
            case "donor_cost" -> DONOR_COST;
            case "marketplace_account" -> MARKETPLACE_ACCOUNT;
            case "donor" -> DONOR;
            case "supply" -> SUPPLY;
            case "part_name" -> PART_NAME;
            case "part_photo", "donor_photo" -> PHOTO;
            case "part_applicability" -> PART_APPLICABILITY;
            case "customer" -> CUSTOMER;
            case "payment_source", "deal_source" -> SOURCE;
            case "branch" -> BRANCH;
            case "warehouse" -> WAREHOUSE;
            case "storage_cell" -> STORAGE_CELL;
            case "tenant_member" -> TENANT_MEMBER;
            case "company_setting" -> COMPANY_SETTING;
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

    /**
     * Состояние — словарём самого перечисления, а не копией здесь
     * (задача 0039). Копий было пять, и эта совпадала с остальными
     * по совпадению: её писали в другой день с тем же источником, —
     * а совпадение и есть худший вид связи.
     *
     * <p>С заглавной буквы: тут это значение поля в таблице журнала,
     * а не слово посреди фразы. Поднимается она здесь, а не хранится
     * вторым написанием в перечислении: второе написание того же слова
     * разошлось бы с первым ровно так же, как расходились словари.
     */
    private static final Map<String, String> CONDITIONS = capitalized(PartCondition.titles());

    /** Код состояния, который знает перечисление: «USED» — да, мусор снимка — нет. */
    public static boolean isCondition(String code) {
        return code != null && CONDITIONS.containsKey(code);
    }

    private static Map<String, String> capitalized(Map<String, String> titles) {
        Map<String, String> capitalized = new java.util.LinkedHashMap<>();
        titles.forEach((code, title) -> capitalized.put(code,
                title.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + title.substring(1)));
        return capitalized;
    }

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

    /** «Обе» зависит от оси: одно слово на три колонки назвало бы заднюю деталь «левой и правой». */
    private static final Map<String, String> BOTH_SIDES = Map.of(
            "side_lr", "Левый и правый", "side_fr", "Передний и задний",
            "side_ud", "Верхний и нижний");

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
            case "side_lr", "side_fr", "side_ud" -> "BOTH".equals(raw)
                    ? BOTH_SIDES.get(column) : SIDES.getOrDefault(raw, raw);
            case "product_line" -> LINES.getOrDefault(raw, raw);
            case "payment_type" -> PAYMENT_TYPES.getOrDefault(raw, raw);
            case "cost_type" -> COST_TYPES.getOrDefault(raw, raw);
            case "feed_status" -> FEED_STATUSES.getOrDefault(raw, raw);
            case "kind" -> SupplyKinds.title(raw);
            case "match_status" -> MATCH_STATUSES.getOrDefault(raw, raw);
            case "customer_type" -> "COMPANY".equals(raw) ? "да" : "PERSON".equals(raw) ? "нет" : raw;
            case String flag when FLAGS.contains(flag) -> "true".equals(raw) ? "да" : "нет";
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
