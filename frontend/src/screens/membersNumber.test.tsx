import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';

import { MembersScreen } from './MembersScreen';

/**
 * Видимый номер сотрудника на экране «Сотрудники» (задача 0373, ответ
 * владельца 23.10.2026, пункт 10: «мы должны идентифицировать сотрудника
 * на 100%»).
 *
 * <p>Номер в фикстуре нарочно не равен `id`: совпади они, проверка прошла бы
 * и на экране, показывающем номер строки базы, — верное число по неверной
 * причине (тот же приём, что `dealNumber: 1274` при `dealId: 8`).
 */
describe('номер сотрудника на экране «Сотрудники»', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/csrf')) {
        return new Response(null, { status: 204 });
      }
      if (url.endsWith('/api/members')) {
        return json([
          { id: 31, number: 1, login: 'vladelec', displayName: 'Пётр Владельцев',
            role: 'OWNER', active: true, lastLoginAt: null },
          { id: 57, number: 2, login: 'prodavec', displayName: 'Анна Продавцова',
            role: 'SELLER', active: false, lastLoginAt: null },
        ]);
      }
      if (url.endsWith('/api/organization/admin-access')) {
        return json({ grant: null, request: null });
      }
      return new Response(null, { status: 404 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('в строке стоит видимый номер, а не номер строки базы', async () => {
    render(<MembersScreen />);
    await waitFor(() => expect(screen.getByText('Пётр Владельцев')).toBeTruthy());

    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers.slice(0, 3)).toEqual(['Номер', 'Логин', 'Имя']);

    const owner = screen.getByText('Пётр Владельцев').closest('tr')!;
    const seller = screen.getByText('Анна Продавцова').closest('tr')!;
    expect(within(owner).getAllByRole('cell')[0]?.textContent).toBe('1');
    // У выключенного номер тот же: номер не освобождается и второму
    // не достаётся.
    expect(within(seller).getAllByRole('cell')[0]?.textContent).toBe('2');

    const shown = screen.getAllByRole('cell').map((c) => c.textContent);
    expect(shown, 'номер строки базы человеку не показывается').not.toContain('31');
    expect(shown).not.toContain('57');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}
