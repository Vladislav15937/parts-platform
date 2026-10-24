import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { rightsWith } from '../test/rights';
import { MembersScreen } from './MembersScreen';
import { MEMBERS_RIGHTS } from './tabs';

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
          // Владельцу сервер отдаёт итогом весь справочник (AccessRoleService.members).
          { memberId: 1, roleId: 10, roleName: 'Владелец', owner: true, added: [], removed: [],
            effective: ['deal.return', 'deal.sell', 'intake.receive'] },
          { memberId: 2, roleId: 11, roleName: 'Приёмщик', owner: false, added: ['deal.sell'],
            removed: [], effective: ['intake.receive', 'deal.sell'] },
          { memberId: 3, roleId: null, roleName: null, owner: false, added: [], removed: [],
            effective: ['journal.view'] },
          { memberId: 4, roleId: null, roleName: null, owner: false, added: [], removed: [],
            effective: [] },
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
    // Причина погашенного — видимым текстом рядом, а не только подсказкой:
    // на телефоне наводить нечем (решение владельца 23.10.2026, вопрос 3).
    const row = screen.getByRole('button', { name: 'Удалить роль: Приёмщик' }).closest('tr') as HTMLElement;
    expect(within(row).getByText(/Роль назначена — удалить можно, назначив сотрудникам другую/))
      .toBeTruthy();
    const free = screen.getByRole('button', { name: 'Удалить роль: Пустая' }).closest('tr') as HTMLElement;
    expect(within(free).queryByText(/Роль назначена/)).toBeNull();
  });

  it('смена роли говорит до «Сохранить», какие отдельные права сбросятся', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: ivan' }));
    fireEvent.click(screen.getByRole('button', { name: 'Права: ivan' }));
    const editor = screen.getByRole('button', { name: 'Сохранить права' }).closest('.card') as HTMLElement;
    expect(within(editor).queryByText(/Смена роли сбросит/), 'предупреждение без смены роли').toBeNull();

    fireEvent.change(within(editor).getByLabelText('Роль'), { target: { value: '12' } });
    expect(within(editor).getByText(
      /Смена роли сбросит отдельные права сотрудника: добавленные «Продавать»/)).toBeTruthy();
    expect(sent, 'предупреждение пришло после сохранения, а не до').toEqual([]);

    // Вернул прежнюю роль — терять нечего, предупреждение ушло.
    fireEvent.change(within(editor).getByLabelText('Роль'), { target: { value: '11' } });
    expect(within(editor).queryByText(/Смена роли сбросит/)).toBeNull();
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
    // У владельца прав поверх роли не бывает — и подписи «поверх роли» у сорока
    // прав врали бы о данных (живой прогон 24.10.2026: API отвечает added: []).
    const owners = screen.getByRole('button', { name: 'Сохранить права' }).closest('.card') as HTMLElement;
    expect(within(owners).queryByText(/поверх роли/), 'у владельца права подписаны «поверх роли»')
      .toBeNull();
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

  it('не владельцу пароль и выключение владельца погашены и названы — по признаку сервера', async () => {
    // Признак и слова — сервера (accountLocked, задача 0044, 24.10, 1а): экран
    // своих правил о владельце больше не держит.
    membersAnswer = () => json(MEMBERS.map((m) => (m.login === 'vladelec'
      ? { ...m, accountLocked: 'Учётную запись владельца меняет только владелец' }
      : { ...m, accountLocked: null })));
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

  // Находка (б) живого прогона 24.10.2026: ревизор без роли клиента стоял
  // «без роли» при открытом журнале.
  it('ревизор без роли с журналом назван словами, без журнала — «без роли»', async () => {
    render(<MembersScreen />);
    await waitFor(() => screen.getByRole('button', { name: 'Права: revizor' }));
    const row = screen.getByRole('button', { name: 'Права: revizor' }).closest('tr') as HTMLElement;
    expect(within(row).getByText('Ревизор: журнал действий')).toBeTruthy();
    const other = screen.getByRole('button', { name: 'Права: revizor2' }).closest('tr') as HTMLElement;
    expect(within(other).getByText('без роли')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Права: revizor' }));
    expect(screen.getByText(/журнал действий у сотрудника — от прежней роли «Ревизор»/)).toBeTruthy();
  });

  // Повторный живой прогон 24.10.2026: у сотрудника без права блок показывал
  // голое «Недостаточно прав» вместо погашенного с причиной (решение 3).
  it('без права на доступ администратора блок погашен с причиной и за состоянием не ходит', async () => {
    render(<MembersScreen ownerActs={false} mine={rightsWith(...MEMBERS_RIGHTS, 'intake.receive')} />);
    await waitFor(() => screen.getByRole('button', { name: 'Завести' }));
    const card = screen.getByText('Доступ администратора сервиса').closest('.card') as HTMLElement;
    const button = within(card).getByRole('button', { name: 'Дать доступ администратору' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(within(card).getByText(/^Нет права/)).toBeTruthy();
    expect(card.textContent).not.toContain('Недостаточно прав');
    const asked = vi.mocked(fetch).mock.calls.map((call) => String(call[0]));
    expect(asked.some((url) => url.includes('/api/organization/admin-access')),
      'погашенный блок пошёл за состоянием, которое сервер отобьёт').toBe(false);
  });

  // Ответ владельца 24.10.2026 (2б): выдавать можно только свои права. Решает
  // сервер; экран гасит заранее и называет причину.
  describe('выдавать можно только свои права', () => {
    it('роль шире своих в выборе нового сотрудника погашена и названа', async () => {
      render(<MembersScreen ownerActs={false} mine={rightsWith(...MEMBERS_RIGHTS, 'intake.receive')} />);
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
      render(<MembersScreen ownerActs={false} mine={rightsWith(...MEMBERS_RIGHTS, 'intake.receive')} />);
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
      render(<MembersScreen ownerActs={false} mine={rightsWith(...MEMBERS_RIGHTS, 'intake.receive')} />);
      await waitFor(() => screen.getByRole('button', { name: 'Изменить роль: Пустая' }));
      fireEvent.click(screen.getByRole('button', { name: 'Изменить роль: Пустая' }));
      const save = screen.getByRole('button', { name: 'Сохранить роль' }) as HTMLButtonElement;
      const editor = save.closest('.card') as HTMLElement;
      // Набор не тронут — это переименование, и оно ничего не выдаёт (живой прогон
      // волны 24.10c: «переименовать можно»; сервер «свои права» о прежнем наборе
      // не спрашивает).
      expect(save.disabled, 'переименование роли шире своих погашено').toBe(false);
      fireEvent.click(within(editor).getByLabelText(/Принимать товар/));
      expect(save.disabled, 'изменённый набор шире своих уходит на сервер').toBe(true);
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
  // Ревизоры без роли клиента (задача 0044): одному «Ревизора» не заводили —
  // журнал у него есть; у другого клиент роль удалил — журнала нет.
  { id: 3, number: 3, login: 'revizor', displayName: 'Ревизор', role: 'AUDITOR', active: true, lastLoginAt: null },
  { id: 4, number: 4, login: 'revizor2', displayName: 'Ревизор 2', role: 'AUDITOR', active: true, lastLoginAt: null },
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
