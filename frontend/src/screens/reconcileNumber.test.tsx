import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryReconcile } from './InventoryReconcile';

/**
 * Номер позиции в сведении расхождений (задача 0168).
 *
 * <p><b>Как это выглядело для человека.</b> Владелец смотрит, что не сошлось,
 * и дальше идёт к кладовщику: «пересчитай позицию 347». В строке стояло одно
 * наименование, а публичного кода здесь нет вовсе — то есть у единственного
 * экрана, с которого начинается разбирательство о недостаче, не было способа
 * назвать спорную деталь.
 *
 * <p>Проверяется <b>по колонке</b>, а не по тексту где-нибудь на странице:
 * убранная колонка обязана валить тест словами про её отсутствие.
 */
describe('сведение расхождений называет позицию номером', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/discrepancies')) {
        return json([{
          partId: 1, number: 347, title: 'Фара левая',
          qtyExpectedAtOpen: 2, qtyExpectedAtCount: 2, qtyCounted: 1,
          delta: -1, shortage: true, applied: false,
        }]);
      }
      // Карточка одной сессии — путь без хвоста; список — он же с отбором.
      if (/\/api\/inventory\/sessions\/\d+$/.test(url)) {
        return json(session());
      }
      if (url.includes('/api/inventory/sessions')) {
        return json({ rows: [session()], total: 1 });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('колонка «№ позиции» стоит первой, и в ней номер этой позиции', async () => {
    render(<InventoryReconcile reference={reference()} />);

    // «Выполненные» накрывает и завершённый подсчёт, и проведённый: расхождения
    // читаются у сессии, которая уже не OPEN.
    fireEvent.click(await screen.findByRole('button', { name: 'Выполненные' }));
    fireEvent.click(await screen.findByText('Подсчёт завершён'));

    await waitFor(() => expect(screen.getByText('Фара левая')).toBeTruthy());

    // Таблиц на экране две — журнал пересчётов и расхождения, — поэтому
    // берётся та, в которой стоит сама позиция: иначе проверка мерила бы
    // заголовки журнала, где «Номер/дата» это номер документа.
    const table = screen.getByText('Фара левая').closest('table')!;
    const headers = [...table.querySelectorAll('thead th')]
      .map((cell) => (cell.textContent ?? '').trim());

    const at = headers.indexOf('№ позиции');
    expect(
      at,
      `колонки «№ позиции» в расхождениях нет: ${headers.join(' · ')}`,
    ).toBeGreaterThanOrEqual(0);
    expect(at, 'колонка «№ позиции» стоит не первой').toBe(0);

    const cells = [...screen.getByText('Фара левая').closest('tr')!.querySelectorAll('td')]
      .map((cell) => (cell.textContent ?? '').trim());
    expect(
      cells[at],
      'в колонке «№ позиции» стоит не номер позиции',
    ).toBe('347');
  });
});

function session() {
  return {
    id: 8, warehouseId: 2, warehouseName: 'Ткацкая',
    selection: 'Ткацкая · весь склад', status: 'COUNTED',
    startedAt: '2026-09-05T10:00:00Z', appliedAt: null,
    lines: 1, counted: 1, note: null,
  };
}

function reference(): never {
  return {
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [], donors: [], cells: [], partNames: [],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
