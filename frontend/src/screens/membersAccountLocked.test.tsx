import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { rightsWith } from '../test/rights';
import { MembersScreen } from './MembersScreen';
import { MEMBERS_RIGHTS } from './tabs';

/**
 * Пароль, выключение и включение у сотрудника с правами шире, чем у вошедшего,
 * погашены с причиной (задача 0044, ответ владельца 24.10.2026 после волны, 1а;
 * решение 3 от 24.10: погашено с причиной, а не скрыто) — и каждое из четырёх
 * прав на раздачу гасит своё (ответ 24.10, «развёрнутые вопросы», 2а).
 *
 * <p>Признак и слова — сервера (`accountLocked` в списке сотрудников): экран
 * своих правил не держит. Проверяется тем, что видно и что уходит на сервер.
 */

const WIDER = 'У сотрудника прав больше, чем у вас: «Смотреть отчёты». '
  + 'Менять ему пароль, выключать и включать может тот, у кого прав не меньше';
const OWNER_ONLY = 'Учётную запись владельца меняет только владелец';
const ROLE_LOCKED = 'Набор роли «Старший» не поправить: она назначена сотрудникам, у которых прав '
  + 'больше, чем у вас (Мария (menedzher, ID 2)), — новый набор сузил бы их. Править его может тот, '
  + 'у кого прав не меньше';

const MEMBERS = [
  { id: 1, number: 1, login: 'vladelec', displayName: 'Владелец', role: 'OWNER', active: true,
    lastLoginAt: null, accountLocked: OWNER_ONLY },
  { id: 2, number: 2, login: 'menedzher', displayName: 'Менеджер', role: 'MANAGER', active: true,
    lastLoginAt: null, accountLocked: WIDER },
  { id: 3, number: 3, login: 'vyklyuchen', displayName: 'Выключенный менеджер', role: 'MANAGER',
    active: false, lastLoginAt: null, accountLocked: WIDER },
  { id: 4, number: 4, login: 'prodavec', displayName: 'Продавец', role: 'SELLER', active: true,
    lastLoginAt: null, accountLocked: null },
];

const CATALOG = [
  { title: 'Компания', permissions: [
    { key: 'members.accounts', title: 'Заводить сотрудников, менять им пароль, выключать и включать',
      hint: null },
    { key: 'members.assign_roles', title: 'Назначать сотрудникам готовые роли', hint: null },
    { key: 'members.roles', title: 'Заводить и править роли', hint: null },
    { key: 'members.grant_overrides', title: 'Выдавать и снимать права поверх роли', hint: null }] },
  { title: 'Продажи', permissions: [{ key: 'deal.sell', title: 'Продавать', hint: null }] },
];

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}

describe('учётная запись шире своих — погашена с причиной', () => {
  let sent: string[];

  beforeEach(() => {
    forgetCatalog();
    sent = [];
    vi.stubGlobal('prompt', () => 'новый-пароль-1');
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(url);
        return new Response(null, { status: 204 });
      }
      if (url.endsWith('/api/members')) return json(MEMBERS);
      if (url.endsWith('/api/access/members')) {
        return json(MEMBERS.map((m) => ({ memberId: m.id, roleId: m.id === 1 ? 10 : 11,
          roleName: m.id === 1 ? 'Владелец' : 'Продавец', owner: m.id === 1, added: [], removed: [],
          effective: ['deal.sell'] })));
      }
      if (url.endsWith('/api/access/roles')) {
        return json([
          { id: 10, name: 'Владелец', owner: true, permissions: [], members: 1 },
          { id: 11, name: 'Продавец', owner: false, permissions: ['deal.sell'], members: 3 },
          { id: 12, name: 'Старший', owner: false, permissions: ['deal.sell'], members: 1,
            editLocked: ROLE_LOCKED },
        ]);
      }
      if (url.endsWith('/api/access/catalog')) return json(CATALOG);
      if (url.endsWith('/api/organization/admin-access')) return json({ grant: null, request: null });
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  /** Строка сотрудника по логину — когда экран догрузился. */
  async function row(login: string): Promise<HTMLElement> {
    await waitFor(() => screen.getByText(login));
    return screen.getByText(login).closest('tr') as HTMLElement;
  }

  function button(where: HTMLElement, name: string): HTMLButtonElement {
    return within(where).getByRole('button', { name }) as HTMLButtonElement;
  }

  const kadrovik = rightsWith(...MEMBERS_RIGHTS, 'deal.sell');

  it('пароль менеджеру: кнопка на месте, погашена, причина видимым текстом; нажатие ничего не шлёт', async () => {
    render(<MembersScreen ownerActs={false} mine={kadrovik} />);
    const manager = await row('menedzher');
    const change = button(manager, 'Сменить пароль');
    expect(change.disabled, 'кнопка пароля у шире своих живая').toBe(true);
    expect(within(manager).getByText(WIDER), 'причина не показана видимым текстом').toBeTruthy();
    fireEvent.click(change);
    expect(sent).toEqual([]);
  });

  it('выключить менеджера: погашено с той же причиной', async () => {
    render(<MembersScreen ownerActs={false} mine={kadrovik} />);
    const manager = await row('menedzher');
    expect(button(manager, 'Выключить').disabled, 'выключение у шире своих живое').toBe(true);
    expect(button(manager, 'Выключить').title).toBe(WIDER);
  });

  it('включить выключенного менеджера: погашено с причиной', async () => {
    render(<MembersScreen ownerActs={false} mine={kadrovik} />);
    const disabled = await row('vyklyuchen');
    expect(button(disabled, 'Включить').disabled, 'включение у шире своих живое').toBe(true);
    expect(within(disabled).getByText(WIDER)).toBeTruthy();
  });

  it('владелец назван словами сервера, продавцу всё живое и уходит на сервер', async () => {
    render(<MembersScreen ownerActs={false} mine={kadrovik} />);
    const owner = await row('vladelec');
    expect(button(owner, 'Сменить пароль').disabled).toBe(true);
    expect(within(owner).getByText(OWNER_ONLY)).toBeTruthy();

    const seller = await row('prodavec');
    expect(button(seller, 'Сменить пароль').disabled).toBe(false);
    expect(within(seller).queryByText(/прав больше/)).toBeNull();
    fireEvent.click(button(seller, 'Выключить'));
    await waitFor(() => expect(sent).toEqual(['/api/members/4/disable']));
  });

  it('роль и права сотрудника шире своих: «Права» погашены с той же причиной; продавцу — живые', async () => {
    render(<MembersScreen ownerActs={false} mine={kadrovik} />);
    const manager = await row('menedzher');
    expect(button(manager, 'Права: menedzher').disabled, '«Права» у шире своих живые').toBe(true);
    expect(button(manager, 'Права: menedzher').title).toBe(WIDER);
    const seller = await row('prodavec');
    expect(button(seller, 'Права: prodavec').disabled).toBe(false);
  });

  it('роль, назначенная тем, кто шире: «Изменить» живое — переименовать можно, набор погашен с причиной', async () => {
    render(<MembersScreen ownerActs={false} mine={kadrovik} />);
    await waitFor(() => screen.getByRole('button', { name: 'Изменить роль: Старший' }));
    const row = screen.getByRole('button', { name: 'Изменить роль: Старший' }).closest('tr') as HTMLElement;
    expect(within(row).getByText(ROLE_LOCKED), 'причина у строки роли').toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Изменить роль: Старший' }));
    const save = screen.getByRole('button', { name: 'Сохранить роль' }) as HTMLButtonElement;
    const editor = save.closest('.card') as HTMLElement;
    const title = within(editor).getByLabelText('Название роли') as HTMLInputElement;
    expect(title.disabled, 'название роли погашено').toBe(false);
    expect((within(editor).getByLabelText(/Продавать/) as HTMLInputElement).disabled, 'набор живой').toBe(true);
    expect((within(editor).getByLabelText(/Продавать/) as HTMLInputElement).checked, 'набор показан не тем').toBe(true);
    expect(within(editor).getByText(ROLE_LOCKED)).toBeTruthy();
    fireEvent.change(title, { target: { value: 'Старший смены' } });
    expect(save.disabled, 'переименование погашено').toBe(false);
  });

  it('без права на учётные записи пароль и выключение погашены у всех, причина одна; «Завести» погашено', async () => {
    render(<MembersScreen ownerActs={false}
      mine={rightsWith('members.assign_roles', 'members.roles', 'members.grant_overrides')} />);
    const seller = await row('prodavec');
    expect(button(seller, 'Сменить пароль').disabled).toBe(true);
    await waitFor(() => expect(screen.getAllByText(/Нет права «Заводить сотрудников, менять им пароль/)
      .length).toBeGreaterThan(0));
    const create = screen.getByRole('heading', { name: 'Новый сотрудник' }).closest('.card') as HTMLElement;
    expect(button(create, 'Завести').disabled).toBe(true);
    expect(within(create).queryByLabelText('Логин'), 'форма заведения без права').toBeNull();
  });

  it('только «Заводить сотрудников…»: «Права» погашены с причиной — роль и права поверх менять нечем', async () => {
    render(<MembersScreen ownerActs={false} mine={rightsWith('members.accounts', 'deal.sell')} />);
    const seller = await row('prodavec');
    expect(button(seller, 'Права: prodavec').disabled, '«Права» живые без обоих прав').toBe(true);
    expect(button(seller, 'Сменить пароль').disabled).toBe(false);
    await waitFor(() => expect(screen.getByText(/«Права»: Нет ни одного из прав: «Назначать сотрудникам готовые роли»/))
      .toBeTruthy());
  });

  it('без права на роли «Новая роль» погашена с причиной, а не спрятана', async () => {
    render(<MembersScreen ownerActs={false}
      mine={rightsWith('members.accounts', 'members.assign_roles', 'members.grant_overrides')} />);
    await row('prodavec');
    await waitFor(() => expect((screen.getByRole('button', { name: 'Новая роль' }) as HTMLButtonElement)
      .disabled).toBe(true));
    expect(screen.getByText(/Нет права «Заводить и править роли»/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Изменить роль: Продавец' }) as HTMLButtonElement)
      .disabled).toBe(true);
  });

  it('без права назначать роли выбор роли погашен с причиной', async () => {
    render(<MembersScreen ownerActs={false} mine={rightsWith('members.grant_overrides', 'deal.sell')} />);
    await row('prodavec');
    fireEvent.click(screen.getByRole('button', { name: 'Права: prodavec' }));
    const editor = screen.getByRole('button', { name: 'Сохранить права' }).closest('.card') as HTMLElement;
    expect((within(editor).getByLabelText('Роль') as HTMLSelectElement).disabled).toBe(true);
    expect(within(editor).getByText(/Нет права «Назначать сотрудникам готовые роли»/)).toBeTruthy();
    expect((within(editor).getByLabelText(/Продавать/) as HTMLInputElement).disabled).toBe(false);
  });

  it('без права на права поверх роли галочки погашены с причиной', async () => {
    render(<MembersScreen ownerActs={false} mine={rightsWith('members.assign_roles', 'deal.sell')} />);
    await row('prodavec');
    fireEvent.click(screen.getByRole('button', { name: 'Права: prodavec' }));
    const editor = screen.getByRole('button', { name: 'Сохранить права' }).closest('.card') as HTMLElement;
    expect((within(editor).getByLabelText('Роль') as HTMLSelectElement).disabled).toBe(false);
    expect((within(editor).getByLabelText(/Продавать/) as HTMLInputElement).disabled).toBe(true);
    expect(within(editor).getByText(/Нет права «Выдавать и снимать права поверх роли»/)).toBeTruthy();
  });
});
