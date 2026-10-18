import { useState } from 'react';
import { CONDITIONS, CONDITION_UNSET } from '../inventory/catalog';
import { ConditionWord } from '../inventory/ConditionWord';
import { LongNumberNote } from '../inventory/LongNumberNote';
import { OEM_FIELD_NOTE, numberTooLong } from '../inventory/oemLength';
import { PhotoPicker } from '../photos/PhotoPicker';
import type { ResizedPhoto } from '../photos/resize';
import { donorTitle } from '../intake/donors';
import { suggestNames } from '../reference/reference';
import type { ExpectedPartRef, Reference } from '../reference/reference';
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
  /** Принимается заведённая заранее позиция, а не новая карточка. */
  partId: number | null;
  /**
   * Состояние кодом. У заведённой заранее позиции — выбранное на приёмке
   * (задача 0326) и уходит на сервер: пусто там значит «не трогать».
   * У новой карточки пусто всегда — сервер ставит своё умолчание.
   */
  condition: string | null;
  quantity: number;
  rawName: string;
  price: string;
  cellId: number | null;
  sideLr: 'LEFT' | 'RIGHT' | null;
  sideFr: 'FRONT' | 'REAR' | null;
  /** Номер производителя (задача 0302): вписывают сразу, не открывая потом карточку. */
  oemNumber: string;
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
    /** Заведённая заранее позиция; пусто — обычная приёмка новой карточки. */
    partId: number | null;
    /** Пусто — номера нет; сервер приводит его тем же правилом, что поиск. */
    oemNumber: string | null;
    /** Состояние кодом; пусто — у новой умолчание сервера, у заведённой «не трогать». */
    condition: string | null;
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
  /**
   * Подсказку выбрали — список закрыт до следующего набора.
   *
   * <p>Список считается от текста поля, поэтому выбранное написание
   * оставалось в нём и после нажатия: приёмщик не видел, что выбор
   * засчитан, а список продолжал стоять между полем и ценой. На телефоне,
   * где эта форма и живёт, шесть подсказок отжимают «Цена» и «Добавить
   * в партию» за край экрана — и так на каждой детали из тридцати.
   */
  const [pickedName, setPickedName] = useState(false);

  // Сколько пришло по ожидаемой позиции — по умолчанию всё, что оставалось.
  const [arrived, setArrived] = useState<Record<number, string>>({});
  /**
   * Каким приехало (задача 0326, решение владельца 18.10.2026 «6 - в»):
   * выбор стоит на заведённом, и тому, у кого приехало то же, поле не
   * прибавляет ни одного нажатия. Пусто — «Не указано».
   */
  const [arrivedAs, setArrivedAs] = useState<Record<number, string>>({});

  const warehouse = reference.warehouses.find((w) => w.id === warehouseId);
  // Ожидаемое по выбранной поставке: принимать его надо этой же позицией.
  const expectedHere = supplyId === null ? [] : (reference.expectedParts ?? [])
    .filter((part) => part.supplyId === supplyId);
  const suggestions = pickedName ? [] : suggestNames(reference.partNames, draft.rawName);
  // Склад проверяется здесь, а не при отправке: остановить приёмщика надо
  // до того, как он наберёт двадцать позиций, а не после.
  // Номер, который нельзя сохранить (задача 0312), останавливает здесь же:
  // ушедший в очередь, он вернулся бы отказом сервера уже из «требует внимания».
  // Причина стоит под полем (`LongNumberNote`).
  const canAdd = warehouseId !== null
    && draft.rawName.trim().length > 0 && Number(draft.price) > 0
    && numberTooLong('oem', draft.oemNumber) === null;

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

      {/* Заведённое владельцем заранее принимается ЭТОЙ ЖЕ позицией. Завести
          такую деталь заново руками — вторая карточка, и предзаказы покупателей
          остались бы висеть на первой. */}
      {expectedHere.length > 0 && (
        <>
          <hr />
          <p className="note">
            Ожидалось по этой поставке — принимайте эту же позицию, а не
            заводите новую:
          </p>
          <ul className="suggestions">
            {expectedHere.map((part) => {
              const taken = items.some((item) => item.partId === part.id);
              const quantity = Number(arrived[part.id] ?? part.remaining);
              // Кэш телефона до 0318 поля не несёт — это «Не указано».
              const registered = part.condition ?? null;
              const condition = arrivedAs[part.id] ?? registered ?? '';
              return (
                <li key={part.id}>
                  {part.title}
                  {/* Состояние (задача 0318): «Фара…» новая и б/у по одной
                      поставке — две разные ожидаемые позиции. */}
                  <ConditionWord code={part.condition} />
                  <span className="muted"> · ждём {part.remaining} шт</span>
                  <label>
                    Пришло, шт
                    <input
                      type="number"
                      inputMode="numeric"
                      value={arrived[part.id] ?? String(part.remaining)}
                      onChange={(e) => setArrived({ ...arrived, [part.id]: e.target.value })}
                    />
                  </label>
                  <label>
                    Состояние
                    <select
                      value={condition}
                      disabled={taken}
                      onChange={(e) => setArrivedAs({ ...arrivedAs, [part.id]: e.target.value })}
                    >
                      {CONDITIONS.map((c) => (
                        <option key={c.key} value={c.key}>{c.title}</option>
                      ))}
                      {/* Только у заведённой без состояния: снять состояние
                          приёмкой нельзя, как и правкой карточки (0322). */}
                      {registered === null && <option value="">{CONDITION_UNSET}</option>}
                    </select>
                  </label>
                  {condition === '' && (
                    <p className="note">Без состояния позиция не уйдёт на Дром.</p>
                  )}
                  <button
                    type="button"
                    className="button--ghost"
                    disabled={warehouseId === null || taken || !(quantity > 0)}
                    onClick={() => acceptExpected(part, quantity, condition)}
                  >
                    {taken ? 'в партии' : 'Принять'}
                  </button>
                </li>
              );
            })}
          </ul>
          {warehouseId === null && (
            <p className="note">Выберите склад — принимать эту позицию будем на него.</p>
          )}
        </>
      )}

      <hr />

      <label>
        Вид детали
        <input
          value={draft.rawName}
          onChange={(e) => {
            setDraft({ ...draft, rawName: e.target.value });
            // Набирают дальше — значит выбранное больше не подходит.
            setPickedName(false);
          }}
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
                onClick={() => {
                  setDraft({ ...draft, rawName: name.name });
                  setPickedName(true);
                }}
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

      <label>
        Номер производителя
        <input
          value={draft.oemNumber}
          autoCapitalize="characters"
          onChange={(e) => setDraft({ ...draft, oemNumber: e.target.value })}
        />
      </label>
      {/* У детали один номер производителя (задача 0323): лишние уходят в кросс-номера. */}
      <span className="muted">{OEM_FIELD_NOTE}</span>
      {/* Необычно длинный номер — предупреждение, а не запрет (задача 0310). */}
      <LongNumberNote field="oem" value={draft.oemNumber} />

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
                {item.rawName}
                <ConditionWord code={item.condition} />
                {' '}· {item.price} ₽
                {item.oemNumber.trim() !== '' && (
                  <span className="muted"> · {item.oemNumber.trim()}</span>
                )}
                {item.partId !== null && (
                  <span className="muted"> · заведена заранее, {item.quantity} шт</span>
                )}
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

  /**
   * Принять заведённую заранее позицию в партию.
   *
   * <p>Ячейка — та, что выбрана в форме ниже: на экране один выбор ячейки, и
   * второй такой же для ожидаемой позиции был бы вторым способом сделать то же.
   */
  function acceptExpected(part: ExpectedPartRef, quantity: number, condition: string) {
    setItems([...items, {
      ...emptyItem(),
      key: crypto.randomUUID(),
      partId: part.id,
      condition: condition === '' ? null : condition,
      quantity,
      rawName: part.title,
      price: String(part.price),
      cellId: draft.cellId,
    }]);
  }

  function addItem() {
    setItems([...items, { ...draft, key: crypto.randomUUID(), photos }]);
    // Склад, поставка и машина остаются: с одного донора снимают подряд.
    setDraft(emptyItem());
    // Позиция ушла в партию — следующую набирают с чистого листа,
    // и подсказки для неё снова нужны.
    setPickedName(false);
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
        quantity: item.quantity,
        partId: item.partId,
        oemNumber: item.oemNumber.trim() === '' ? null : item.oemNumber.trim(),
        condition: item.condition,
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
    partId: null,
    condition: null,
    quantity: 1,
    rawName: '',
    price: '',
    cellId: null,
    sideLr: null,
    sideFr: null,
    oemNumber: '',
    photos: [],
  };
}
