import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { getAll, remove, STORE_OUTBOX } from '../storage/db';
import {
  backoffMs,
  dropRecord,
  enqueue,
  listOutbox,
  processOutbox,
  retryRecord,
} from './outbox';
import type { OutboxRecord } from './outbox';

/**
 * Очередь отправки.
 *
 * <p>Здесь проверяется то, что стоит смены работы приёмщика: запись не теряется
 * при обрыве, не повторяется вечно при отказе по существу и переживает
 * истечение сессии.
 */
describe('очередь отправки', () => {
  beforeEach(async () => {
    for (const record of await getAll<OutboxRecord>(STORE_OUTBOX)) {
      await remove(STORE_OUTBOX, record.id);
    }
  });

  it('успешная отправка убирает запись', async () => {
    await enqueue('receipt', { warehouseId: 1 }, 'Партия из 2 позиций');

    const result = await processOutbox(async () => undefined);

    expect(result.sent).toBe(1);
    expect(await listOutbox()).toEqual([]);
  });

  it('обрыв связи запись не теряет', async () => {
    await enqueue('receipt', { warehouseId: 1 }, 'Партия');

    const result = await processOutbox(() => {
      throw new ApiError('transient', 0, 'Нет связи с сервером');
    });

    // Потерять здесь значит потерять работу приёмщика при исправной системе.
    expect(result.sent).toBe(0);
    const queue = await listOutbox();
    expect(queue).toHaveLength(1);
    expect(queue[0]?.state).toBe('pending');
    expect(queue[0]?.lastError).toBe('Нет связи с сервером');
  });

  it('ключ идемпотентности не меняется между попытками', async () => {
    await enqueue('receipt', { warehouseId: 1 }, 'Партия');
    const before = (await listOutbox())[0]?.requestId;

    await processOutbox(() => {
      throw new ApiError('transient', 500, 'Сервер не отвечает');
    });

    // Смена ключа при повторе создала бы на сервере вторую партию — то есть
    // удвоила бы принятое на каждом обрыве связи.
    expect((await listOutbox())[0]?.requestId).toBe(before);
  });

  it('отказ по существу уводит запись в «требует внимания», а не в повторы', async () => {
    await enqueue('receipt', { donorId: 7 }, 'Партия с донора 7');

    const result = await processOutbox(() => {
      throw new ApiError('permanent', 409, 'Донор списан');
    });

    expect(result.failed).toBe(1);
    const queue = await listOutbox();
    expect(queue[0]?.state).toBe('failed');
    expect(queue[0]?.lastError).toBe('Донор списан');
  });

  it('отклонённая запись больше не отправляется сама', async () => {
    await enqueue('receipt', {}, 'Партия');
    await processOutbox(() => {
      throw new ApiError('permanent', 400, 'Склад не найден');
    });

    const send = vi.fn();
    await processOutbox(send);

    expect(send).not.toHaveBeenCalled();
  });

  it('потеря сессии не тратит попытки и не портит записи', async () => {
    await enqueue('receipt', {}, 'Партия');

    const result = await processOutbox(() => {
      throw new ApiError('unauthenticated', 401, 'Нужен вход');
    });

    // Виноват не приёмщик: он войдёт заново, и очередь продолжится с того же
    // места, целой.
    expect(result.needsSignIn).toBe(true);
    const queue = await listOutbox();
    expect(queue[0]?.state).toBe('pending');
    expect(queue[0]?.attempts).toBe(0);
    expect(queue[0]?.lastError).toBeUndefined();
  });

  it('после входа очередь уходит целиком', async () => {
    await enqueue('receipt', { n: 1 }, 'Первая');
    await enqueue('receipt', { n: 2 }, 'Вторая');
    await processOutbox(() => {
      throw new ApiError('unauthenticated', 401, 'Нужен вход');
    });

    const result = await processOutbox(async () => undefined);

    expect(result.sent).toBe(2);
    expect(await listOutbox()).toEqual([]);
  });

  it('временная ошибка прекращает проход: сеть не появится за миллисекунду', async () => {
    await enqueue('receipt', { n: 1 }, 'Первая');
    await enqueue('receipt', { n: 2 }, 'Вторая');

    const send = vi.fn().mockImplementation(() => {
      throw new ApiError('transient', 0, 'Нет связи');
    });
    await processOutbox(send);

    // Иначе один обрыв съел бы по попытке у каждой записи очереди и развёл
    // задержки там, где ничего не сломано.
    expect(send).toHaveBeenCalledTimes(1);
    const queue = await listOutbox();
    expect(queue[1]?.attempts).toBe(0);
  });

  it('отказ по существу проход не прекращает: следующая запись может быть цела', async () => {
    await enqueue('receipt', { n: 1 }, 'Битая');
    await enqueue('receipt', { n: 2 }, 'Целая');

    const send = vi.fn().mockImplementationOnce(() => {
      throw new ApiError('permanent', 409, 'Донор списан');
    });
    const result = await processOutbox(send);

    expect(result.failed).toBe(1);
    expect(result.sent).toBe(1);
  });

  it('задержка растёт вдвое и упирается в пять минут', () => {
    expect(backoffMs(1)).toBe(5_000);
    expect(backoffMs(2)).toBe(10_000);
    expect(backoffMs(3)).toBe(20_000);
    // Без верхней границы телефон, пролежавший ночь, проснулся бы с задержкой
    // в сутки, и приёмщик утром не понял бы, почему ничего не уходит.
    expect(backoffMs(20)).toBe(300_000);
  });

  it('до истечения задержки запись не трогают', async () => {
    await enqueue('receipt', {}, 'Партия');
    const start = 1_000_000;
    await processOutbox(() => {
      throw new ApiError('transient', 500, 'Сервер не отвечает');
    }, start);

    const send = vi.fn();
    await processOutbox(send, start + 1_000);

    expect(send).not.toHaveBeenCalled();
  });

  it('после истечения задержки запись уходит', async () => {
    await enqueue('receipt', {}, 'Партия');
    const start = 1_000_000;
    await processOutbox(() => {
      throw new ApiError('transient', 500, 'Сервер не отвечает');
    }, start);

    const result = await processOutbox(async () => undefined, start + 10_000);

    expect(result.sent).toBe(1);
  });

  it('записи отправляются в порядке набора', async () => {
    await enqueue('receipt', { n: 1 }, 'Первая');
    await enqueue('receipt', { n: 2 }, 'Вторая');
    await enqueue('receipt', { n: 3 }, 'Третья');

    const order: string[] = [];
    await processOutbox(async (record) => {
      order.push(record.title);
    });

    expect(order).toEqual(['Первая', 'Вторая', 'Третья']);
  });

  it('возврат отклонённой записи обнуляет задержку', async () => {
    const record = await enqueue('receipt', {}, 'Партия');
    await processOutbox(() => {
      throw new ApiError('permanent', 409, 'Донор списан');
    });

    await retryRecord(record.id);

    const queue = await listOutbox();
    expect(queue[0]?.state).toBe('pending');
    // Иначе запись уйдёт с пятиминутной задержкой, и приёмщик решит,
    // что кнопка не работает.
    expect(queue[0]?.attempts).toBe(0);
    expect(queue[0]?.nextAttemptAt).toBe(0);
    expect(queue[0]?.lastError).toBeUndefined();
  });

  it('запись можно убрать совсем', async () => {
    const record = await enqueue('receipt', {}, 'Партия');

    await dropRecord(record.id);

    expect(await listOutbox()).toEqual([]);
  });

  it('запись переживает перезапуск приложения', async () => {
    await enqueue('receipt', { warehouseId: 5 }, 'Партия из 3 позиций');

    // listOutbox читает из IndexedDB, а не из памяти: перезапуск приложения
    // очередь не теряет.
    const queue = await listOutbox();
    expect(queue[0]?.payload).toEqual({ warehouseId: 5 });
    expect(queue[0]?.title).toBe('Партия из 3 позиций');
  });
});
