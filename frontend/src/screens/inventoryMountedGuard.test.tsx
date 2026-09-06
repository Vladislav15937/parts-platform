import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryScreen } from './InventoryScreen';

/**
 * Ошибка из размонтированного колбэка никуда не возвращается: обработчик
 * зовёт {@code void start(...)}, и отклонённое обещание всплывает только
 * необработанным — поймать его можно лишь на хосте, где vitest выполняется.
 *
 * <p>Объявлено здесь, а не через {@code @types/node}: типов Node в проекте
 * нет ни одной строкой, и заводить зависимость ради двух вызовов дороже,
 * чем назвать то, чем пользуемся.
 */
declare const process: {
  on(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
  off(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
};

/**
 * Сторож «экран ещё на месте» на экране пересчёта.
 *
 * <p>У сторожа два обязательства, и каждое проверяется своей стороной —
 * поодиночке любое из них выполняется тривиально и неправильно:
 *
 * <ul>
 *   <li><b>Пока экран на месте, колбэки работают.</b> Ловится
 *       {@code StrictMode}: в разработке он прогоняет эффекты дважды
 *       (setup → cleanup → setup), и сторож, который только снимается,
 *       остаётся {@code false} навсегда. Экран при этом не падает — он
 *       молчит: «Загружаем…» не сменяется числом, кнопки открытия не
 *       зажигаются обратно, отказ сервера не показывается.</li>
 *   <li><b>Уйдя с вкладки, колбэки молчат.</b> Ловится сносом среды:
 *       {@code cleanup()} сразу после нажатия и снятый {@code window} —
 *       так vitest убирает среду между файлами. Незакрытый колбэк даёт
 *       {@code ReferenceError: window is not defined}, и это ровно то,
 *       чем краснела {@code main}.</li>
 * </ul>
 *
 * <p><b>Почему полный прогон это не ловит.</b> React 18 на правку состояния
 * размонтированного компонента не говорит ничего — предупреждение убрали
 * в 18.0. Обычный тест размонтирует экран в {@code afterEach}, когда среда
 * ещё цела и колбэки уже отработали, поэтому набор остаётся зелёным при обеих
 * поломках. Замерено: со сломанным сторожем и без этого файла проходят все
 * 369 остальных тестов — то есть полный прогон здесь не отличает починенное
 * от сломанного и доказательством не является.
 */
describe('сторож размонтирования на пересчёте', () => {
  let openRequests: number;

  beforeEach(() => {
    openRequests = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);

      if (url.startsWith('/api/inventory/count')) {
        return json({ count: 312 });
      }
      // Открытой сессии на складе нет: экран обязан сказать это словами.
      if (url.startsWith('/api/inventory/sessions/open')) {
        return empty();
      }
      if (url === '/api/inventory/sessions' && init?.method === 'POST') {
        openRequests += 1;
        return json({ id: 1, warehouseId: 2, status: 'OPEN', lines: 0, counted: 0 });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('в StrictMode счётчик отвечает числом, а не висит на «Загружаем…»', async () => {
    render(<StrictMode><InventoryScreen reference={reference()} onCount={vi.fn()} /></StrictMode>);

    fireEvent.change(await warehouseSelect(), { target: { value: '2' } });

    await waitFor(() => expect(screen.getByText('Найдено товаров: 312')).toBeTruthy());
  });

  it('в StrictMode отказ виден, а кнопки открытия зажигаются обратно', async () => {
    render(<StrictMode><InventoryScreen reference={reference()} onCount={vi.fn()} /></StrictMode>);

    // Счётчика здесь намеренно не ждём: эта проверка про отказ и кнопки,
    // и падать она должна на них, а не на чужом утверждении.
    fireEvent.change(await warehouseSelect(), { target: { value: '2' } });

    const resume = screen.getByRole('button', { name: 'Продолжить начатую' });
    fireEvent.click(resume);

    await waitFor(() =>
      expect(screen.getByText('На этом складе инвентаризация не открыта')).toBeTruthy());
    expect(resume.hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('button', { name: 'Открыть новую' }).hasAttribute('disabled'))
      .toBe(false);
  });

  it('уход с вкладки посреди открытия не будит снесённую среду', async () => {
    const escaped: unknown[] = [];
    const collect = (reason: unknown): void => { escaped.push(reason); };
    process.on('unhandledRejection', collect);

    try {
      render(<InventoryScreen reference={reference()} onCount={vi.fn()} />);

      fireEvent.change(await warehouseSelect(), { target: { value: '2' } });
      await waitFor(() => expect(screen.getByText('Найдено товаров: 312')).toBeTruthy());

      // Нажали и в тот же тик ушли с вкладки: ответ сервера ещё в пути.
      fireEvent.click(screen.getByRole('button', { name: 'Открыть новую' }));
      cleanup();

      // Так vitest убирает среду между файлами — а колбэк ещё не вернулся.
      const globals = globalThis as unknown as { window?: unknown };
      const savedWindow = globals.window;
      delete globals.window;
      await sleep(500);
      globals.window = savedWindow;

      expect(openRequests).toBe(1);
      expect(escaped).toEqual([]);
    } finally {
      process.off('unhandledRejection', collect);
    }
  });
});

/** Пауза без Node API: в `src/` их нет ни одной, и заводить не за чем. */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

async function warehouseSelect(): Promise<HTMLSelectElement> {
  return await waitFor(() => {
    const found = document.querySelectorAll('select');
    expect(found).toHaveLength(2);
    return found[0] as HTMLSelectElement;
  });
}

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [
      { id: 2, name: 'Ткацкая', cells: [{ id: 10, code: 'А-01-1', zone: null }] },
    ],
    supplies: [],
    donors: [],
    partNames: [],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 204: открытой сессии нет — не отказ, а «ничего не нашлось». */
function empty(): Response {
  return new Response(null, { status: 204 });
}
