import { useEffect, useState } from 'react';
import { storageEstimate } from '../storage/db';
import { suggestNames } from './reference';
import { useReference } from './useReference';

/**
 * Состояние справочников и проверка подсказок.
 *
 * <p>Свежесть показывается всегда, а не всплывает при ошибке: справочник
 * трёхдневной давности не содержит вчерашний контейнер, и приёмщик должен
 * понимать это до того, как не найдёт поставку.
 *
 * <p>Поле подсказок здесь — не украшение. Оно доказывает, что поиск идёт
 * по локальным данным: с погашенным сервером подсказки продолжают работать.
 */
export function ReferencePanel() {
  const { status, refresh } = useReference();
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [space, setSpace] = useState<{ usedMb: number; quotaMb: number } | null>(null);

  useEffect(() => {
    void storageEstimate().then(setSpace);
  }, [status]);

  if (status.kind === 'loading') {
    return <p className="note">Читаем локальные справочники…</p>;
  }

  if (status.kind === 'empty') {
    return (
      <section className="card">
        <h2>Справочников нет</h2>
        <p className="note">
          Приёмка без них невозможна: склады, ячейки и машины приходят с сервера.
          Нужно подключиться к сети хотя бы раз.
        </p>
        {status.error !== undefined && <p className="error">{status.error}</p>}
        <button type="button" disabled={busy} onClick={() => void reload()}>
          {busy ? 'Загружаем…' : 'Загрузить'}
        </button>
      </section>
    );
  }

  const { reference, stale } = status;
  const cells = reference.warehouses.reduce((sum, w) => sum + w.cells.length, 0);
  const suggestions = suggestNames(reference.partNames, query);

  return (
    <section className="card">
      <div className="header">
        <h2>Справочники</h2>
        <span className={stale ? 'badge badge--offline' : 'badge badge--online'}>
          {stale ? 'устарели' : 'свежие'}
        </span>
      </div>

      <p className="note">Загружены: {new Date(reference.loadedAt).toLocaleString('ru-RU')}</p>

      <ul className="counts">
        <li>
          Склады: <strong>{reference.warehouses.length}</strong>, ячейки{' '}
          <strong>{cells}</strong>
        </li>
        <li>
          Поставки: <strong>{reference.supplies.length}</strong>
        </li>
        <li>
          Машины в разборе: <strong>{reference.donors.length}</strong>
        </li>
        <li>
          Наименования: <strong>{reference.partNames.length}</strong>
        </li>
      </ul>

      {space !== null && (
        <p className="note">
          Место: {space.usedMb.toFixed(1)} из {space.quotaMb.toFixed(0)} МБ
        </p>
      )}

      {status.error !== undefined && <p className="error">{status.error}</p>}

      <button type="button" disabled={busy} onClick={() => void reload()}>
        {busy ? 'Обновляем…' : 'Обновить'}
      </button>

      <label>
        Проверка подсказок — работает без сети
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="начните писать вид детали"
          autoCapitalize="none"
        />
      </label>

      {suggestions.length > 0 && (
        <ul className="suggestions">
          {suggestions.map((name) => (
            <li key={name.id}>
              {name.name}
              {!name.matched && <span className="muted"> · не распознано</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );

  async function reload() {
    setBusy(true);
    try {
      await refresh();
    } finally {
      setBusy(false);
    }
  }
}
