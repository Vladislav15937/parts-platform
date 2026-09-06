import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryReconcile } from './InventoryReconcile';

/**
 * Ошибочно открытый пересчёт можно отменить.
 *
 * <p><b>Зачем.</b> Вторую инвентаризацию на складе открыть нельзя — две дадут
 * двойную корректировку, и сервер это отбивает словами. Значит кладовщик,
 * выбравший не тот склад, запирал пересчёт на нём насовсем: `POST
 * /api/inventory/sessions/{id}/cancel` был написан и закрыт ролью владельца,
 * но не звала его ни одна строка фронтенда, и штатного выхода не было вовсе.
 *
 * <p>Вторым нажатием: отмена выбрасывает лист обхода вместе с посчитанным,
 * а это работа смены. Склад при этом не меняется ничем — корректировки
 * делает только проведение.
 */
describe('отмена пересчёта', () => {
  let cancelled: string[];

  beforeEach(() => {
    cancelled = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/cancel')) {
        cancelled.push(url);
        return json({ id: 15, warehouseId: 2, status: 'CANCELLED',
                      lines: 36072, counted: 4 });
      }
      if (url.includes('/discrepancies')) {
        return json([]);
      }
      // Журнал пересчётов — свой список, отдельно от поиска по складу:
      // без этой ветки он попадал бы в общий разбор ниже и получал
      // одиночный объект сессии там, где ждёт массив.
      if (url.includes('/api/inventory/sessions?')) {
        return json({ rows: [], total: 0 });
      }
      if (url.includes('/api/inventory/sessions/open')) {
        return json({ id: 15, warehouseId: 2, status: 'COUNTED',
                      lines: 36072, counted: 4 });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('спрашивает вторым нажатием и не трогает склад', async () => {
    render(<InventoryReconcile reference={reference()} />);

    fireEvent.change(document.querySelector('select')!, { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Найти пересчёт' }));

    const cancel = await waitFor(() =>
      screen.getByRole('button', { name: 'Отменить пересчёт' }));

    // Первое нажатие только спрашивает: пропадёт работа смены.
    fireEvent.click(cancel);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Точно отменить\? Посчитанное \(4\)/ })).toBeTruthy());
    expect(cancelled, 'отмена ушла на сервер с первого нажатия').toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: /Точно отменить/ }));

    await waitFor(() => expect(cancelled).toHaveLength(1));
    expect(cancelled[0]).toContain('/api/inventory/sessions/15/cancel');
    // И сказано, что склад цел: иначе отмена читается как списание.
    await waitFor(() =>
      expect(screen.getByText(/склад не изменился/)).toBeTruthy());
    // Успех — не ошибка: красным он читался бы как поломка.
    expect(document.querySelector('.note--error')).toBeNull();
  });
});

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
