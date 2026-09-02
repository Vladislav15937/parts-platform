import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Перенос фотографий идёт пачками сам, а не по нажатию на каждую.
 *
 * <p><b>Зачем.</b> Пачка — это запрос, который живёт секунды, а очередь
 * у живого клиента 192 255 снимков: при двухстах на пачку это 961 нажатие
 * подряд. Экран так и предлагал — «нажимать, пока в очереди не станет
 * пусто», — то есть простоять у него смену. Замерено на живом переносе:
 * десять снимков в секунду, двести уходят за двадцать секунд, весь
 * перенос — часы.
 *
 * <p>А на разборке продаёт фотография: склад, переехавший без снимков, —
 * это прайс, по которому не покупают.
 */
describe('перенос фотографий', () => {
  let batches: number;

  beforeEach(() => {
    batches = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/import/bazon/photos') && init?.method === 'POST') {
        batches += 1;
        // Три пачки по 200 из шестисот — и очередь пуста.
        const pending = Math.max(0, 600 - batches * 200);
        return json({ done: 200, failed: 0, pending, total: batches * 200, broken: 0 });
      }
      if (url.includes('/api/import/bazon/photos')) {
        return json({ done: 0, failed: 0, pending: 600, total: 0, broken: 0 });
      }
      return json({});
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('гонит пачки, пока очередь не опустеет', async () => {
    render(<ImportScreen reference={reference()} canImport />);
    await waitFor(() => expect(screen.getByText(/ждёт 600/)).toBeTruthy());

    fireEvent.click(button('Перенести все')!);

    // Одна пачка на нажатие означала бы 961 нажатие у живого клиента.
    await waitFor(() => expect(batches).toBe(3));
    await waitFor(() => expect(screen.getByText(/ждёт 0/)).toBeTruthy());
  });

  /**
   * Пачка, целиком легшая в неудачные, очередь не двигает. Без проверки
   * «очередь не сдвинулась» цикл молотил бы по чужому CDN вечно.
   */
  it('останавливается, когда очередь перестала убывать', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('photos') && init?.method === 'POST') {
        batches += 1;
        return json({ done: 0, failed: 200, pending: 600, total: 0, broken: 200 * batches });
      }
      return json({ done: 0, failed: 0, pending: 600, total: 0, broken: 0 });
    }));

    render(<ImportScreen reference={reference()} canImport />);
    await waitFor(() => expect(screen.getByText(/ждёт 600/)).toBeTruthy());
    fireEvent.click(button('Перенести все')!);

    await waitFor(() => expect(batches).toBe(1));
    await waitFor(() => expect(button('Перенести все')).toBeTruthy());
    expect(batches, 'цикл продолжился при неподвижной очереди').toBe(1);
  });
  /**
   * Уход с раздела останавливает проход.
   *
   * <p>Промис размонтированием не отменяется: цикл жил бы дальше, а кнопки
   * «Остановить» на экране уже нет. Вернувшись, владелец увидел бы
   * «Перенести все» и запустил второй проход рядом с первым — двойная
   * нагрузка на чужой CDN и счётчик, прыгающий от двух источников.
   */
  it('останавливается при уходе с раздела', async () => {
    // Пачка отвечает не мгновенно: иначе цикл успевает уйти во вторую
    // раньше ухода с раздела, и проверка ничего не стережёт. Очередь при
    // этом длинная — сама по себе она не кончится.
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('photos') && init?.method === 'POST') {
        batches += 1;
        await new Promise((done) => { setTimeout(done, 40); });
        return json({ done: 200, failed: 0, pending: 10_000 - batches * 200,
                      total: batches * 200, broken: 0 });
      }
      return json({ done: 0, failed: 0, pending: 10_000, total: 0, broken: 0 });
    }));

    const view = render(<ImportScreen reference={reference()} canImport />);
    await waitFor(() => expect(screen.getByText(/ждёт 10.000/)).toBeTruthy());
    fireEvent.click(button('Перенести все')!);
    await waitFor(() => expect(batches).toBe(1));

    view.unmount();
    await new Promise((done) => { setTimeout(done, 300); });

    expect(batches, 'проход продолжился после ухода с раздела').toBe(1);
  });
});

function button(text: string): HTMLElement | undefined {
  return [...document.querySelectorAll('button')].find((b) => b.textContent === text);
}

function reference(): never {
  return { warehouses: [], supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
