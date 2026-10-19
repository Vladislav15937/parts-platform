import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { WheelsScreen } from './WheelsScreen';
import type { Wheel } from '../inventory/wheels';

/**
 * Износ шины в процентах (задача 0340, решение владельца 19.10.2026).
 *
 * <p>Как у клиента в Bazon: «Износ, %» в форме, «30%» в таблице и карточке.
 * Проверяется то, что видит и делает владелец: поле формы уходит на сервер
 * полем {@code wearPercent} числом без знака (набирают «30%» — так написано
 * у клиента), а заведённое показывается процентом, а не миллиметрами.
 */
describe('износ шины в процентах', () => {
  const posted: Array<Record<string, unknown>> = [];
  // Что ответит сервер на заведение: пусто — принял, иначе 400 с этими словами.
  let refuse = '';

  function wheel(): Wheel {
    return {
      id: 7, number: 347, publicCode: 'W1A2B3C4D5E6',
      title: 'Шина 225/55 R18 Dunlop Winter Maxx SJ8 зимняя (липучка)',
      price: 7500, status: 'IN_STOCK', qty: 1,
      kind: 'TYRE', setNo: null, setPieces: null, diameter: 18, tyreWidth: 225, tyreHeight: 55,
      construction: 'R', tyreType: 'Легковая', season: 'WINTER_FRICTION', wearPercent: 30,
      madeYear: 2014, discType: null, discWidth: null, offsetMm: null,
      boltPattern: null, hubBore: null,
      brand: 'Dunlop', model: 'Winter Maxx SJ8', discBrand: null, discModel: null,
      markingType: null, treadType: null, runFlat: null, lightTruck: null,
      speedIndex: null, loadIndex: null,
      partName: '', condition: 'USED', supply: null, donorCode: null, oem: null,
      description: null, note: null, section: null, published: true,
      barcode: null, legacyCode: null, photoCount: 0,
      createdAt: null, updatedAt: null, updatedByName: null,
      priceChangedAt: null, priceChangedByName: null, stock: { '1': 1 },
    };
  }

  beforeEach(() => {
    posted.length = 0;
    refuse = '';
    try { localStorage.clear(); } catch { /* нет хранилища — и так умолчание */ }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/wheels/sets')) {
        posted.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
        if (refuse) return json({ message: refuse }, 400);
        return json({ setNo: null, title: 'Шина 225/55 R18', partIds: [8] }, 201);
      }
      if (url.includes('/api/organization/warehouses')) {
        return json([{ id: 1, name: 'Исакова', branchId: 1, branchName: 'Филиал' }]);
      }
      if (url.includes('/api/wheels')) {
        return json({
          total: 1,
          warehouses: [{ id: 1, name: 'Исакова' }],
          filterable: [],
          rows: [{ wheel: wheel(), photoUrl: null }],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('«30%» из поля «Износ, %» уходит числом 30, без миллиметров', async () => {
    render(<WheelsScreen canIntake role="OWNER" />);
    const form = (await screen.findByText('Завести')).closest('.card') as HTMLElement;

    fireEvent.change(within(form).getByLabelText('Склад'), { target: { value: '1' } });
    fireEvent.change(within(form).getByLabelText('Износ, %'), { target: { value: '30%' } });
    fireEvent.click(within(form).getByText('Завести'));

    await waitFor(() => expect(posted).toHaveLength(1));
    const body = posted[0] ?? {};
    expect(body.wearPercent, 'износ уехал не тем числом').toBe('30');
    expect('wearMm' in body, 'в запросе осталось поле миллиметров').toBe(false);
  });

  it('отказ сервера по износу стоит у поля «Износ, %», а не только над формой', async () => {
    const words = 'Износ шины — целое число процентов от 0 до 100';
    refuse = words;
    render(<WheelsScreen canIntake role="OWNER" />);
    const form = (await screen.findByText('Завести')).closest('.card') as HTMLElement;

    fireEvent.change(within(form).getByLabelText('Склад'), { target: { value: '1' } });
    fireEvent.change(within(form).getByLabelText('Износ, %'), { target: { value: '30,5' } });
    fireEvent.click(within(form).getByText('Завести'));

    const alert = await within(form).findByRole('alert');
    expect(alert.textContent).toBe(words);
    expect(alert.closest('label')?.textContent, 'слова не у поля износа').toContain('Износ, %');
    // Правка поля снимает отказ: он про прежнее значение.
    fireEvent.change(within(form).getByPlaceholderText('30'), { target: { value: '30' } });
    expect(within(form).queryByRole('alert')).toBeNull();
  });

  it('в таблице и в карточке износ — «30%», а не миллиметры', async () => {
    render(<WheelsScreen canIntake={false} role="OWNER" />);
    await waitFor(() => expect(screen.getByText('W1A2B3C4D5E6')).toBeTruthy());

    const headers = Array.from(document.querySelectorAll('thead th'))
      .map((cell) => (cell.textContent ?? '').trim());
    const at = headers.findIndex((title) => title.startsWith('Износ'));
    expect(at, `колонки «Износ» нет: ${headers.join(' · ')}`).toBeGreaterThanOrEqual(0);
    const cells = Array.from(document.querySelectorAll('tbody tr td'))
      .map((cell) => (cell.textContent ?? '').trim());
    expect(cells[at]).toBe('30%');

    fireEvent.click(screen.getByText('W1A2B3C4D5E6').closest('tr') as HTMLElement);
    await waitFor(() => expect(document.querySelector('dl')).not.toBeNull());
    const terms = Array.from(document.querySelectorAll('dl dt'));
    const wear = terms.find((term) => term.textContent === 'Износ');
    expect(wear?.nextElementSibling?.textContent).toBe('30%');
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
