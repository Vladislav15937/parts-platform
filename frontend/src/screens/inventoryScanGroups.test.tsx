import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { InventoryScreen } from './InventoryScreen';
import { forgetSession } from '../inventory/inventory';

/**
 * Три группы и четыре вкладки пересчёта (задача 0019).
 *
 * <p>Строка, отсканированная вне листа, приезжает от сервера уже с учётным
 * нулём ({@code qtyExpected: '0'}) — это и есть признак «вне списка»
 * (см. {@code statusOf} в {@code inventory/counts.test.ts}), а не отдельное
 * поле. Тут проверяется то, что видит человек: три группы под вкладкой
 * «Все», перенос строки во «Отсканированы» после внесения факта и фильтр
 * по вкладке «С проблемами».
 *
 * <p>Разбор самого скана штрихкода (в списке / вне списка / не найден)
 * проверен отдельно как чистая функция — driving the real camera overlay
 * from a test is not how scanning is tested anywhere else in this project.
 */
describe('группы и вкладки пересчёта', () => {
  beforeEach(async () => {
    // Сессия во всех тестах файла получает один и тот же id — иначе adopt()
    // не сбросит локальные подсчёты между тестами, потому что сочтёт сессию
    // продолженной, а не новой.
    await forgetSession();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/inventory/count' || url.startsWith('/api/inventory/count?')) {
        return json({ count: 2 });
      }
      if (url === '/api/inventory/sessions' && init?.method === 'POST') {
        return json({ id: 1, warehouseId: 2, status: 'OPEN', lines: 0, counted: 0 });
      }
      if (url.includes('/sessions/1/lines')) {
        return json([
          { partId: 1, title: 'Фара левая', cellId: 10, cellCode: 'А-01-1',
            qtyExpected: '2', qtyCounted: null },
          // Учётный ноль — этот скан нашёл деталь, которой не было в листе.
          { partId: 2, title: 'Дверь передняя', cellId: 11, cellCode: 'А-01-2',
            qtyExpected: '0', qtyCounted: '1' },
        ]);
      }
      if (url.includes('/sessions/1/codes')) {
        return json([]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  async function openSession(): Promise<void> {
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} />);
    const warehouseSelect = await waitFor(() => document.querySelector('select') as HTMLSelectElement);
    fireEvent.change(warehouseSelect, { target: { value: '2' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть новую' }));
    await screen.findByText('Дверь передняя');
  }

  it('вкладка «Все» показывает три группы со своими статусами', async () => {
    await openSession();

    expect(screen.getByText('Фара левая').closest('li')?.textContent).toContain('Не сканирован');
    expect(screen.getByText('Дверь передняя').closest('li')?.textContent).toContain('Вне списка');
  });

  it('внесённый факт переводит строку в «Отсканированы»', async () => {
    await openSession();

    const row = screen.getByText('Фара левая').closest('li') as HTMLElement;
    fireEvent.change(row.querySelector('input')!, { target: { value: '2' } });
    fireEvent.click(within(row).getByRole('button', { name: 'Внести' }));

    await waitFor(() =>
      expect(screen.getByText('Фара левая').closest('li')?.textContent).toContain('Отсканирован'));
  });

  it('вкладка «С проблемами» показывает только эту строку', async () => {
    await openSession();

    fireEvent.click(screen.getByRole('button', { name: 'С проблемами' }));

    expect(screen.getByText('Дверь передняя')).toBeTruthy();
    expect(screen.queryByText('Фара левая')).toBeNull();
  });

  it('вкладка «Не сканировались» не показывает строки «вне списка»', async () => {
    await openSession();

    fireEvent.click(screen.getByRole('button', { name: 'Не сканировались' }));

    expect(screen.getByText('Фара левая')).toBeTruthy();
    expect(screen.queryByText('Дверь передняя')).toBeNull();
  });
});

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
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
