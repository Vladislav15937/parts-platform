import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { AdminAccessPanel } from './AdminAccessPanel';
import type { AdminAccessState } from '../organization/adminAccess';

/**
 * Доступ администратора сервиса — со стороны владельца разборки (задача 0042).
 *
 * <p>Решения владельца продукта от 8 сентября 2026, по которым проверяется:
 * у клиента «отдельная кнопка дать доступ администратору» — она есть и без
 * входящего запроса; срок выбирает клиент — «до отзыва» либо число часов,
 * и до какого часа открыт доступ, владелец видит; отозвать можно, пока
 * администратор внутри; запрос администратора виден владельцу.
 */
describe('доступ администратора сервиса у владельца', () => {
  let state: AdminAccessState;
  const posts: Array<{ url: string; body: string | null }> = [];

  beforeEach(() => {
    posts.length = 0;
    state = { grant: null, request: null };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'POST') {
        const body = typeof init.body === 'string' ? init.body : null;
        posts.push({ url, body });
        if (url.endsWith('/grant')) {
          const hours = (JSON.parse(body ?? '{}') as { hours: number | null }).hours;
          state = {
            grant: {
              grantedAt: new Date().toISOString(),
              expiresAt: hours === null ? null : '2026-10-12T12:00:00Z',
            },
            request: null,
          };
        } else if (url.endsWith('/revoke')) {
          state = { ...state, grant: null };
        } else if (url.endsWith('/decline')) {
          state = { ...state, request: null };
        }
      }
      return json(state);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('своя кнопка есть и без запроса; «до отзыва» уходит пустым сроком', async () => {
    render(<AdminAccessPanel canChange />);

    await waitFor(() => expect(screen.getByText('Доступ закрыт.')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Дать доступ администратору' }));

    await waitFor(() => expect(screen.getByText('Доступ открыт до отзыва.')).toBeTruthy());
    expect(posts[0]?.url).toContain('/api/organization/admin-access/grant');
    expect(posts[0]?.body).toBe(JSON.stringify({ hours: null }));
  });

  it('срок в часах уходит числом, и до какого часа открыто — видно словами, а не ISO', async () => {
    render(<AdminAccessPanel canChange />);
    await waitFor(() => expect(screen.getByText('Доступ закрыт.')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Срок'), { target: { value: 'hours' } });
    fireEvent.change(screen.getByLabelText('Часов'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Дать доступ администратору' }));

    await waitFor(() => expect(screen.getByText(/^Доступ открыт до 12 октября/)).toBeTruthy());
    expect(posts[0]?.body).toBe(JSON.stringify({ hours: 3 }));
    expect(document.body.textContent).not.toContain('2026-10-12T');
  });

  it('открытый доступ отзывается кнопкой', async () => {
    state = { grant: { grantedAt: '2026-10-12T09:00:00Z', expiresAt: null }, request: null };
    render(<AdminAccessPanel canChange />);

    await waitFor(() => expect(screen.getByText('Доступ открыт до отзыва.')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Отозвать доступ' }));

    await waitFor(() => expect(screen.getByText('Доступ закрыт.')).toBeTruthy());
    expect(posts[0]?.url).toContain('/api/organization/admin-access/revoke');
  });

  it('запрос администратора виден, и его можно отклонить', async () => {
    state = { grant: null, request: { requestedAt: '2026-10-12T09:30:00Z', adminName: 'Владислав' } };
    render(<AdminAccessPanel canChange />);

    await waitFor(() => expect(screen.getByText(/Владислав просит доступ/)).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Отклонить запрос' }));

    await waitFor(() => expect(screen.queryByText(/просит доступ/)).toBeNull());
    expect(posts[0]?.url).toContain('/api/organization/admin-access/decline');
  });

  it('администратор в кабинете видит состояние, но кнопок не видит', async () => {
    state = { grant: { grantedAt: '2026-10-12T09:00:00Z', expiresAt: null }, request: null };
    render(<AdminAccessPanel canChange={false} />);

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
