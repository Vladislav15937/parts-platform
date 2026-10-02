import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryScreen } from './InventoryScreen';
import { forgetSession } from '../inventory/inventory';

/**
 * Номер позиции в листе обхода пересчёта (задача 0168).
 *
 * <p><b>Как это выглядело для человека.</b> Кладовщик идёт по полкам с этим
 * листом и, найдя не то, называет деталь вслух тому, кто сводит расхождения.
 * В строке стояло одно наименование: «Фара левая» на живом складе это сотни
 * строк, а публичного кода в листе обхода нет вовсе — то есть назвать позицию
 * было нечем. Номер для разговора и заведён (задача 0060).
 *
 * <p>Выдача здесь список, а не таблица, поэтому проверяется сама строка:
 * убранный номер обязан валить тест словами про строку, в которой его нет,
 * а не пустым экраном. Тот же приём, что в `sellerPartNumber.test.tsx`.
 */
describe('лист обхода называет позицию номером', () => {
  beforeEach(async () => {
    // Сессия у всех тестов файла одна и та же: иначе локальные подсчёты
    // не сбрасываются между прогонами — adopt() сочтёт сессию продолженной.
    await forgetSession();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/inventory/count' || url.startsWith('/api/inventory/count?')) {
        return json({ count: 1 });
      }
      if (url === '/api/inventory/sessions' && init?.method === 'POST') {
        return json({ id: 1, warehouseId: 2, status: 'OPEN', lines: 1, counted: 0 });
      }
      if (url.includes('/sessions/1/lines')) {
        return json([{
          partId: 1, number: 347, title: 'Фара левая',
          cellId: 10, cellCode: 'А-01-1', qtyExpected: '2', qtyCounted: null,
        }]);
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

  it('строка листа обхода несёт номер позиции', async () => {
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} />);

    const warehouse = await waitFor(
      () => document.querySelector('select') as HTMLSelectElement);
    fireEvent.change(warehouse, { target: { value: '2' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть новую' }));

    const row = (await screen.findByText('Фара левая')).closest('li')!;
    const line = plain(row.textContent);

    expect(
      line,
      `номера позиции в строке листа обхода нет: ${line}`,
    ).toContain('№ 347');

    // Учётный остаток рядом остался: с ним кладовщик и сверяет факт.
    expect(line, 'учётный остаток пропал из строки').toContain('учёт 2');
  });
});

/**
 * Текст строки с обычными пробелами: номер отделён неразрывным — он там ради
 * переноса, а не ради текста. Escape, а не сам символ: невидимый пробел
 * в исходнике теста однажды уже сделал такую замену пустой.
 */
function plain(text: string | null): string {
  return (text ?? '').replace(/ /g, ' ');
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
