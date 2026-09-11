import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { WheelsScreen } from './WheelsScreen';
import type { Wheel } from '../inventory/wheels';

/**
 * Номер позиции на вкладке «Шины и диски» (задача 0061).
 *
 * <p><b>Как это выглядело для человека.</b> Задача 0060 завела позиции свой
 * порядковый номер — тот, которым деталь называют вслух: «посмотри позицию
 * 347», — и показала его первой колонкой витрины склада. До карточки колеса
 * номер доезжал (колесо это та же `part`), а колонки на вкладке не было:
 * вкладка собирает свой состав колонок отдельно. То есть назвать колесо
 * цифрами в разговоре было нельзя, хотя номер у него есть.
 *
 * <p>Проверяется то, что видно человеку, и проверяется <b>по колонке</b>,
 * а не по тексту где-нибудь на странице: убранная колонка обязана валить
 * тест словами про отсутствие колонки, а не пустым экраном. Поэтому сначала
 * ищется заголовок, затем — ячейка строки ровно под ним.
 */
describe('номер позиции на вкладке «Шины и диски»', () => {
  const asked: string[] = [];

  /** Колесо со всеми полями строки: витрина читает их без разбора. */
  function wheel(): Wheel {
    return {
      id: 7, number: 347, publicCode: 'W1A2B3C4D5E6',
      title: 'Шина 195/65 R15 Goodyear EfficientGrip летняя',
      price: 3500, status: 'IN_STOCK', qty: 4,
      kind: 'TYRE', setNo: 12, diameter: 15, tyreWidth: 195, tyreHeight: 65,
      construction: 'R', tyreType: 'Легковая', season: 'SUMMER', wearMm: 5,
      madeYear: 2022, discType: null, discWidth: null, offsetMm: null,
      boltPattern: null, hubBore: null,
      brand: 'Goodyear', model: 'EfficientGrip', discBrand: null, discModel: null,
      markingType: 'METRIC', treadType: 'DIRECTIONAL', runFlat: false, lightTruck: false,
      speedIndex: 'H', loadIndex: 91,
      partName: 'Шина', condition: 'USED', supply: null, donorCode: null, oem: null,
      description: null, note: null, section: 'A-1', published: true,
      barcode: null, legacyCode: null, photoCount: 0,
      createdAt: null, updatedAt: null, updatedByName: null,
      priceChangedAt: null, priceChangedByName: null, stock: { '1': 4 },
    };
  }

  beforeEach(() => {
    asked.length = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      asked.push(url);
      if (url.includes('/organization/warehouses')) {
        return json([]);
      }
      if (url.includes('/api/wheels')) {
        return json({
          total: 1,
          warehouses: [{ id: 1, name: 'Ткацкая' }],
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

  it('колонка «№ позиции» есть, и в ней стоит номер этой позиции', async () => {
    render(<WheelsScreen canIntake={false} role="OWNER" />);
    await waitFor(() => expect(screen.getByText(/Goodyear/)).toBeTruthy());

    const headers = Array.from(document.querySelectorAll('thead th'))
      .map((cell) => (cell.textContent ?? '').trim());
    const at = headers.findIndex((title) => title.startsWith('№ позиции'));
    expect(
      at,
      `колонки «№ позиции» на вкладке нет: ${headers.join(' · ')}`,
    ).toBeGreaterThanOrEqual(0);

    // Первой, как на витрине склада: два соседних экрана, показывающих
    // один и тот же номер в разных местах, читаются как разные числа.
    expect(at, 'колонка «№ позиции» стоит не первой').toBe(0);

    const cells = Array.from(document.querySelectorAll('tbody tr td'))
      .map((cell) => (cell.textContent ?? '').trim());
    expect(
      cells[at],
      'в колонке «№ позиции» стоит не номер позиции',
    ).toBe('347');
  });

  it('вкладка открывается порядком по номеру позиции, как витрина склада',
    async () => {
      render(<WheelsScreen canIntake={false} role="OWNER" />);
      await waitFor(() => expect(screen.getByText(/Goodyear/)).toBeTruthy());

      // Прежним умолчанием был номер комплекта по убыванию: колесо,
      // заведённое поштучно, его не имеет вовсе и уезжало в конец.
      const wheels = asked.find((url) => url.includes('/api/wheels')) ?? '';
      expect(wheels, `запроса вкладки не было: ${asked.join(' · ')}`).not.toBe('');
      expect(wheels, 'вкладка открылась не порядком по номеру позиции')
        .toContain('sort=number');
      expect(wheels, 'порядок по номеру позиции открылся по убыванию')
        .toContain('desc=false');
    });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
