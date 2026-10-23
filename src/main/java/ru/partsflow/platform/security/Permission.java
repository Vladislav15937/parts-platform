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
    INTAKE_RECEIVE("intake.receive", Section.INTAKE, "Принимать товар",
            "позиции, поставки и «прибыла», машины, разбор, место машины"),
    PHOTOS_MANAGE("photos.manage", Section.INTAKE, "Снимки позиций и машин",
            "загрузить, сделать главным, удалить"),
    DONOR_COSTS("donor.costs", Section.INTAKE, "Затраты по машине",
            "добавить и удалить"),
    SUPPLY_EXPECTED("supply.expected", Section.INTAKE, "Ожидаемые позиции поставки",
            "состав поставки и дата прихода"),

    // ------------------------------------------------------------------ склад
    STOCK_MOVE("stock.move", Section.STOCK, "Перевозить между складами",
            "перемещение и журнал перевозок"),
    STOCK_WRITE_OFF("stock.write_off", Section.STOCK, "Списывать", null),
    STOCK_DOCUMENT_DELETE_DONE("stock_document.delete_done", Section.STOCK,
            "Удалять выполненные складские документы",
            "поступление, списание, перемещение — встречным движением"),
    PART_EDIT("part.edit", Section.STOCK, "Править позиции и цены",
            "карточка, правка списком, применимость из карточки, «Двигатель важен»"),
    PART_PUBLISH("part.publish", Section.STOCK, "«Выгружать» списком", null),
    PART_CELL("part.cell", Section.STOCK, "Ячейка позиции", null),
    PART_MONEY_VIEW("part.money.view", Section.STOCK, "Видеть закупку и себестоимость",
            "в истории позиции"),
    APPLICABILITY_BULK("applicability.bulk", Section.STOCK, "Проставлять применимость по складу",
            "из наименований и с Дрома"),
    NAMES_MATCH("names.match", Section.STOCK, "Сопоставлять наименования",
            "сопоставить, снять, пересопоставить"),
    TEXT_BLOCKS_MANAGE("text_blocks.manage", Section.STOCK, "Текстовые блоки", null),
    EXPORT_TABLE("export.table", Section.STOCK, "Скачивать таблицу склада файлом",
            "склад и шины с дисками"),
    WHEELS_SET("wheels.set", Section.STOCK, "Колёса комплектом", null),
    IMPORT_MANAGE("import.manage", Section.STOCK, "Загружать склад",
            "перенос из Bazon и из таблицы"),

    // --------------------------------------------------------------- пересчёт
    INVENTORY_COUNT("inventory.count", Section.INVENTORY, "Считать на пересчёте",
            "открыть пересчёт, считать, обход полок"),
    INVENTORY_COUNTER_NOTE("inventory.counter_note", Section.INVENTORY,
            "Комментарий ходившего на пересчёте", null),
    INVENTORY_RECONCILE("inventory.reconcile", Section.INVENTORY, "Сводить расхождения",
            "завершить, провести, отменить, комментарий"),
    INVENTORY_JOURNAL_VIEW("inventory.journal.view", Section.INVENTORY, "Журнал пересчётов", null),

    // ---------------------------------------------------------------- продажи
    DEAL_SELL("deal.sell", Section.SALES, "Продавать",
            "сделка, заказ с площадки, резерв, оплата, перенос, ссылка покупателю"),
    DEAL_ISSUE("deal.issue", Section.SALES, "Выдавать",
            "открыть сделку, напечатать, выдать, история сделки"),
    DEAL_RETURN("deal.return", Section.SALES, "Оформлять возврат", "оформить и отменить"),
    DEAL_SOURCES_MANAGE("deal_sources.manage", Section.SALES, "Источники сделок", null),

    // ------------------------------------------------------- клиенты и деньги
    CUSTOMER_VIEW("customer.view", Section.MONEY, "Видеть клиентов",
            "карточка, справочник, «Частное лицо», лицевой счёт"),
    CUSTOMER_CREATE("customer.create", Section.MONEY, "Заводить клиентов", null),
    CUSTOMER_EDIT("customer.edit", Section.MONEY, "Править клиентов", null),
    CUSTOMER_ACCOUNT("customer.account", Section.MONEY, "Пополнять и снимать лицевой счёт", null),
    CUSTOMER_ACCOUNT_CORRECT("customer.account.correct", Section.MONEY,
            "Исправлять лицевой счёт", null),
    PAYMENTS_REGISTRY_VIEW("payments.registry.view", Section.MONEY, "Реестр платежей", null),
    PAYMENT_SOURCES_MANAGE("payment_sources.manage", Section.MONEY, "Источники платежей", null),
    REPORTS_VIEW("reports.view", Section.MONEY, "Отчёты", null),

    // --------------------------------------------------------------- выгрузки
    MARKETPLACE_VIEW("marketplace.view", Section.MARKETPLACE, "Видеть выгрузки",
            "список, адрес прайса, счёт отбора"),
    MARKETPLACE_CONFIGURE("marketplace.configure", Section.MARKETPLACE, "Настраивать отбор выгрузки",
            "отбор и настройки прайса"),
    MARKETPLACE_MANAGE("marketplace.manage", Section.MARKETPLACE, "Заводить и удалять выгрузки",
            "кабинет, ключи, имя файла, адрес прайса"),
    DELIVERY_MANAGE("delivery.manage", Section.MARKETPLACE, "«Доставка» событий",
            "повторить и снять недоставленное"),

    // ---------------------------------------------------------------- компания
    ORG_WAREHOUSES("org.warehouses", Section.COMPANY, "Заводить склады и ячейки", null),
    ORG_BRANCHES("org.branches", Section.COMPANY, "Заводить филиалы", null),
    ORG_AVAILABILITY("org.availability", Section.COMPANY, "Доступность склада", null),
    MEMBERS_MANAGE("members.manage", Section.COMPANY, "Сотрудники и роли",
            "заводить, менять пароль, выключать, назначать роли и права"),
    ADMIN_ACCESS_MANAGE("admin_access.manage", Section.COMPANY, "Доступ администратора сервиса",
            "дать, отозвать, отклонить запрос"),
    SETTINGS_COMPANY("settings.company", Section.COMPANY, "Настройки компании",
            "срок резерва и печатные формы"),
    JOURNAL_VIEW("journal.view", Section.COMPANY, "Журнал действий и входов", null);

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
