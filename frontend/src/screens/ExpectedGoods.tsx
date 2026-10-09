import { useEffect, useState } from 'react';

import { ApiError } from '../api/client';
import {
  addExpectedPart,
  donorsOfSupply,
  donorTitle,
  expectedPartsOf,
  setExpectedOn,
  type DonorEntry,
  type ExpectedPartEntry,
} from '../intake/donors';
import type { SupplyRef } from '../reference/reference';
import { useMounted } from '../ui/useMounted';

/**
 * Товар по ожидаемой поставке: завести, пока контейнер в пути (задача 0170).
 *
 * <p><b>Зачем.</b> Выгрузка умеет отправлять на площадку товар, которого ещё
 * нет на складе, — но данных для неё человеку взять было неоткуда: приёмка
 * всегда создавала новую позицию и всегда клала её на склад. Контейнеры из
 * Японии клиент заводит заранее и продаёт по ним до прихода; пока этого
 * экрана не было, предзаказы жили в телефонных разговорах.
 *
 * <p><b>Заводит владелец, из карточки поставки</b> (решение владельца
 * продукта 29.09.2026): предзаказ по контейнеру в пути — кабинетная работа,
 * её делают сидя и заранее; приёмка с телефона рассчитана на работу стоя.
 * Кнопка показывается только владельцу, и закрыт тот же адрес на сервере:
 * спрятанная кнопка при открытом адресе — не защита.
 *
 * <p>Минимум — вид детали, цена, количество (вариант «а» владельца):
 * заводится за полминуты, объявление выходит без снимков. Машина необязательна
 * (ответ владельца 9.10.2026: контрактные агрегаты возят партиями без машин);
 * если указана, берётся из тех, что пришли этой же партией.
 */
interface Props {
  supply: SupplyRef;
  online: boolean;
  /** Поставка изменилась (дата): справочник приёмки надо перечитать. */
  onChanged: () => void;
}

export function ExpectedGoods({ supply, online, onChanged }: Props) {
  const mounted = useMounted();
  const [parts, setParts] = useState<ExpectedPartEntry[] | null>(null);
  const [cars, setCars] = useState<DonorEntry[]>([]);
  const [loadFailure, setLoadFailure] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [rawName, setRawName] = useState('');
  const [donorId, setDonorId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [price, setPrice] = useState('');
  const [date, setDate] = useState(supply.expectedOn ?? '');

  useEffect(() => {
    setParts(null);
    setLoadFailure(null);
    void Promise.all([expectedPartsOf(supply.id), donorsOfSupply(supply.id)])
      .then(([found, donors]) => {
        if (!mounted.current) return;
        setParts(found);
        setCars(donors);
      })
      .catch((cause) => {
        if (mounted.current) {
          setLoadFailure(
            cause instanceof ApiError ? cause.message : 'Ожидаемые позиции не загрузились');
        }
      });
  }, [supply.id, mounted]);

  // Почему нельзя завести — одним выражением на кнопку и на подпись под ней:
  // разойдись они, серая кнопка снова начала бы молчать о причине.
  const obstacle = addObstacle(rawName, quantity, price);

  return (
    <div>
      <h4>Ожидаемый товар</h4>
      <p className="note">
        Позиции, которые ещё в пути: по ним можно отложить деталь под клиента
        до прихода. Когда контейнер приедет, приёмщик примет ту же позицию.
      </p>

      {/* Ожидаемая дата — то, что продавец называет покупателю. Срок резерва
          открытых предзаказов едет за ней, и сдвиг виден продавцам на сделках. */}
      <div className="row">
        <label className="field">
          Ожидаемая дата прихода
          <input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </label>
        <button
          type="button"
          className="button--ghost"
          disabled={!online || busy || date === '' || date === (supply.expectedOn ?? '')}
          onClick={() => void saveDate()}
        >
          Сохранить дату
        </button>
      </div>
      <p className="note">
        Сдвиг даты переносит срок резерва открытых предзаказов на столько же
        суток и оставляет продавцам пометку на сделках: клиенту названа прежняя.
      </p>

      {failure !== null && <p className="note note--error">{failure}</p>}
      {notice !== null && <p className="note">{notice}</p>}

      <h4>Завести позицию</h4>
      <>
          <div className="row">
            <label className="field">
              Вид детали
              <input
                value={rawName}
                onChange={(e) => setRawName(e.target.value)}
                placeholder="например, фара левая"
                autoCapitalize="none"
              />
            </label>
            <label className="field">
              Машина (необязательно)
              <select value={donorId} onChange={(e) => setDonorId(e.target.value)}>
                <option value="">— без машины —</option>
                {cars.map((car) => (
                  <option key={car.id} value={car.id}>{donorTitle(car)}</option>
                ))}
              </select>
            </label>
          </div>
          <div className="row">
            <label className="field">
              Количество
              <input
                type="number"
                inputMode="numeric"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
              />
            </label>
            <label className="field">
              Цена, ₽
              <input
                type="number"
                inputMode="numeric"
                value={price}
                onChange={(e) => setPrice(e.target.value)}
              />
            </label>
            <button type="button" disabled={!online || busy || obstacle !== null}
                    onClick={() => void add()}>
              {busy ? 'Заводим…' : 'Завести'}
            </button>
          </div>
          {obstacle !== null && <p className="note">{obstacle}</p>}
        </>

      {loadFailure !== null && <p className="note note--error">{loadFailure}</p>}
      {loadFailure === null && parts === null && <p className="note">Загружаем…</p>}
      {parts !== null && parts.length === 0 && (
        <p className="note">Ожидаемых позиций по этой партии ещё нет.</p>
      )}
      {parts !== null && parts.length > 0 && (
        <ul>
          {parts.map((part) => (
            <li key={part.id}>
              № {part.number} · {part.title}
              <span className="muted">
                {' '}· обещано {part.quantity} шт · принято {part.received}
                {part.preordered > 0 && ` · отложено под клиентов ${part.preordered}`}
                {' '}· {part.price.toLocaleString('ru-RU')} ₽
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );

  async function add(): Promise<void> {
    setBusy(true);
    setFailure(null);
    setNotice(null);
    try {
      const updated = await addExpectedPart(supply.id, {
        rawName: rawName.trim(),
        donorId: donorId === '' ? null : Number(donorId),
        quantity: Number(quantity),
        price: Number(price),
      });
      if (!mounted.current) return;
      setParts(updated);
      setRawName('');
      setPrice('');
      setQuantity('1');
    } catch (cause) {
      if (mounted.current) {
        setFailure(cause instanceof ApiError ? cause.message : 'Позицию завести не удалось');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function saveDate(): Promise<void> {
    setBusy(true);
    setFailure(null);
    setNotice(null);
    try {
      await setExpectedOn(supply.id, date);
      if (!mounted.current) return;
      setNotice('Ожидаемая дата сохранена');
      onChanged();
    } catch (cause) {
      if (mounted.current) {
        setFailure(cause instanceof ApiError ? cause.message : 'Дату сохранить не удалось');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function addObstacle(rawName: string, quantity: string, price: string): string | null {
  if (rawName.trim() === '') {
    return 'Впишите вид детали.';
  }
  if (!(Number(quantity) > 0)) {
    return 'Количество должно быть больше нуля.';
  }
  if (!(Number(price) > 0)) {
    return 'Впишите цену: с нулевой ценой объявление не уйдёт на площадку.';
  }
  return null;
}
