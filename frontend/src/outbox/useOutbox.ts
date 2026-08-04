import { useCallback, useEffect, useState } from 'react';
import { dropRecord, enqueue, listOutbox, processOutbox, retryRecord } from './outbox';
import type { OutboxKind, OutboxRecord, PendingPhoto } from './outbox';

/**
 * Очередь отправки для экранов.
 *
 * <p>Разгребается обычным кодом при активном приложении, а не Background Sync:
 * его нет в Safari, а половина складских телефонов — iPhone. Это то самое
 * ограничение PWA, ради которого в архитектуре записано «нативное приложение
 * в v2».
 *
 * <p>Поводы для прохода: запуск приложения, появление связи, постановка новой
 * записи и таймер. Таймер нужен из-за задержек после отказов: без него запись,
 * отложенная на минуту, ждала бы следующего действия приёмщика.
 */
const TICK_MS = 15_000;

/**
 * @param company схема вошедшей компании. Записи, поставленные в другой,
 *                не отправляются: в теле лежат идентификаторы её складов
 *                и ячеек
 */
export function useOutbox(company?: string) {
  const [records, setRecords] = useState<OutboxRecord[]>([]);
  const [needsSignIn, setNeedsSignIn] = useState(false);

  const reload = useCallback(async () => {
    setRecords(await listOutbox());
  }, []);

  const flush = useCallback(async () => {
    // Пока неизвестно, в какой компании мы вошли, отправлять нельзя вовсе.
    // Это окно между входом и ответом «кто я»: отметку о компании в записи
    // не с чем сравнивать, и фильтр пропускает любую. Партия одной компании
    // ушла под сессией другой — спасло лишь то, что склада с таким номером
    // там не оказалось. Номера складов у арендаторов свои и начинаются
    // с единицы: у обоих есть склад №1, и деталь завелась бы в чужой компании
    // молча. «Не знаю» не должно значить «в любой».
    if (company === undefined) {
      return { sent: 0, failed: 0, needsSignIn: false, foreign: 0 };
    }
    const result = await processOutbox(undefined, Date.now(), company);
    setNeedsSignIn(result.needsSignIn);
    await reload();
    return result;
  }, [reload, company]);

  const add = useCallback(
    async (kind: OutboxKind, payload: unknown, title: string, photos?: PendingPhoto[]) => {
      await enqueue(kind, payload, title, photos, company);
      await reload();
      // Пробуем сразу: если связь есть, приёмщик увидит, что работа ушла.
      void flush();
    },
    [flush, reload, company],
  );

  const retry = useCallback(
    async (id: string) => {
      await retryRecord(id);
      await flush();
    },
    [flush],
  );

  const drop = useCallback(
    async (id: string) => {
      await dropRecord(id);
      await reload();
    },
    [reload],
  );

  useEffect(() => {
    void flush();

    const timer = window.setInterval(() => void flush(), TICK_MS);
    const onOnline = () => void flush();
    window.addEventListener('online', onOnline);

    return () => {
      window.clearInterval(timer);
      window.removeEventListener('online', onOnline);
    };
  }, [flush]);

  return {
    records,
    pending: records.filter((r) => r.state === 'pending'),
    failed: records.filter((r) => r.state === 'failed'),
    needsSignIn,
    add,
    retry,
    drop,
    flush,
  };
}
