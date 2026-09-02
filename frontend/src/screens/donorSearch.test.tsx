import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';

/**
 * Список машин ищется, а не прокручивается.
 *
 * <p><b>Зачем.</b> У переехавшего клиента 441 машина, и список идёт простынёй
 * в 27 801 пиксель — тридцать четыре экрана подряд, замерено в браузере.
 * Владелец приходит сюда за одной машиной: положить на неё эвакуатор или
 * перевести в разбор, — а найти её мог только глазами. Возможность есть,
 * воспользоваться нельзя: та же болезнь, что с правкой списком в семьсот
 * страниц и переносом снимков в девятьсот нажатий.
 *
 * <p>Ищем по тому же, что видно в строке: клиент помнит машину по своему
 * номеру и по заметке, а не по нашему внутреннему коду.
 */
describe('поиск по списку машин', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [] });
      }
      if (url.includes('/costs')) {
        return json([]);
      }
      if (url.includes('/api/intake/donors')) {
        return json([
          { id: 1, code: '261', brand: 'Toyota', model: 'RAV4', year: 2019,
            vin: null, status: 'DISMANTLED', note: 'Синий маркер' },
          { id: 2, code: '418', brand: 'Toyota', model: 'Vanguard', year: 2011,
            vin: null, status: 'DISMANTLED', note: null },
          { id: 3, code: '77', brand: 'Honda', model: 'Fit', year: 2004,
            vin: 'JHMGD18604S000777', status: 'PURCHASED', note: null },
        ]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('сужает список по номеру клиента и говорит, сколько показано', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(3));

    fireEvent.change(search(), { target: { value: '261' } });

    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(1));
    expect(document.querySelector('tbody tr')!.textContent).toContain('RAV4');
    // Обрезанный список обязан сказать, что он обрезан.
    expect(screen.getByText(/Показано 1 машина из 3/)).toBeTruthy();
  });

  it('находит по заметке, по модели и по VIN — тому, что владелец помнит', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(3));

    for (const [needle, expected] of [['маркер', 1], ['toyota', 2], ['000777', 1]] as const) {
      fireEvent.change(search(), { target: { value: String(needle) } });
      await waitFor(() =>
        expect(document.querySelectorAll('tbody tr').length,
          `по запросу «${needle}» найдено не то`).toBe(expected));
    }
  });

  it('пустая выдача объясняет себя, а не выглядит пустым складом', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(3));

    fireEvent.change(search(), { target: { value: 'нетакоймашины' } });

    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(0));
    expect(screen.getByText(/Ничего не найдено среди 3/)).toBeTruthy();
  });
});

function search(): HTMLInputElement {
  return document.querySelector('input[type="search"]') as HTMLInputElement;
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
