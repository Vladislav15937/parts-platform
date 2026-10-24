import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { rightsWith } from '../test/rights';
import { MembersScreen } from './MembersScreen';
import { MEMBERS_RIGHTS } from './tabs';
import {
  closeBrowser, measureBrokenWords, measureLayout, measureOverflow, measureRects, openBrowser,
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

function stub(members: unknown = MEMBERS, roles: unknown = ROLES, catalog: unknown = CATALOG): void {
  forgetCatalog();
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/api/members')) return json(members);
    if (url.endsWith('/api/access/members')) return json(ACCESS);
    if (url.endsWith('/api/access/roles')) return json(roles);
    if (url.endsWith('/api/access/catalog')) return json(catalog);
    if (url.endsWith('/api/organization/admin-access')) return json({ grant: null, request: null });
    return json([]);
  }));
}

/** Экран догрузился: раскрытые права с галочками на месте. */
/**
 * Запас ожидания: файл гоняется в полном наборе рядом с другими, поднимающими
 * Chrome, и секунды по умолчанию там не хватает (замерено: падал на первом
 * ожидании только внутри полного прогона).
 */
const SETTLE = { timeout: 10_000 };

async function opened(): Promise<string> {
  render(<MembersScreen />);
  await waitFor(() => screen.getByRole('button', { name: 'Права: mgr1' }), SETTLE);
  await waitFor(() => screen.getByRole('button', { name: 'Удалить роль: Временная' }), SETTLE);
  fireEvent.click(screen.getByRole('button', { name: 'Права: mgr1' }));
  await waitFor(() => screen.getByLabelText(/Продавать/), SETTLE);
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

  // Пароль, выключение и включение у сотрудника шире вошедшего погашены, причина —
  // видимым текстом сервера у строки (задача 0044, ответ 24.10, 1а). Причина длинная
  // (лишние права перечислены), а стоит в ряду кнопок, который уже уводил страницу.
  it.each([390, 1280])('%i px: погашенные пароль и выключение с причиной не уводят страницу и не за краем',
    async (width) => {
      const wider = 'У сотрудника прав больше, чем у вас: «Смотреть отчёты», «Править цены и карточку '
        + 'товара», «Списывать товар», «Перевозить товар между складами». Менять ему пароль, '
        + 'выключать и включать может тот, у кого прав не меньше';
      stub(MEMBERS.map((m) => ({ ...m,
        accountLocked: m.id === 1 ? 'Учётную запись владельца меняет только владелец'
          : m.id === 2 ? wider : null })));
      render(<MembersScreen ownerActs={false} mine={rightsWith(...MEMBERS_RIGHTS, 'intake.receive')} />);
      await waitFor(() => screen.getByText(wider), SETTLE);
      await waitFor(() => screen.getByRole('button', { name: 'Удалить роль: Временная' }), SETTLE);
      const html = document.body.innerHTML;
      const layout = await measureLayout(html, ['.stack-table__actions .locked-action__reason',
        '.stack-table__actions button'], width);
      const hidden = await measureOverflow(html,
        '.table-scroll, .stack-table__actions, .stack-table__actions .locked-action__reason', width);
      expect(layout.scrollWidth, `страница уехала вбок — ${layout.scrollWidth} при ${layout.clientWidth}`)
        .toBeLessThanOrEqual(layout.clientWidth);
      expect(layout.boxes['.stack-table__actions .locked-action__reason']?.length, 'причин нет').toBe(2);
      expect(hidden.map((h) => `${h.text.trim().slice(0, 20)}: ${h.scrollWidth} при ${h.clientWidth}`),
        'причина спрятана за краем').toEqual([]);
      (layout.boxes['.stack-table__actions button'] ?? []).forEach((box, i) => {
        expect(box.right, `кнопка №${i + 1} за краем экрана — ${box.left}–${box.right}`)
          .toBeLessThanOrEqual(layout.clientWidth);
      });
    }, 60_000);

  // Под кнопками роли две причины — «назначена» и «набор не поправить» — и на 390 они
  // склеивались в одно слово без пробела (живой прогон волны 24.10c).
  it('390 px: причины под кнопками роли — разными строками, не слитно', async () => {
    const locked = 'Набор роли «Кладовщик» не поправить: она назначена сотрудникам, у которых прав '
      + 'больше, чем у вас (Кирилл Кладовщиков (kl1, ID 3)), — новый набор сузил бы их. Править '
      + 'его может тот, у кого прав не меньше';
    stub(MEMBERS, ROLES.map((r) => (r.id === 12 ? { ...r, editLocked: locked } : r)));
    render(<MembersScreen ownerActs={false} mine={rightsWith(...MEMBERS_RIGHTS, 'intake.receive')} />);
    await waitFor(() => screen.getByText(locked), SETTLE);
    const rects = await measureRects(document.body.innerHTML, '.stack-table__actions > .note', 390);
    const assigned = rects.findIndex((r) => r.text.startsWith('Набор роли «Кладовщик»')) - 1;
    expect(assigned, 'причины роли не нашлись').toBeGreaterThanOrEqual(0);
    const [first, second] = [rects[assigned]!, rects[assigned + 1]!];
    expect(first.text, 'перед причиной набора — причина «назначена»').toMatch(/^Роль назначена/);
    expect(second.top, `вторая причина начинается на строке первой — ${first.bottom} / ${second.top}`)
      .toBeGreaterThanOrEqual(first.bottom - 1);
  }, 60_000);

  // Подписи прав на 390 рвались посреди слова: «недоставленно/е», «администратор/а» — во
  // flex-ряду название и пояснение были двумя узкими колонками (живой прогон волны 24.10c).
  it('390 px: подписи прав в «Правах» не рвутся посреди слова', async () => {
    const long = [
      { title: 'Выгрузки', permissions: [
        { key: 'delivery.manage', title: 'Повторять недоставленное на площадки',
          hint: 'раздел «Доставка»: повторить или снять' }] },
      { title: 'Компания', permissions: [
        { key: 'admin_access.manage', title: 'Пускать администратора сервиса',
          hint: 'дать, отозвать, отклонить запрос' },
        { key: 'members.accounts', title: 'Заводить сотрудников, менять им пароль, выключать и включать',
          hint: 'только тех, у кого нет прав сверх ваших' },
        { key: 'deal.sell', title: 'Продавать', hint: null }] },
    ];
    stub(MEMBERS, ROLES, long);
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: mgr1' }), SETTLE);
    fireEvent.click(screen.getByRole('button', { name: 'Права: mgr1' }));
    await waitFor(() => screen.getByLabelText(/Повторять недоставленное/), SETTLE);
    const broken = await measureBrokenWords(document.body.innerHTML, '.access-permissions label', 390);
    expect(broken, 'слова, разорванные переносом').toEqual([]);
  }, 60_000);

  // Погашенный блок доступа администратора (повторный живой прогон 24.10.2026):
  // причина видимым текстом не уводит страницу и не вылезает из своей коробки.
  it('390 px: погашенный блок доступа администратора на месте и не за краем', async () => {
    stub();
    render(<MembersScreen ownerActs={false} mine={rightsWith(...MEMBERS_RIGHTS, 'intake.receive')} />);
    await waitFor(() => screen.getByRole('button', { name: 'Дать доступ администратору' }), SETTLE);
    await waitFor(() => screen.getByRole('button', { name: 'Удалить роль: Временная' }), SETTLE);
    const html = document.body.innerHTML;
    const layout = await measureLayout(html, ['.locked-action', '.locked-action__reason'], 390);
    const hidden = await measureOverflow(html, '.locked-action, .locked-action__reason', 390);
    expect(layout.scrollWidth, `страница уехала вбок — ${layout.scrollWidth} при ${layout.clientWidth}`)
      .toBeLessThanOrEqual(layout.clientWidth);
    expect(layout.boxes['.locked-action__reason']?.length, 'причины нет').toBeGreaterThan(0);
    expect(hidden.map((h) => `${h.text.trim().slice(0, 20)}: ${h.scrollWidth} при ${h.clientWidth}`),
      'причина спрятана за краем').toEqual([]);
  }, 60_000);
});
