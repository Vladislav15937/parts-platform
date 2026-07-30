import { useState } from 'react';
import { suggestNames } from '../reference/reference';
import type { Reference } from '../reference/reference';

/**
 * Приёмка детали и набор партии.
 *
 * <p><b>Партия, а не деталь за раз.</b> С одного донора снимают десятки позиций
 * подряд, и отправлять их по одной значит получить десятки незавершённых
 * операций при первом же обрыве. Приёмщик набирает список и отправляет целиком —
 * одна повторяемая операция вместо тридцати.
 *
 * <p><b>Заголовок карточки здесь не пишут.</b> Приёмщик выбирает вид детали,
 * машину и стороны, а заголовок собирает сервер: набранное руками название
 * у каждого своё, и по такому складу нельзя ни искать, ни выгружать.
 */
interface Props {
  reference: Reference;
  onSend(payload: Payload, title: string): void;
}

interface Item {
  key: string;
  rawName: string;
  price: string;
  cellId: number | null;
  sideLr: 'LEFT' | 'RIGHT' | null;
  sideFr: 'FRONT' | 'REAR' | null;
}

interface Payload {
  warehouseId: number;
  supplyId: number | null;
  donorId: number | null;
  items: {
    rawName: string;
    price: number;
    cellId: number | null;
    sideLr: string | null;
    sideFr: string | null;
    quantity: number;
  }[];
}

export function IntakeScreen({ reference, onSend }: Props) {
  const [warehouseId, setWarehouseId] = useState<number | null>(
    reference.warehouses[0]?.id ?? null,
  );
  const [supplyId, setSupplyId] = useState<number | null>(reference.supplies[0]?.id ?? null);
  const [donorId, setDonorId] = useState<number | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [draft, setDraft] = useState<Item>(emptyItem());

  const warehouse = reference.warehouses.find((w) => w.id === warehouseId);
  const suggestions = suggestNames(reference.partNames, draft.rawName);
  const canAdd = draft.rawName.trim().length > 0 && Number(draft.price) > 0;

  return (
    <section className="card">
      <h2>Приёмка</h2>

      <label>
        Склад
        <select
          value={warehouseId ?? ''}
          onChange={(e) => {
            setWarehouseId(Number(e.target.value));
            // Ячейка принадлежит складу: оставить её при смене склада значит
            // положить деталь в ячейку, которой на этом складе нет.
            setDraft((d) => ({ ...d, cellId: null }));
          }}
        >
          {reference.warehouses.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name}
            </option>
          ))}
        </select>
      </label>

      <label>
        Поставка
        <select
          value={supplyId ?? ''}
          onChange={(e) => setSupplyId(e.target.value === '' ? null : Number(e.target.value))}
        >
          <option value="">не указана</option>
          {reference.supplies.map((s) => (
            <option key={s.id} value={s.id}>
              {s.number} · {s.supplierName ?? 'без поставщика'}
            </option>
          ))}
        </select>
      </label>

      <label>
        Машина
        <select
          value={donorId ?? ''}
          onChange={(e) => setDonorId(e.target.value === '' ? null : Number(e.target.value))}
        >
          {/* Контрактные детали приходят контейнером напрямую, без машины. */}
          <option value="">без машины (контракт)</option>
          {reference.donors.map((d) => (
            <option key={d.id} value={d.id}>
              {d.publicCode} · {[d.brand, d.model, d.year].filter(Boolean).join(' ')}
            </option>
          ))}
        </select>
      </label>

      <hr />

      <label>
        Вид детали
        <input
          value={draft.rawName}
          onChange={(e) => setDraft({ ...draft, rawName: e.target.value })}
          placeholder="например, фара левая"
          autoCapitalize="none"
        />
      </label>

      {suggestions.length > 0 && (
        <ul className="suggestions">
          {suggestions.map((name) => (
            <li key={name.id}>
              <button
                type="button"
                className="button--ghost"
                onClick={() => setDraft({ ...draft, rawName: name.name })}
              >
                {name.name}
                {!name.matched && <span className="muted"> · не распознано</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="row">
        <label>
          Сторона
          <select
            value={draft.sideLr ?? ''}
            onChange={(e) =>
              setDraft({ ...draft, sideLr: (e.target.value || null) as Item['sideLr'] })
            }
          >
            <option value="">—</option>
            <option value="LEFT">левая</option>
            <option value="RIGHT">правая</option>
          </select>
        </label>

        <label>
          Перед/зад
          <select
            value={draft.sideFr ?? ''}
            onChange={(e) =>
              setDraft({ ...draft, sideFr: (e.target.value || null) as Item['sideFr'] })
            }
          >
            <option value="">—</option>
            <option value="FRONT">перед</option>
            <option value="REAR">зад</option>
          </select>
        </label>
      </div>

      <div className="row">
        <label>
          Цена
          <input
            type="number"
            inputMode="numeric"
            value={draft.price}
            onChange={(e) => setDraft({ ...draft, price: e.target.value })}
          />
        </label>

        <label>
          Ячейка
          <select
            value={draft.cellId ?? ''}
            onChange={(e) =>
              setDraft({ ...draft, cellId: e.target.value === '' ? null : Number(e.target.value) })
            }
          >
            <option value="">—</option>
            {(warehouse?.cells ?? []).map((cell) => (
              <option key={cell.id} value={cell.id}>
                {cell.code}
              </option>
            ))}
          </select>
        </label>
      </div>

      <button type="button" disabled={!canAdd} onClick={addItem}>
        Добавить в партию
      </button>

      {items.length > 0 && (
        <>
          <hr />
          <p className="note">В партии: {items.length}</p>
          <ul className="suggestions">
            {items.map((item) => (
              <li key={item.key}>
                {item.rawName} · {item.price} ₽
                <button
                  type="button"
                  className="button--ghost"
                  onClick={() => setItems(items.filter((i) => i.key !== item.key))}
                >
                  убрать
                </button>
              </li>
            ))}
          </ul>
          <button type="button" onClick={send}>
            Отправить партию
          </button>
        </>
      )}
    </section>
  );

  function addItem() {
    setItems([...items, { ...draft, key: crypto.randomUUID() }]);
    // Склад, поставка и машина остаются: с одного донора снимают подряд.
    setDraft(emptyItem());
  }

  function send() {
    if (warehouseId === null || items.length === 0) {
      return;
    }
    const payload: Payload = {
      warehouseId,
      supplyId,
      donorId,
      items: items.map((item) => ({
        rawName: item.rawName.trim(),
        price: Number(item.price),
        cellId: item.cellId,
        sideLr: item.sideLr,
        sideFr: item.sideFr,
        quantity: 1,
      })),
    };
    onSend(payload, `Партия из ${items.length} поз. · ${describeDonor()}`);
    setItems([]);
  }

  function describeDonor(): string {
    if (donorId === null) {
      return 'без машины';
    }
    const donor = reference.donors.find((d) => d.id === donorId);
    return donor === undefined ? 'машина' : donor.publicCode;
  }
}

function emptyItem(): Item {
  return { key: '', rawName: '', price: '', cellId: null, sideLr: null, sideFr: null };
}
