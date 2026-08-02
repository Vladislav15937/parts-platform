import type { PartHistory } from '../inventory/catalog';

/**
 * История позиции: две ленты, между которыми переключаются.
 *
 * <p>Разведены они не для красоты. «Кто уронил цену» и «куда делась деталь» —
 * разные вопросы, и в общем списке правка заметки встаёт между продажей
 * и возвратом: движение остатка в такой ленте не найти. Так же сделано
 * в кабинете, к которому клиент привык.
 *
 * <p>Свежее сверху: историю открывают, чтобы понять, что произошло только что.
 */
export function PartHistoryView({ history, tab, onTab }: {
  history: PartHistory | null;
  tab: 'changes' | 'movements';
  onTab: (tab: 'changes' | 'movements') => void;
}) {
  if (history === null) {
    return <p className="muted">Загружаем…</p>;
  }

  return (
    <div className="history">
      <div className="history__tabs">
        <button
          type="button"
          className={tab === 'changes' ? 'chip chip--active' : 'chip'}
          onClick={() => onTab('changes')}
        >
          Правки {history.changes.length > 0 && `· ${history.changes.length}`}
        </button>
        <button
          type="button"
          className={tab === 'movements' ? 'chip chip--active' : 'chip'}
          onClick={() => onTab('movements')}
        >
          Движения {history.movements.length > 0 && `· ${history.movements.length}`}
        </button>
      </div>

      {tab === 'changes' ? (
        history.changes.length === 0 ? (
          <p className="muted">Карточку не правили.</p>
        ) : (
          <ul className="history__list">
            {history.changes.map((change, i) => (
              <li key={`${change.at}-${i}`} className="history__item">
                <div className="history__head">
                  <span className="history__at">{moment(change.at)}</span>
                  {/* Автор известен не всегда, и придумывать его нельзя:
                      у всего, что приехало переносом, его нет. */}
                  <span className="muted">{change.author ?? 'автор не записан'}</span>
                </div>
                {change.action !== null ? (
                  <div className="history__action">{change.action}</div>
                ) : (
                  <ul className="history__fields">
                    {change.fields.map((field) => (
                      <li key={field.label}>
                        <span className="history__field">{field.label}</span>
                        <span className="history__was">{field.before ?? '—'}</span>
                        <span className="history__arrow">→</span>
                        <span className="history__now">{field.after ?? '—'}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        )
      ) : history.movements.length === 0 ? (
        <p className="muted">Движений не было.</p>
      ) : (
        <table className="history__moves">
          <thead>
            <tr>
              <th>Когда</th>
              <th>Что</th>
              <th className="num">Кол-во</th>
              <th>Документ</th>
              <th>Склад</th>
            </tr>
          </thead>
          <tbody>
            {history.movements.map((move, i) => (
              <tr key={`${move.at}-${i}`}>
                <td>{moment(move.at)}</td>
                <td>
                  {move.type}
                  {move.reason !== null && <div className="muted">{move.reason}</div>}
                  {move.author !== null && <div className="muted">{move.author}</div>}
                </td>
                {/* Знак не убираем: минус — ушло со склада, плюс — пришло.
                    Без него продажа и возврат выглядят одинаково. */}
                <td className="num">{qty(move.qty)}</td>
                <td>
                  {move.document ?? '—'}
                  {move.status !== null && <div className="muted">{move.status}</div>}
                </td>
                <td>{move.warehouse ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** Дата и время: без времени две правки одного дня сливаются в одну. */
function moment(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit', month: '2-digit', year: '2-digit',
    hour: '2-digit', minute: '2-digit',
  });
}

function qty(value: number): string {
  const shown = Number(value);
  return shown > 0 ? `+${shown}` : String(shown);
}
