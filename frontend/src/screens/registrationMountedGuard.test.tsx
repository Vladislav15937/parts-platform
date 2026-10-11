import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

import { RegistrationScreen } from './RegistrationScreen';

/**
 * Типы Node в проекте не подключены ни одной строкой; называем только то, чем
 * пользуемся. Тот же приём, что в {@code feedsMountedGuard.test.tsx}.
 */
declare const process: {
  on(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
  off(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
};

/**
 * Сторож «экран ещё на месте» на регистрации (задача 0184).
 *
 * <p>Экран пишет состояние после ожидания в трёх местах: после задания
 * от сервера, после вычисления и после ответа на отправку. Человек уходит
 * со страницы, не дожидаясь, — колбэк догоняет среду, которую vitest уже
 * снёс. Обязательств у сторожа два, и каждое проверяется своей стороной:
 * пока экран на месте, колбэки работают (иначе {@code StrictMode} оставил бы
 * экран на «Загружаем…» навсегда), а уйдя с экрана, они молчат.
 *
 * <p>Полный прогон этого класса не доказывает ничего: обычный тест
 * размонтирует экран в {@code afterEach}, когда среда цела. Гонку здесь
 * устраивают руками — {@code cleanup()} сразу после нажатия и снос
 * {@code window}.
 */
describe('сторож размонтирования на регистрации', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      // Ответ с задержкой: колбэк обязан прийти ПОСЛЕ ухода с экрана.
      await sleep(80);
      if (String(input).includes('/api/registration/challenge')) {
        return json({ challenge: 'v1.9999999999.n.s', bits: 2 });
      }
      if (String(input).includes('/api/registration/companies')) {
        return json({ companyCode: 'razborka', companyName: 'Разборка', replayed: false }, 201);
      }
      return new Response(null, { status: 401 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('в StrictMode форма доезжает, а не висит на «Загружаем…»', async () => {
    render(<StrictMode><RegistrationScreen /></StrictMode>);

    await screen.findByRole('button', { name: 'Зарегистрировать' }, { timeout: 5000 });
    expect(screen.queryByText('Загружаем…')).toBeNull();
  });

  it('уход с экрана посреди загрузки задания не будит снесённую среду', async () => {
    await leaveAndTearDown(() => { render(<RegistrationScreen />); });
  });

  it('уход с экрана посреди отправки формы не будит снесённую среду', async () => {
    await leaveAndTearDown(async () => {
      render(<RegistrationScreen />);
      await screen.findByRole('button', { name: 'Зарегистрировать' }, { timeout: 5000 });
      fireEvent.change(screen.getByLabelText(/Название компании/), { target: { value: 'К' } });
      fireEvent.change(screen.getByLabelText(/Код компании/), { target: { value: 'razborka' } });
      fireEvent.change(screen.getByLabelText(/Почта/), { target: { value: 'a@b.ru' } });
      fireEvent.change(screen.getByLabelText(/Логин/), { target: { value: 'hozyain' } });
      fireEvent.change(screen.getByLabelText(/Пароль/), { target: { value: 'parol-12345' } });
      fireEvent.click(screen.getByRole('button', { name: 'Зарегистрировать' }));
    });
  });
});

/**
 * Делает действие, тут же уходит с экрана, сносит среду и ждёт, пока догонят
 * колбэки. Необработанный отказ — это и есть тот самый {@code ReferenceError}:
 * обработчик события зовёт {@code void}, и больше он нигде не всплывает.
 */
async function leaveAndTearDown(action: () => void | Promise<void>): Promise<void> {
  const escaped: unknown[] = [];
  const collect = (reason: unknown): void => { escaped.push(reason); };
  process.on('unhandledRejection', collect);

  try {
    await action();
    cleanup();

    const globals = globalThis as unknown as { window?: unknown };
    const savedWindow = globals.window;
    delete globals.window;
    await sleep(500);
    globals.window = savedWindow;

    expect(escaped).toEqual([]);
  } finally {
    process.off('unhandledRejection', collect);
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
