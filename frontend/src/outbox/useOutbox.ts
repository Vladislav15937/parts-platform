import { useCallback, useEffect, useState } from 'react';
import { dropRecord, enqueue, listOutbox, processOutbox, retryRecord } from './outbox';
import type { OutboxKind, OutboxRecord } from './outbox';

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

export function useOutbox() {
  const [records, setRecords] = useState<OutboxRecord[]>([]);
  const [needsSignIn, setNeedsSignIn] = useState(false);

  const reload = useCallback(async () => {
    setRecords(await listOutbox());
  }, []);

  const flush = useCallback(async () => {
    const result = await processOutbox();
    setNeedsSignIn(result.needsSignIn);
    await reload();
    return result;
  }, [reload]);

  const add = useCallback(
    async (kind: OutboxKind, payload: unknown, title: string) => {
      await enqueue(kind, payload, title);
      await reload();
      // Пробуем сразу: если связь есть, приёмщик увидит, что работа ушла.
      void flush();
    },
    [flush, reload],
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
