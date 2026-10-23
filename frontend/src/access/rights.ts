import { useEffect, useState } from 'react';
import { request } from '../api/client';
import type { Me } from '../api/auth';

/**
 * Права вошедшего — с сервера, а не списками ролей на экране (задача 0044).
 *
 * <p>До 0044 экран знал свои перечни ролей (`SELLING_ROLES`, `NAMING_ROLES`…)
 * рядом с такими же на сервере, и два перечня одного правила в этом проекте
 * расходились трижды. Теперь сервер присылает в `/api/auth/me` ключи прав,
 * а экран спрашивает только «есть ли ключ»: какие права у какой роли,
 * решает владелец на экране «Сотрудники», и экрану знать это незачем.
 *
 * <p><b>Ключи перечислены здесь типом</b> — чтобы опечатка в `has('deal.sel')`
 * не собралась. Тип сверяется со справочником сервера (`Permission`) тестом
 * на стороне сервера (`FrontendRightsTest`): ключ, переименованный только там,
 * краснит прогон, а не гасит кнопку молча.
 */
export type PermissionKey =
  | 'intake.receive'
  | 'photos.manage'
  | 'donor.costs'
  | 'supply.expected'
  | 'stock.move'
  | 'stock.write_off'
  | 'stock_document.delete_done'
  | 'part.edit'
  | 'part.publish'
  | 'part.cell'
  | 'part.money.view'
  | 'applicability.bulk'
  | 'names.match'
  | 'text_blocks.manage'
  | 'export.table'
  | 'wheels.set'
  | 'import.manage'
  | 'inventory.count'
  | 'inventory.counter_note'
  | 'inventory.reconcile'
  | 'inventory.journal.view'
  | 'deal.sell'
  | 'deal.issue'
  | 'deal.return'
  | 'deal_sources.manage'
  | 'customer.view'
  | 'customer.create'
  | 'customer.edit'
  | 'customer.account'
  | 'customer.account.correct'
  | 'payments.registry.view'
  | 'payment_sources.manage'
  | 'reports.view'
  | 'marketplace.view'
  | 'marketplace.configure'
  | 'marketplace.manage'
  | 'delivery.manage'
  | 'org.warehouses'
  | 'org.branches'
  | 'org.availability'
  | 'members.manage'
  | 'admin_access.manage'
  | 'settings.company'
  | 'journal.view';

/** Что может вошедший. */
export interface Rights {
  /** Роль «Владелец» или администратор сервиса: все права всегда. */
  owner: boolean;
  has(key: PermissionKey): boolean;
  /** Хоть одно из названных — для раздела, где есть и чтение, и действия. */
  any(...keys: PermissionKey[]): boolean;
}

export function rightsFrom(owner: boolean, keys: readonly string[]): Rights {
  const set = new Set(keys);
  const has = (key: PermissionKey) => owner || set.has(key);
  return { owner, has, any: (...wanted) => wanted.some(has) };
}

/**
 * Права вошедшего по ответу «кто я».
 *
 * <p><b>Личность без набора прав — из кэша телефона, запомненная до выкладки
 * 0044.</b> Приложение открывается без связи на запомненной личности
 * (`SessionProvider`), и приёмщик, у которого в ангаре нет сети в день
 * выкладки, получил бы погашенную приёмку при исправной системе. Поэтому
 * такой личности права даются по прежней роли — набором, равным раздаче
 * при переносе (`tenant/101`); сверяет его с раздачей тест сервера. Как только
 * связь появится, `auth.me` пришлёт настоящий набор и это место перестанет
 * действовать.
 */
export function rightsOf(me: Me): Rights {
  if (me.role === 'ADMIN') {
    return rightsFrom(true, []);
  }
  if (me.permissions === undefined) {
    return rightsFrom(me.role === 'OWNER', LEGACY_ROLE_PERMISSIONS[me.role] ?? []);
  }
  return rightsFrom(me.owner === true, me.permissions);
}

/**
 * Наборы прежних ролей — только для личности, запомненной до 0044
 * (см. {@link rightsOf}). Равны раздаче `tenant/101`; не правится руками.
 */
export const LEGACY_ROLE_PERMISSIONS: Readonly<Record<string, readonly PermissionKey[]>> = {
  MANAGER: [
    'intake.receive', 'photos.manage', 'donor.costs', 'stock.move', 'stock.write_off',
    'part.edit', 'part.publish', 'part.cell', 'part.money.view', 'names.match',
    'text_blocks.manage', 'export.table', 'wheels.set', 'inventory.count',
    'inventory.counter_note', 'inventory.reconcile', 'inventory.journal.view', 'deal.sell',
    'deal.issue', 'deal.return', 'customer.view', 'customer.create', 'customer.edit',
    'customer.account', 'customer.account.correct', 'payments.registry.view',
    'marketplace.view', 'marketplace.configure', 'reports.view', 'delivery.manage',
    'org.warehouses',
  ],
  STOREKEEPER: [
    'intake.receive', 'photos.manage', 'stock.move', 'part.cell', 'wheels.set',
    'inventory.count', 'inventory.counter_note', 'deal.issue',
  ],
  SELLER: [
    'intake.receive', 'photos.manage', 'inventory.count', 'deal.sell', 'deal.issue',
    'deal.return', 'customer.view', 'customer.create', 'customer.account',
  ],
  VIEWER: ['inventory.journal.view'],
  AUDITOR: ['journal.view'],
};

/** Действие справочника, как его называет сервер. */
export interface Action {
  key: PermissionKey;
  title: string;
  hint: string | null;
}

export interface Section {
  title: string;
  permissions: Action[];
}

/**
 * Справочник прав словами — с сервера (`GET /api/access/catalog`), один раз
 * на запуск: слова живут в коде сервера, и меняются они вместе со сборкой.
 * Своей копии слов у экрана нет — разошлась бы на первой правке.
 */
let catalogOnce: Promise<Section[]> | null = null;

export function loadCatalog(): Promise<Section[]> {
  if (catalogOnce === null) {
    catalogOnce = request<Section[]>('/api/access/catalog').catch((cause: unknown) => {
      // Отказ не запоминается: без связи справочник не приехал, и следующий
      // экран спросит снова, когда она появится.
      catalogOnce = null;
      throw cause;
    });
  }
  return catalogOnce;
}

/** Для тестов: каждый файл начинает с пустого справочника. */
export function forgetCatalog(): void {
  catalogOnce = null;
}

/**
 * Названия прав по ключу; пусто, пока справочник не приехал (без связи
 * причина называется общими словами — см. {@link lockedReason}).
 */
export function useCatalogTitles(): ReadonlyMap<string, string> {
  const [titles, setTitles] = useState<ReadonlyMap<string, string>>(() => new Map());
  useEffect(() => {
    let alive = true;
    loadCatalog()
      .then((sections) => {
        if (!alive) return;
        const found = new Map<string, string>();
        for (const section of sections) {
          for (const action of section.permissions) {
            found.set(action.key, action.title);
          }
        }
        setTitles(found);
      })
      .catch(() => {
        // Без связи — общие слова причины, а не пустота.
      });
    return () => {
      alive = false;
    };
  }, []);
  return titles;
}

/**
 * Причина погашенного: решение владельца 23.10.2026 (задача 0044, вопрос 3) —
 * недоступное показывается погашенным и называет причину, а не скрывается.
 */
export function lockedReason(key: PermissionKey, titles: ReadonlyMap<string, string>): string {
  const title = titles.get(key);
  return title === undefined
    ? 'Нет права на это действие — его даёт владелец на экране «Сотрудники»'
    : `Нет права «${title}» — его даёт владелец на экране «Сотрудники»`;
}
