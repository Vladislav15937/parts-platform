import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { cachedReference, refreshReference } from './reference';
import type { Reference } from './reference';

type Status =
  | { kind: 'loading' }
  /** Справочников нет: приёмка невозможна, нужен онлайн хотя бы раз. */
  | { kind: 'empty'; error?: string }
  | { kind: 'ready'; reference: Reference; stale: boolean; error?: string };

/**
 * Справочники приёмки: сначала из локального хранилища, потом обновление с сервера.
 *
 * <p>Порядок обязателен именно такой. Приложение должно показать работоспособный
 * экран немедленно и без связи — иначе приёмщик стоит у стеллажа и ждёт сеть,
 * которой нет. Обновление идёт после и молча: не получилось — работаем
 * на локальных.
 *
 * <p>Отдельно помечаем «устарели»: справочник трёхдневной давности не содержит
 * контейнер, который пришёл вчера, и приёмщик должен понимать, почему не находит
 * поставку, вместо того чтобы заводить деталь без неё.
 */
const STALE_AFTER_HOURS = 12;

export function useReference() {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });

  const refresh = useCallback(async () => {
    try {
      const loaded = await refreshReference();
      setStatus({ kind: 'ready', reference: loaded, stale: false });
      return true;
    } catch (error) {
      const message =
        error instanceof ApiError && error.kind === 'transient'
          ? 'Нет связи — справочники не обновлены'
          : 'Не удалось обновить справочники';

      // Локальные не выбрасываем: работать на устаревших можно, без них — нет.
      setStatus((previous) =>
        previous.kind === 'ready'
          ? { ...previous, error: message }
          : { kind: 'empty', error: message },
      );
      return false;
    }
  }, []);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      let local: Reference | undefined;
      try {
        local = await cachedReference();
      } catch {
        // IndexedDB может быть недоступна (приватный режим) — это не повод
        // не работать онлайн.
        local = undefined;
      }
      if (cancelled) {
        return;
      }
      if (local !== undefined) {
        setStatus({ kind: 'ready', reference: local, stale: isStale(local) });
      }
      await refresh();
    })();

    return () => {
      cancelled = true;
    };
  }, [refresh]);

  return { status, refresh };
}

function isStale(reference: Reference): boolean {
  const age = Date.now() - new Date(reference.loadedAt).getTime();
  return age > STALE_AFTER_HOURS * 60 * 60 * 1000;
}
