import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Кнопка «Разложить «Подходит»» на экране «Импорт» (задача 0289).
 *
 * <p>Стоит рядом с «Проставить применимость» (разбор наименований) и зовёт
 * свой путь; итог прохода — словами, а не сырым ответом: сколько позиций
 * прошло, сколько строк заведено, дополнено и снято, и что не легло —
 * по причинам, уникальными названиями из блока клиента.
 */
describe('применимость из карточек Дрома', () => {
  let report: unknown;
  let calls: string[];

  beforeEach(() => {
    calls = [];
    report = {
      parts: 2897,
      added: 9120,
      refined: 14,
      removed: 2410,
      misses: [
        { reason: 'модель не найдена у марки', items: 1603, names: ['Toyota Dyna', 'Lexus GX400'], unique: 43 },
        { reason: 'поколение не найдено', items: 1,
          names: ['Mitsubishi Delica — 3 поколение, рестайлинг (1988–1997)'], unique: 1 },
      ],
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'POST') {
        calls.push(url);
      }
      if (url.endsWith('/api/parts/catalog/applicability/from-drom')) {
        return json(report);
      }
      return json({ done: 0, failed: 0, pending: 0, total: 0, broken: 0 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('зовёт свой путь и называет итог словами, с причинами и названиями', async () => {
    render(<ImportScreen reference={reference()} canImport />);
    fireEvent.click(screen.getByRole('button', { name: 'Разложить «Подходит»' }));

    await waitFor(() => expect(text()).toContain('Позиций обработано: 2 897.'));
    expect(calls).toEqual(['/api/parts/catalog/applicability/from-drom']);
    expect(text()).toContain(
      'Строк применимости заведено: 9 120, дополнено годами: 14, снято менее точных: 2 410.');
    expect(text()).toContain('Пунктов, не легших в справочник целиком: 1 604.');
    expect(text()).toContain('модель не найдена у марки: Toyota Dyna, Lexus GX400 и ещё 41');
    expect(text()).toContain('поколение не найдено (марка, модель и годы легли): '
      + 'Mitsubishi Delica — 3 поколение, рестайлинг (1988–1997)');
    expect(text(), 'сырой ответ на экране').not.toContain('"added"');
  });

  it('повтор без изменений говорит «ничего нового», а не три нуля', async () => {
    report = { parts: 2897, added: 0, refined: 0, removed: 0, misses: [] };
    render(<ImportScreen reference={reference()} canImport />);
    fireEvent.click(screen.getByRole('button', { name: 'Разложить «Подходит»' }));

    await waitFor(() => expect(text()).toContain(
      'Ничего нового: проверено 2 897 позиций, применимость уже разложена.'));
    expect(text()).not.toContain('Строк применимости заведено');
    expect(text()).not.toContain('не легших');
  });
});

/** Текст экрана одной строкой: числа приходят с неразрывными пробелами разрядов. */
function text(): string {
  return (document.body.textContent ?? '').replace(/\s+/g, ' ');
}

function reference(): never {
  return {
    warehouses: [{ id: 7, name: 'Ткацкая' }], supplies: [], donors: [], cells: [], partNames: [],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
