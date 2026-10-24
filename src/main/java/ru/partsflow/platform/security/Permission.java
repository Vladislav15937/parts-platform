package ru.partsflow.platform.security;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Справочник действий, на которые владелец раздаёт права (задача 0044).
 *
 * <p><b>Справочник живёт в коде, а не в схеме</b> — так решено в {@code tenant/100}:
 * что значит «deal.return», каким словом оно показано владельцу и какие пути
 * оно открывает — это код. Схема держит только форму ключа, а наборы ролей
 * ({@code access_role_permission}) и права поверх роли
 * ({@code member_permission_override}) — строки с этими ключами. Сверку
 * «каждый ключ раздачи {@code tenant/101} есть здесь» держит
 * {@code PermissionCatalogTest}: ключ, переименованный только здесь, молча
 * отнял бы право у всех, кому оно было роздано.
 *
 * <p><b>Ключ — действие, а не путь и не экран.</b> Решение владельца
 * 23.10.2026 (tasks/0044, вопрос 1): «по действиям, каждому сотруднику
 * отдельно». Под одним ключом собраны пути, у которых до 0044 был один
 * список ролей и один смысл; на методах контроллеров ключ стоит
 * в {@code @PreAuthorize("hasAuthority('…')")}, и опечатку в нём ловит тот же
 * тест (ключ в аннотации, которого нет здесь, не открыл бы путь никому,
 * кроме владельца).
 *
 * <p><b>У владельца все права всегда</b> — и это не набор, а признак:
 * {@link TenantPrincipal#owner()}. Новый ключ, появившийся в следующей
 * сборке, владельцу открыт сразу, без нового входа и без строки в базе.
 *
 * <p>Последние двенадцать до 0044 мог только владелец; при переносе их
 * не получила ни одна роль («Остальное да», ответ владельца 23.10.2026),
 * раздаёт их он сам.
 */
public enum Permission {

    // ---------------------------------------------------------------- приёмка
    INTAKE_RECEIVE("intake.receive", Section.INTAKE,
            "Принимать товар", "заводить позиции и машины, отмечать приход поставки, ставить машину в разбор и на место"),
    PHOTOS_MANAGE("photos.manage", Section.INTAKE,
            "Добавлять и удалять снимки", "у позиций и машин, выбирать главный снимок"),
    DONOR_COSTS("donor.costs", Section.INTAKE,
            "Записывать затраты по машине", "покупка, эвакуатор, разбор"),
    SUPPLY_EXPECTED("supply.expected", Section.INTAKE,
            "Заводить товар, который ещё в пути", "позиции ожидаемой поставки и дата её прихода"),

    // ------------------------------------------------------------------ склад
    STOCK_MOVE("stock.move", Section.STOCK,
            "Перевозить товар между складами", "и смотреть журнал перевозок"),
    STOCK_WRITE_OFF("stock.write_off", Section.STOCK,
            "Списывать товар", null),
    STOCK_DOCUMENT_DELETE_DONE("stock_document.delete_done", Section.STOCK,
            "Удалять выполненные складские документы", "поступление, списание, перемещение — встречным движением"),
    PART_EDIT("part.edit", Section.STOCK,
            "Править позиции и цены", "карточка, правка списком, к каким машинам подходит, важен ли двигатель"),
    PART_PUBLISH("part.publish", Section.STOCK,
            "Включать и снимать выгрузку позиций списком", "какие позиции уходят на площадки, без открытия карточки"),
    PART_CELL("part.cell", Section.STOCK,
            "Раскладывать по полкам и печатать этикетки", "ячейка хранения позиции, этикетки ячеек и товара"),
    PART_MONEY_VIEW("part.money.view", Section.STOCK,
            "Смотреть закупочную цену и себестоимость", "в истории позиции"),
    APPLICABILITY_BULK("applicability.bulk", Section.STOCK,
            "Проставлять применимость по всему складу", "разбором наименований и объявлений Дрома"),
    NAMES_MATCH("names.match", Section.STOCK,
            "Сопоставлять наименования со справочником", "сопоставить, снять, пересопоставить"),
    TEXT_BLOCKS_MANAGE("text_blocks.manage", Section.STOCK,
            "Править общие описания объявлений", "тексты, которые подставляются в объявления многих позиций"),
    EXPORT_TABLE("export.table", Section.STOCK,
            "Скачивать склад таблицей", "склад и шины с дисками одним файлом"),
    WHEELS_SET("wheels.set", Section.STOCK,
            "Заводить колёса комплектом", "четыре колеса одной позицией"),
    IMPORT_MANAGE("import.manage", Section.STOCK,
            "Загружать склад из файла", "перенос из Bazon и из таблицы"),

    // --------------------------------------------------------------- пересчёт
    INVENTORY_COUNT("inventory.count", Section.INVENTORY,
            "Считать товар на пересчёте", "открыть пересчёт и обходить полки"),
    INVENTORY_COUNTER_NOTE("inventory.counter_note", Section.INVENTORY,
            "Оставлять комментарий к пересчёту", "с экрана обхода полок"),
    INVENTORY_RECONCILE("inventory.reconcile", Section.INVENTORY,
            "Сводить расхождения пересчёта", "завершить, провести, отменить, комментарий сводящего"),
    INVENTORY_JOURNAL_VIEW("inventory.journal.view", Section.INVENTORY,
            "Смотреть журнал пересчётов", null),

    // ---------------------------------------------------------------- продажи
    DEAL_SELL("deal.sell", Section.SALES,
            "Продавать", "сделка, заказ с площадки, резерв, оплата, перенос, ссылка покупателю"),
    DEAL_ISSUE("deal.issue", Section.SALES,
            "Выдавать товар по сделке", "открыть сделку, напечатать документы, выдать, история сделки"),
    DEAL_RETURN("deal.return", Section.SALES,
            "Оформлять возврат", "оформить и отменить"),
    DEAL_SOURCES_MANAGE("deal_sources.manage", Section.SALES,
            "Править список источников сделок", "откуда пришёл покупатель"),

    // ------------------------------------------------------- клиенты и деньги
    CUSTOMER_VIEW("customer.view", Section.MONEY,
            "Смотреть клиентов", "карточка, справочник, «Частное лицо», лицевой счёт"),
    CUSTOMER_CREATE("customer.create", Section.MONEY,
            "Заводить клиентов", null),
    CUSTOMER_EDIT("customer.edit", Section.MONEY,
            "Править клиентов", null),
    CUSTOMER_ACCOUNT("customer.account", Section.MONEY,
            "Пополнять и снимать лицевой счёт", null),
    CUSTOMER_ACCOUNT_CORRECT("customer.account.correct", Section.MONEY,
            "Исправлять остаток лицевого счёта", "без платежа, по решению"),
    PAYMENTS_REGISTRY_VIEW("payments.registry.view", Section.MONEY,
            "Смотреть реестр платежей", null),
    PAYMENT_SOURCES_MANAGE("payment_sources.manage", Section.MONEY,
            "Править список способов оплаты", "наличные, карты, расчётные счета"),
    REPORTS_VIEW("reports.view", Section.MONEY,
            "Смотреть отчёты", "продажи по менеджерам, окупаемость, проданные позиции"),

    // --------------------------------------------------------------- выгрузки
    MARKETPLACE_VIEW("marketplace.view", Section.MARKETPLACE,
            "Смотреть выгрузки на площадки", "список, адрес прайса, сколько уходит по отбору"),
    MARKETPLACE_CONFIGURE("marketplace.configure", Section.MARKETPLACE,
            "Настраивать отбор и цены выгрузки", null),
    MARKETPLACE_MANAGE("marketplace.manage", Section.MARKETPLACE,
            "Заводить, выключать и удалять выгрузки", "кабинет площадки, ключ, ссылка на прайс"),
    DELIVERY_MANAGE("delivery.manage", Section.MARKETPLACE,
            "Повторять недоставленное на площадки", "раздел «Доставка»: повторить или снять"),

    // ---------------------------------------------------------------- компания
    ORG_WAREHOUSES("org.warehouses", Section.COMPANY,
            "Заводить склады и ячейки", null),
    ORG_BRANCHES("org.branches", Section.COMPANY,
            "Заводить филиалы", null),
    ORG_AVAILABILITY("org.availability", Section.COMPANY,
            "Править наличие склада в объявлениях", "текст наличия и срок доставки со склада"),
    // Четыре права на раздачу вместо одного «Управлять сотрудниками и ролями»
    // (ответ владельца 24.10.2026, «развёрнутые вопросы», 2а). Прежний ключ
    // members.manage кодом не знается и не читается: tenant/103 выдал вместо
    // него эти четыре, а в данных он остаётся для прежнего образа (expand/contract).
    MEMBERS_ACCOUNTS("members.accounts", Section.COMPANY,
            "Заводить сотрудников, менять им пароль, выключать и включать",
            "только тех, у кого нет прав сверх ваших"),
    MEMBERS_ASSIGN_ROLES("members.assign_roles", Section.COMPANY,
            "Назначать сотрудникам готовые роли", "роль не шире ваших прав"),
    MEMBERS_ROLES("members.roles", Section.COMPANY,
            "Заводить и править роли", "название и набор прав, удаление роли"),
    MEMBERS_GRANT_OVERRIDES("members.grant_overrides", Section.COMPANY,
            "Выдавать и снимать права поверх роли", "отдельному сотруднику, только свои права"),
    ADMIN_ACCESS_MANAGE("admin_access.manage", Section.COMPANY,
            "Пускать администратора сервиса", "дать, отозвать, отклонить запрос"),
    SETTINGS_COMPANY("settings.company", Section.COMPANY,
            "Менять настройки компании", "срок резерва и печатные формы"),
    JOURNAL_VIEW("journal.view", Section.COMPANY,
            "Смотреть журнал действий и входов", null);

    /** Раздел на экране ролей: восемьдесят галочек подряд владелец не прочтёт. */
    public enum Section {
        INTAKE("Приёмка"),
        STOCK("Склад"),
        INVENTORY("Пересчёт"),
        SALES("Продажи"),
        MONEY("Клиенты и деньги"),
        MARKETPLACE("Выгрузки"),
        COMPANY("Компания");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    private static final Map<String, Permission> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::key, Function.identity()));

    private final String key;
    private final Section section;
    private final String title;
    private final String hint;

    Permission(String key, Section section, String title, String hint) {
        this.key = key;
        this.section = section;
        this.title = title;
        this.hint = hint;
    }

    /** Ключ: им право записано в базе и назван в {@code @PreAuthorize}. */
    public String key() {
        return key;
    }

    public Section section() {
        return section;
    }

    /** Слово для экрана: «нет права «Продавать»». */
    public String title() {
        return title;
    }

    /** Что под ним, мелко; {@code null} — название говорит само за себя. */
    public String hint() {
        return hint;
    }

    public static Optional<Permission> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
