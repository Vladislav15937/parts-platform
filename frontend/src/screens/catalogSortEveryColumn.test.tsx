import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';
import { COLUMNS } from '../inventory/catalog';

/**
 * Сортировка у каждой колонки склада, включая колонки складов (задача 0367,
 * решение владельца продукта 23.10.2026: «сортировка по колонкам должна быть
 * на каждую колонку»).
 *
 * <p>До правки сортировались одиннадцать колонок из сорока шести, а у колонок
 * складов стрелки не было вовсе: «у кого больше всего на Ткацкой» владелец
 * узнать не мог. Колонка склада сортируется по свободному остатку — крупному
 * числу клетки «0 (1)», — и это решает сервер; экран отправляет её имя.
 */
describe('сортировка у каждой колонки склада', () => {
  const requests: string[] = [];

  beforeEach(() => {
    requests.length = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requests.push(url);
      if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')
          || url.includes('/values')) {
        return json([]);
      }
      return json({
        total: 2,
        warehouses: [{ id: 7, name: 'Ткацкая' }, { id: 9, name: 'Дальний' }],
        filterable: ['brand', 'condition'],
        rows: [
          { id: 1, number: 1, code: 'A-1', title: 'Фара левая', condition: 'USED',
            price: 1000, installationPrice: null, stock: { 7: 3 }, reserved: { 7: 1 },
            photoCount: 0 },
          { id: 2, number: 2, code: 'A-2', title: 'Фара правая', condition: 'NEW',
            price: 2000, installationPrice: null, stock: { 9: 1 }, reserved: {},
            photoCount: 0 },
        ],
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('у каждой колонки, кроме «Рестайлинга», есть имя сортировки', () => {
    // «Рестайлинг» — прочерк во всех строках: сортировать нечего, и стрелка
    // обещала бы действие, которого нет.
    const without = COLUMNS.filter((c) => c.sort === undefined).map((c) => c.title);
    expect(without, 'колонки без сортировки').toEqual(['Рестайлинг']);
  });

  it('стрелка меню стоит у каждой колонки таблицы, включая склады', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));

    const headers = [...document.querySelectorAll('thead th')];
    const bare = headers
      .filter((th) => th.querySelector('.th__menu') === null)
      .map((th) => th.textContent);
    expect(bare, 'колонки без стрелки сортировки').toEqual([]);
    expect(headers.map((th) => th.textContent?.replace(/[▾↑↓]/g, '')))
      .toEqual(expect.arrayContaining(['Ткацкая', 'Дальний']));
  });

  it('колонка склада сортирует по своему имени, в обе стороны', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));

    fireEvent.click(screen.getByRole('button', { name: 'Сортировка: Ткацкая' }));
    fireEvent.click(screen.getByRole('button', { name: '↓ по убыванию' }));
    await waitFor(() => expect(last()).toContain('sort=warehouse%3A7'));
    expect(last()).toContain('desc=true');

    // Выбранная сортировка видна на стрелке своей колонки, а не соседней.
    await waitFor(() => expect(
      screen.getByRole('button', { name: 'Сортировка: Ткацкая' }).textContent).toBe('↓'));
    expect(screen.getByRole('button', { name: 'Сортировка: Дальний' }).textContent).toBe('▾');

    fireEvent.click(screen.getByRole('button', { name: 'Сортировка: Дальний' }));
    fireEvent.click(screen.getByRole('button', { name: '↑ по возрастанию' }));
    await waitFor(() => expect(last()).toContain('sort=warehouse%3A9'));
    expect(last()).toContain('desc=false');
  });

  it('колонка, не сортировавшаяся до 0367, сортирует своим именем', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(2));

    const th = [...document.querySelectorAll('thead th')]
      .find((cell) => cell.querySelector('.th__title')?.textContent === 'Состояние');
    const arrow = th?.querySelector<HTMLButtonElement>('.th__menu');
    expect(arrow, 'у «Состояния» нет стрелки').toBeTruthy();
    fireEvent.click(arrow!);
    fireEvent.click(screen.getByRole('button', { name: '↑ по возрастанию' }));
    await waitFor(() => expect(last()).toContain('sort=condition'));
  });

  function last(): string {
    return [...requests].reverse().find((url) => url.includes('/api/parts/catalog?')) ?? '';
  }
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
