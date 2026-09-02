import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { LabelsScreen } from './LabelsScreen';

/**
 * «На этом складе ячеек нет» — только когда склад назван.
 *
 * <p><b>Зачем.</b> Склад на этом экране не подставляется: у клиента с тремя
 * складами первый по имени оказывался пустым. Но сообщение о пустоте
 * показывалось по одному условию «ячеек нет» — и встречало владельца сразу,
 * до всякого выбора. Он читает утверждение о складе, которого не выбирал,
 * и решает, что печатать нечего.
 *
 * <p>Ровно этой формулировки избегали, когда убирали подстановку склада:
 * она названа в комментарии рядом с тем же кодом. Убрали причину, оставили
 * следствие.
 */
describe('этикетки ячеек до выбора склада', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/warehouses')) {
        return json([{ id: 1, branchId: 1, name: 'Ткацкая', isActive: true }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('до выбора склада не утверждает, что ячеек нет', async () => {
    render(<LabelsScreen canPrint />);
    await waitFor(() => expect(document.querySelector('select')).toBeTruthy());

    expect(screen.queryByText(/На этом складе ячеек нет/),
      'экран говорит о складе, которого не выбирали').toBeNull();
    // И говорит, чего он ждёт: пустое место без объяснения читается
    // как поломка.
    expect(screen.getByText(/Выберите склад/)).toBeTruthy();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
