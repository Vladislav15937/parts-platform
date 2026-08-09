import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';

import { request } from '../api/client';
import { SessionProvider, useSession } from './SessionProvider';

/**
 * Кончившаяся сессия просит войти заново, а не показывает «401» на экране.
 *
 * <p><b>Зачем.</b> Сессия истекает по таймауту и умирает при каждой выкладке —
 * то есть регулярно. До этого приложение оставалось «вошедшим»: шапка писала
 * «Хозяин · владелец · на связи», рельс работал, а экраны один за другим
 * встречали владельца красной строкой «Запрос отклонён (401)». Догадаться
 * по ней, что дело в сессии, нельзя, а больше сказать некому.
 *
 * <p>Поймано живым прогоном: после перезапуска приложения экран этикеток
 * показал «Запрос отклонён (401)» рядом с бодрым «на связи», и все запросы
 * страницы отвечали 401.
 *
 * <p>Личность стирается — та же причина, что и при 401 на старте. Очередь
 * отправки при этом цела: она ждёт входа.
 */
describe('потеря сессии', () => {
  beforeEach(() => {
    localStorage.setItem('partsflow.me', JSON.stringify({
      memberId: 1, login: 'hozyain', displayName: 'Хозяин',
      role: 'OWNER', companySchema: 't_1000011',
    }));
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/csrf')) {
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/auth/me')) {
        return json({
          memberId: 1, login: 'hozyain', displayName: 'Хозяин',
          role: 'OWNER', companySchema: 't_1000011',
        });
      }
      // Всё остальное — сессия уже кончилась.
      return new Response(null, { status: 401 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('переводит приложение в «войдите заново» после 401 на обычном запросе', async () => {
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));

    // Экран запрашивает свои данные — и получает 401.
    await act(async () => {
      await request('/api/organization/warehouses').catch(() => {});
    });

    await waitFor(() => expect(screen.getByTestId('status').textContent,
      'приложение осталось «вошедшим» при мёртвой сессии').toBe('anonymous'));
    expect(screen.getByTestId('reason').textContent).toContain('войдите заново');
    // И локальная личность стёрта: офлайн-старт на ней открыл бы приложение
    // снова, хотя сессии нет.
    expect(localStorage.getItem('partsflow.me'),
      'личность осталась в памяти устройства').toBeNull();
  });

  it('неверный пароль из приложения не выбрасывает', async () => {
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authenticated'));

    // 401 на путях входа означает «не тот пароль», а не «сессия кончилась».
    await act(async () => {
      await request('/api/auth/login', { method: 'POST', body: {} }).catch(() => {});
    });

    expect(screen.getByTestId('status').textContent).toBe('authenticated');
  });
});

function Probe() {
  const session = useSession();
  return (
    <>
      <span data-testid="status">{session.state.status}</span>
      <span data-testid="reason">
        {session.state.status === 'anonymous' ? session.state.reason ?? '' : ''}
      </span>
    </>
  );
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
