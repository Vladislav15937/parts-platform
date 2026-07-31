import { useEffect, useState } from 'react';

/**
 * Есть ли связь.
 *
 * <p><b>`navigator.onLine` доверять нельзя.</b> Он говорит только «интерфейс
 * поднят», а в ангаре это ровно тот случай, когда wi-fi подключён, а интернета
 * за ним нет. Поэтому событий `online`/`offline` недостаточно, и настоящим
 * признаком остаётся успешный запрос — его подставит очередь отправки,
 * когда появится.
 *
 * <p>Пока показываем то, что знает браузер: `false` здесь всегда правда,
 * `true` — предположение. Ошибка в оптимистичную сторону безопасна: приёмщик
 * увидит «на связи», а работа всё равно ляжет в очередь.
 */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() => navigator.onLine);

  useEffect(() => {
    const up = () => setOnline(true);
    const down = () => setOnline(false);

    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
    };
  }, []);

  return online;
}
