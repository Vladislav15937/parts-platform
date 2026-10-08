import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryScreen } from './InventoryScreen';
import { forgetSession } from '../inventory/inventory';
import type { OutboxRecord } from '../outbox/outbox';

/**
 * Комментарий ходившего на экране обхода (задача 0169, решение владельца
 * продукта от 29 сентября 2026: пишет и кладовщик, один на документ,
 * в конце обхода).
 *
 * <p>Проверяется то, что видит человек у полки: поле «Комментарий» стоит
 * в конце обхода, пустое ничего не отправляет, написанное уходит очередью
 * (а не прямым запросом — экран работает без связи) и остаётся видно тому,
 * кто его написал, в том числе после перезапуска экрана. Роль, которой
 * писать нельзя, поля не видит.
 */
describe('комментарий ходившего на экране обхода', () => {
  const fetched: string[] = [];

  beforeEach(async () => {
    await forgetSession();
    fetched.length = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      fetched.push(url);
      if (url.startsWith('/api/inventory/count')) {
        return json({ count: 1 });
      }
      if (url === '/api/inventory/sessions' && init?.method === 'POST') {
        // Комментарий уже есть на сервере: его написали с другого телефона
        // или раньше. Ходивший обязан его увидеть, а не писать поверх вслепую.
        return json({ id: 1, warehouseId: 2, status: 'OPEN', lines: 1, counted: 0,
                      note: '83619 не найден', counterNote: 'Не сканировали' });
      }
      if (url.includes('/sessions/1/lines')) {
        return json([{ partId: 1, number: 347, title: 'Фара левая', cellId: 10,
                       cellCode: 'А-01-1', qtyExpected: '1', qtyCounted: null }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  async function openSession(props: Partial<Parameters<typeof InventoryScreen>[0]> = {}) {
    const onNote = vi.fn();
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} onNote={onNote} {...props} />);
    const warehouse = await waitFor(() => document.querySelector('select') as HTMLSelectElement);
    fireEvent.change(warehouse, { target: { value: '2' } });
    fireEvent.click(await screen.findByRole('button', { name: 'Открыть новую' }));
    await screen.findByText('Фара левая');
    return onNote;
  }

  it('поле стоит в конце обхода и показывает уже написанное ходившим', async () => {
    await openSession();

    const field = await screen.findByLabelText('Комментарий') as HTMLTextAreaElement;
    // Комментарий сводившего сюда не попадает: у ходившего своё поле.
    expect(field.value).toBe('Не сканировали');

    // В конце обхода, а не у каждой полки: после последней группы.
    const lastGroup = screen.getByRole('heading', { name: 'Отсканированы' });
    expect(lastGroup.compareDocumentPosition(field) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(screen.getAllByLabelText('Комментарий')).toHaveLength(1);
  });

  it('пустое ничего не отправляет, написанное уходит очередью, а не запросом', async () => {
    const onNote = await openSession();
    const field = await screen.findByLabelText('Комментарий') as HTMLTextAreaElement;
    const save = screen.getByRole('button', { name: 'Сохранить комментарий' }) as HTMLButtonElement;

    // То, что уже лежит на сервере, отправлять незачем.
    expect(save.disabled).toBe(true);
    fireEvent.change(field, { target: { value: '   ' } });
    expect(save.disabled).toBe(true);

    fireEvent.change(field, { target: { value: '  Катушки не считали ' } });
    expect(save.disabled).toBe(false);
    fireEvent.click(save);

    await waitFor(() => expect(onNote).toHaveBeenCalledWith(1, 'Катушки не считали'));
    expect(onNote).toHaveBeenCalledTimes(1);
    // Прямого запроса нет: экран обхода работает без связи.
    expect(fetched.some((url) => url.includes('counter-note'))).toBe(false);
  });

  it('написанное видно и после перезапуска экрана', async () => {
    await openSession();
    fireEvent.change(await screen.findByLabelText('Комментарий'),
      { target: { value: 'Катушки не считали' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить комментарий' }));
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Сохранить комментарий' }) as HTMLButtonElement)
        .disabled).toBe(true));

    cleanup();
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} onNote={vi.fn()} />);

    const field = await screen.findByLabelText('Комментарий') as HTMLTextAreaElement;
    await waitFor(() => expect(field.value).toBe('Катушки не считали'));
  });

  it('пока комментарий в очереди, экран так и говорит, а отказ называет словами сервера', async () => {
    await openSession({ noteQueue: [queued('pending')] });
    expect(await screen.findByText(/Ждёт отправки/)).toBeTruthy();

    cleanup();
    render(<InventoryScreen reference={reference()} onCount={vi.fn()} onNote={vi.fn()}
                            noteQueue={[queued('failed')]} />);
    expect(await screen.findByText(
      /Не принят: Комментарий пишут, пока пересчёт не проведён и не отменён/)).toBeTruthy();
  });

  it('роль, которой писать нельзя, поля не видит', async () => {
    await openSession({ onNote: undefined });

    expect(screen.queryByLabelText('Комментарий')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Сохранить комментарий' })).toBeNull();
  });
});

function queued(state: 'pending' | 'failed'): OutboxRecord {
  return {
    id: 'q1', requestId: 'r1', kind: 'inventoryNote',
    payload: { sessionId: 1, note: 'Катушки не считали' },
    title: 'Комментарий к пересчёту', state, attempts: 1, nextAttemptAt: 0, createdAt: 1,
    ...(state === 'failed'
      ? { lastError: 'Комментарий пишут, пока пересчёт не проведён и не отменён' }
      : {}),
  };
}

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
