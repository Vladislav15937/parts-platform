import { Fragment, useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  COLUMNS,
  columnValues,
  FILTER_EMPTY,
  FILTER_PRESENT,
  loadCatalog,
  loadPhotos,
  exportUrl,
  loadVisible,
  saveVisible,
  vehicleLabel,
  NO_VEHICLE,
  type CatalogPage,
  type CatalogQuery,
  type CatalogRow,
  type PartPhoto,
} from '../inventory/catalog';
import { PartCard } from './PartCard';
import { BulkEditForm } from './BulkEditForm';
import { ColumnMenu } from './ColumnMenu';
import { VehiclePicker } from './VehiclePicker';

/**
 * Витрина склада: таблица товаров, как её видит владелец.
 *
 * <p>Собрана по устройству прежней системы клиента: те же колонки, тот же
 * набор фильтров, настройка видимости, сортировка по колонке и счётчик.
 *
 * <p><b>Колонок больше двадцати, но показываются не все.</b> Разом это
 * горизонтальная простыня, в которой не найти цену. Выбор владельца
 * запоминается: настройка таблицы — работа на несколько минут, и терять её
 * при каждом заходе значит не дать ею пользоваться.
 *
 * <p><b>Колонки складов приходят с сервера.</b> У клиента их два, у другого
 * будет пять, и остаток по каждому — своя колонка. Знать это заранее экран
 * не может.
 */
const SIZE = 50;

/** Название колонки для чипа отбора: ключ ничего не говорит владельцу. */
function columnTitle(key: string): string {
  return COLUMNS.find((c) => c.key === key)?.title ?? key;
}

export function CatalogScreen({ role }: { role: string }) {
  const [page, setPage] = useState<CatalogPage | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [visible, setVisible] = useState<string[]>(loadVisible);
  const [settings, setSettings] = useState(false);
  const [query, setQuery] = useState<CatalogQuery>({
    q: '',
    vehicle: NO_VEHICLE,
    reserved: true,
    missing: false,
    warehouses: [],
    columns: {},
    words: {},
    sort: 'code',
    desc: true,
    page: 0,
    size: SIZE,
  });
  const [search, setSearch] = useState('');
  const [picking, setPicking] = useState(false);
  // Снимки показываются по наведению, накладкой поверх таблицы.
  // Не вставкой строки: вставленная сдвигает всё ниже, и строка, на которую
  // человек вёл курсор, уезжает из-под него.
  const [hovered, setHovered] = useState<number | null>(null);
  // Где показывать накладку. Считается от миниатюры, а не задаётся стилями:
  // таблица прокручивается по горизонтали, и absolute внутри неё обрезается
  // контейнером — накладка превращалась в белую полоску сбоку.
  const [at, setAt] = useState<{ left: number; top: number } | null>(null);
  const [photos, setPhotos] = useState<PartPhoto[]>([]);
  // Карточка позиции — по нажатию на строку.
  const [card, setCard] = useState<CatalogRow | null>(null);
  // Режим правки списком: флажки в строках и форма над таблицей. Отдельным
  // режимом, а не всегда, потому что нажатие по строке в обычном режиме
  // открывает карточку — и промах по флажку менял бы не то.
  const [selecting, setSelecting] = useState(false);
  const [chosen, setChosen] = useState<number[]>([]);
  const [editing, setEditing] = useState(false);
  // Какая колонка сейчас набирается и какая показывает меню: открытых
  // больше одной быть не может — они перекрывают друг друга.
  const [typing, setTyping] = useState<string | null>(null);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [menuAt, setMenuAt] = useState<{ left: number; top: number } | null>(null);

  function showPhotos(id: number, target: HTMLElement) {
    if (hovered === id) {
      return;
    }
    const box = target.getBoundingClientRect();
    setAt({ left: box.right + 8, top: box.top - 4 });
    setHovered(id);
    setPhotos([]);
    // Ссылки подписанные и короткоживущие — берутся при показе, а не заранее
    // на все тридцать пять тысяч строк.
    void loadPhotos(id).then(setPhotos).catch(() => setPhotos([]));
  }

  const load = useCallback((next: CatalogQuery) => {
    loadCatalog(next)
      .then((found) => {
        setPage(found);
        setError('');
      })
      .catch((cause) => setError(describe(cause, 'Склад не загрузился')));
  }, []);

  useEffect(() => load(query), [load, query]);

  function change(patch: Partial<CatalogQuery>) {
    // Любая смена отбора возвращает на первую страницу: остаться на сорок
    // второй после нового фильтра значит увидеть пустоту и решить,
    // что ничего не нашлось.
    // Курсор живёт ровно один переход: смена отбора или прыжок по номеру
    // страницы его сбрасывают, иначе он указывал бы в прежнюю выдачу.
    setQuery({ ...query, after: undefined, ...patch, page: patch.page ?? 0 });
  }

  function toggleColumn(key: string) {
    const next = visible.includes(key)
      ? visible.filter((k) => k !== key)
      : [...visible, key];
    setVisible(next);
    saveVisible(next);
  }

  // Постоянные колонки не зависят от сохранённого выбора: настройка могла
  // быть записана до их появления.
  const columns = COLUMNS.filter((c) => c.fixed === true || visible.includes(c.key));
  const warehouses = page?.warehouses ?? [];
  const pages = page === null ? 0 : Math.ceil(page.total / SIZE);

  return (
    <section className="screen screen--wide">
      <h2>
        Склад{' '}
        <span className="muted counter">
          {page === null ? '' : `${page.total.toLocaleString('ru-RU')} товаров`}
        </span>
      </h2>

      {error && <p className="note note--error">{error}</p>}
      {notice && <p className="note">{notice}</p>}

      <div className="filter-row filter-row--search">
        <label className="field">
          Поиск
          <input
            value={search}
            placeholder="номер товара, наименование или номер детали"
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && change({ q: search })}
          />
        </label>
        <button type="button" onClick={() => change({ q: search })}>Найти</button>
      </div>

      <fieldset className="choices">
        <legend>Показывать</legend>
        <label>
          <input
            type="checkbox"
            checked={query.reserved}
            onChange={(e) => change({ reserved: e.target.checked })}
          />
          зарезервированные
        </label>
        <label>
          <input
            type="checkbox"
            checked={query.missing}
            onChange={(e) => change({ missing: e.target.checked })}
          />
          отсутствующие
        </label>
        {warehouses.map((warehouse) => (
          <label key={warehouse.id}>
            <input
              type="checkbox"
              checked={query.warehouses.includes(warehouse.id)}
              onChange={() =>
                change({
                  warehouses: query.warehouses.includes(warehouse.id)
                    ? query.warehouses.filter((id) => id !== warehouse.id)
                    : [...query.warehouses, warehouse.id],
                })
              }
            />
            {warehouse.name}
          </label>
        ))}
      </fieldset>

      <div className="filter-row">
        <button type="button" className="button--ghost" onClick={() => setPicking(true)}>
          Подбор по машине
        </button>
        {query.vehicle.brandId !== null && (
          <button
            type="button"
            className="crumb"
            onClick={() => change({ vehicle: NO_VEHICLE })}
          >
            {vehicleLabel(query.vehicle)} ✕
          </button>
        )}

        {/* Отборы колонок — здесь, а не только в шапке таблицы. Снимаются они
            в меню колонки, а при пустой выдаче таблицы нет вовсе: отбор
            действует, объяснения ему нет нигде, и снять его нечем — остаётся
            перезагрузить страницу. Та же беда, что со скрытой колонкой
            «Номер донора»: почему из тридцати пяти тысяч осталось ноль,
            на экране не написано. */}
        {Object.entries(query.columns).map(([key, value]) => (
          <button
            key={`col-${key}`}
            type="button"
            className="crumb"
            onClick={() => {
              const columns = { ...query.columns };
              delete columns[key];
              change({ columns, page: 0 });
            }}
          >
            {columnTitle(key)}: {value} ✕
          </button>
        ))}

        {Object.entries(query.words).map(([key, value]) => (
          <button
            key={`word-${key}`}
            type="button"
            className="crumb"
            onClick={() => {
              const words = { ...query.words };
              delete words[key];
              change({ words, page: 0 });
            }}
          >
            {columnTitle(key)}: «{value}» ✕
          </button>
        ))}
        <button type="button" className="button--ghost" onClick={() => setSettings(!settings)}>
          Настроить таблицу
        </button>
        {(role === 'OWNER' || role === 'MANAGER') && (
          <button
            type="button"
            className="button--ghost"
            onClick={() => {
              setSelecting(!selecting);
              setChosen([]);
              setEditing(false);
            }}
          >
            {selecting ? 'Выйти из правки' : 'Правка списком'}
          </button>
        )}
        {/* Ссылкой, а не кнопкой с запросом: файл на двенадцать мегабайт
            качает браузер, показывая ход, и вкладка при этом жива. */}
        <a className="button--ghost" href={exportUrl(query)} download>
          Скачать таблицу
        </a>
      </div>

      {/* Накладка со снимками — вне таблицы: внутри её обрезал бы контейнер
          с горизонтальной прокруткой. */}
      {hovered !== null && at !== null && photos.length > 0 && (
        <div className="thumb-popover" style={{ left: at.left, top: at.top }}>
          {photos.map((photo) => (
            <img key={photo.photoId} src={photo.url} alt="" />
          ))}
        </div>
      )}

      {menuFor !== null && menuAt !== null && (
        <ColumnMenu
          column={menuFor}
          at={menuAt}
          chosen={query.columns[menuFor]}
          sortable={COLUMNS.find((c) => c.key === menuFor)?.sort}
          sort={query.sort}
          desc={query.desc}
          onSort={(desc) => {
            const column = COLUMNS.find((c) => c.key === menuFor);
            if (column?.sort !== undefined) change({ sort: column.sort, desc, page: 0 });
            setMenuFor(null);
          }}
          onPick={(value) => {
            const chosenColumns = { ...query.columns };
            if (value === null) delete chosenColumns[menuFor];
            else chosenColumns[menuFor] = value;
            change({ columns: chosenColumns, page: 0 });
            setMenuFor(null);
          }}
          values={columnValues}
          empty={FILTER_EMPTY}
          present={FILTER_PRESENT}
          onClose={() => setMenuFor(null)}
        />
      )}

      {card !== null && (
        <PartCard
          row={card}
          warehouses={warehouses}
          role={role}
          onClose={() => setCard(null)}
          onChanged={() => {
            setCard(null);
            load(query);
          }}
          // «Что ещё сняли с этой машины» — обычный отбор по колонке
          // «Номер донора», а не отдельный экран: список тот же самый,
          // с теми же колонками и той же выгрузкой.
          //
          // Колонка при этом включается, даже если была скрыта: иначе
          // отбор невидим — из трёхсот семидесяти пяти позиций остаётся две,
          // и почему, на экране не написано нигде, а снять его нечем.
          onDonorParts={(donorCode) => {
            setCard(null);
            if (!visible.includes('donor')) {
              toggleColumn('donor');
            }
            change({ columns: { ...query.columns, donor: donorCode } });
          }}
        />
      )}

      {picking && (
        <VehiclePicker
          chosen={query.vehicle}
          onPick={(vehicle) => {
            setPicking(false);
            change({ vehicle });
          }}
          onClose={() => setPicking(false)}
        />
      )}

      {/* Панель показывается на весь режим правки, а не с первого выбранного:
          появившись, она сдвигает таблицу вниз, и следующее нажатие попадает
          в соседнюю строку — снимая то, что было только что выбрано. Та же
          ловушка, что с накладкой снимков. Поймано живым прогоном. */}
      {selecting && !editing && (
        <div className="filter-row">
          <span className="note">
            {chosen.length === 0 ? 'Отметьте позиции' : `Выбрано ${chosen.length}`}
          </span>
          <button type="button" disabled={chosen.length === 0} onClick={() => setEditing(true)}>
            Изменить
          </button>
          <button type="button" className="button--ghost" disabled={chosen.length === 0}
                  onClick={() => setChosen([])}>
            Снять выделение
          </button>
        </div>
      )}

      {editing && (
        <BulkEditForm
          partIds={chosen}
          onSaved={(changed) => {
            setEditing(false);
            setChosen([]);
            setNotice(`Изменено позиций: ${changed}`);
            load(query);
          }}
          onCancel={() => setEditing(false)}
        />
      )}

      {settings && (
        <fieldset className="choices">
          <legend>Колонки</legend>
          {/* Постоянные колонки в список не идут: отключить их нельзя,
              а флажок, который не работает, хуже отсутствующего. */}
          {COLUMNS.filter((c) => c.fixed !== true).map((column) => (
            <label key={column.key}>
              <input
                type="checkbox"
                checked={visible.includes(column.key)}
                onChange={() => toggleColumn(column.key)}
              />
              {column.title}
            </label>
          ))}
        </fieldset>
      )}

      {page === null ? (
        <p className="note">Загружаем…</p>
      ) : page.rows.length === 0 ? (
        <p className="note">Ничего не найдено.</p>
      ) : (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                {selecting && (
                  <th className="th--check">
                    <input
                      type="checkbox"
                      checked={chosen.length === page.rows.length && page.rows.length > 0}
                      onChange={(e) => setChosen(
                        e.target.checked ? page.rows.map((r) => r.id) : [])}
                    />
                  </th>
                )}
                {columns.map((column) => (
                  <th
                    key={column.key}
                    className={
                      [column.numeric ? 'num' : '',
                       query.columns[column.key] !== undefined
                         || query.words[column.key] !== undefined ? 'th--filtered' : '']
                        .filter((c) => c !== '').join(' ') || undefined
                    }
                  >
                    {/* Нажатие на название даёт поле ввода, список значений
                        и сортировка — на стрелке. Так в кабинете, и так
                        быстрее: набрать три буквы легче, чем искать значение
                        глазами в списке из сотни. */}
                    {typing === column.key ? (
                      <input
                        autoFocus
                        className="th__input"
                        defaultValue={query.words[column.key] ?? ''}
                        onKeyDown={(e) => {
                          if (e.key === 'Escape') setTyping(null);
                          if (e.key !== 'Enter') return;
                          const words = { ...query.words };
                          const typed = e.currentTarget.value.trim();
                          if (typed === '') delete words[column.key];
                          else words[column.key] = typed;
                          change({ words, page: 0 });
                          setTyping(null);
                        }}
                        onBlur={() => setTyping(null)}
                      />
                    ) : (
                      <span
                        className="th__title"
                        onClick={() => setTyping(column.key)}
                        style={{ cursor: 'text' }}
                      >
                        {column.title}
                      </span>
                    )}
                    <button
                      type="button"
                      className="th__menu"
                      onClick={(e) => {
                        const box = e.currentTarget.getBoundingClientRect();
                        setMenuAt({ left: box.left, top: box.bottom });
                        setMenuFor(menuFor === column.key ? null : column.key);
                      }}
                    >
                      {column.sort === query.sort ? (query.desc ? '↓' : '↑') : '▾'}
                    </button>
                    {query.columns[column.key] !== undefined && (
                      <div className="th__value">«{query.columns[column.key]}»</div>
                    )}
                    {query.words[column.key] !== undefined && (
                      <div className="th__value">~{query.words[column.key]}</div>
                    )}
                  </th>
                ))}
                {warehouses.map((warehouse) => (
                  <th key={warehouse.id} className="num">{warehouse.name}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {page.rows.map((row) => (
                <Fragment key={row.id}>
                <tr
                  className={
                    chosen.includes(row.id) ? 'row--clickable is-chosen' : 'row--clickable'
                  }
                  onClick={() => (selecting
                    ? setChosen(chosen.includes(row.id)
                        ? chosen.filter((id) => id !== row.id)
                        : [...chosen, row.id])
                    : setCard(row))}
                >
                  {selecting && (
                    <td className="th--check">
                      <input type="checkbox" readOnly checked={chosen.includes(row.id)} />
                    </td>
                  )}
                  {columns.map((column) => (
                    <td key={column.key} className={column.numeric ? 'num' : undefined}>
                      {column.image
                        ? column.image(row) !== null && (
                            <span
                              className="thumb-hover"
                              onMouseEnter={(e) => showPhotos(row.id, e.currentTarget)}
                              onMouseLeave={() => setHovered(null)}
                            >
                              <img
                                className="thumb"
                                src={column.image(row) ?? ''}
                                alt=""
                                loading="lazy"
                              />
                            </span>
                          )
                        : column.value(row)}
                    </td>
                  ))}
                  {warehouses.map((warehouse) => (
                    <td key={warehouse.id} className="num">
                      {/* Прочерк, а не ноль: «на этом складе её нет»
                          и «есть ноль штук» читаются одинаково, но первое
                          понятнее. */}
                      {row.stock[String(warehouse.id)] ?? '—'}
                    </td>
                  ))}
                </tr>
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {pages > 1 && (
        <div className="filter-row">
          <button
            type="button"
            className="button--ghost"
            disabled={query.page === 0}
            onClick={() => change({ page: query.page - 1 })}
          >
            ←
          </button>
          <span className="note">
            {query.page + 1} из {pages.toLocaleString('ru-RU')}
          </span>
          <button
            type="button"
            className="button--ghost"
            disabled={query.page + 1 >= pages}
            onClick={() => change({
              page: query.page + 1,
              // Вперёд — от последней строки: серверу не придётся читать
              // и выбрасывать всё, что до неё. Назад и при прыжке курсора
              // нет, и страница берётся отступом, как раньше.
              after: query.sort === 'code' && page !== null
                ? (page.rows[page.rows.length - 1]?.code ?? undefined)
                : undefined,
            })}
          >
            →
          </button>
        </div>
      )}
    </section>
  );
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}
