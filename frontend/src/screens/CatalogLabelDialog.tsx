import { useState } from 'react';
import type { CatalogRow } from '../inventory/catalog';
import {
  COPIES_MODES,
  copiesOf,
  copiesProblem,
  partLabel,
  repeated,
  type CopiesMode,
  type Label,
} from '../labels/labels';
import { count, plural } from '../ui/plural';
import { useLockedScroll } from '../ui/useLockedScroll';

/** Подпись поля числа копий — она же называет его в причине отказа. */
const GIVEN_FIELD = 'Количество на товар';

/**
 * Печать этикеток отмеченных строк склада (задача 0371).
 *
 * <p>Окно и его слова — с ориентира: «Печать этикеток для N товаров», три
 * режима, «Отправить в печать» и «Отменить». Строки «Принтер» нет: печатает
 * браузер (`labels.ts`), и принтер выбирается в его диалоге.
 *
 * <p>Лист этикеток окно само не рисует — отдаёт его экрану (`onSend`). Внутри
 * окна (`position: fixed`) многостраничная печать не идёт: рулон — это
 * страница на этикетку, а печать фиксированного слоя обрезается первой.
 */
export function CatalogLabelDialog({ total, rows, failure, onSend, onCancel }: {
  /** Сколько товаров выбрано — для заголовка, пока строки весь отбор ещё грузят. */
  total: number;
  /** Строки выбранного; `null` — ещё грузятся (весь отбор). */
  rows: CatalogRow[] | null;
  /** Не смогли загрузить строки — слова причины. */
  failure: string | null;
  onSend: (labels: Label[]) => void;
  onCancel: () => void;
}) {
  const [mode, setMode] = useState<CopiesMode>('one');
  // Единица, а не пусто: поле появляется по выбору режима, и пустое
  // встречало бы человека отказом раньше, чем он что-то набрал.
  const [given, setGiven] = useState('1');

  useLockedScroll();

  const problem = mode === 'given' ? copiesProblem(given, GIVEN_FIELD) : null;
  const perItem = problem === null && mode === 'given' ? Number(given.trim()) : 1;

  // Без номера товара печатать нечего: он и кодируется штрихкодом (задача 0366).
  const coded = (rows ?? []).filter((row) => row.number !== null && row.number !== undefined);
  const uncoded = (rows ?? []).length - coded.length;
  const items = coded.map((row) => ({
    label: partLabel(row.number, row.title, row.price, row.condition),
    copies: copiesOf(mode, Number(row.qty), perItem),
  }));
  const empty = mode === 'stock' ? items.filter((item) => item.copies === 0).length : 0;
  const labels = repeated(items);

  // Одно выражение на кнопку и на подпись под ней: серая кнопка без причины
  // читается как поломка.
  const obstacle = rows === null ? null
    : problem !== null ? problem
    : labels.length === 0 ? 'Печатать нечего: ни на одну позицию этикетки не выходит.'
    : null;

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div
        className="modal modal--short"
        role="dialog"
        aria-label="Печать этикеток"
        onClick={(event) => event.stopPropagation()}
      >
        <h2>Печать этикеток для {count(total)} {plural(total, 'товара', 'товаров', 'товаров')}</h2>

        {failure !== null && <p className="note note--error">{failure}</p>}
        {rows === null && failure === null && <p className="note">Загружаем…</p>}

        <fieldset className="choices">
          <legend>Настройки печати</legend>
          {COPIES_MODES.map((option) => (
            <label key={option.mode}>
              <input
                type="radio"
                name="label-copies"
                checked={mode === option.mode}
                onChange={() => setMode(option.mode)}
              />
              {option.title}
            </label>
          ))}
        </fieldset>

        {mode === 'given' && (
          <label className="field">
            {GIVEN_FIELD}
            <input
              value={given}
              inputMode="numeric"
              autoFocus
              onChange={(event) => setGiven(event.target.value)}
            />
          </label>
        )}

        {rows !== null && (
          <p className="note">
            К печати: {count(labels.length)}{' '}
            {plural(labels.length, 'этикетка', 'этикетки', 'этикеток')}.
          </p>
        )}
        {uncoded > 0 && (
          <p className="note">
            У {count(uncoded)} {plural(uncoded, 'позиции', 'позиций', 'позиций')} нет
            номера товара — на них этикеток не будет: штрихкодом кодируется он.
          </p>
        )}
        {empty > 0 && (
          <p className="note">
            У {count(empty)} {plural(empty, 'позиции', 'позиций', 'позиций')} остатка
            нет — по количеству в остатке на них этикеток не будет.
          </p>
        )}
        {obstacle !== null && <p className="note note--error">{obstacle}</p>}

        <div className="filter-row">
          <button
            type="button"
            disabled={rows === null || obstacle !== null}
            onClick={() => onSend(labels)}
          >
            Отправить в печать
          </button>
          <button type="button" className="button--ghost" onClick={onCancel}>
            Отменить
          </button>
        </div>
      </div>
    </div>
  );
}
