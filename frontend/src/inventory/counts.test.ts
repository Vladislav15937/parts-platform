import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAll, remove, STORE_OUTBOX } from '../storage/db';
import { enqueue, listOutbox, processOutbox } from '../outbox/outbox';
import type { OutboxRecord } from '../outbox/outbox';
import { cellsOf, linesOfCell } from './inventory';
import type { InventoryLine } from './inventory';

/**
 * Подсчёты в очереди.
 *
 * <p>Проверяется одна вещь, ради которой всё и сделано: на сервер уходит
 * давность подсчёта, посчитанная в момент отправки. Ошибись здесь — и проданное
 * за время лежания записи в очереди станет излишком, а проведение вернёт
 * на склад то, что уже уехало к клиенту.
 */

const { calls, requestMock } = vi.hoisted(() => ({
  calls: [] as { path: string; body: Record<string, unknown> }[],
  requestMock: vi.fn(),
}));

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>();
  return { ...actual, request: requestMock };
});

const { ApiError } = await import('../api/client');

describe('отправка подсчёта', () => {
  beforeEach(async () => {
    for (const record of await getAll<OutboxRecord>(STORE_OUTBOX)) {
      await remove(STORE_OUTBOX, record.id);
    }
    calls.length = 0;
    requestMock.mockReset();
    requestMock.mockImplementation(async (path: string, init?: { body?: unknown }) => {
      calls.push({ path, body: (init?.body ?? {}) as Record<string, unknown> });
      return undefined;
    });
  });

  it('уходит давность, а не момент подсчёта', async () => {
    const countedAt = Date.now() - 3_600_000; // посчитали час назад
    await enqueue('count', { sessionId: 7, partId: 42, qty: '3', countedAt }, 'Фара · 3 шт');

    await processOutbox();

    expect(calls[0]?.path).toBe('/api/inventory/sessions/7/counts');
    // Момент подсчёта в теле не нужен: часы устройства врут смещением,
    // а в разности двух своих же отсчётов оно сокращается.
    expect(calls[0]?.body).not.toHaveProperty('countedAt');
    expect(calls[0]?.body['countedAgoMs']).toBeGreaterThanOrEqual(3_600_000);
    expect(calls[0]?.body['countedAgoMs']).toBeLessThan(3_610_000);
  });

  it('давность растёт, пока запись лежит в очереди', async () => {
    const countedAt = Date.now();
    await enqueue('count', { sessionId: 7, partId: 42, qty: '3', countedAt }, 'Фара · 3 шт');

    // Первая попытка: связи нет.
    requestMock.mockRejectedValueOnce(new ApiError('transient', 0, 'Нет связи'));
    await processOutbox(undefined, countedAt);

    // Телефон пролежал в ангаре два часа. Подменяем только чтение часов:
    // фейковые таймеры ломают IndexedDB, на которой держится сама очередь.
    const later = countedAt + 2 * 3_600_000;
    const clock = vi.spyOn(Date, 'now').mockReturnValue(later);
    try {
      await processOutbox(undefined, later);
    } finally {
      clock.mockRestore();
    }

    // Зафиксируй мы давность при постановке в очередь — сюда уехал бы ноль,
    // и два часа продаж записались бы в излишки.
    expect(calls).toHaveLength(1);
    expect(calls[0]?.body['countedAgoMs']).toBe(2 * 3_600_000);
    expect(await listOutbox()).toEqual([]);
  });

  it('проведённая сессия — постоянная ошибка, а не вечный повтор', async () => {
    requestMock.mockRejectedValue(new ApiError('permanent', 409, 'сессия в состоянии APPLIED'));
    await enqueue('count', { sessionId: 7, partId: 42, qty: '3', countedAt: Date.now() }, 'Фара');

    const result = await processOutbox();

    // Досылать подсчёты в проведённую инвентаризацию нельзя: расхождения
    // уже посчитаны и скорректированы. Нужен человек.
    expect(result.failed).toBe(1);
    expect((await listOutbox())[0]?.state).toBe('failed');
  });

  it('отрицательная давность не уезжает на сервер', async () => {
    // Часы устройства перевели назад после подсчёта.
    const countedAt = Date.now() + 60_000;
    await enqueue('count', { sessionId: 7, partId: 42, qty: '3', countedAt }, 'Фара');

    await processOutbox();

    expect(calls[0]?.body['countedAgoMs']).toBe(0);
  });
});

const lines: InventoryLine[] = [
  { partId: 1, title: 'Фара левая', cellId: 10, cellCode: 'А-01-1', qtyExpected: '2', qtyCounted: null },
  { partId: 2, title: 'Бампер', cellId: 10, cellCode: 'А-01-1', qtyExpected: '1', qtyCounted: null },
  { partId: 3, title: 'Дверь', cellId: 11, cellCode: 'А-01-2', qtyExpected: '1', qtyCounted: null },
  { partId: 4, title: 'Капот', cellId: null, cellCode: null, qtyExpected: '1', qtyCounted: null },
];

describe('лист обхода', () => {
  it('ячейки идут по кодам — это маршрут кладовщика', () => {
    expect(cellsOf(lines).map((c) => c.code)).toEqual(['А-01-1', 'А-01-2', 'без ячейки']);
  });

  it('позиции без ячейки не прячутся', () => {
    // Деталь без адреса всё равно лежит на складе и в пересчёт входит.
    expect(linesOfCell(lines, null).map((l) => l.partId)).toEqual([4]);
  });

  it('в ячейке видно только её содержимое', () => {
    expect(linesOfCell(lines, 10).map((l) => l.partId)).toEqual([1, 2]);
  });
});
