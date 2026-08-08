import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { dropRecord, enqueue, processOutbox, listOutbox } from './outbox';
import { ApiError } from '../api/client';

/**
 * Связь определяется ответом сервера, а не `navigator.onLine`.
 *
 * <p>Тот знает только «интерфейс поднят», а в ангаре это ровно тот случай,
 * когда wi-fi подключён, а сервера за ним нет: приёмщик видит «на связи»
 * и решает, что работа ушла, — при том что она легла в очередь. Настоящее
 * свидетельство связи одно: успешный или осмысленно отказавший запрос,
 * и знает о нём только очередь.
 */
describe('очередь сообщает, был ли сервер', () => {
  beforeEach(async () => {
    for (const record of await listOutbox()) {
      await dropRecord(record.id);
    }
  });

  afterEach(() => vi.unstubAllGlobals());

  it('успешная отправка означает связь', async () => {
    await enqueue('receipt', { warehouseId: 1, items: [] }, 'Партия', undefined, 'co');
    const result = await processOutbox(async () => undefined, Date.now(), 'co');

    expect(result.reachedServer, 'ответ получен, а связи будто нет').toBe(true);
  });

  /** Сервер не ответил вовсе — это и есть отсутствие связи. */
  it('временная ошибка означает отсутствие связи', async () => {
    await enqueue('receipt', { warehouseId: 1, items: [] }, 'Партия', undefined, 'co');
    const result = await processOutbox(async () => {
      throw new ApiError('transient', 0, 'Сервер не отвечает');
    }, Date.now(), 'co');

    expect(result.reachedServer, 'до сервера не достучались, а связь будто есть').toBe(false);
  });

  /**
   * Отказ по существу — тоже ответ: сервер жив, ошибка в данных.
   * Показать «без связи» тут значит отправить приёмщика чинить wi-fi.
   */
  it('отказ по существу означает, что сервер есть', async () => {
    await enqueue('receipt', { warehouseId: 1, items: [] }, 'Партия', undefined, 'co');
    const result = await processOutbox(async () => {
      throw new ApiError('permanent', 409, 'Нечего снимать с резерва');
    }, Date.now(), 'co');

    expect(result.reachedServer).toBe(true);
  });

  /** Пустой проход о связи не говорит ничего — прежний ответ не затирается. */
  it('без записей ответ не даётся', async () => {
    const result = await processOutbox(async () => undefined, Date.now(), 'co');

    expect(result.reachedServer).toBeUndefined();
  });
});
