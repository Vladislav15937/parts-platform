import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { App } from '../App';
import { accessText } from './AdminScreen';
import type { TenantAccess } from './adminApi';
import { navigation } from './navigation';

/**
 * Система администрирования сервиса (задача 0042) — по тому, что видит
 * администратор.
 *
 * <p>Три вещи из критерия приёмки: страница живёт отдельно от всех кабинетов
 * (своя страница `/admin`, свой вход без кода компании), войти в кабинет
 * можно только при разрешении владельца — иначе «Запросить доступ», —
 * и срок разрешения виден администратору остатком («сколько осталось»).
 */
describe('система администрирования', () => {
  const calls: Array<{ url: string; method: string; body: string | null }> = [];
  let signedIn = false;
  let assign: ReturnType<typeof vi.spyOn>;

  const tenants: TenantAccess[] = [
    {
      code: 'tkackaya', companyName: 'Разборка на Ткацкой',
      grant: { grantedAt: new Date(Date.now() - 3_600_000).toISOString(),
               expiresAt: new Date(Date.now() + 3 * 3_600_000 + 12 * 60_000 + 30_000).toISOString() },
      requestedAt: null,
    },
    {
      code: 'dalniy', companyName: 'Дальний склад', grant: null, requestedAt: null,
    },
  ];

  beforeEach(() => {
    calls.length = 0;
    signedIn = false;
    window.history.replaceState(null, '', '/admin');
    assign = vi.spyOn(navigation, 'go').mockImplementation(() => {});
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: typeof init?.body === 'string' ? init.body : null });
      if (url.includes('/api/auth/csrf')) {
        return new Response(null, { status: 204 });
      }
      if (url.endsWith('/api/admin/me')) {
        return signedIn
          ? json({ login: 'sluzhba', displayName: 'Владислав', visit: null })
          : new Response(null, { status: 401 });
      }
      if (url.endsWith('/api/admin/login')) {
        signedIn = true;
        return json({ login: 'sluzhba', displayName: 'Владислав', visit: null });
      }
      if (url.endsWith('/api/admin/tenants')) {
        return json(tenants);
      }
      if (url.includes('/request') || url.includes('/enter')) {
        return url.includes('/enter') ? json({ code: 'tkackaya' }) : new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    window.history.replaceState(null, '', '/');
  });

  it('открывается своим входом, без кода компании, и не трогает сессию кабинета', async () => {
    render(<App />);

    await waitFor(() => expect(screen.getByText('Администрирование сервиса')).toBeTruthy());
    expect(document.title).toBe('Администрирование сервиса — PartsFlow');
    expect(screen.queryByLabelText(/Код компании/),
      'у администратора нет кода компании — он вне всех разборок').toBeNull();
    expect(calls.some((c) => c.url.includes('/api/auth/me')),
      'страница администрирования спросила сессию кабинета').toBe(false);

    fireEvent.change(screen.getByLabelText('Логин'), { target: { value: 'sluzhba' } });
    fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: 'пароль-длинный' } });
    fireEvent.click(screen.getByRole('button', { name: 'Войти' }));

    await waitFor(() => expect(screen.getByText('Разборка на Ткацкой')).toBeTruthy());
    const login = calls.find((c) => c.url.endsWith('/api/admin/login'));
    expect(login?.body).toBe(JSON.stringify({ login: 'sluzhba', password: 'пароль-длинный' }));
  });

  it('с разрешением — «Войти в кабинет» и остаток срока; без — «Запросить доступ»', async () => {
    signedIn = true;
    render(<App />);

    await waitFor(() => expect(screen.getByText('Разборка на Ткацкой')).toBeTruthy());
    expect(screen.getByText(/осталось 3 ч 12 мин/)).toBeTruthy();
    expect(screen.getByText('Нет доступа')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Запросить доступ' }));
    await waitFor(() => expect(calls.some((c) =>
      c.method === 'POST' && c.url.endsWith('/api/admin/tenants/dalniy/request'))).toBe(true));

    fireEvent.click(screen.getByRole('button', { name: 'Войти в кабинет' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith('/'));
    expect(calls.some((c) =>
      c.method === 'POST' && c.url.endsWith('/api/admin/tenants/tkackaya/enter'))).toBe(true);
  });

  it('состояние доступа словами: до отзыва, до часа с остатком, запрошен, нет', () => {
    const now = Date.parse('2026-10-12T10:00:00Z');
    const base = { code: 'x', companyName: 'X', grant: null, requestedAt: null };
    expect(accessText({ ...base, grant: { grantedAt: '2026-10-12T09:00:00Z', expiresAt: null } }, now))
      .toBe('Открыт до отзыва');
    expect(accessText({ ...base, grant: { grantedAt: '2026-10-12T09:00:00Z',
                                          expiresAt: '2026-10-12T10:40:00Z' } }, now))
      .toMatch(/^Открыт до .+ · осталось 40 мин$/);
    expect(accessText({ ...base, requestedAt: '2026-10-12T09:30:00Z' }, now))
      .toMatch(/^Запрошен .+ — ждёт владельца$/);
    expect(accessText(base, now)).toBe('Нет доступа');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
