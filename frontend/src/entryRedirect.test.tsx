import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { App } from './App';

/*
 * Рабочий экран и сделка по ссылке подменены: здесь проверяется одно решение
 * `App` — куда попадает человек по адресу, — а не сами экраны. Настоящий
 * HomeScreen тянет три десятка справочников, и отказ любого из них путал бы
 * этот тест с чужой поломкой.
 */
vi.mock('./screens/HomeScreen', () => ({
  HomeScreen: () => <p>Рабочий экран</p>,
}));
vi.mock('./screens/SharedDealScreen', () => ({
  SharedDealScreen: ({ company, token }: { company: string; token: string }) => (
    <p>Сделка по ссылке {company}/{token}</p>
  ),
}));

/**
 * Вход и регистрация — одна страница, и невошедшего уводит на неё с любого
 * адреса (задача 0265, запрос владельца 11 октября 2026).
 *
 * <p>Адрес меняется заменой записи истории, а не новой: «Назад» не должен
 * возвращать на закрытый адрес, откуда страница тут же уведёт снова. Это
 * и меряется длиной истории — {@code replaceState} её не растит,
 * {@code pushState} растит.
 */
describe('общая страница входа и регистрации', () => {
  let signedIn: boolean;
  let challenges: number;

  beforeEach(() => {
    signedIn = false;
    challenges = 0;
    localStorage.clear();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/csrf')) return new Response(null, { status: 204 });
      if (url.includes('/api/auth/me')) {
        return signedIn ? json(ME) : new Response(null, { status: 401 });
      }
      if (url.includes('/api/auth/login')) {
        signedIn = true;
        return json(ME);
      }
      if (url.includes('/api/registration/challenge')) {
        challenges += 1;
        return json({ challenge: `v1.9999999999.nonce${challenges}.sig`, bits: 2 });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.replaceState(null, '', '/');
  });

  it.each(['/', '/sales', '/что-угодно/глубже?x=1#y'])(
    'невошедшего с %s уводит на страницу входа заменой адреса, а не новой записью',
    async (path) => {
      window.history.pushState(null, '', path);
      const before = window.history.length;

      render(<App />);
      await screen.findByText('Вход', { selector: 'h1' });

      // Адрес меняет эффект `Root`, а он идёт после отрисовки заголовка:
      // ждём сам адрес, а длину истории читаем уже после него.
      await waitFor(() => expect(window.location.pathname).toBe('/login'));
      expect(window.location.search).toBe('');
      expect(window.history.length).toBe(before);
      expect(screen.getByRole('tab', { name: 'Вход' }).getAttribute('aria-selected')).toBe('true');
    },
  );

  it('/register открывает ту же страницу на вкладке регистрации и адрес не трогает', async () => {
    window.history.pushState(null, '', '/register');
    render(<App />);

    await screen.findByText('Регистрация компании', { selector: 'h1' });
    await effectsSettled();
    expect(screen.getByRole('tab', { name: 'Регистрация' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'Вход' })).toBeTruthy();
    expect(window.location.pathname).toBe('/register');
  });

  it('ссылка на сделку открывается без входа и никуда не уводит', async () => {
    window.history.pushState(null, '', '/s/razborka/tok-123');
    render(<App />);

    await screen.findByText('Сделка по ссылке razborka/tok-123');
    await effectsSettled();
    expect(window.location.pathname).toBe('/s/razborka/tok-123');
    expect(screen.queryByRole('tab', { name: 'Вход' })).toBeNull();
  });

  it.each(['/login', '/register'])('вошедшего с %s уводит в работу, а не показывает форму', async (path) => {
    signedIn = true;
    window.history.pushState(null, '', path);
    const before = window.history.length;
    render(<App />);

    await screen.findByText('Рабочий экран');
    expect(screen.queryByRole('tab', { name: 'Вход' })).toBeNull();
    await waitFor(() => expect(window.location.pathname).toBe('/'));
    expect(window.history.length).toBe(before);
  });

  it('после входа адрес перестаёт быть адресом входа', async () => {
    window.history.pushState(null, '', '/sales');
    render(<App />);
    await screen.findByText('Вход', { selector: 'h1' });

    fireEvent.change(screen.getByRole('textbox', { name: 'Компания' }), { target: { value: 'razborka' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Логин' }), { target: { value: 'vladelec' } });
    fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: 'parol-12345' } });
    fireEvent.click(screen.getByRole('button', { name: 'Войти' }));

    await screen.findByText('Рабочий экран');
    await waitFor(() => expect(window.location.pathname).toBe('/'));
  });

  it('переключение вкладок не перезагружает страницу и не теряет набранного', async () => {
    window.history.pushState(null, '', '/login');
    const before = window.history.length;
    render(<App />);
    await screen.findByText('Вход', { selector: 'h1' });
    await effectsSettled();

    fireEvent.change(screen.getByRole('textbox', { name: 'Компания' }), { target: { value: 'razborka' } });

    fireEvent.click(screen.getByRole('tab', { name: 'Регистрация' }));
    await screen.findByRole('button', { name: 'Зарегистрировать' });
    expect(window.location.pathname).toBe('/register');
    fireEvent.change(screen.getByRole('textbox', { name: 'Название компании' }), { target: { value: 'Разборка' } });

    fireEvent.click(screen.getByRole('tab', { name: 'Вход' }));
    expect(window.location.pathname).toBe('/login');
    expect(screen.getByRole('textbox', { name: 'Компания' })).toHaveProperty('value', 'razborka');

    fireEvent.click(screen.getByRole('tab', { name: 'Регистрация' }));
    expect(screen.getByRole('textbox', { name: 'Название компании' })).toHaveProperty('value', 'Разборка');
    // Задание взято один раз: вторая вкладка не пересоздавалась при переключении.
    expect(challenges).toBe(1);
    expect(window.history.length).toBe(before);
  });

  it('вход не считает проверку «вы не робот», пока регистрацию не открыли', async () => {
    window.history.pushState(null, '', '/');
    render(<App />);
    await screen.findByText('Вход', { selector: 'h1' });
    await waitFor(() => expect(window.location.pathname).toBe('/login'));
    expect(challenges).toBe(0);

    fireEvent.click(screen.getByRole('link', { name: 'Зарегистрировать компанию' }));
    await screen.findByRole('button', { name: 'Зарегистрировать' });
    expect(challenges).toBe(1);
    expect(window.location.pathname).toBe('/register');

    fireEvent.click(screen.getByRole('link', { name: 'Уже есть компания? Войти' }));
    expect(screen.getByRole('tab', { name: 'Вход' }).getAttribute('aria-selected')).toBe('true');
    expect(window.location.pathname).toBe('/login');
  });
});

/**
 * Дожидается эффектов после отрисовки. Заголовок появляется раньше, чем
 * отработает эффект `Root`, правящий адрес, поэтому утверждение «адрес
 * остался прежним», прочитанное сразу за заголовком, проходило бы и на коде,
 * который уводит позже: проверка обязана смотреть после эффекта.
 */
async function effectsSettled(): Promise<void> {
  await act(async () => { await Promise.resolve(); });
}

const ME = {
  memberId: 1,
  login: 'vladelec',
  displayName: 'Владелец',
  role: 'OWNER',
  companySchema: 't_000001',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
