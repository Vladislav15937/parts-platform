import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { rightsWith } from '../test/rights';
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
  let rolesAnswer: () => Response;
  let membersAnswer: () => Response;
  let catalogFails: boolean;

  beforeEach(() => {
    forgetCatalog();
    sent = [];
    catalogFails = false;
    rolesAnswer = () => json(ROLES);
    membersAnswer = () => json(MEMBERS);
    vi.stubGlobal('confirm', () => true);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : null });
        return json({ id: 30, name: 'x', owner: false, permissions: [], members: 0 });
      }
      if (url.endsWith('/api/members')) {
        return membersAnswer();
      }
      if (url.endsWith('/api/access/members')) {
        return json([
          { memberId: 1, roleId: 10, roleName: 'Владелец', owner: true, added: [], removed: [], effective: [] },
          { memberId: 2, roleId: 11, roleName: 'Приёмщик', owner: false, added: ['deal.sell'],
            removed: [], effective: ['intake.receive', 'deal.sell'] },
        ]);
      }
      if (url.endsWith('/api/access/roles')) {
        return rolesAnswer();
      }
      if (url.endsWith('/api/access/catalog')) {
        if (catalogFails) return new Response('{"message":"нет"}', { status: 500 });
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

  it('правка роли уходит PUT с новым названием и набором ключей', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Изменить роль: Приёмщик' }));
    fireEvent.click(screen.getByRole('button', { name: 'Изменить роль: Приёмщик' }));
    fireEvent.change(screen.getByLabelText('Название роли'), { target: { value: 'Приёмщик-2' } });
    const editor = screen.getByRole('button', { name: 'Сохранить роль' }).closest('.card') as HTMLElement;
    fireEvent.click(within(editor).getByLabelText(/Продавать/));
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить роль' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toEqual({ url: '/api/access/roles/11', method: 'PUT',
      body: { name: 'Приёмщик-2', permissions: ['deal.sell', 'intake.receive'] } });
  });

  it('без справочника прав роль не сохранить: сказано почему, набор не затирается', async () => {
    catalogFails = true;
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Изменить роль: Приёмщик' }));
    fireEvent.click(screen.getByRole('button', { name: 'Изменить роль: Приёмщик' }));

    const save = screen.getByRole('button', { name: 'Сохранить роль' }) as HTMLButtonElement;
    const editor = save.closest('.card') as HTMLElement;
    expect(within(editor).getByText('Список прав не загрузился — обновите страницу.')).toBeTruthy();
    expect(save.disabled, 'роль без справочника сохраняется пустым набором').toBe(true);
    fireEvent.click(save);
    expect(sent).toEqual([]);
  });

  it('свободная роль удаляется DELETE по своему номеру', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Удалить роль: Пустая' }));
    fireEvent.click(screen.getByRole('button', { name: 'Удалить роль: Пустая' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toEqual({ url: '/api/access/roles/12', method: 'DELETE', body: null });
  });

  it('право роли, оставленное отмеченным, поверх роли не пишется', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: ivan' }));
    fireEvent.click(screen.getByRole('button', { name: 'Права: ivan' }));
    const editor = screen.getByRole('button', { name: 'Сохранить права' }).closest('.card') as HTMLElement;
    // «Принимать товар» — право роли, оно остаётся отмеченным; добавляется возврат.
    expect((within(editor).getByLabelText(/Принимать товар/) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(within(editor).getByLabelText(/Оформлять возврат/));
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить права' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]?.body).toEqual({ roleId: 11, overrides: { 'deal.sell': true, 'deal.return': true } });
  });

  it('отказ ролей не стирается успехом списка сотрудников', async () => {
    rolesAnswer = () => new Response(JSON.stringify({ message: 'Роли не загрузились: сервер занят' }),
      { status: 409, headers: { 'Content-Type': 'application/json' } });
    // Список сотрудников приходит позже отказа ролей: так его успех и стирал
    // общую строку ошибки, пока она была одна на три запроса.
    membersAnswer = () => new Promise<Response>((resolve) => {
      setTimeout(() => resolve(json(MEMBERS)), 80);
    }) as unknown as Response;
    render(<MembersScreen />);
    await waitFor(() => expect(screen.getByText('ivan')).toBeTruthy());
    await waitFor(() => expect(screen.getAllByText(/Роли не загрузились/).length).toBeGreaterThan(0));
    expect(screen.queryByText('Ролей нет — заведите первую.'),
      'отказ выдан за пустой список').toBeNull();
  });

  it('пустой список ролей так и назван, а пока грузим — «Загружаем…»', async () => {
    let release: (r: Response) => void = () => {};
    rolesAnswer = () => json([]);
    membersAnswer = () => json(MEMBERS);
    const pending = new Promise<Response>((resolve) => { release = resolve; });
    const original = rolesAnswer;
    rolesAnswer = () => pending as unknown as Response;
    render(<MembersScreen />);
    const roles = await waitFor(() => screen.getByText('Роли').closest('section') as HTMLElement);
    expect(within(roles).getByText('Загружаем…')).toBeTruthy();
    release(original());
    await waitFor(() => expect(within(roles).getByText('Ролей нет — заведите первую.')).toBeTruthy());
  });

  it('не владельцу пароль и выключение владельца погашены и названы', async () => {
    render(<MembersScreen ownerActs={false} />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: vladelec' }));
    const row = screen.getByRole('button', { name: 'Права: vladelec' }).closest('tr') as HTMLElement;
    expect((within(row).getByRole('button', { name: 'Сменить пароль' }) as HTMLButtonElement).disabled)
      .toBe(true);
    expect((within(row).getByRole('button', { name: 'Выключить' }) as HTMLButtonElement).disabled)
      .toBe(true);
    expect(within(row).getByText('Учётную запись владельца меняет только владелец')).toBeTruthy();
    const other = screen.getByRole('button', { name: 'Права: ivan' }).closest('tr') as HTMLElement;
    expect((within(other).getByRole('button', { name: 'Сменить пароль' }) as HTMLButtonElement).disabled)
      .toBe(false);
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

  // Ответ владельца 24.10.2026 (2б): выдавать можно только свои права. Решает
  // сервер; экран гасит заранее и называет причину.
  describe('выдавать можно только свои права', () => {
    it('роль шире своих в выборе нового сотрудника погашена и названа', async () => {
      render(<MembersScreen ownerActs={false} mine={rightsWith('members.manage', 'intake.receive')} />);
      await waitFor(() => screen.getByRole('button', { name: 'Завести' }));
      const card = screen.getByRole('button', { name: 'Завести' }).closest('.card') as HTMLElement;
      const wide = within(card).getByRole('option', { name: 'Пустая — шире ваших прав' }) as HTMLOptionElement;
      expect(wide.disabled, 'роль шире своих предлагают назначить').toBe(true);
      expect((within(card).getByRole('option', { name: 'Приёмщик' }) as HTMLOptionElement).disabled)
        .toBe(false);
      expect(within(card).getByText(/Роли шире ваших прав погашены/)).toBeTruthy();
    });

    it('чужое право поверх роли: сохранить нельзя, сказано какое; снял — уходит', async () => {
      // У ivan поверх «Приёмщика» добавлено «Продавать», а у вошедшего его нет.
      render(<MembersScreen ownerActs={false} mine={rightsWith('members.manage', 'intake.receive')} />);
      await waitFor(() => screen.getByRole('button', { name: 'Права: ivan' }));
      fireEvent.click(screen.getByRole('button', { name: 'Права: ivan' }));
      const save = screen.getByRole('button', { name: 'Сохранить права' }) as HTMLButtonElement;
      const editor = save.closest('.card') as HTMLElement;

      expect(save.disabled, 'чужое право поверх роли уходит на сервер').toBe(true);
      expect(within(editor).getByText(/Отмечены права, которых у вас нет: «Продавать»/)).toBeTruthy();
      const notMine = within(editor).getByLabelText(/Оформлять возврат/) as HTMLInputElement;
      expect(notMine.disabled, 'чужое право можно отметить').toBe(true);
      expect(within(editor).getAllByText(/этого права у вас нет/).length).toBeGreaterThan(0);

      // Снять отмеченное чужое можно — иначе набор было бы не сузить.
      fireEvent.click(within(editor).getByLabelText(/Продавать/));
      expect(save.disabled).toBe(false);
      fireEvent.click(save);
      await waitFor(() => expect(sent).toHaveLength(1));
      expect(sent[0]?.body).toEqual({ roleId: 11, overrides: {} });
    });

    it('роль с набором шире своих: новой не отметить чужое, старую не сохранить, пока не снято', async () => {
      render(<MembersScreen ownerActs={false} mine={rightsWith('members.manage', 'intake.receive')} />);
      await waitFor(() => screen.getByRole('button', { name: 'Изменить роль: Пустая' }));
      fireEvent.click(screen.getByRole('button', { name: 'Изменить роль: Пустая' }));
      const save = screen.getByRole('button', { name: 'Сохранить роль' }) as HTMLButtonElement;
      const editor = save.closest('.card') as HTMLElement;
      expect(save.disabled, 'набор шире своих уходит на сервер').toBe(true);
      expect(within(editor).getByText(/В наборе есть права, которых у вас нет: «Оформлять возврат»/))
        .toBeTruthy();
      fireEvent.click(within(editor).getByLabelText(/Оформлять возврат/));
      expect(save.disabled).toBe(false);
      fireEvent.click(screen.getByRole('button', { name: 'Отмена' }));

      fireEvent.click(screen.getByRole('button', { name: 'Новая роль' }));
      const fresh = screen.getByRole('button', { name: 'Сохранить роль' }).closest('.card') as HTMLElement;
      expect((within(fresh).getByLabelText(/Продавать/) as HTMLInputElement).disabled).toBe(true);
      expect((within(fresh).getByLabelText(/Принимать товар/) as HTMLInputElement).disabled).toBe(false);
    });

    it('владельцу не погашено ничего', async () => {
      render(<MembersScreen />);
      await waitFor(() => screen.getByRole('button', { name: 'Завести' }));
      expect(screen.queryByText(/шире ваших прав/)).toBeNull();
      expect(screen.queryByText(/этого права у вас нет/)).toBeNull();
    });
  });
});

const MEMBERS = [
  { id: 1, number: 1, login: 'vladelec', displayName: 'Пётр', role: 'OWNER', active: true, lastLoginAt: null },
  { id: 2, number: 2, login: 'ivan', displayName: 'Иван', role: 'VIEWER', active: true, lastLoginAt: null },
];

const ROLES = [
  { id: 10, name: 'Владелец', owner: true, permissions: [], members: 1 },
  { id: 11, name: 'Приёмщик', owner: false, permissions: ['intake.receive'], members: 3 },
  { id: 12, name: 'Пустая', owner: false, permissions: ['deal.return'], members: 0 },
];

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
