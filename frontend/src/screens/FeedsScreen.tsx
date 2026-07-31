import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import {
  CONDITIONS,
  countMatching,
  filterSummary,
  listFeeds,
  rotateFeedUrl,
  setFilter,
  type Feed,
  type FeedFilter,
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
  const [matching, setMatching] = useState<number | null>(null);

  // Списки видов и марок правятся не здесь: выбор из справочника
  // на четыре с половиной тысячи моделей — это отдельный экран, и до него
  // отбор задаётся через API. Показываем то, что задано, чтобы владелец
  // хотя бы видел, почему прайс короче склада.
  const current = (): FeedFilter => ({
    priceFrom: priceFrom.trim() === '' ? null : priceFrom.trim(),
    priceTo: priceTo.trim() === '' ? null : priceTo.trim(),
    conditions,
    warehouseIds,
    kindIds: feed.kindIds,
    kindsExcluded: feed.kindsExcluded,
    brandIds: feed.brandIds,
    brandsExcluded: feed.brandsExcluded,
  });

  async function save() {
    setBusy(true);
    try {
      await setFilter(feed.id, current());
      onChanged();
    } catch (cause) {
      onError(describe(cause, 'Отбор не сохранён'));
    } finally {
      setBusy(false);
    }
  }

  async function count() {
    setBusy(true);
    try {
      setMatching((await countMatching(current())).parts);
    } catch (cause) {
      onError(describe(cause, 'Посчитать не удалось'));
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

      <div className="filter-row">
        <button type="button" className="button--ghost" disabled={busy}
                onClick={() => void count()}>
          Посчитать
        </button>
        <button type="button" disabled={busy} onClick={() => void save()}>
          Сохранить отбор
        </button>
      </div>

      {matching !== null && (
        <p className={matching === 0 ? 'note note--error' : 'note'}>
          {matching === 0
            ? 'С таким отбором в прайс не попадёт ничего. Площадка примет пустой '
              + 'прайс молча, и объявления пропадут вместе с просмотрами'
            : `В прайс попадёт позиций: ${matching}`}
        </p>
      )}

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
