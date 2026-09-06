/**
 * Разделы приложения — один список на рельс, на заголовок вкладки браузера
 * и на проверки, которым нужен полный перечень экранов.
 *
 * <p><b>Почему списком, а не разметкой.</b> Рельс был написан двадцатью одним
 * блоком JSX, а названия разделов — вторым перечнем в `switch`; проверка
 * «страница не уезжает вбок» стала бы третьим. Третий разошёлся бы с первым
 * на первом же новом экране — и разошёлся бы молча, ровно как расходились
 * белые списки колонок и списки отбираемых полей. Сторож, не знающий
 * про новый экран, не сторожит ничего, но выглядит зелёным.
 *
 * <p>Здесь только то, что известно до отрисовки: ключ, слово на рельсе,
 * название раздела и кому он виден. Сами экраны с их свойствами остаются
 * в `HomeScreen`: половина из них требует справочников, связи или сессии,
 * и затащить это сюда значило бы затащить сюда всю оболочку.
 */

/** Ключ раздела. Он же значение состояния `tab` в оболочке. */
export type Tab =
  | 'intake'
  | 'donor'
  | 'sales'
  | 'orders'
  | 'returns'
  | 'customers'
  | 'catalog'
  | 'wheels'
  | 'inventory'
  | 'moves'
  | 'outbox'
  | 'import'
  | 'names'
  | 'reports'
  | 'feeds'
  | 'delivery'
  | 'labels'
  | 'members'
  | 'organization'
  | 'settings'
  | 'reference';

export interface TabSpec {
  id: Tab;
  /** Слово на рельсе — короткое: «Очередь», а не «Очередь отправки». */
  label: string;
  /** Название раздела в верхней полосе и в заголовке вкладки браузера. */
  section: string;
  /** Кому раздел виден. Те же списки, что в `@PreAuthorize` на сервере. */
  roles: readonly string[] | typeof ANYONE;
}

/**
 * Раздел, открытый любому вошедшему.
 *
 * <p>Именно так, а не перечислением пяти сегодняшних ролей: перечень
 * замолчал бы на шестой — новая роль на сервере убрала бы у себя склад
 * и продажу, и убрала бы молча. «Всем» — это утверждение, а список ролей
 * был бы догадкой о том, какие роли бывают.
 */
const ANYONE = 'ANYONE';

/** Кто имеет право продавать. Тот же список стоит на сервере в @PreAuthorize. */
export const SELLING_ROLES = ['OWNER', 'MANAGER', 'SELLER'];

/**
 * Кто правит справочник наименований и смотрит отчёты. Тот же список
 * в @PreAuthorize: в отчётах лежат зарплатная база смены и себестоимость.
 */
export const NAMING_ROLES = ['OWNER', 'MANAGER'];

/**
 * Кто печатает этикетки. Кладовщик здесь есть: подписывать стеллажи —
 * его работа, и гонять за этим владельца значит не подписать их вовсе.
 */
export const LABEL_ROLES = ['OWNER', 'MANAGER', 'STOREKEEPER'];

/**
 * Кто перевозит между складами и смотрит журнал перевозок. Тот же список
 * в @PreAuthorize у POST /api/stock/moves: перевозит кладовщик наравне
 * с владельцем — деталь у него в руках, и перестановка между складами —
 * работа, а не расход.
 */
export const MOVE_ROLES = ['OWNER', 'MANAGER', 'STOREKEEPER'];

/**
 * Кто заводит данные: приёмка, машины, пересчёт, очередь отправки.
 *
 * <p>Здесь все, кроме «Просмотра». Роль эта названа владельцу «только
 * смотреть», и заводят её тому, кому дают посмотреть; форма приёмки,
 * открытая ей, — это работа, которую сервер отобьёт, а очередь пометит
 * «требует внимания». Экран, называющий действие и не дающий его сделать,
 * хуже отсутствующего экрана.
 */
export const WRITING_ROLES = ['OWNER', 'MANAGER', 'STOREKEEPER', 'SELLER'];

/**
 * Кто видит вкладку «Пересчёт»: все, кто заводит данные, и «Просмотр».
 *
 * <p>Журнал пересчётов раньше был недоступен «Просмотру» вовсе — вкладки
 * не было. А журнал склада ссылается на пересчёт («Пересчёт №4»), и
 * посмотреть, что тогда считали, — не то же самое, что провести или
 * отменить: то же разделение, что и на сервере (`InventoryController.READS`
 * против `RECONCILES`).
 */
export const INVENTORY_ROLES = [...WRITING_ROLES, 'VIEWER'];

/** Только владелец: сотрудники, склады, настройки. */
const OWNER_ONLY = ['OWNER'];

/**
 * Порядок здесь — порядок кнопок на рельсе. Приёмка первой: с неё начинают
 * смену, и открывается приложение на ней.
 */
export const TABS: readonly TabSpec[] = [
  { id: 'intake', label: 'Приёмка', section: 'Приёмка', roles: WRITING_ROLES },
  { id: 'donor', label: 'Машина', section: 'Машины', roles: WRITING_ROLES },
  { id: 'sales', label: 'Продажа', section: 'Продажа', roles: ANYONE },
  { id: 'orders', label: 'Заказы', section: 'Заказы с площадок', roles: SELLING_ROLES },
  { id: 'returns', label: 'Возвраты', section: 'Возвраты', roles: SELLING_ROLES },
  { id: 'customers', label: 'Клиенты', section: 'Клиенты', roles: SELLING_ROLES },
  { id: 'catalog', label: 'Склад', section: 'Склад', roles: ANYONE },
  { id: 'wheels', label: 'Шины и диски', section: 'Шины и диски', roles: ANYONE },
  { id: 'inventory', label: 'Пересчёт', section: 'Пересчёт склада', roles: INVENTORY_ROLES },
  { id: 'moves', label: 'Перевозки', section: 'Перевозки', roles: MOVE_ROLES },
  { id: 'outbox', label: 'Очередь', section: 'Очередь отправки', roles: WRITING_ROLES },
  { id: 'import', label: 'Загрузка', section: 'Загрузка склада', roles: WRITING_ROLES },
  { id: 'names', label: 'Наименования', section: 'Нераспознанные наименования', roles: ANYONE },
  { id: 'reports', label: 'Отчёты', section: 'Отчёты', roles: NAMING_ROLES },
  { id: 'feeds', label: 'Выгрузки', section: 'Выгрузки на площадки', roles: NAMING_ROLES },
  { id: 'delivery', label: 'Доставка', section: 'Доставка событий', roles: NAMING_ROLES },
  { id: 'labels', label: 'Этикетки', section: 'Этикетки', roles: LABEL_ROLES },
  { id: 'members', label: 'Сотрудники', section: 'Сотрудники', roles: OWNER_ONLY },
  { id: 'organization', label: 'Склады', section: 'Филиалы и склады', roles: OWNER_ONLY },
  { id: 'settings', label: 'Настройки', section: 'Настройки', roles: OWNER_ONLY },
  { id: 'reference', label: 'Справочники', section: 'Справочники', roles: ANYONE },
];

/** Виден ли раздел этой роли. */
export function visibleTo(spec: TabSpec, role: string): boolean {
  return spec.roles === ANYONE || spec.roles.includes(role);
}

/** Название раздела в верхней полосе: видно, где ты, не считая вкладки. */
export function sectionName(tab: string): string {
  return TABS.find((t) => t.id === tab)?.section ?? 'Справочники';
}
