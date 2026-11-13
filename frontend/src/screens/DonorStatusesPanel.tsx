import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  addDonorStatus,
  listDonorStatuses,
  removeDonorStatus,
  renameDonorStatus,
  type DonorStatusEntry,
} from '../intake/donors';
import { plural } from '../ui/plural';
import { useMounted } from '../ui/useMounted';

/**
 * «Статусы машин» (задача 0364): список, из которого выбирают статус в форме
 * машины. У ориентира он свой у каждого клиента («В Японии», «В [город]»),
 * решение владельца 19.10.2026 — так же. Первые значения — прежние стадии,
 * их завела миграция.
 *
 * <p>Удалить выбранный у машин статус сервер не даёт и говорит почему; экран
 * кнопку у такого не прячет, а называет число машин рядом — чтобы отказ
 * не был неожиданным, но и чтобы было видно, где статус ещё стоит.
 *
 * <p>«Пусто» и «не смогли узнать» различаются: отказ сервера не выдаётся
 * за пустой список (`failed`), как было бы с `[]` при ошибке.
 */
export function DonorStatusesPanel() {
  const [statuses, setStatuses] = useState<DonorStatusEntry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState('');
  const [name, setName] = useState('');
  const [editing, setEditing] = useState<number | null>(null);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const mounted = useMounted();

  useEffect(() => {
    void (async () => {
      try {
        const found = await listDonorStatuses();
        if (mounted.current) setStatuses(found);
      } catch (cause) {
        if (mounted.current) {
          setFailed(true);
          setError(describe(cause, 'Статусы машин не загрузились'));
        }
      }
    })();
  }, [mounted]);

  return (
    <div className="card">
      <h3>Статусы машин</h3>
      <p className="note">Из этого списка выбирают статус в карточке машины</p>

      {error !== '' && <p className="note note--error">{error}</p>}

      {statuses === null ? (
        !failed && <p className="note">Загружаем…</p>
      ) : statuses.length === 0 ? (
        <p className="note">Статусы не заведены. Пока их нет, статус у машины не выбрать.</p>
      ) : (
        // Своя прокрутка: панель настроек узкая (280 на мониторе), а две
        // кнопки в строке шире её — без обёртки таблица вылезала за полотно
        // карточки, замерено живым прогоном.
        <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Статус</th>
              <th>Машин</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {statuses.map((status) => (
              <tr key={status.id}>
                <td>
                  {editing === status.id ? (
                    <input aria-label={`Новое название: ${status.name}`} value={draft}
                           onChange={(e) => setDraft(e.target.value)} />
                  ) : status.name}
                </td>
                <td>{status.donors}</td>
                <td>
                  {editing === status.id ? (
                    <>
                      <button type="button" disabled={busy || draft.trim() === ''}
                              onClick={() => void run(() => renameDonorStatus(status.id, draft.trim()),
                                'Статус не переименован', () => setEditing(null))}>
                        Сохранить
                      </button>
                      <button type="button" className="button--ghost" disabled={busy}
                              onClick={() => setEditing(null)}>
                        Отменить
                      </button>
                    </>
                  ) : (
                    <>
                      <button type="button" className="button--ghost"
                              aria-label={`Переименовать: ${status.name}`}
                              onClick={() => { setEditing(status.id); setDraft(status.name); }}>
                        Переименовать
                      </button>
                      <button type="button" className="button--ghost" disabled={busy}
                              aria-label={`Удалить: ${status.name}`}
                              title={status.donors > 0
                                ? `Выбран у ${status.donors} ${plural(status.donors, 'машины', 'машин', 'машин')}`
                                : undefined}
                              onClick={() => void run(() => removeDonorStatus(status.id),
                                'Статус не удалён')}>
                        Удалить
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}

      <div className="row">
        <label className="field">
          Название
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="В Японии" />
        </label>
      </div>
      <button type="button" disabled={busy || name.trim() === ''}
              onClick={() => void run(() => addDonorStatus(name.trim()), 'Статус не заведён',
                () => setName(''))}>
        Добавить статус
      </button>
    </div>
  );

  /** Каждое действие отвечает списком целиком — его и показываем. */
  async function run(action: () => Promise<DonorStatusEntry[]>, fallback: string,
                     done?: () => void): Promise<void> {
    setBusy(true);
    setError('');
    try {
      const found = await action();
      if (!mounted.current) return;
      setStatuses(found);
      setFailed(false);
      done?.();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, fallback));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    return cause.status === 0 ? 'Нет связи с сервером' : cause.message;
  }
  return fallback;
}
