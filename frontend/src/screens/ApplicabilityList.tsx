import { useCallback, useEffect, useState } from 'react';
import { loadFitList, setEngineMatters, type FitListPage } from '../inventory/catalog';
import { useLockedScroll } from '../ui/useLockedScroll';
import { useMounted } from '../ui/useMounted';
import { count } from '../ui/plural';

/**
 * «Применимость подробно» (задача 0363, как у Bazon): позиции склада по номеру
 * по убыванию, машина применимости, кто и когда её установил, «Двигатель важен».
 *
 * <p>Три состояния различимы: грузим, не смогли узнать, пусто. Страница по 50 строк,
 * подвал называет, сколько показано из скольких. Неизвестный автор и время (строка
 * лежала до задачи 0363, перенос, разбор) — прочерк, а не выдуманное значение.
 */
export function ApplicabilityList({ canEdit, onClose }: { canEdit: boolean; onClose: () => void }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<FitListPage | null>(null);
  const [failed, setFailed] = useState(false);
  const mounted = useMounted();
  useLockedScroll();

  const load = useCallback((n: number) => {
    setFailed(false);
    void loadFitList(n)
      .then((found) => { if (mounted.current) setData(found); })
      .catch(() => { if (mounted.current) { setData(null); setFailed(true); } });
  }, [mounted]);

  useEffect(() => { load(page); }, [load, page]);

  async function toggle(partId: number, matters: boolean): Promise<void> {
    try {
      await setEngineMatters(partId, matters);
      if (mounted.current) load(page);
    } catch {
      if (mounted.current) setFailed(true);
    }
  }

  const last = data === null ? 0 : Math.max(0, Math.ceil(data.total / data.size) - 1);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="card-view" onClick={(event) => event.stopPropagation()}>
        <header className="card-view__head">
          <h2>Применимость подробно</h2>
          <button type="button" className="button--ghost" onClick={onClose}>Закрыть</button>
        </header>
        {failed && <p className="note note--error">Не удалось получить список. Повторите.</p>}
        {data === null && !failed && <p className="muted">Загружаем…</p>}
        {data !== null && data.rows.length === 0 && <p className="muted">Позиций нет.</p>}
        {data !== null && data.rows.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>№ позиции</th><th>Запчасть</th><th>Марка</th><th>Модель</th>
                <th>Поколение</th><th>Годы</th><th>Кто установил</th><th>Установлена</th>
                <th>Двигатель важен</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((r, i) => (
                <tr key={`${r.partId}-${i}`}>
                  <td>{r.number}</td>
                  <td>{r.title}</td>
                  <td>{r.brand ?? '—'}</td>
                  <td>{r.model ?? '—'}</td>
                  <td>{r.generation ?? '—'}</td>
                  <td>{r.yearFrom === null ? '—' : `${r.yearFrom}—${r.yearTo ?? 'н.в.'}`}</td>
                  <td>{r.setBy ?? '—'}</td>
                  <td>{r.setAt === null ? '—' : new Date(r.setAt).toLocaleString('ru-RU')}</td>
                  <td>
                    {r.engineMatters ? 'важен' : 'неважен'}
                    {canEdit && (
                      <button type="button" className="mark__link"
                              onClick={() => void toggle(r.partId, !r.engineMatters)}>
                        изменить
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data !== null && (
          <footer>
            <span className="muted">
              Строк {count(data.rows.length)} из {count(data.total)}
            </span>
            <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Назад</button>
            <button type="button" disabled={page >= last} onClick={() => setPage(page + 1)}>Вперёд</button>
          </footer>
        )}
      </div>
    </div>
  );
}
