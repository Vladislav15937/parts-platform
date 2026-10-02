import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { StockMovesScreen } from './StockMovesScreen';

/**
 * Номер позиции в составе документа перевозки (задача 0168).
 *
 * <p><b>Как это выглядело для человека.</b> Состав перевозки раскрывают,
 * чтобы назвать увезённое другому человеку — «позиция 347 уехала на Ангар», —
 * а в строке стоял только публичный код: шесть случайных байт, которые
 * по телефону не диктуют. Номер для разговора и заведён (задача 0060).
 *
 * <p>Проверяется <b>по колонке</b>, а не по тексту где-нибудь на странице:
 * убранная колонка обязана валить тест словами про её отсутствие, а не пустым
 * экраном. Тот же приём, что в `originReport.test.tsx`
 * и `wheelNumberColumn.test.tsx`.
 */
describe('состав перевозки называет позицию номером', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      // Состав читается отдельным запросом, по нажатию на строку: порядок
      // веток важен — путь состава длиннее и обязан стоять раньше.
      if (url.includes('/api/stock/moves/4/lines')) {
        return json([
          { partId: 11, number: 347, publicCode: 'A1B2C3', title: 'Фара левая', qty: '2' },
        ]);
      }
      if (url.includes('/api/stock/moves')) {
        return json([{
          id: 4, number: 4, createdAt: '2026-09-05T20:01:00Z',
          fromWarehouse: 'Ткацкая', toWarehouse: 'Ангар',
          lines: 1, note: null, author: 'Иванов',
        }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('колонка «№ позиции» стоит первой, и в ней номер этой позиции', async () => {
    render(<StockMovesScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Ангар')).toBeTruthy());

    fireEvent.click(screen.getByText('Ангар').closest('tr')!);
    await waitFor(() => expect(screen.getByText('Фара левая')).toBeTruthy());

    // Таблиц на экране две — журнал документов и состав внутри раскрытой
    // строки, — поэтому берётся та, в которой стоит сама позиция: иначе
    // проверка мерила бы заголовки журнала, где «Номер» это номер документа.
    const table = screen.getByText('Фара левая').closest('table')!;
    const headers = [...table.querySelectorAll('thead th')]
      .map((cell) => (cell.textContent ?? '').trim());

    const at = headers.indexOf('№ позиции');
    expect(
      at,
      `колонки «№ позиции» в составе документа нет: ${headers.join(' · ')}`,
    ).toBeGreaterThanOrEqual(0);
    expect(at, 'колонка «№ позиции» стоит не первой').toBe(0);

    const cells = [...table.querySelectorAll('tbody tr td')]
      .map((cell) => (cell.textContent ?? '').trim());
    expect(
      cells[at],
      'в колонке «№ позиции» стоит не номер позиции',
    ).toBe('347');

    // Публичный код рядом остался — его читают с этикетки на самой детали, —
    // и зовётся он тем же словом, что на витрине и в карточке: рядом
    // с «№ позиции» прежнее «Публичный код» было третьим написанием одного.
    expect(
      headers,
      `публичный код потерял своё название: ${headers.join(' · ')}`,
    ).toContain('Номер товара');
    expect(cells[1]).toBe('A1B2C3');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
