import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { DonorCosts } from './DonorCosts';
import { DonorScreen } from './DonorScreen';
import { IntakeScreen } from './IntakeScreen';
import { InventoryReconcile } from './InventoryReconcile';
import { MembersScreen } from './MembersScreen';
import { OrganizationScreen } from './OrganizationScreen';
import { OutboxScreen } from './OutboxScreen';
import { ReportsScreen } from './ReportsScreen';
import { SettingsScreen } from './SettingsScreen';
import { PHONE_WIDTH, closeBrowser, measurePage, openBrowser } from '../test/narrowViewport';

/**
 * Страница не уезжает вбок на телефоне (задача 0027).
 *
 * <p><b>Как выглядело для человека.</b> Владелец открывает «Пересчёт»
 * с телефона, ведёт список вниз — и страница всё время сползает вбок:
 * уезжает не таблица внутри своей обёртки, а вся страница вместе с рельсом
 * и шапкой. Замерено на экране 386: `scrollWidth` 404 при `clientWidth` 386.
 *
 * <p><b>Причина — общий `div.row`.</b> Флекс-строка без `flex-wrap`: её
 * минимальная ширина это сумма минимумов элементов плюс зазор, и «Склад»
 * с кнопкой «Найти пересчёт» просят 368 при 322 доступных. `.screen` —
 * растянутый элемент flex-колонки, он не обрезается по родителю, а растёт
 * под содержимое, и вбок уезжает страница целиком. Класс общий, поэтому
 * здесь меряется не один экран, а все, у которых `div.row` виден сразу
 * при открытии.
 *
 * <p><b>Меряет настоящий браузер.</b> jsdom раскладку не считает вовсе —
 * `scrollWidth` и `clientWidth` у него всегда 0, — и такой тест зеленел бы
 * на любой вёрстке. Разметку экраны дают настоящую: они отрисовываются здесь
 * же, как в дымовом проходе, а измеряется их разметка в оболочке приложения.
 */
const EMPTY: Record<string, unknown> = { total: 0, rows: [], warehouses: [], items: [] };

describe('на телефоне страница не уезжает вбок', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const listLike = ['/api/members', '/api/organization', '/api/deals/sources',
        '/api/deals/services', '/api/catalog/vehicles', '/api/intake/donors',
        '/api/payment-sources', '/api/intake/donors/1/costs'];
      const body: unknown = listLike.some((path) => url.includes(path))
        ? []
        : url.includes('/api/reports/')
          ? {
              month: '2026-08',
              rows: [],
              totals: {
                advances: 0, debts: 0, withAdvance: 0, withDebt: 0, problems: [],
                donors: 0, totalCost: 0, revenue: 0, stockValue: 0,
              },
              parts: { qty: 0, amount: 0 },
              wheels: { qty: 0, amount: 0 },
              deals: { count: 0, amount: 0, prepaid: 0 },
            }
          : EMPTY;
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  const screens: Array<[string, () => JSX.Element]> = [
    // Тот самый экран, на котором находка замерена: «Склад» и «Найти пересчёт»
    // одной строкой.
    ['Пересчёт', () => <InventoryReconcile reference={reference()} />],
    ['Приёмка', () => <IntakeScreen reference={reference()} onSend={() => {}} />],
    ['Машины', () => (
      <DonorScreen reference={reference()} online onChanged={() => {}} />
    )],
    ['Затраты по машине', () => <DonorCosts donorId={1} title="Toyota Camry" />],
    ['Сотрудники', () => <MembersScreen />],
    ['Склады', () => <OrganizationScreen />],
    ['Настройки', () => <SettingsScreen />],
    ['Отчёты', () => <ReportsScreen canRead />],
    ['Очередь отправки', () => (
      <OutboxScreen
        records={[{
          id: 1, kind: 'receipt', title: 'Партия из 3 позиций', state: 'failed',
          lastError: 'Сервер не отвечает', attempts: 3,
        } as never]}
        needsSignIn={false}
        onRetry={() => {}}
        onDrop={() => {}}
      />
    )],
  ];

  it.each(screens)('%s помещается в экран 386', async (name, make) => {
    const { container } = render(make());
    await waitFor(() => expect(container.textContent).not.toBe(''));

    // Без этого проверка была бы пустой: экран без `div.row` ничего
    // не доказывает про `div.row`.
    await waitFor(() => expect(
      container.querySelector('.row'),
      `${name}: на экране нет ни одного div.row — мерить нечего`,
    ).not.toBeNull());

    const { scrollWidth, clientWidth } = await measurePage(container.innerHTML);

    expect(
      scrollWidth,
      `${name}: страница уезжает вбок на ${scrollWidth - clientWidth} пикселей `
      + `(scrollWidth ${scrollWidth} при clientWidth ${clientWidth}, экран ${PHONE_WIDTH})`,
    ).toBe(clientWidth);
  }, 30_000);
});

function reference(): never {
  return {
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [{ id: 7, code: 'A-01-1' }] }],
    supplies: [], donors: [], cells: [{ id: 7, code: 'A-01-1', warehouseId: 2 }],
    partNames: [],
  } as never;
}
