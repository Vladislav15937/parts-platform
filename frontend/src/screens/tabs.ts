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
  | 'deals'
  | 'orders'
  | 'returns'
  | 'customers'
  | 'payments'
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
  | 'journal'
  | 'members'
  | 'organization'
  | 'settings'
  | 'reference';

import type { PermissionKey, Rights } from '../access/rights';

export interface TabSpec {
  id: Tab;
  /** Слово на рельсе — короткое: «Очередь», а не «Очередь отправки». */
  label: string;
  /** Название раздела в верхней полосе и в заголовке вкладки браузера. */
  section: string;
  /**
   * Какое право открывает раздел — хоть одно из названных (у «Пересчёта»
   * есть и чтение журнала, и обход, и сведение). Те же ключи, что
   * в `@PreAuthorize` на сервере; списков ролей у экрана нет (задача 0044):
   * какие права у какой роли, решает владелец.
   */
  needs: readonly PermissionKey[] | typeof ANYONE;
}

/**
 * Раздел, открытый любому вошедшему.
 *
 * <p>Именно так, а не перечислением прав: «всем» — это утверждение,
 * а список был бы догадкой о том, какие права бывают.
 */
const ANYONE = 'ANYONE';

/**
 * Порядок здесь — порядок кнопок на рельсе. Приёмка первой: с неё начинают
 * смену, и открывается приложение на ней.
 *
 * <p><b>Этикетки — по праву «Раскладывать по полкам и печатать этикетки»</b>
 * (`part.cell`): своего права у печати нет (на сервере она ничего не меняет),
 * а до 0044 этикетки печатали ровно владелец, менеджер и кладовщик — те же,
 * у кого право на ячейку. Поэтому право и названо обоими действиями.
 * Решение исполнителя, названо в PR.
 */
export const TABS: readonly TabSpec[] = [
  { id: 'intake', label: 'Приёмка', section: 'Приёмка', needs: ['intake.receive'] },
  { id: 'donor', label: 'Машина', section: 'Машины', needs: ['intake.receive'] },
  { id: 'sales', label: 'Продажа', section: 'Продажа', needs: ANYONE },
  // Сразу за «Продажей»: это её оборотная сторона — то, что уже отложено
  // и ждёт действия. Право то же, что у эндпоинта (`SalesController.SELLS`):
  // список несёт клиента, суммы и долг по всему складу.
  { id: 'deals', label: 'Сделки', section: 'Сделки', needs: ['deal.sell'] },
  { id: 'orders', label: 'Заказы', section: 'Заказы с площадок', needs: ['deal.sell'] },
  { id: 'returns', label: 'Возвраты', section: 'Возвраты', needs: ['deal.return'] },
  { id: 'customers', label: 'Клиенты', section: 'Клиенты', needs: ['customer.view'] },
  // Реестр кассы (задача 0045) — рядом с клиентами и сделками, потому что
  // деньги приходят оттуда, но право у него своё: в списке видно всё,
  // чем живёт компания, включая возвраты и выдачи с лицевых счетов.
  { id: 'payments', label: 'Платежи', section: 'Платежи', needs: ['payments.registry.view'] },
  { id: 'catalog', label: 'Склад', section: 'Склад', needs: ANYONE },
  { id: 'wheels', label: 'Шины и диски', section: 'Шины и диски', needs: ANYONE },
  {
    id: 'inventory', label: 'Пересчёт', section: 'Пересчёт склада',
    needs: ['inventory.count', 'inventory.journal.view', 'inventory.reconcile'],
  },
  { id: 'moves', label: 'Перевозки', section: 'Перевозки', needs: ['stock.move'] },
  {
    id: 'outbox', label: 'Очередь', section: 'Очередь отправки',
    needs: ['intake.receive', 'inventory.count', 'inventory.counter_note'],
  },
  { id: 'import', label: 'Загрузка', section: 'Загрузка склада', needs: ['import.manage'] },
  { id: 'names', label: 'Наименования', section: 'Нераспознанные наименования', needs: ANYONE },
  { id: 'reports', label: 'Отчёты', section: 'Отчёты', needs: ['reports.view'] },
  { id: 'feeds', label: 'Выгрузки', section: 'Выгрузки на площадки', needs: ['marketplace.view'] },
  { id: 'delivery', label: 'Доставка', section: 'Доставка событий', needs: ['delivery.manage'] },
  { id: 'labels', label: 'Этикетки', section: 'Этикетки', needs: ['part.cell'] },
  // Перед разделами владельца: журнал — про людей компании, и открывают его
  // оттуда же, откуда смотрят на сотрудников.
  { id: 'journal', label: 'Журнал', section: 'Журнал действий', needs: ['journal.view'] },
  { id: 'members', label: 'Сотрудники', section: 'Сотрудники', needs: ['members.manage'] },
  {
    id: 'organization', label: 'Склады', section: 'Филиалы и склады',
    needs: ['org.warehouses', 'org.branches', 'org.availability'],
  },
  { id: 'settings', label: 'Настройки', section: 'Настройки', needs: ['settings.company'] },
  { id: 'reference', label: 'Справочники', section: 'Справочники', needs: ANYONE },
];

/** Открыт ли раздел этим правам. */
export function allowed(spec: TabSpec, rights: Rights): boolean {
  return spec.needs === ANYONE || rights.any(...spec.needs);
}

/** Права, которые назовёт причина погашенного раздела; у открытого всем — нет. */
export function neededFor(spec: TabSpec): readonly PermissionKey[] {
  return spec.needs === ANYONE ? [] : spec.needs;
}

/** Название раздела в верхней полосе: видно, где ты, не считая вкладки. */
export function sectionName(tab: string): string {
  return TABS.find((t) => t.id === tab)?.section ?? 'Справочники';
}
