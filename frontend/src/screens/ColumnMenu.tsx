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
  column, at, chosen, sortable, filterable, sort, desc, values, empty, present,
  onSort, onPick, onClose,
}: {
  column: string;
  at: { left: number; top: number };
  chosen: string | undefined;
  /** Имя сортировки, если по колонке сортируют. */
  sortable: string | undefined;
  /**
   * Делается ли по колонке отбор.
   *
   * <p>Список приходит с сервера, а не повторяется здесь: два списка
   * разошлись бы на первой же новой колонке, и разошлись бы молча. Пока
   * меню открывалось у любой колонки, по превью и состоянию оно предлагало
   * отбор, которого нет: сервер отвечал «по этой колонке отбор не делается»,
   * `catch` превращал отказ в пустой список, а выбранное значение ничего
   * не меняло. Владелец видел те же тридцать пять тысяч строк и решал,
   * что весь склад новый или что отбор сломан.
   */
  filterable: boolean;
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
    if (!filterable) {
      return;
    }
    let alive = true;
    void values(column)
      .then((list) => { if (alive) setFound(list); })
      .catch(() => { if (alive) setFound([]); });
    return () => { alive = false; };
  }, [column, values, filterable]);

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
      {filterable && (
        <input
          autoFocus
          value={typed}
          placeholder="поиск"
          onChange={(e) => setTyped(e.target.value)}
        />
      )}
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
        {filterable && (
        <li>
          <button type="button" className={chosen === undefined ? 'is-chosen' : ''}
                  onClick={() => onPick(null)}>
            — все —
          </button>
        </li>
        )}
        {filterable && found === null ? (
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
        {filterable && (
        <>
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
        </>
        )}
      </ul>
    </div>
  );
}
