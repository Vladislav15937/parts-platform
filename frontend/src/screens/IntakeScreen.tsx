import { useState } from 'react';
import { PhotoPicker } from '../photos/PhotoPicker';
import type { ResizedPhoto } from '../photos/resize';
import { donorTitle } from '../intake/donors';
import { suggestNames } from '../reference/reference';
import type { Reference } from '../reference/reference';
import { ScanOverlay } from '../scan/ScanOverlay';
import { resolveScan } from '../scan/codes';
import type { PendingPhoto } from '../outbox/outbox';

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
  onSend(payload: Payload, title: string, photos: PendingPhoto[]): void;
}

interface Item {
  key: string;
  rawName: string;
  price: string;
  cellId: number | null;
  sideLr: 'LEFT' | 'RIGHT' | null;
  sideFr: 'FRONT' | 'REAR' | null;
  photos: ResizedPhoto[];
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
  /**
   * Склад не подставляется — его выбирают.
   *
   * <p>Умолчанием стоял первый склад списка, а список отсортирован по имени:
   * у клиента с тремя складами первым оказывался «54 YARD», пустой, тогда
   * как весь товар лежит на «Ткацкой». Приёмщик поле не смотрит — оно
   * заполнено, — и партия уходит не туда. Ошибка тихая: деталь заведена,
   * остаток сходится, ничего не падает; находят её, когда деталь ищут
   * на полке и не могут найти.
   *
   * <p>Подставить «правильный» склад система не может: какой из них
   * правильный, знает только тот, кто стоит у стеллажа. Значит спрашиваем.
   */
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  /**
   * Поставка не подставляется — «не указана», пока приёмщик не выбрал.
   *
   * <p>Списком идут поставки от свежей к старой, и первая из них
   * подставлялась как умолчание. То есть каждая принятая деталь молча
   * приписывалась к последнему приехавшему контейнеру, к которому она может
   * не иметь никакого отношения: приёмщик поле не трогает, потому что оно
   * уже заполнено и выглядит осмысленно.
   *
   * <p>Правильное умолчание в списке уже стояло первым пунктом — «не
   * указана». Пусто здесь означает «неизвестно», и это честнее любой
   * догадки: поставку правят потом, а неверную не находят никогда.
   * Та же причина, по которой на экране машины поставка тоже пуста.
   */
  const [supplyId, setSupplyId] = useState<number | null>(null);
  const [donorId, setDonorId] = useState<number | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [draft, setDraft] = useState<Item>(emptyItem());
  const [photos, setPhotos] = useState<ResizedPhoto[]>([]);
  const [scanning, setScanning] = useState(false);
  const [scanNote, setScanNote] = useState<string | null>(null);

  const warehouse = reference.warehouses.find((w) => w.id === warehouseId);
  const suggestions = suggestNames(reference.partNames, draft.rawName);
  // Склад проверяется здесь, а не при отправке: остановить приёмщика надо
  // до того, как он наберёт двадцать позиций, а не после.
  const canAdd = warehouseId !== null
    && draft.rawName.trim().length > 0 && Number(draft.price) > 0;

  return (
    <section className="card">
      <h2>Приёмка</h2>

      <label>
        Склад
        <select
          value={warehouseId ?? ''}
          onChange={(e) => {
            setWarehouseId(e.target.value === '' ? null : Number(e.target.value));
            // Ячейка принадлежит складу: оставить её при смене склада значит
            // положить деталь в ячейку, которой на этом складе нет.
            setDraft((d) => ({ ...d, cellId: null }));
          }}
        >
          <option value="">— выберите склад —</option>
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
              {donorTitle(d)}
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

      <button type="button" className="button--ghost" onClick={() => setScanning(true)}>
        Сканировать
      </button>
      {scanNote !== null && <p className="note">{scanNote}</p>}

      {scanning && (
        <ScanOverlay
          hint="Код ячейки или VIN машины"
          onScan={applyScan}
          onClose={() => setScanning(false)}
        />
      )}

      <PhotoPicker photos={photos} onChange={setPhotos} />

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
                {item.photos.length > 0 && (
                  <span className="muted"> · {item.photos.length} фото</span>
                )}
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

  /**
   * Применяет прочитанный код.
   *
   * <p>Что сканируют, приёмщик не выбирает: этикетка ячейки и VIN различаются
   * сами, а лишний выбор режима — это лишнее действие с деталью в руках.
   *
   * <p>Ячейка с чужого склада меняет и склад: приёмщик стоит там, где стоит,
   * и этикетка в руках сообщает это точнее, чем выпадающий список, выбранный
   * час назад. Партию при этом сбрасывать нельзя — она уже набрана.
   */
  function applyScan(text: string) {
    setScanning(false);
    const match = resolveScan(reference, warehouseId, text);

    if (match.kind === 'cell') {
      if (match.warehouse.id !== warehouseId) {
        setWarehouseId(match.warehouse.id);
        setScanNote(`Ячейка ${match.cell.code} — склад «${match.warehouse.name}»`);
      } else {
        setScanNote(`Ячейка ${match.cell.code}`);
      }
      setDraft((d) => ({ ...d, cellId: match.cell.id }));
      return;
    }
    if (match.kind === 'donor') {
      setDonorId(match.donor.id);
      setScanNote(`Машина ${match.donor.code}`);
      return;
    }
    if (match.kind === 'ambiguous') {
      // Такой код есть на нескольких складах. Выбрать за приёмщика значит
      // однажды положить деталь на другой конец базы.
      setScanNote(`Код «${match.text}» есть на нескольких складах — выберите склад`);
      return;
    }
    setScanNote(`Код «${match.text}» не найден в справочниках`);
  }

  function addItem() {
    setItems([...items, { ...draft, key: crypto.randomUUID(), photos }]);
    // Склад, поставка и машина остаются: с одного донора снимают подряд.
    setDraft(emptyItem());
    setPhotos([]);
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
    // Снимки привязываются к позиции по номеру, а не по идентификатору:
    // деталей ещё нет, их создаст сервер. Порядок позиций он сохраняет.
    const pending: PendingPhoto[] = items.flatMap((item, itemIndex) =>
      item.photos.map((photo) => ({
        itemIndex,
        blob: photo.blob,
        contentType: photo.contentType,
        width: photo.width,
        height: photo.height,
      })),
    );

    onSend(payload, `Партия из ${items.length} поз. · ${describeDonor()}`, pending);
    setItems([]);
  }

  function describeDonor(): string {
    if (donorId === null) {
      return 'без машины';
    }
    const donor = reference.donors.find((d) => d.id === donorId);
    return donor === undefined ? 'машина' : donor.code;
  }
}

function emptyItem(): Item {
  return {
    key: '',
    rawName: '',
    price: '',
    cellId: null,
    sideLr: null,
    sideFr: null,
    photos: [],
  };
}
