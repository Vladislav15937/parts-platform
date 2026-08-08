import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';

import { SessionProvider, useSession } from './SessionProvider';

/**
 * Вход берёт CSRF-токен заново, а не пользуется лежащим в cookie.
 *
 * <p><b>Зачем.</b> Cookie с токеном переживает сессию, которой он принадлежал:
 * утром в браузере лежит вчерашний. Сервер такой вход отвергает — и отвергает
 * <b>401 с пустым телом</b>, тем же ответом, что и неверный пароль: на входе
 * человек ещё анонимен, поэтому отказ CSRF уходит через точку входа
 * аутентификации, а не как 403.
 *
 * <p>Отличить одно от другого не может ни человек, ни повтор по 403
 * в {@code request}: он ждёт 403, а приходит 401, и запрос уводится
 * в постоянную ошибку. Владелец с верным паролем читает «неверный логин
 * или пароль» и заперт снаружи, пока не догадается почистить cookie.
 *
 * <p>Поймано прогоном инструкции подключения на живой ячейке: свежий
 * арендатор, пароль сверен в базе, вход отвечает 401.
 */
describe('вход при устаревшем CSRF-токене', () => {
  let stale: boolean;

  beforeEach(() => {
    stale = true;
    document.cookie = 'XSRF-TOKEN=vcherashniy';
    localStorage.clear();

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const headers = new Headers(init?.headers);

      if (url.includes('/api/auth/csrf')) {
        // Сервер выдаёт токен новой сессии — как настоящий.
        stale = false;
        document.cookie = 'XSRF-TOKEN=segodnyashniy';
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/auth/me')) {
        return new Response(null, { status: 401 });
      }
      if (url.includes('/api/auth/login')) {
        // Вчерашний токен сервер отвергает — пустым 401, неотличимым
        // от неверного пароля.
        if (stale || headers.get('X-XSRF-TOKEN') !== 'segodnyashniy') {
          return new Response(null, { status: 401 });
        }
        return json({
          memberId: 1, login: 'vladelec', displayName: 'Владимир',
          role: 'OWNER', companySchema: 't_1000004',
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('не выдаёт протухший токен за неверный пароль', async () => {
    render(
      <SessionProvider>
        <Probe />
      </SessionProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('anonymous'));

    await act(async () => {
      await (window as unknown as { signIn: () => Promise<void> }).signIn();
    });

    await waitFor(() => expect(screen.getByTestId('status').textContent,
      'вход отвергнут при верном пароле — токен не обновили перед ним')
      .toBe('authenticated'));
  });
});

function Probe() {
  const session = useSession();
  (window as unknown as { signIn: () => Promise<void> }).signIn = () =>
    session.signIn({ company: 'proba3', login: 'vladelec', password: 'parol12345' });
  return <span data-testid="status">{session.state.status}</span>;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
