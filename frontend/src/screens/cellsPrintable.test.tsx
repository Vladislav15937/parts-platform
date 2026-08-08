import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { OrganizationScreen } from './OrganizationScreen';

/**
 * Уже заведённая непечатаемая ячейка помечена там же, где её завели.
 *
 * <p><b>Зачем.</b> Предупреждение при вводе стояло с самого начала и с верным
 * объяснением: «переименовать десять полок в первый день дешевле, чем через
 * месяц объяснять кладовщику, почему сканер их не видит». Но считалось оно
 * только по тому, что набирают сейчас, — а «Б-02-1» у клиента заведена
 * давно, переездом или руками до появления проверки. В списке она выглядела
 * обычной, и владелец узнавал о ней на печати этикеток: ровно тот случай,
 * которого предупреждение и должно избегать.
 */
describe('непечатаемые ячейки в списке', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/cells')) {
        return json([
          { id: 1, warehouseId: 2, code: 'А-01-1' },
          // «Б» латинского двойника не имеет: такой адрес не отсканируется
          // никогда, и подставить похожую «B» нельзя — сольётся с «В-01-1».
          { id: 2, warehouseId: 2, code: 'Б-02-1' },
        ]);
      }
      if (url.includes('/warehouses')) {
        return json([{ id: 2, branchId: 1, name: 'Ткацкая', isActive: true }]);
      }
      if (url.includes('/branches')) {
        return json([{ id: 1, name: 'Полный цикл' }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('помечает то, что не напечатать, и объясняет почему', async () => {
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Ткацкая')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Ячейки' }));

    await waitFor(() => expect(screen.getByText('Б-02-1')).toBeTruthy());
    const marks = [...document.querySelectorAll('.chip')]
      .map((chip) => chip.textContent);
    expect(marks, 'заведённая «Б» выглядит обычной ячейкой')
      .toEqual(['А-01-1', 'Б-02-1 · не печатается']);
    // И почему: одной пометки мало, надо сказать, что делать.
    expect(screen.getByText(/Code128 не знает кириллицы/)).toBeTruthy();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
