import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { MembersScreen } from './MembersScreen';

/** Отклонённое обещание из размонтированного колбэка ловится только на хосте. */
declare const process: {
  on(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
  off(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
};

/**
 * Сторож размонтирования на «Сотрудниках» (задача 0044): роли и права.
 *
 * <p>Образец — `inventoryMountedGuard.test.tsx`, и доводы те же: у сторожа два
 * обязательства, и каждое проверяется своей стороной. В `StrictMode` колбэки
 * обязаны работать (сторож, который только снимается, оставил бы экран
 * на «Загружаем…» навсегда), а уйдя с вкладки, обязаны молчать — иначе
 * колбэк догоняет снесённую среду и роняет чужой файл
 * `ReferenceError: window is not defined`. Полный прогон этого не видит.
 *
 * <p>Ответ сервера здесь нарочно приходит с опозданием: уход с вкладки должен
 * случиться, пока запрос в пути. Запись состояния ушедшего компонента React 18
 * пропускает молча, поэтому видно здесь то, что сторож предотвращает
 * наблюдаемо: перечитывание экрана (четыре запроса) после ухода и отказ
 * из снесённой среды.
 */
describe('сторож размонтирования на сотрудниках', () => {
  let requests: string[];

  beforeEach(() => {
    requests = [];
    forgetCatalog();
    vi.stubGlobal('confirm', () => true);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      requests.push(`${method} ${url}`);
      await sleep(method === 'GET' ? 20 : 150);
      if (method !== 'GET') {
        return json({ id: 30, name: 'x', owner: false, permissions: [], members: 0 });
      }
      if (url.endsWith('/api/members')) {
        return json([
          { id: 2, number: 2, login: 'ivan', displayName: 'Иван', role: 'VIEWER', active: true, lastLoginAt: null },
        ]);
      }
      if (url.endsWith('/api/access/members')) {
        return json([{ memberId: 2, roleId: 11, roleName: 'Приёмщик', owner: false,
          added: [], removed: [], effective: ['intake.receive'] }]);
      }
      if (url.endsWith('/api/access/roles')) {
        return json([
          { id: 11, name: 'Приёмщик', owner: false, permissions: ['intake.receive'], members: 1 },
          { id: 12, name: 'Пустая', owner: false, permissions: [], members: 0 },
        ]);
      }
      if (url.endsWith('/api/access/catalog')) {
        return json([{ title: 'Приёмка', permissions: [
          { key: 'intake.receive', title: 'Принимать товар', hint: null }] }]);
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

  it('в StrictMode роли, права и справочник доезжают до экрана', async () => {
    render(<StrictMode><MembersScreen /></StrictMode>);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Изменить роль: Приёмщик' })).toBeTruthy(), { timeout: 3000 });
    expect(screen.getByRole('button', { name: 'Изменить роль: Пустая' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Права: ivan' }));
    await waitFor(() => expect(screen.getByLabelText(/Принимать товар/)).toBeTruthy(),
      { timeout: 3000 });
  });

  const LEAVES: Array<[string, () => void]> = [
    ['загрузка', () => {}],
    ['сохранение роли', () => {
      fireEvent.click(screen.getByRole('button', { name: 'Новая роль' }));
      fireEvent.change(screen.getByLabelText('Название роли'), { target: { value: 'Новая' } });
      fireEvent.click(screen.getByRole('button', { name: 'Сохранить роль' }));
    }],
    ['удаление роли', () => {
      fireEvent.click(screen.getByRole('button', { name: 'Удалить роль: Пустая' }));
    }],
    ['сохранение прав', () => {
      fireEvent.click(screen.getByRole('button', { name: 'Права: ivan' }));
      fireEvent.click(screen.getByRole('button', { name: 'Сохранить права' }));
    }],
  ];

  it.each(LEAVES)('уход с вкладки посреди «%s» не будит снесённую среду', async (what, act) => {
    const escaped: unknown[] = [];
    const collect = (reason: unknown): void => { escaped.push(reason); };
    process.on('unhandledRejection', collect);

    try {
      render(<MembersScreen />);
      if (what !== 'загрузка') {
        await waitFor(() => expect(screen.getByRole('button', { name: 'Изменить роль: Приёмщик' })).toBeTruthy());
        await waitFor(() => expect(screen.getByRole('button', { name: 'Права: ivan' })).toBeTruthy());
      }
      act();
      cleanup();

      const globals = globalThis as unknown as { window?: unknown };
      const savedWindow = globals.window;
      delete globals.window;
      await sleep(400);
      globals.window = savedWindow;

      expect(escaped).toEqual([]);
    } finally {
      process.off('unhandledRejection', collect);
    }
  });

  // Ответ, пришедший после ухода, не перечитывает экран, которого нет:
  // без сторожа сохранение звало бы reload — четыре запроса в пустоту.
  // Среда здесь цела намеренно: со снятым window запрос отказал бы раньше
  // fetch, и считать было бы нечего.
  it.each(LEAVES.slice(1))('ответ «%s» после ухода не перечитывает экран', async (_what, act) => {
    render(<MembersScreen />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Права: ivan' })).toBeTruthy());
    await waitFor(() => expect(screen.getByRole('button', { name: 'Изменить роль: Пустая' })).toBeTruthy());
    act();
    await waitFor(() => expect(requests.some((r) => !r.startsWith('GET'))).toBe(true));
    cleanup();
    const before = requests.length;
    await sleep(400);
    expect(requests.slice(before), 'ушедший экран пошёл за данными').toEqual([]);
  });
});

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
