import { Fragment, useState } from 'react';

import { ApiError } from '../api/client';
import {
  donorsOfSupply,
  donorTitle,
  markSupplyArrived,
  statusTitle,
  type DonorEntry,
} from '../intake/donors';
import type { SupplyRef } from '../reference/reference';
import { plural } from '../ui/plural';

/**
 * Поставки: что заведено, что уже приехало и какие машины пришли партией.
 *
 * <p><b>Зачем.</b> Завести поставку экран умел, а дальше она была тупиком:
 * отметить приход было нечем — `POST /api/intake/supplies/{id}/arrived`
 * не звала ни одна строка фронтенда, — а «какие машины пришли этой партией»
 * (`GET /api/intake/supplies/{id}/donors`) нельзя было спросить вовсе.
 * При том что по партии и разбирают, что из контейнера ещё не продано:
 * ради этого вопроса поставки и заведены. Оба пути написаны с самого начала
 * и покрыты тестами — то есть выглядели работающей возможностью.
 *
 * <p>Здесь же, где поставку заводят и выбирают: контейнер и машины приходят
 * вместе, и занимается ими один человек за один заход.
 */
interface Props {
  supplies: SupplyRef[];
  online: boolean;
  /** Поставка изменилась: справочник приёмки надо перечитать. */
  onChanged: () => void;
}

/**
 * Виды и состояния — словами.
 *
 * <p>«CONTAINER» и «EXPECTED» на экране это внутреннее представление, ровно
 * то, чего избегает выгрузка витрины, где стороны пишутся «Задн.» и «Лев.».
 */
const KINDS: Record<string, string> = {
  // Слово в слово с SupplyKinds на сервере: подпись «Поставка №5» собирают
  // и там (витрина, колёса, история, карточка машины), и здесь. Стережёт
  // WordingConsistencyTest — до него сервер звал OTHER «Поставкой»,
  // а форма заведения «Прочим».
  CONTAINER: 'Контейнер',
  PURCHASE: 'Закупка',
  OTHER: 'Поставка',
};

const STATUSES: Record<string, string> = {
  EXPECTED: 'ожидается',
  IN_TRANSIT: 'в пути',
  ARRIVED: 'приехала',
  CLOSED: 'закрыта',
};

export function SupplyList({ supplies, online, onChanged }: Props) {
  const [openId, setOpenId] = useState<number | null>(null);
  const [cars, setCars] = useState<DonorEntry[] | null>(null);
  // Три состояния, а не два: «грузим», «не смогли» и «пусто» — разные вещи,
  // и экран, который их путает, обещает пустую партию при истёкшей сессии.
  const [loadingCars, setLoadingCars] = useState(false);
  const [carsFailure, setCarsFailure] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [dates, setDates] = useState<Record<number, string>>({});
  const [failure, setFailure] = useState<string | null>(null);

  if (supplies.length === 0) {
    return (
      <>
        <h3>Поставки</h3>
        <p className="note">Поставок пока нет — заведите первую выше.</p>
      </>
    );
  }

  return (
    <>
      <h3>Поставки</h3>
      {failure !== null && <p className="note note--error">{failure}</p>}
      {/* Та же обёртка, что у таблицы машин: поставки живут на том же экране,
          и без неё вбок уезжает он целиком — вместе с уже обёрнутой таблицей. */}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Партия</th>
              <th>Поставщик</th>
              <th>Состояние</th>
              <th>Приход</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {supplies.map((supply) => (
              <Fragment key={supply.id}>
                <tr>
                  <td>{`${KINDS[supply.kind] ?? supply.kind} №${supply.number}`}</td>
                  <td>{supply.supplierName ?? '—'}</td>
                  <td>{STATUSES[supply.status] ?? supply.status}</td>
                  <td>
                    {supply.arrivedOn !== null ? (
                      day(supply.arrivedOn)
                    ) : (
                      // Дата стоит в поле до нажатия, а не подставляется молча
                      // на сервере: контейнер отмечают и задним числом, а
                      // невидимое значение читается как факт.
                      <input
                        type="date"
                        aria-label={`Дата прихода партии №${supply.number}`}
                        value={dates[supply.id] ?? today()}
                        onChange={(e) =>
                          setDates({ ...dates, [supply.id]: e.target.value })}
                      />
                    )}
                  </td>
                  <td className="filter-row">
                    {supply.arrivedOn === null && (
                      <button
                        type="button"
                        className="button--ghost"
                        disabled={!online || busyId === supply.id}
                        onClick={() => void arrive(supply)}
                      >
                        {busyId === supply.id ? 'Отмечаем…' : 'Отметить приход'}
                      </button>
                    )}
                    <button
                      type="button"
                      className="button--ghost"
                      onClick={() => void toggle(supply.id)}
                    >
                      {openId === supply.id ? 'Свернуть' : 'Машины'}
                    </button>
                  </td>
                </tr>
                {/* Раскрывается под своей же строкой, как затраты по машине:
                    результат нажатия обязан быть виден там, где нажали. */}
                {openId === supply.id && (
                  <tr>
                    <td colSpan={5}>{content()}</td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );

  function content() {
    if (loadingCars) {
      return <p className="note">Загружаем…</p>;
    }
    if (carsFailure !== null) {
      return <p className="note note--error">{carsFailure}</p>;
    }
    if (cars === null || cars.length === 0) {
      return <p className="note">Машин этой партией не заводили.</p>;
    }
    return (
      <>
        <p className="note">
          {cars.length} {plural(cars.length, 'машина', 'машины', 'машин')} этой партией
        </p>
        <ul>
          {cars.map((car) => (
            <li key={car.id}>
              {donorTitle(car)} · {statusTitle(car.status)}
              {car.location !== null && car.location !== '' && ` · ${car.location}`}
            </li>
          ))}
        </ul>
      </>
    );
  }

  async function toggle(id: number): Promise<void> {
    if (openId === id) {
      setOpenId(null);
      return;
    }
    setOpenId(id);
    setCars(null);
    setCarsFailure(null);
    setLoadingCars(true);
    try {
      setCars(await donorsOfSupply(id));
    } catch (cause) {
      setCarsFailure(
        cause instanceof ApiError ? cause.message : 'Машины партии не загрузились');
    } finally {
      setLoadingCars(false);
    }
  }

  async function arrive(supply: SupplyRef): Promise<void> {
    setBusyId(supply.id);
    setFailure(null);
    try {
      await markSupplyArrived(supply.id, dates[supply.id] ?? today());
      // Справочник перечитывается: без этого отметка не видна до перезагрузки
      // экрана, и приёмщик жмёт кнопку второй раз.
      onChanged();
    } catch (cause) {
      setFailure(cause instanceof ApiError ? cause.message : 'Отметить приход не удалось');
    } finally {
      setBusyId(null);
    }
  }
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Дата как везде на экранах: «30.08.2026», а не «2026-08-30».
 *
 * <p>Разбирается строкой, а не через {@code new Date(...)}: у даты без времени
 * тот разбирает её как полночь UTC, и западнее Гринвича `toLocaleDateString`
 * показывает вчерашний день. Партия, приехавшая тридцатого, у клиента
 * с отрицательным смещением значилась бы приехавшей двадцать девятого.
 */
function day(iso: string): string {
  const [year, month, date] = iso.split('-');
  return date === undefined ? iso : `${date}.${month}.${year}`;
}
