import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { OrganizationScreen } from './OrganizationScreen';
import { PartShelfBlock } from './PartShelfBlock';
import { ShelfMarking } from './ShelfMarking';
import { bySchema, markingAddresses, normalizeAddress } from '../inventory/storageAddress';

/**
 * Адрес хранения из пяти частей и «Маркировка полок склада» (задача 0368).
 *
 * <p>Проверяется тем, что видит человек: сколько этикеток уйдёт в печать
 * и что на них напечатано, какая ячейка названа «не по схеме», и что уезжает
 * на сервер, когда кладовщик ставит деталь на полку.
 */

const sent: { url: string; method: string; body: unknown }[] = [];

beforeEach(() => {
  sent.length = 0;
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    sent.push({ url, method, body: init?.body === undefined ? null : JSON.parse(String(init.body)) });
    if (url.includes('/section')) {
      return json({ section: '01-01-03-01-02' });
    }
    if (url.includes('/cells')) {
      return json([
        { id: 1, warehouseId: 1, code: 'А-01-1' },
        { id: 2, warehouseId: 1, code: '01-01-03-01-02' },
      ]);
    }
    if (url.includes('/warehouses')) {
      return json([{ id: 1, branchId: 1, name: 'Склад А', isActive: true, cells: 2 }]);
    }
    if (url.includes('/branches')) {
      return json([{ id: 1, name: 'Филиал' }]);
    }
    return json([]);
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('адрес из пяти частей', () => {
  it('разбирается по схеме, короткая часть — та же полка', () => {
    expect(normalizeAddress('01-01-03-01-02')).toBe('01-01-03-01-02');
    expect(normalizeAddress('01-01-3-1-2'), 'опечатка без нуля — новая полка').toBe('01-01-03-01-02');
    expect(bySchema('А-01-1'), 'прежняя ячейка выдана за адрес по схеме').toBe(false);
    expect(bySchema('01-01-03-01')).toBe(false);
    expect(bySchema('01-01-03-01-100')).toBe(false);
  });

  it('склад 01, зона 1, ряд 3, секции 1–12, полки 1–6 — 72 адреса 01-01-03-ss-pp', () => {
    const all = markingAddresses(1, {
      zone: '1', row: '3', sectionFrom: '1', sectionTo: '12', shelfFrom: '1', shelfTo: '6',
    });
    expect(all).toHaveLength(72);
    expect(all[0]).toBe('01-01-03-01-01');
    expect(all[1]).toBe('01-01-03-01-02');
    expect(all[71]).toBe('01-01-03-12-06');
    expect(new Set(all).size, 'адреса повторяются').toBe(72);
  });
});

describe('«Маркировка полок склада»', () => {
  it('печатает лист из 72 этикеток с адресами', () => {
    render(<ShelfMarking warehouseId={1} warehouseName="Склад А" onClose={() => {}} />);
    type('Ряд на складе', '3');
    type('Маркируемые секции: по', '12');
    type('Полки в каждой секции: по', '6');

    expect(screen.getByText(/К печати: 72 этикетки/)).toBeTruthy();
    const titles = [...document.querySelectorAll('.label-sheet .label-title')]
      .map((t) => t.textContent);
    expect(titles).toHaveLength(72);
    expect(titles[1]).toBe('01-01-03-01-02');
    expect(titles.every((t) => /^01-01-03-(0[1-9]|1[0-2])-0[1-6]$/.test(t ?? '')),
      'на этикетке не адрес из пяти частей').toBe(true);
    expect((screen.getByRole('button', { name: 'Напечатать' }) as HTMLButtonElement).disabled)
      .toBe(false);
  });

  it('«по» меньше «с» — печатать нечего, и сказано почему', () => {
    render(<ShelfMarking warehouseId={1} warehouseName="Склад А" onClose={() => {}} />);
    type('Маркируемые секции: с', '5');
    type('Маркируемые секции: по', '2');
    expect(screen.getByText('Секции: «по» меньше, чем «с».')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Напечатать' }) as HTMLButtonElement).disabled)
      .toBe(true);
    expect(document.querySelector('.label-sheet')).toBeNull();
  });

  it('открывается с экрана «Склады» у склада', async () => {
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Склад А')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Маркировка полок: Склад А' }));
    expect(screen.getByText('Маркировка полок склада')).toBeTruthy();
    expect((screen.getByLabelText('Склад') as HTMLInputElement).value).toBe('Склад А');
  });
});

describe('прежние ячейки', () => {
  it('не пропали и названы «не по схеме», адрес по схеме — без пометки', async () => {
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Склад А')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Ячейки' }));
    await waitFor(() => expect(screen.getByText(/А-01-1/)).toBeTruthy());
    const chips = [...document.querySelectorAll('.chip')].map((c) => c.textContent);
    expect(chips).toEqual(['А-01-1 · не по схеме', '01-01-03-01-02']);
  });
});

describe('поставить на полку', () => {
  it('адрес без нулей уезжает той же полкой', async () => {
    const changed = vi.fn();
    render(<PartShelfBlock partId={7} section={null} canPlace onChanged={changed} />);
    fireEvent.click(screen.getByRole('button', { name: 'Поставить на полку' }));
    type('Адрес полки', '01-01-3-1-2');
    expect(screen.getByText('Будет записано: 01-01-03-01-02')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Поставить' }));
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(sent.find((r) => r.url === '/api/parts/7/section')).toMatchObject({
      method: 'PUT', body: { section: '01-01-03-01-02' },
    });
  });

  it('не адрес — кнопка серая и называет схему, запроса нет', () => {
    render(<PartShelfBlock partId={7} section={null} canPlace onChanged={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'Поставить на полку' }));
    type('Адрес полки', 'А-01-1');
    expect((screen.getByRole('button', { name: 'Поставить' }) as HTMLButtonElement).disabled)
      .toBe(true);
    expect(screen.getByText(/не по схеме «склад-зона-ряд-секция-полка»/)).toBeTruthy();
    expect(sent.some((r) => r.url.includes('/section'))).toBe(false);
  });

  it('без права кнопки нет', () => {
    render(<PartShelfBlock partId={7} section={null} canPlace={false} onChanged={() => {}} />);
    expect(screen.queryByRole('button', { name: 'Поставить на полку' })).toBeNull();
  });
});

function type(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
