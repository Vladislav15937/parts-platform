import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SessionProvider } from '../auth/SessionProvider';
import { HomeScreen } from './HomeScreen';

/**
 * Администратор сервиса в кабинете разборки (задача 0042) — что он видит.
 *
 * <p>Решение владельца продукта от 8 сентября 2026: администратор «должен
 * иметь возможность делать абсолютно всё в личном кабинете владельца
 * разборки». Значит рельс у него владельца, а не «Просмотра», которым
 * экраны считали бы незнакомую роль; называется он при этом своей ролью;
 * срок доступа и путь обратно видны всегда; а давать и отзывать доступ
 * ему самому экран не предлагает — это решает владелец.
 */
describe('администратор сервиса в кабинете', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/csrf')) {
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/auth/me')) {
        return json({
          memberId: 9, login: 'sluzhba', displayName: 'Владислав',
          role: 'ADMIN', companySchema: 't_1000011',
        });
      }
      if (url.endsWith('/api/admin/me')) {
        return json({
          login: 'sluzhba', displayName: 'Владислав',
          visit: {
            code: 'tkackaya', companyName: 'Разборка на Ткацкой',
            expiresAt: new Date(Date.now() + 2 * 3_600_000 + 30_000).toISOString(),
          },
        });
      }
      if (url.endsWith('/api/members')) {
        return json([
          { id: 1, login: 'vladelec', displayName: 'Пётр', role: 'OWNER', active: true, lastLoginAt: null },
          { id: 9, login: null, displayName: 'Владислав', role: 'ADMIN', active: true, lastLoginAt: null },
        ]);
      }
      if (url.endsWith('/api/organization/admin-access')) {
        return json({ grant: { grantedAt: '2026-10-12T09:00:00Z', expiresAt: null }, request: null });
      }
      return new Response(null, { status: 404 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('рельс владельца, своя роль в шапке, срок доступа и путь обратно', async () => {
    render(<SessionProvider><HomeScreen /></SessionProvider>);

    await waitFor(() => expect(screen.getByText(/администратор сервиса$/)).toBeTruthy());
    for (const section of ['Сотрудники', 'Склады', 'Настройки', 'Журнал']) {
      expect(screen.queryByRole('button', { name: section }),
        `раздела владельца «${section}» у администратора нет`).toBeTruthy();
    }
    await waitFor(() => expect(screen.getByText(/осталось 2 ч/)).toBeTruthy());
    expect(screen.getByRole('button', { name: 'Вернуться в администрирование' })).toBeTruthy();
  });

  it('в списке сотрудников он назван администратором, без кнопок, и доступ себе не даёт', async () => {
    render(<SessionProvider><HomeScreen /></SessionProvider>);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Сотрудники' })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Сотрудники' }));

    await waitFor(() => expect(screen.getByText('Администратор сервиса')).toBeTruthy());
    // Кто входит в кабинет, решает владелец: ни у одной строки нет кнопок
    // пароля и выключения, и завести сотрудника негде (разбор PR 393).
    expect(screen.queryByRole('button', { name: 'Выключить' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Сменить пароль' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Завести' })).toBeNull();
    expect(screen.getByText(/Сотрудников заводит и меняет владелец/)).toBeTruthy();
    await waitFor(() => expect(screen.getByText('Доступ открыт до отзыва.')).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Дать доступ администратору' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Отозвать доступ' })).toBeNull();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
