import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { MembersScreen } from './MembersScreen';
import {
  closeBrowser, measureLayout, measureOverflow, openBrowser,
} from '../test/narrowViewport';

/**
 * «Сотрудники» на телефоне 390 и на мониторе 1280 — настоящим Chrome
 * (задача 0044, живой прогон 24.10.2026).
 *
 * <p><b>Как это выглядело.</b> На 390 таблица сотрудников уходила в свою
 * прокрутку (`scrollWidth` 609 при `clientWidth` 318): страница вбок не ехала,
 * и сторож ширины молчал, — а кнопка «Права» стояла за краем, и раскрытые
 * права растягивали таблицу до 605 px внутри прокрутки. На мониторе та же
 * таблица не помещалась в 40rem экрана, и край прокрутки резал «Выключить»
 * до «Выключи».
 *
 * <p><b>Что меряется.</b> Страница не шире экрана; ни прокрутка таблицы,
 * ни кнопки строки, ни раскрытые права не прячут содержимое за своим краем
 * (`measureOverflow`); каждая кнопка «Права» — внутри экрана.
 */

const MEMBERS = [
  { id: 1, number: 1, login: 'vladelec', displayName: 'Владислав', role: 'OWNER', active: true,
    lastLoginAt: '2026-10-24T03:28:55Z' },
  { id: 2, number: 2, login: 'mgr1', displayName: 'Мария Менеджерова', role: 'MANAGER',
    active: true, lastLoginAt: null },
  { id: 3, number: 3, login: 'kl1', displayName: 'Кирилл Кладовщиков', role: 'STOREKEEPER',
    active: true, lastLoginAt: null },
];

const ACCESS = [
  { memberId: 1, roleId: 10, roleName: 'Владелец', owner: true, added: [], removed: [], effective: [] },
  { memberId: 2, roleId: 11, roleName: 'Бухгалтер', owner: false, added: ['deal.sell'],
    removed: ['intake.receive'], effective: ['deal.sell'] },
  { memberId: 3, roleId: 12, roleName: 'Кладовщик', owner: false, added: [], removed: [],
    effective: ['intake.receive'] },
];

const ROLES = [
  { id: 10, name: 'Владелец', owner: true, permissions: [], members: 1 },
  { id: 11, name: 'Бухгалтер', owner: false, permissions: ['intake.receive'], members: 1 },
  { id: 12, name: 'Кладовщик', owner: false, permissions: ['intake.receive'], members: 1 },
  { id: 13, name: 'Временная', owner: false, permissions: [], members: 0 },
];

const CATALOG = [
  { title: 'Приёмка', permissions: [
    { key: 'intake.receive', title: 'Принимать товар', hint: 'приёмка с телефона, партии' }] },
  { title: 'Продажи', permissions: [
    { key: 'deal.sell', title: 'Продавать',
      hint: 'сделка, заказ с площадки, резерв, оплата, перенос, ссылка покупателю' }] },
];

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}

function stub(): void {
  forgetCatalog();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/api/members')) return json(MEMBERS);
    if (url.endsWith('/api/access/members')) return json(ACCESS);
    if (url.endsWith('/api/access/roles')) return json(ROLES);
    if (url.endsWith('/api/access/catalog')) return json(CATALOG);
    if (url.endsWith('/api/organization/admin-access')) return json({ grant: null, request: null });
    return json([]);
  }));
}

/** Экран догрузился: раскрытые права с галочками на месте. */
async function opened(): Promise<string> {
  render(<MembersScreen />);
  await waitFor(() => screen.getByRole('button', { name: 'Права: mgr1' }));
  await waitFor(() => screen.getByRole('button', { name: 'Удалить роль: Временная' }));
  fireEvent.click(screen.getByRole('button', { name: 'Права: mgr1' }));
  await waitFor(() => screen.getByLabelText(/Продавать/));
  return document.body.innerHTML;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('«Сотрудники» на телефоне и на мониторе', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  it.each([390, 1280])('%i px: ничего не спрятано за краем таблицы, «Права» на виду', async (width) => {
    stub();
    const html = await opened();
    const layout = await measureLayout(html, ['button[aria-label^="Права:"]', '.stack-table__wide .card'],
      width);
    const hidden = await measureOverflow(html,
      '.table-scroll, .stack-table__actions, .stack-table__actions button, .stack-table__wide .card',
      width);
    console.log(`ширина ${width}: страница ${layout.scrollWidth} при ${layout.clientWidth}; `
      + `спрятано за краем: ${JSON.stringify(hidden)}`);

    expect(layout.scrollWidth, `страница уехала вбок — ${layout.scrollWidth} при ${layout.clientWidth}`)
      .toBeLessThanOrEqual(layout.clientWidth);
    expect(hidden.map((h) => `${h.text.trim().slice(0, 20)}: ${h.scrollWidth} при ${h.clientWidth}`),
      'содержимое спрятано за краем своей коробки').toEqual([]);

    const rights = layout.boxes['button[aria-label^="Права:"]'] ?? [];
    expect(rights.length, 'кнопок «Права» не по строке на сотрудника').toBe(MEMBERS.length);
    rights.forEach((box, i) => {
      expect(box.right, `«Права» №${i + 1} за краем экрана — ${box.left}–${box.right}`)
        .toBeLessThanOrEqual(layout.clientWidth);
    });
    const editor = layout.boxes['.stack-table__wide .card'] ?? [];
    expect(editor.length, 'раскрытые права не нашлись').toBe(1);
    expect(editor[0]!.right, `раскрытые права за краем — ${editor[0]!.right}`)
      .toBeLessThanOrEqual(layout.clientWidth);
  }, 60_000);
});
