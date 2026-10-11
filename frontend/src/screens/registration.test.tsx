import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { App } from '../App';
import { RegistrationScreen } from './RegistrationScreen';

/**
 * Регистрация компании клиентом (задача 0184), глазами человека.
 *
 * <p>Заглушка отвечает так, как отвечает сервер: задание с лёгкой сложностью
 * (два бита — вычисление настоящее, но мгновенное), 201 на новую компанию,
 * словами на отказ. Проверяется то, что видно на экране, а не вызов функций:
 * тест по внутренностям зеленеет при сломанном экране.
 */
describe('регистрация компании', () => {
  let posts: Array<Record<string, string>>;
  let challenges: number;
  let registerReply: () => Response;

  beforeEach(() => {
    posts = [];
    challenges = 0;
    localStorage.clear();
    registerReply = () => json({ companyCode: 'razborka-sever', companyName: 'Разборка', replayed: false }, 201);

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/auth/csrf')) return new Response(null, { status: 204 });
      if (url.includes('/api/auth/me')) return new Response(null, { status: 401 });
      if (url.includes('/api/registration/challenge')) {
        challenges += 1;
        return json({ challenge: `v1.9999999999.nonce${challenges}.sig`, bits: 2 });
      }
      if (url.includes('/api/registration/companies')) {
        posts.push(JSON.parse(String(init?.body)) as Record<string, string>);
        return registerReply();
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    window.history.pushState({}, '', '/');
  });

  it('форма просит ровно то, без чего компанию не завести, и не уходит с пустым паролем', async () => {
    render(<RegistrationScreen />);
    await screen.findByRole('button', { name: 'Зарегистрировать' });

    for (const label of ['Название компании', 'Код компании', 'Почта', 'Логин', 'Пароль']) {
      expect(screen.getByLabelText(new RegExp(label))).toBeTruthy();
    }
    expect(screen.getByLabelText(/Ваше имя/)).toBeTruthy();

    fireEvent.change(screen.getByLabelText(/Название компании/), { target: { value: 'Разборка' } });
    fireEvent.change(screen.getByLabelText(/Код компании/), { target: { value: 'razborka-sever' } });
    fireEvent.change(screen.getByLabelText(/Почта/), { target: { value: 'owner@example.ru' } });
    fireEvent.change(screen.getByLabelText(/Логин/), { target: { value: 'hozyain' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрировать' }));

    await screen.findByText(/Пароль — не короче 8 символов/, { selector: 'p.error' });
    expect(posts).toHaveLength(0);
  });

  it('говорит про код компании и пароль до отправки, словами', async () => {
    render(<RegistrationScreen />);
    await screen.findByRole('button', { name: 'Зарегистрировать' });

    // Пока не печатали, подсказка спокойная; ошибкой она становится на плохом вводе.
    expect(screen.getByText(/Латиница, цифры и дефис, от 2 до 31 символа/).className).toBe('note');

    fireEvent.change(screen.getByLabelText(/Код компании/), { target: { value: 'Разборка №1' } });
    const code = screen.getByText(/Код компании — от 2 до 31 символа/);
    expect(code.className).toBe('error');

    fireEvent.change(screen.getByLabelText(/Пароль/), { target: { value: '1234567' } });
    expect(screen.getByText('Пароль — не короче 8 символов').className).toBe('error');

    // Неверная форма не уходит на сервер вовсе.
    fireEvent.change(screen.getByLabelText(/Название компании/), { target: { value: 'К' } });
    fireEvent.change(screen.getByLabelText(/Почта/), { target: { value: 'a@b.ru' } });
    fireEvent.change(screen.getByLabelText(/Логин/), { target: { value: 'l' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрировать' }));
    await waitFor(() => expect(screen.getAllByText(/Код компании — от 2 до 31 символа/).length)
      .toBeGreaterThan(1));
    expect(posts).toHaveLength(0);
  });

  it('новая компания: код и логин показаны для входа, а «Войти» помнит код', async () => {
    render(<RegistrationScreen />);
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрировать' }));

    await screen.findByText('Компания зарегистрирована');
    expect(screen.getByText('razborka-sever')).toBeTruthy();
    expect(screen.getByText('hozyain')).toBeTruthy();
    // Внутренних номеров в ответе нет, и на экране их быть не может.
    expect(document.body.textContent).not.toMatch(/t_\d{6}/);

    expect(posts).toHaveLength(1);
    expect(posts[0]).toMatchObject({
      companyName: 'Разборка', companyCode: 'razborka-sever', email: 'owner@example.ru',
      ownerLogin: 'hozyain', ownerPassword: 'parol-12345', ownerName: 'Пётр',
    });
    expect(posts[0]?.['solution']).toMatch(/^\d+$/);

    fireEvent.click(screen.getByRole('link', { name: 'Войти' }));
    expect(localStorage.getItem('partsflow.company')).toBe('razborka-sever');
  });

  it('отказ сервера показан его словами, форма осталась заполненной, ключ повтора тот же', async () => {
    registerReply = () => json({ message: 'Код компании «razborka-sever» занят' }, 409);
    render(<RegistrationScreen />);
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрировать' }));

    await screen.findByText('Код компании «razborka-sever» занят');
    expect(screen.getByLabelText(/Название компании/)).toHaveProperty('value', 'Разборка');

    // Человек поправил код и нажал снова: ключ повтора остаётся прежним —
    // иначе «ответ не дошёл, нажал ещё раз» заводило бы вторую компанию.
    registerReply = () => json({ companyCode: 'razborka-yug', companyName: 'Разборка', replayed: false }, 201);
    fireEvent.change(screen.getByLabelText(/Код компании/), { target: { value: 'razborka-yug' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрировать' }));
    await screen.findByText('Компания зарегистрирована');

    expect(posts).toHaveLength(2);
    expect(posts[0]?.['requestId']).toBeTruthy();
    expect(posts[1]?.['requestId']).toBe(posts[0]?.['requestId']);
  });

  it('устаревшую проверку заменяет сама: берёт новое задание и отправляет ещё раз', async () => {
    let first = true;
    registerReply = () => {
      if (first) {
        first = false;
        return json({ message: 'Проверка устарела. Обновите страницу и отправьте форму ещё раз' }, 400);
      }
      return json({ companyCode: 'razborka-sever', companyName: 'Разборка', replayed: false }, 201);
    };
    render(<RegistrationScreen />);
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрировать' }));

    // Человек не видит красного: сервер отказал, экран взял новое задание
    // и отправил ту же форму тем же ключом.
    await screen.findByText('Компания зарегистрирована');
    expect(posts).toHaveLength(2);
    expect(posts[1]?.['challenge']).not.toBe(posts[0]?.['challenge']);
    expect(posts[1]?.['requestId']).toBe(posts[0]?.['requestId']);
  });

  it('регистрация закрыта потолком: сразу слова сервера, формы нет', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/registration/challenge')) {
        return json({ message: 'Регистрация временно закрыта. Напишите нам — мы подключим вашу компанию сами' }, 409);
      }
      return new Response(null, { status: 401 });
    }));
    render(<RegistrationScreen />);

    await screen.findByText(/Регистрация временно закрыта/);
    expect(screen.queryByRole('button', { name: 'Зарегистрировать' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Войти в свою компанию' })).toBeTruthy();
  });

  it('сервера не слышно: это не «закрыто», и можно повторить', async () => {
    let reachable = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (!reachable) throw new TypeError('network');
      if (String(input).includes('/api/registration/challenge')) {
        return json({ challenge: 'v1.9999999999.n.s', bits: 2 });
      }
      return new Response(null, { status: 401 });
    }));
    render(<RegistrationScreen />);

    await screen.findByText('Нет связи с сервером');
    expect(screen.queryByText(/временно закрыта/)).toBeNull();

    reachable = true;
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }));
    await screen.findByRole('button', { name: 'Зарегистрировать' });
  });

  it('по адресу /register без входа открывается регистрация, а на входе есть ссылка на неё', async () => {
    const { unmount } = render(<App />);
    await screen.findByText('Вход', { selector: 'h1' });
    expect(screen.getByRole('link', { name: 'Зарегистрировать компанию' }).getAttribute('href'))
      .toBe('/register');
    unmount();

    window.history.pushState({}, '', '/register');
    render(<App />);
    await screen.findByText('Регистрация компании', { selector: 'h1' });
  });

  /** Заполняет форму так, чтобы её можно было отправить. */
  async function fill(): Promise<void> {
    await screen.findByRole('button', { name: 'Зарегистрировать' });
    fireEvent.change(screen.getByLabelText(/Название компании/), { target: { value: 'Разборка' } });
    fireEvent.change(screen.getByLabelText(/Код компании/), { target: { value: 'razborka-sever' } });
    fireEvent.change(screen.getByLabelText(/Почта/), { target: { value: 'owner@example.ru' } });
    fireEvent.change(screen.getByLabelText(/Ваше имя/), { target: { value: 'Пётр' } });
    fireEvent.change(screen.getByLabelText(/Логин/), { target: { value: 'hozyain' } });
    fireEvent.change(screen.getByLabelText(/Пароль/), { target: { value: 'parol-12345' } });
  }
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
