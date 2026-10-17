import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getAll, remove, STORE_OUTBOX } from '../storage/db';
import { enqueue, listOutbox, processOutbox } from './outbox';
import type { OutboxRecord } from './outbox';

/**
 * Приёмка со слишком длинным номером производителя не крутится в очереди
 * вечно (задача 0312).
 *
 * <p>Сервер до 0312 отвечал на такой номер 500 «Внутренняя ошибка», а очередь
 * повторяет 5xx бесконечно: одна запись забивала смену приёмщика. Теперь это
 * 400 словами, и запись уходит в «требует внимания» с теми же словами.
 *
 * <p>Настоящая очередь с настоящим отправителем (`sendRecord` → `request`):
 * заглушен только ответ сервера, поэтому проверяется и разбор кода ответа
 * в `api/client.ts`, а не подставленный готовым вид ошибки.
 */
describe('приёмка с номером, который нельзя сохранить', () => {
  beforeEach(async () => {
    for (const record of await getAll<OutboxRecord>(STORE_OUTBOX)) {
      await remove(STORE_OUTBOX, record.id);
    }
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const refusal = '«генератор»: Номер производителя длиннее 1000 знаков: сохранить нельзя.'
    + ' Похоже, вставлено не то — проверьте номер';

  it('400 сервера — запись в «требует внимания» с его словами и больше не уходит', async () => {
    const fetch = answer(400, { message: refusal });
    await enqueue('receipt', { warehouseId: 1, items: [] }, 'Партия из 1 позиции');

    const first = await processOutbox();
    expect(first.failed).toBe(1);
    const queue = await listOutbox();
    expect(queue[0]?.state).toBe('failed');
    expect(queue[0]?.lastError).toBe(refusal);

    const calls = fetch.mock.calls.length;
    await processOutbox(undefined, Date.now() + 3_600_000);
    expect(fetch.mock.calls.length, 'отказ по существу отправлен повторно').toBe(calls);
  });

  it('контроль: 500 по-прежнему повторяется — запись ждёт следующей попытки', async () => {
    answer(500, { message: 'Внутренняя ошибка' });
    await enqueue('receipt', { warehouseId: 1, items: [] }, 'Партия из 1 позиции');

    const result = await processOutbox();
    expect(result.failed).toBe(0);
    const queue = await listOutbox();
    expect(queue[0]?.state).toBe('pending');
    expect(queue[0]?.attempts).toBe(1);
  });
});

function answer(status: number, body: unknown) {
  const fetch = vi.fn(async () => new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  }));
  vi.stubGlobal('fetch', fetch);
  return fetch;
}
