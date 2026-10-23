import { useState } from 'react';
import { bulkCaption, type PartHistory } from '../inventory/catalog';

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
export function PartHistoryView({ history, tab, onTab, onDelete, busy = false, notice }: {
  history: PartHistory | null;
  tab: 'changes' | 'movements';
  onTab: (tab: 'changes' | 'movements') => void;
  /**
   * Удаление выполненного документа строки (задача 0367). Не передано —
   * колонки действия нет вовсе; передано — кнопка стоит только у строк,
   * которые сервер отметил удаляемыми.
   */
  onDelete?: (documentId: number) => Promise<void>;
  busy?: boolean;
  /** Итог удаления словами — что удалено и что не снято со склада. */
  notice?: { text: string; error: boolean } | null;
}) {
  const [asked, setAsked] = useState<number | null>(null);

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
                        {/* Насколько подвинулись деньги. Считает сервер
                            по «было» и «стало»: «5 000 → 4 500» надо делить
                            в уме, а разбираются с этим по два десятка строк
                            за раз. */}
                        {field.delta !== null && (
                          <span className="history__delta">({field.delta})</span>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
                {/* Массовая правка называет себя (задача 0373): без этого
                    «Заметка: → Наличные» читается как правка руками по одной,
                    и разбор «почему у детали вдруг другое» уходит в журнал. */}
                {change.bulk != null && (
                  <div className="history__bulk">{bulkCaption(change.bulk)}</div>
                )}
              </li>
            ))}
          </ul>
        )
      ) : history.movements.length === 0 ? (
        <p className="muted">Движений не было.</p>
      ) : (
        <>
          {notice !== null && notice !== undefined && (
            <p className={notice.error ? 'note note--error' : 'note'} role="status">{notice.text}</p>
          )}
          {/* Колонки — как «Движение товара» у Bazon (решение 7 владельца,
              23.10.2026): статус своей колонкой, а не мелко под документом,
              и колонки «Что» нет. Автор и причина остались строкой под
              документом: без них «кто списал и почему» не узнать ничем. */}
          <table className="history__moves">
            <thead>
              <tr>
                <th>Дата/Время</th>
                <th>Документ/Склад</th>
                <th>Статус</th>
                <th className="num">Кол-во</th>
                <th className="num">Цена</th>
                <th>Поступление</th>
                <th>Поставка</th>
                {onDelete !== undefined && <th aria-label="Действия" />}
              </tr>
            </thead>
            <tbody>
              {history.movements.map((move, i) => (
                <tr key={`${move.at}-${i}`}>
                  <td>{moment(move.at)}</td>
                  <td>
                    {move.document ?? '—'}
                    {move.warehouse !== null && ` / ${move.warehouse}`}
                    {move.reason !== null && <div className="muted">{move.reason}</div>}
                    {move.author !== null && <div className="muted">{move.author}</div>}
                  </td>
                  <td className={move.deleted === true ? 'history__status--deleted' : undefined}>
                    {move.status ?? '—'}
                  </td>
                  {/* Знак не убираем: минус — ушло со склада, плюс — пришло.
                      Без него продажа и возврат выглядят одинаково. */}
                  <td className="num">{move.held === true ? String(Number(move.qty)) : qty(move.qty)}</td>
                  <td className="num">{move.price != null ? rub(move.price) : ''}</td>
                  <td>{history.intake ?? ''}</td>
                  <td>{history.supply ?? ''}</td>
                  {onDelete !== undefined && (
                    <td>
                      {move.deletableDocumentId != null && (
                        asked === move.deletableDocumentId ? (
                          <span className="history__confirm">
                            {/* Документ удаляется целиком, а не одна строка:
                                у поступления их бывает тридцать. Сказать
                                об этом надо до нажатия. */}
                            <span className="muted">
                              Удалить {move.document} целиком? Со склада снимется только
                              свободное.
                            </span>
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => {
                                const id = move.deletableDocumentId;
                                if (id == null) return;
                                setAsked(null);
                                void onDelete(id);
                              }}
                            >
                              Удалить документ
                            </button>
                            <button type="button" className="button--ghost" onClick={() => setAsked(null)}>
                              Отмена
                            </button>
                          </span>
                        ) : (
                          <button
                            type="button"
                            className="button--ghost"
                            disabled={busy}
                            aria-label={`Удалить: ${move.document ?? 'документ'}`}
                            onClick={() => setAsked(move.deletableDocumentId ?? null)}
                          >
                            Удалить
                          </button>
                        )
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

function rub(value: number): string {
  return `${Number(value).toLocaleString('ru-RU')} ₽`;
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
