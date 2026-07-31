import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  COLUMNS,
  loadCatalog,
  exportUrl,
  loadVisible,
  saveVisible,
  vehicleLabel,
  NO_VEHICLE,
  type CatalogPage,
  type CatalogQuery,
} from '../inventory/catalog';
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

export function CatalogScreen() {
  const [page, setPage] = useState<CatalogPage | null>(null);
  const [error, setError] = useState('');
  const [visible, setVisible] = useState<string[]>(loadVisible);
  const [settings, setSettings] = useState(false);
  const [query, setQuery] = useState<CatalogQuery>({
    q: '',
    vehicle: NO_VEHICLE,
    reserved: true,
    missing: false,
    warehouses: [],
    sort: 'code',
    desc: true,
    page: 0,
    size: SIZE,
  });
  const [search, setSearch] = useState('');
  const [picking, setPicking] = useState(false);

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
    setQuery({ ...query, ...patch, page: patch.page ?? 0 });
  }

  function toggleColumn(key: string) {
    const next = visible.includes(key)
      ? visible.filter((k) => k !== key)
      : [...visible, key];
    setVisible(next);
    saveVisible(next);
  }

  function sortBy(sort: string) {
    // Второй клик по той же колонке переворачивает порядок — так это
    // работает везде, и объяснять не нужно.
    change(query.sort === sort ? { desc: !query.desc } : { sort, desc: false });
  }

  const columns = COLUMNS.filter((c) => visible.includes(c.key));
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
        <button type="button" className="button--ghost" onClick={() => setSettings(!settings)}>
          Настроить таблицу
        </button>
        {/* Ссылкой, а не кнопкой с запросом: файл на двенадцать мегабайт
            качает браузер, показывая ход, и вкладка при этом жива. */}
        <a className="button--ghost" href={exportUrl(query)} download>
          Скачать таблицу
        </a>
      </div>

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

      {settings && (
        <fieldset className="choices">
          <legend>Колонки</legend>
          {COLUMNS.map((column) => (
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
                {columns.map((column) => (
                  <th
                    key={column.key}
                    className={column.numeric ? 'num' : undefined}
                    onClick={() => column.sort !== undefined && sortBy(column.sort)}
                    style={column.sort === undefined ? undefined : { cursor: 'pointer' }}
                  >
                    {column.title}
                    {/* Стрелка только там, где сортировка есть: на колонке
                        без неё она обманывала бы. */}
                    {column.sort === query.sort && (query.desc ? ' ↓' : ' ↑')}
                  </th>
                ))}
                {warehouses.map((warehouse) => (
                  <th key={warehouse.id} className="num">{warehouse.name}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {page.rows.map((row) => (
                <tr key={row.id}>
                  {columns.map((column) => (
                    <td key={column.key} className={column.numeric ? 'num' : undefined}>
                      {column.value(row)}
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
            onClick={() => change({ page: query.page + 1 })}
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
