import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import {
  CONDITIONS,
  filterSummary,
  listFeeds,
  rotateFeedUrl,
  setFilter,
  type Feed,
} from '../publishing/feeds';

/**
 * Выгрузки на площадки и отбор товара в каждую.
 *
 * <p>Выгрузок на одну площадку бывает несколько: у живого клиента пять прайсов
 * на Дром, разложенных по ценовым диапазонам, — у каждого свой прайс-лист
 * в кабинете площадки и своя цена размещения. Различаются они только отбором.
 *
 * <p><b>Пустое поле — «без ограничения», а не «ничего».</b> Так и написано
 * рядом с полями: выгрузка, у которой стёрли цену, отдаёт весь склад. Обратное
 * прочтение стоит дорого — пустой прайс площадка примет молча, и объявления
 * пропадут вместе с накопленными просмотрами.
 *
 * <p>Ссылка меняется отдельной кнопкой с предупреждением: смена останавливает
 * выгрузку до тех пор, пока техспециалист площадки не пропишет новую в кабинете
 * клиента, а это не минуты.
 */
export function FeedsScreen() {
  const [feeds, setFeeds] = useState<Feed[] | null>(null);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    listFeeds()
      .then((found) => {
        setFeeds(found);
        setError('');
      })
      .catch((cause) => setError(describe(cause, 'Выгрузки не загрузились')));
  }, []);

  useEffect(load, [load]);

  useEffect(() => {
    void listWarehouses()
      .then(setWarehouses)
      // Молча: без списка складов отбор по цене всё ещё работает.
      .catch(() => setWarehouses([]));
  }, []);

  if (feeds === null) {
    return <p className="note">Загружаем выгрузки…</p>;
  }

  return (
    <section className="screen">
      <h2>Выгрузки</h2>
      <p className="note">
        Пустое поле отбора значит «без ограничения», а не «ничего»: такая
        выгрузка отдаёт весь склад.
      </p>

      {error && <p className="note note--error">{error}</p>}

      {feeds.length === 0 && (
        <p className="note">
          Выгрузок пока нет. Кабинет площадки заводит владелец.
        </p>
      )}

      <ul className="cards">
        {feeds.map((feed) => (
          <FeedCard
            key={feed.id}
            feed={feed}
            warehouses={warehouses}
            onChanged={load}
            onError={setError}
          />
        ))}
      </ul>
    </section>
  );
}

function FeedCard({
  feed,
  warehouses,
  onChanged,
  onError,
}: {
  feed: Feed;
  warehouses: Warehouse[];
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const [priceFrom, setPriceFrom] = useState(feed.priceFrom ?? '');
  const [priceTo, setPriceTo] = useState(feed.priceTo ?? '');
  const [conditions, setConditions] = useState<string[]>(feed.conditions);
  const [warehouseIds, setWarehouseIds] = useState<number[]>(feed.warehouseIds);
  const [link, setLink] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    try {
      await setFilter(feed.id, {
        priceFrom: priceFrom.trim() === '' ? null : priceFrom.trim(),
        priceTo: priceTo.trim() === '' ? null : priceTo.trim(),
        conditions,
        warehouseIds,
      });
      onChanged();
    } catch (cause) {
      onError(describe(cause, 'Отбор не сохранён'));
    } finally {
      setBusy(false);
    }
  }

  async function rotate() {
    setBusy(true);
    try {
      setLink((await rotateFeedUrl(feed.id)).path);
      onChanged();
    } catch (cause) {
      onError(describe(cause, 'Ссылка не сменилась'));
    } finally {
      setBusy(false);
    }
  }

  function toggle<T>(list: T[], value: T): T[] {
    return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
  }

  return (
    <li className="card">
      <div className="order-head">
        <strong>{feed.title}</strong>
        <span>{feed.marketplace === 'AVITO' ? 'Авито' : 'Дром'}</span>
      </div>
      <p className="muted">{filterSummary(feed)}</p>

      {feed.lastError && <p className="note note--error">{feed.lastError}</p>}

      <div className="filter-row">
        <label className="field">
          Цена от, ₽
          <input
            inputMode="decimal"
            value={priceFrom}
            placeholder="без границы"
            onChange={(e) => setPriceFrom(e.target.value)}
          />
        </label>
        <label className="field">
          Цена до, ₽
          <input
            inputMode="decimal"
            value={priceTo}
            placeholder="без границы"
            onChange={(e) => setPriceTo(e.target.value)}
          />
        </label>
      </div>

      <fieldset className="choices">
        <legend>Состояние — пусто значит любое</legend>
        {CONDITIONS.map((condition) => (
          <label key={condition.code}>
            <input
              type="checkbox"
              checked={conditions.includes(condition.code)}
              onChange={() => setConditions(toggle(conditions, condition.code))}
            />
            {condition.name}
          </label>
        ))}
      </fieldset>

      {warehouses.length > 1 && (
        <fieldset className="choices">
          <legend>Склады — пусто значит все</legend>
          {warehouses.map((warehouse) => (
            <label key={warehouse.id}>
              <input
                type="checkbox"
                checked={warehouseIds.includes(warehouse.id)}
                onChange={() => setWarehouseIds(toggle(warehouseIds, warehouse.id))}
              />
              {warehouse.name}
            </label>
          ))}
        </fieldset>
      )}

      <button type="button" disabled={busy} onClick={() => void save()}>
        Сохранить отбор
      </button>

      <details>
        <summary>Ссылка для площадки</summary>
        <p className="note">
          Смена ссылки останавливает выгрузку: новую в кабинет площадки
          прописывает её техспециалист руками, и до этого прайс забирать
          будет неоткуда.
        </p>
        {link !== null && <p className="muted">{link}</p>}
        <button type="button" className="button--ghost" disabled={busy}
                onClick={() => void rotate()}>
          {feed.hasFeed ? 'Сменить ссылку' : 'Выдать ссылку'}
        </button>
      </details>
    </li>
  );
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}
