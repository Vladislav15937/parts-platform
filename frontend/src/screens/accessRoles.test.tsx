import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { MembersScreen } from './MembersScreen';

/**
 * Роли клиента и права сотрудника на экране «Сотрудники» (задача 0044).
 *
 * <p>Решения владельца 23.10.2026: клиент заводит роль — название и набор
 * прав — и назначает её одному или нескольким сотрудникам; связь живая;
 * поверх роли сотруднику добавляют и снимают отдельное право; встроенная
 * роль одна — «Владелец», у неё все права всегда. Проверяется тем, что уходит
 * на сервер, а не тем, что компонент вызвался.
 */
describe('роли и права на экране сотрудников', () => {
  let sent: Array<{ url: string; method: string; body: unknown }>;

  beforeEach(() => {
    forgetCatalog();
    sent = [];
    vi.stubGlobal('confirm', () => true);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : null });
        return json({ id: 30, name: 'x', owner: false, permissions: [], members: 0 });
      }
      if (url.endsWith('/api/members')) {
        return json([
          { id: 1, number: 1, login: 'vladelec', displayName: 'Пётр', role: 'OWNER', active: true, lastLoginAt: null },
          { id: 2, number: 2, login: 'ivan', displayName: 'Иван', role: 'VIEWER', active: true, lastLoginAt: null },
        ]);
      }
      if (url.endsWith('/api/access/members')) {
        return json([
          { memberId: 1, roleId: 10, roleName: 'Владелец', owner: true, added: [], removed: [], effective: [] },
          { memberId: 2, roleId: 11, roleName: 'Приёмщик', owner: false, added: ['deal.sell'],
            removed: [], effective: ['intake.receive', 'deal.sell'] },
        ]);
      }
      if (url.endsWith('/api/access/roles')) {
        return json([
          { id: 10, name: 'Владелец', owner: true, permissions: [], members: 1 },
          { id: 11, name: 'Приёмщик', owner: false, permissions: ['intake.receive'], members: 3 },
        ]);
      }
      if (url.endsWith('/api/access/catalog')) {
        return json([
          { title: 'Приёмка', permissions: [
            { key: 'intake.receive', title: 'Принимать товар', hint: null }] },
          { title: 'Продажи', permissions: [
            { key: 'deal.sell', title: 'Продавать', hint: null },
            { key: 'deal.return', title: 'Оформлять возврат', hint: null }] },
        ]);
      }
      if (url.endsWith('/api/organization/admin-access')) {
        return json({ grant: null, request: null });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('роль сотрудника названа с отдельными правами, у «Владельца» нет кнопок правки', async () => {
    render(<MembersScreen />);
    await waitFor(() => expect(screen.getByText('Приёмщик · отдельно: 1')).toBeTruthy());
    expect(screen.getByText('встроенная: не правится и не удаляется')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Изменить роль: Владелец' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Изменить роль: Приёмщик' })).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Удалить роль: Приёмщик' }) as HTMLButtonElement)
      .disabled, 'назначенную роль предлагают удалить').toBe(true);
  });

  it('новая роль уходит названием и набором ключей', async () => {
    render(<MembersScreen />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Новая роль' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Новая роль' }));
    fireEvent.change(screen.getByLabelText('Название роли'), { target: { value: ' Продавец-2 ' } });
    fireEvent.click(screen.getByLabelText(/Продавать/));
    fireEvent.click(screen.getByLabelText(/Оформлять возврат/));
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить роль' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toEqual({ url: '/api/access/roles', method: 'POST',
      body: { name: 'Продавец-2', permissions: ['deal.return', 'deal.sell'] } });
  });

  it('правка назначенной роли предупреждает, что поменяются права у всех и закроются входы', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Изменить роль: Приёмщик' }));
    fireEvent.click(screen.getByRole('button', { name: 'Изменить роль: Приёмщик' }));
    expect(screen.getByText(/Новый набор прав получат все они/)).toBeTruthy();
    expect(screen.getByText(/Роль назначена: 3.сотрудника/)).toBeTruthy();
  });

  it('права сотрудника: отмеченное сверх роли — добавлено, снятое из роли — снято', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: ivan' }));
    fireEvent.click(screen.getByRole('button', { name: 'Права: ivan' }));

    const editor = screen.getByRole('button', { name: 'Сохранить права' }).closest('.card') as HTMLElement;
    expect(within(editor).getByText(/добавлено поверх роли/)).toBeTruthy();
    fireEvent.click(within(editor).getByLabelText(/Принимать товар/));
    fireEvent.click(within(editor).getByLabelText(/Оформлять возврат/));
    expect(within(editor).getByText(/снято поверх роли/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить права' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toEqual({ url: '/api/access/members/2', method: 'PUT',
      body: { roleId: 11, overrides: {
        'intake.receive': false, 'deal.sell': true, 'deal.return': true } } });
  });

  it('у владельца галочки погашены с причиной, отдельных прав не уходит', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: vladelec' }));
    fireEvent.click(screen.getByRole('button', { name: 'Права: vladelec' }));

    expect(screen.getByText(/У владельца все права всегда/)).toBeTruthy();
    const box = screen.getByLabelText(/Продавать/) as HTMLInputElement;
    expect(box.disabled && box.checked, 'у владельца право снимается галочкой').toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить права' }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]?.body).toEqual({ roleId: 10, overrides: {} });
  });

  it('не владелец роль «Владелец» не назначает: пункт погашен и назван', async () => {
    render(<MembersScreen ownerActs={false} />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: ivan' }));
    const option = screen.getAllByRole('option', { name: 'Владелец — назначает только владелец' })[0] as HTMLOptionElement;
    expect(option.disabled).toBe(true);
  });

  it('нового сотрудника не завести без выбранной роли; роль уходит ссылкой', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Завести' }));
    fireEvent.change(screen.getByLabelText('Логин'), { target: { value: 'novyy' } });
    fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: 'восемь-знаков' } });
    const add = screen.getByRole('button', { name: 'Завести' }) as HTMLButtonElement;
    expect(add.disabled, 'роль подставлена молча').toBe(true);
    expect(screen.getByText('Выберите роль сотрудника.')).toBeTruthy();

    const card = add.closest('.card') as HTMLElement;
    fireEvent.change(within(card).getByLabelText('Роль'), { target: { value: '11' } });
    fireEvent.click(add);
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]?.body).toEqual({ login: 'novyy', password: 'восемь-знаков', displayName: '', roleId: 11 });
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
