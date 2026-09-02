import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { getAll, remove, STORE_OUTBOX } from '../storage/db';
import { enqueue, listOutbox } from './outbox';
import type { OutboxRecord } from './outbox';
import { useOutbox } from './useOutbox';

/**
 * Очередь молчит, пока неизвестно, в какой компании вошли.
 *
 * <p>Окно между входом и ответом «кто я» короткое, но в нём отметку
 * о компании в записи не с чем сравнивать, и фильтр чужих записей
 * пропускает любую. Партия одной компании ушла под сессией другой; спасло
 * лишь то, что склада с таким номером там не оказалось. Номера складов
 * у арендаторов свои и начинаются с единицы — у обоих есть склад №1,
 * и деталь завелась бы в чужой компании молча, без единой ошибки.
 */
describe('очередь и неизвестная компания', () => {
  beforeEach(async () => {
    for (const record of await getAll<OutboxRecord>(STORE_OUTBOX)) {
      await remove(STORE_OUTBOX, record.id);
    }
    vi.restoreAllMocks();
  });

  it('без компании не ходит на сервер вовсе', async () => {
    await enqueue('receipt', { warehouseId: 1 }, 'Фара', undefined, 't_000001');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useOutbox(undefined));
    await act(async () => {
      await result.current.flush();
    });

    expect(fetchMock).not.toHaveBeenCalled();
    // Попытки не тратятся и ошибок не приписывается: виноват не приёмщик.
    const [record] = await listOutbox();
    expect(record?.attempts).toBe(0);
    expect(record?.state).toBe('pending');
  });
});
