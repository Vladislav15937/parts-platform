import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';

/**
 * Итоги поставки под её строкой (задача 0375): поступления по статусам и на какую
 * сумму, товары штуками, машины и их себестоимость — словами окна поставки
 * ориентира («Черновики — 1 (на 0 руб.)», «Остаток — 120 шт.», «Авто-доноры»).
 *
 * <p>До задачи итогов в строке поставки не было вовсе: «Партия / Поставщик /
 * Состояние / Приход» и кнопки «Отметить приход», «Машины», «Ожидаемый товар».
 */
describe('поставка: итоги', () => {
  let summaryFails: boolean;
  let asked: string[];

  beforeEach(() => {
    summaryFails = false;
    asked = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/summary')) {
        asked.push(url);
        if (summaryFails) {
          return new Response('{"message":"Поставка не найдена — обновите страницу, список устарел"}',
            { status: 400, headers: { 'Content-Type': 'application/json' } });
        }
        return json({
          receipts: {
            drafts: { count: 1, amount: 400 },
            done: { count: 2, amount: 5700 },
            cancelled: { count: 0, amount: 0 },
            total: { count: 3, amount: 6100 },
          },
          goods: { remaining: 120, sold: 40, writtenOff: 0, total: 160 },
          donors: { count: 3, cost: 600000 },
        });
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('раскрывается под строкой словами окна поставки', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const buttons = await waitFor(() => screen.getAllByRole('button', { name: 'Итоги' }));

    fireEvent.click(buttons[0]!);

    await waitFor(() => expect(screen.getByText('Поступления')).toBeTruthy());
    expect(asked[0], 'итоги спрошены не у той поставки').toContain('/api/intake/supplies/19/summary');

    const text = (document.body.textContent ?? '').replace(/ /g, ' ');
    expect(text).toContain('Черновики — 1 (на 400 руб.)');
    expect(text).toContain('Выполнены — 2 (на 5 700 руб.)');
    expect(text).toContain('Отменены — 0 (на 0 руб.)');
    expect(text).toContain('Всего — 3 (на 6 100 руб.)');

    expect(screen.getByText('Товары')).toBeTruthy();
    expect(text).toContain('Остаток — 120 шт.');
    expect(text).toContain('Продано — 40 шт.');
    expect(text).toContain('Списано — 0 шт.');
    expect(text).toContain('Всего — 160 шт.');

    // «с/с» ориентира расшифрована словом — так велит задача.
    expect(screen.getByText('Авто-доноры')).toBeTruthy();
    expect(text).toContain('Всего — 3 шт. (себестоимость 600 000 руб.)');

    // Повторное нажатие сворачивает: кнопка говорит, что сделает.
    fireEvent.click(screen.getByRole('button', { name: 'Скрыть итоги' }));
    expect(screen.queryByText('Поступления')).toBeNull();
  });

  it('не смогли узнать — говорит причину, а не нули', async () => {
    summaryFails = true;
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const buttons = await waitFor(() => screen.getAllByRole('button', { name: 'Итоги' }));

    fireEvent.click(buttons[0]!);

    // Сначала дождаться причины, потом проверять отсутствие итогов: пока
    // на экране «Загружаем…», отрицание прошло бы само собой.
    await waitFor(() => expect(screen.getByText(/Поставка не найдена/)).toBeTruthy());
    expect(screen.queryByText('Поступления'), 'отказ выдан за итоги').toBeNull();
    expect(screen.queryByText(/Остаток —/)).toBeNull();
  });
});

function reference(): never {
  return {
    warehouses: [], donors: [], cells: [], partNames: [],
    supplies: [
      { id: 19, kind: 'CONTAINER', number: '18', supplierName: 'Иокогама',
        status: 'ARRIVED', arrivedOn: '2026-09-30' },
    ],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
