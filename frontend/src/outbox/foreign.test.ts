import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAll, remove, STORE_OUTBOX } from '../storage/db';
import { enqueue, listOutbox, processOutbox } from './outbox';
import type { OutboxRecord } from './outbox';

/**
 * Записи чужой компании не уезжают в текущую сессию.
 *
 * <p>IndexedDB — хранилище браузера, а не арендатора. В теле приёмки лежат
 * идентификаторы складов, ячеек и машин той компании, где работали: уехав
 * в другую сессию, запись создала бы детали на чужом складе. И удалить её
 * нельзя — это несделанная работа приёмщика.
 */
describe('очередь и компания', () => {
  beforeEach(async () => {
    for (const record of await getAll<OutboxRecord>(STORE_OUTBOX)) {
      await remove(STORE_OUTBOX, record.id);
    }
  });
  it('не отправляет запись, поставленную в другой компании', async () => {
    await enqueue('receipt', { warehouseId: 1 }, 'Фара', undefined, 't_000001');
    const send = vi.fn(async () => undefined);

    const result = await processOutbox(send, Date.now(), 't_000002');

    expect(send).not.toHaveBeenCalled();
    expect(result.foreign).toBe(1);
    expect(result.sent).toBe(0);
    // Осталась на месте: вернутся в свою компанию — уйдёт.
    expect(await listOutbox()).toHaveLength(1);
  });

  it('отправляет запись своей компании', async () => {
    await enqueue('receipt', { warehouseId: 1 }, 'Бампер', undefined, 't_000002');
    const send = vi.fn(async () => undefined);

    const result = await processOutbox(send, Date.now(), 't_000002');

    expect(send).toHaveBeenCalledTimes(1);
    expect(result.sent).toBe(1);
    expect(result.foreign).toBe(0);
  });

  /**
   * Окно между входом и ответом «кто я»: компания ещё неизвестна, и сравнивать
   * отметку записи было не с чем — проверка пропускала любую. Партия прогонной
   * компании ушла под сессией другой; спасло лишь то, что склада с таким
   * номером там не оказалось. Номера складов у арендаторов свои и начинаются
   * с единицы — у обоих есть склад №1, и деталь завелась бы в чужой компании
   * молча, без единой ошибки.
   */
  // Записи, поставленные до появления отметки, отправляются как раньше:
  // иначе обновление приложения заперло бы накопленную смену.
  it('запись без отметки о компании отправляется', async () => {
    await enqueue('receipt', { warehouseId: 1 }, 'Стартер');
    const send = vi.fn(async () => undefined);

    const result = await processOutbox(send, Date.now(), 't_000002');

    expect(result.sent).toBe(1);
  });
});
