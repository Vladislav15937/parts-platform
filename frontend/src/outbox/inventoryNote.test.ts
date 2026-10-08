import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getAll, remove, STORE_OUTBOX } from '../storage/db';
import { enqueue, listOutbox, processOutbox } from './outbox';
import type { OutboxRecord } from './outbox';

/**
 * Комментарий ходившего в офлайн-очереди (задача 0169).
 *
 * <p>Экран обхода работает без связи, поэтому комментарий — не поле в форме,
 * а операция очереди, как подсчёт полки. Здесь проверяется то, ради чего
 * задача заводилась: набранный без связи комментарий доезжает после её
 * появления, повтор идёт с тем же ключом (сервер по нему узнаёт повтор
 * и не затирает написанное после), а закрытый пересчёт — 409 со словами —
 * уводит запись к человеку, а не в вечные повторы.
 *
 * <p>Сеть подменена на уровне `fetch`, а не `request`: классификацию ответа
 * (временный или по существу) делает настоящий клиент API — подмена выше
 * проверяла бы заглушку, а не очередь.
 */
describe('комментарий ходившего в очереди', () => {
  const calls: { url: string; body: string }[] = [];

  beforeEach(async () => {
    for (const record of await getAll<OutboxRecord>(STORE_OUTBOX)) {
      await remove(STORE_OUTBOX, record.id);
    }
    calls.length = 0;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function server(answer: () => Response | Promise<Response>): void {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), body: String(init?.body ?? '') });
      return answer();
    }));
  }

  it('набранный без связи доезжает после её появления, и повтор несёт тот же ключ', async () => {
    const record = await enqueue(
      'inventoryNote', { sessionId: 7, note: 'Катушки не считали' }, 'Комментарий к пересчёту');

    // Связи нет: запрос не уходит дальше телефона.
    server(() => { throw new TypeError('Failed to fetch'); });
    const offline = await processOutbox();
    expect(offline.sent).toBe(0);
    expect((await listOutbox())[0]?.state).toBe('pending');

    // Связь появилась — запись уходит и убирается из очереди.
    server(() => json({ id: 7, note: null, counterNote: 'Катушки не считали' }));
    const online = await processOutbox(undefined, Date.now() + 60_000);
    expect(online.sent).toBe(1);
    expect(await listOutbox()).toEqual([]);

    expect(calls.map((c) => c.url)).toEqual([
      '/api/inventory/sessions/7/counter-note',
      '/api/inventory/sessions/7/counter-note',
    ]);
    const bodies = calls.map((c) => JSON.parse(c.body) as { note: string; requestId: string });
    expect(bodies[0]).toEqual({ note: 'Катушки не считали', requestId: record.requestId });
    // Ключ тот же, что при первой попытке: по нему сервер узнаёт повтор
    // и не затирает то, что написали после.
    expect(bodies[1]?.requestId).toBe(record.requestId);
  });

  it('закрытый пересчёт — запись уходит к человеку со словами сервера и не повторяется', async () => {
    await enqueue('inventoryNote', { sessionId: 7, note: 'уже поздно' }, 'Комментарий к пересчёту');

    server(() => json(
      { message: 'Комментарий пишут, пока пересчёт не проведён и не отменён' }, 409));
    const first = await processOutbox();
    expect(first.failed).toBe(1);

    const [failed] = await listOutbox();
    expect(failed?.state).toBe('failed');
    expect(failed?.lastError).toBe('Комментарий пишут, пока пересчёт не проведён и не отменён');

    // Следующие проходы её не трогают: 409 — не «нет связи».
    await processOutbox(undefined, Date.now() + 10 * 60_000);
    expect(calls).toHaveLength(1);
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
