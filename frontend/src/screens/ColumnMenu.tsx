import { useEffect, useState } from 'react';

/**
 * Меню колонки: сортировка и список значений, которые в ней встречаются.
 *
 * <p>Один на витрину склада и на вкладку колёс: механика одна и та же,
 * а второй экземпляр разошёлся бы с первым на первой же правке. Различаются
 * они только тем, откуда берутся значения, — это и передаётся снаружи.
 *
 * <p>Сортировка здесь же, а не отдельной кнопкой: и то и другое — «что
 * сделать с этой колонкой», и разводить их по двум местам значит заставлять
 * искать каждое.
 *
 * <p>Рисуется вне таблицы: внутри его обрезает контейнер с горизонтальной
 * прокруткой, и от списка остаётся белая полоска.
 */
export function ColumnMenu({
  column, at, chosen, sortable, sort, desc, values, empty, present,
  onSort, onPick, onClose,
}: {
  column: string;
  at: { left: number; top: number };
  chosen: string | undefined;
  /** Имя сортировки, если по колонке сортируют. */
  sortable: string | undefined;
  sort: string;
  desc: boolean;
  /** Откуда брать значения: у склада и у колёс свои списки. */
  values: (column: string) => Promise<string[]>;
  empty: string;
  present: string;
  onSort: (desc: boolean) => void;
  onPick: (value: string | null) => void;
  onClose: () => void;
}) {
  const [found, setFound] = useState<string[] | null>(null);
  const [typed, setTyped] = useState('');

  useEffect(() => {
    let alive = true;
    void values(column)
      .then((list) => { if (alive) setFound(list); })
      .catch(() => { if (alive) setFound([]); });
    return () => { alive = false; };
  }, [column, values]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const shown = (found ?? []).filter(
    (value) => value.toLowerCase().includes(typed.trim().toLowerCase()),
  );

  return (
    <div className="value-picker" style={{ left: at.left, top: at.top }}>
      <input
        autoFocus
        value={typed}
        placeholder="поиск"
        onChange={(e) => setTyped(e.target.value)}
      />
      <ul>
        {sortable !== undefined && (
          <>
            <li>
              <button type="button" className={sortable === sort && !desc ? 'is-chosen' : ''}
                      onClick={() => onSort(false)}>
                ↑ по возрастанию
              </button>
            </li>
            <li>
              <button type="button" className={sortable === sort && desc ? 'is-chosen' : ''}
                      onClick={() => onSort(true)}>
                ↓ по убыванию
              </button>
            </li>
            <li className="value-picker__line" />
          </>
        )}
        <li>
          <button type="button" className={chosen === undefined ? 'is-chosen' : ''}
                  onClick={() => onPick(null)}>
            — все —
          </button>
        </li>
        {found === null ? (
          <li className="muted">Читаем…</li>
        ) : (
          shown.map((value) => (
            <li key={value}>
              <button type="button" className={chosen === value ? 'is-chosen' : ''}
                      onClick={() => onPick(value)}>
                {value}
              </button>
            </li>
          ))
        )}
        {/* «Где не заполнено» — вопрос, который задают, разгребая склад
            после переезда. Отдельными пунктами, а не значением: пустая
            строка в списке выглядела бы промахом мыши. */}
        <li>
          <button type="button" className={chosen === present ? 'is-chosen' : ''}
                  onClick={() => onPick(present)}>
            {present}
          </button>
        </li>
        <li>
          <button type="button" className={chosen === empty ? 'is-chosen' : ''}
                  onClick={() => onPick(empty)}>
            {empty}
          </button>
        </li>
      </ul>
    </div>
  );
}
