import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import {
  createSet,
  EMPTY_WHEEL_QUERY,
  hasWheelFilters,
  listWheels,
  wheelExportUrl,
  wheelValues,
  WHEEL_KINDS,
  FILTER_EMPTY,
  FILTER_PRESENT,
  rowOfWheel,
  wheelFields,
  loadWheelVisible,
  saveWheelVisible,
  SEASONS,
  WHEEL_COLUMNS,
} from '../inventory/wheels';
import type { SetRequest, WheelPage, WheelQuery, WheelRow } from '../inventory/wheels';
import { PartCard } from './PartCard';

/**
 * Шины и диски: своя вкладка, как в кабинете Bazon.
 *
 * <p>Заводятся комплектом, а не по одному: на разборке снимают четыре колеса
 * разом, и повторять двенадцать полей четырежды никто не станет. Продаются
 * поштучно — запаску берут по одной, — поэтому в списке они и стоят
 * по одному, сгруппированные номером комплекта.
 *
 * <p>Размер показан отдельным столбцом и первым: покупатель называет
 * «195 65 15», а не модель шины.
 */
export function WheelsScreen({ canIntake, role }: { canIntake: boolean; role: string }) {
  const [page, setPage] = useState<WheelPage | null>(null);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [settings, setSettings] = useState(false);
  const [visible, setVisible] = useState<string[]>(loadWheelVisible);
  const [query, setQuery] = useState<WheelQuery>(EMPTY_WHEEL_QUERY);
  // Набранное в поле и отправленное на сервер — разные вещи: искать
  // на каждой букве значит слать запрос за запросом на весь склад.
  const [typed, setTyped] = useState('');
  // Какая колонка сейчас показывает список значений: открытых больше одной
  // быть не может — они перекрывают друг друга.
  const [picking, setPicking] = useState<string | null>(null);
  // Координаты заголовка: список значений рисуется вне таблицы, иначе его
  // обрезает контейнер с горизонтальной прокруткой — и от списка остаётся
  // белая полоска. Та же ловушка, что с накладкой снимков на витрине.
  const [pickAt, setPickAt] = useState<{ left: number; top: number } | null>(null);
  // Карточка та же, что у запчасти: цена, списание и перемещение написаны
  // на складе, а не на виде товара. Пока карточки не было, колесо нельзя
  // было ни поправить, ни списать, ни перевезти — витрина склада показывает
  // только запчасти.
  const [card, setCard] = useState<WheelRow | null>(null);

  const load = useCallback(() => {
    listWheels(query)
      .then(setPage)
      .catch((cause) => setError(describe(cause, 'Список не загрузился')));
  }, [query]);

  useEffect(load, [load]);

  /**
   * Смена сортировки: второе нажатие по той же колонке переворачивает
   * порядок, а по другой — начинает с убывания. Так в кабинете, и так
   * ожидает рука.
   */
  function sortBy(sort: string): void {
    setQuery(query.sort === sort ? { ...query, desc: !query.desc } : { ...query, sort, desc: true });
  }

  useEffect(() => {
    void listWarehouses().then(setWarehouses).catch(() => setWarehouses([]));
  }, []);

  function toggleColumn(key: string): void {
    const next = visible.includes(key)
      ? visible.filter((k) => k !== key)
      : [...visible, key];
    setVisible(next);
    saveWheelVisible(next);
  }

  // Порядок колонок задаёт список, а не порядок нажатий в настройке:
  // иначе включённая последней колонка уезжает в конец таблицы, и найти
  // её там можно только прокруткой.
  const columns = WHEEL_COLUMNS.filter(
    (c) => c.fixed === true || visible.includes(c.key),
  );

  return (
    <section className="screen screen--wide">
      <h2>
        Шины и диски
        {page !== null && <span className="muted"> {page.rows.length} товаров</span>}
      </h2>

      {error && <p className="note note--error">{error}</p>}
      {notice && <p className="note">{notice}</p>}

      {canIntake && warehouses.length > 0 && (
        <SetForm
          warehouses={warehouses}
          onCreated={(title, setNo) => {
            setNotice(
              setNo === null
                ? `Заведено: ${title}`
                : `Заведён комплект № ${setNo}: ${title}`,
            );
            load();
          }}
          onError={setError}
        />
      )}

      <div className="filter-row filter-row--search">
        <label className="field">
          <input
            value={typed}
            placeholder="номер товара или наименование"
            onChange={(e) => setTyped(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && setQuery({ ...query, q: typed })}
          />
        </label>
        <button type="button" onClick={() => setQuery({ ...query, q: typed })}>
          Найти
        </button>
      </div>

      <div className="filter-row">
        {/* Половина колонок у второго вида пуста, и «покажи только диски» —
            первое, что делает кладовщик, когда ищет комплект железа. */}
        <label className="field">
          Товар
          <select
            value={query.kind}
            onChange={(e) => setQuery({ ...query, kind: e.target.value as WheelQuery['kind'] })}
          >
            <option value="">все</option>
            <option value="TYRE">только шины</option>
            <option value="DISC">только диски</option>
            <option value="ASSEMBLY">только колёса в сборе</option>
          </select>
        </label>
        <label className="field field--check">
          <input
            type="checkbox"
            checked={query.missing}
            onChange={(e) => setQuery({ ...query, missing: e.target.checked })}
          />
          Показывать отсутствующие
        </label>
        {hasWheelFilters(query) && (
          <button
            type="button"
            className="button--ghost"
            onClick={() => { setQuery(EMPTY_WHEEL_QUERY); setTyped(''); }}
          >
            Сбросить отбор
          </button>
        )}
      </div>

      <div className="filter-row">
        <button type="button" className="button--ghost" onClick={() => setSettings(!settings)}>
          Настроить таблицу
        </button>
        {/* Ссылкой, а не кнопкой с запросом: файл качает браузер, показывая
            ход, и вкладка при этом жива. */}
        <a className="button--ghost" href={wheelExportUrl(query)} download>
          Скачать таблицу
        </a>
      </div>

      {settings && (
        <fieldset className="choices">
          <legend>Колонки</legend>
          {/* Постоянные в список не идут: отключить их нельзя, а флажок,
              который не работает, хуже отсутствующего. */}
          {WHEEL_COLUMNS.filter((c) => c.fixed !== true).map((column) => (
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
        <p className="note">
          {query.q === '' && query.kind === '' ? 'Колёс пока нет.' : 'Ничего не найдено.'}
        </p>
      ) : (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                {columns.map((column) => (
                  <th
                    key={column.key}
                    className={
                      [column.numeric ? 'num' : '',
                       query.columns[column.key] === undefined ? '' : 'th--filtered']
                        .filter((c) => c !== '').join(' ') || undefined
                    }
                  >
                    {/* Нажатие на заголовок открывает отбор, как в кабинете;
                        сортировка — на стрелке рядом. Одно нажатие на два
                        действия пришлось бы разводить по половинкам ячейки,
                        и промах менял бы не то. */}
                    <span
                      className="th__title"
                      onClick={(e) => {
                        if (column.filter === false) return;
                        const box = e.currentTarget.getBoundingClientRect();
                        setPickAt({ left: box.left, top: box.bottom });
                        setPicking(picking === column.key ? null : column.key);
                      }}
                      style={column.filter === false ? undefined : { cursor: 'pointer' }}
                    >
                      {column.title}
                    </span>
                    {column.sort !== undefined && (
                      <button
                        type="button"
                        className="th__sort"
                        onClick={() => sortBy(column.sort ?? '')}
                      >
                        {column.sort === query.sort ? (query.desc ? '↓' : '↑') : '⇅'}
                      </button>
                    )}
                    {query.columns[column.key] !== undefined && (
                      <div className="th__value">«{query.columns[column.key]}»</div>
                    )}
                  </th>
                ))}
                {page.warehouses.map((warehouse) => (
                  <th key={warehouse.id} className="num">{warehouse.name}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {page.rows.map((row) => (
                <tr key={row.wheel.id} className="row--clickable"
                    onClick={() => setCard(row)}>
                  {columns.map((column) => (
                    <td key={column.key} className={column.numeric ? 'num' : undefined}>
                      {column.image
                        ? column.image(row) !== null && (
                            <img
                              className="thumb"
                              src={column.image(row) ?? ''}
                              alt=""
                              loading="lazy"
                            />
                          )
                        : column.value(row.wheel)}
                    </td>
                  ))}
                  {page.warehouses.map((warehouse) => (
                    <td key={warehouse.id} className="num">
                      {/* Прочерк, а не ноль: «на этом складе её нет»
                          и «есть ноль штук» читаются одинаково, но первое
                          понятнее. */}
                      {row.wheel.stock[String(warehouse.id)] ?? '—'}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {picking !== null && pickAt !== null && (
        <ValuePicker
          column={picking}
          at={pickAt}
          chosen={query.columns[picking]}
          onPick={(value) => {
            const columns = { ...query.columns };
            if (value === null) delete columns[picking];
            else columns[picking] = value;
            setQuery({ ...query, columns });
            setPicking(null);
          }}
          onClose={() => setPicking(null)}
        />
      )}

      {card !== null && (
        <PartCard
          row={rowOfWheel(card)}
          warehouses={page?.warehouses ?? []}
          role={role}
          extraFields={wheelFields(card.wheel)}
          applicability={false}
          onClose={() => setCard(null)}
          onChanged={() => {
            setCard(null);
            load();
          }}
        />
      )}
    </section>
  );
}

/**
 * Список значений колонки — то, из чего выбирают отбор.
 *
 * <p>Значения тянутся по нажатию, а не вместе со страницей: колонок сорок
 * с лишним, и считать все списки на каждую страницу значит сорок запросов
 * там, где нужен один, и то не всегда.
 *
 * <p>Поиск внутри списка обязателен: моделей шин у живого клиента под сотню,
 * и глазами по ним не пробежать.
 */
function ValuePicker({ column, at, chosen, onPick, onClose }: {
  column: string;
  at: { left: number; top: number };
  chosen: string | undefined;
  onPick: (value: string | null) => void;
  onClose: () => void;
}) {
  const [values, setValues] = useState<string[] | null>(null);
  const [typed, setTyped] = useState('');

  useEffect(() => {
    let alive = true;
    void wheelValues(column)
      .then((found) => { if (alive) setValues(found); })
      .catch(() => { if (alive) setValues([]); });
    return () => { alive = false; };
  }, [column]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const shown = (values ?? []).filter(
    (value) => value.toLowerCase().includes(typed.trim().toLowerCase()),
  );

  return (
    <div className="value-picker" style={{ left: at.left, top: at.top }}>
      <input
        autoFocus
        value={typed}
        placeholder="поиск"
        onChange={(e) => setTyped(e.target.value)}
        onClick={(e) => e.stopPropagation()}
      />
      <ul>
        <li>
          <button type="button" className={chosen === undefined ? 'is-chosen' : ''}
                  onClick={() => onPick(null)}>
            — все —
          </button>
        </li>
        {values === null ? (
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
        <li>
          <button type="button" className={chosen === FILTER_PRESENT ? 'is-chosen' : ''}
                  onClick={() => onPick(FILTER_PRESENT)}>
            {FILTER_PRESENT}
          </button>
        </li>
        <li>
          <button type="button" className={chosen === FILTER_EMPTY ? 'is-chosen' : ''}
                  onClick={() => onPick(FILTER_EMPTY)}>
            {FILTER_EMPTY}
          </button>
        </li>
      </ul>
    </div>
  );
}

function SetForm({
  warehouses,
  onCreated,
  onError,
}: {
  warehouses: Warehouse[];
  onCreated: (title: string, setNo: number | null) => void;
  onError: (message: string) => void;
}) {
  const [kind, setKind] = useState<'TYRE' | 'DISC' | 'ASSEMBLY'>('TYRE');
  const [quantity, setQuantity] = useState('4');
  const [warehouseId, setWarehouseId] = useState(String(warehouses[0]?.id ?? ''));
  const [busy, setBusy] = useState(false);
  const [field, setField] = useState<Record<string, string>>({});

  function set(name: string, value: string) {
    setField({ ...field, [name]: value });
  }

  function value(name: string): string | null {
    const found = field[name];
    return found === undefined || found.trim() === '' ? null : found.trim();
  }

  async function submit() {
    setBusy(true);
    try {
      const body: SetRequest = {
        kind,
        warehouseId: Number(warehouseId),
        quantity: Number(quantity),
        diameter: value('diameter'),
        tyreWidth: value('tyreWidth'),
        tyreHeight: value('tyreHeight'),
        season: value('season'),
        wearMm: value('wearMm'),
        madeYear: value('madeYear'),
        discType: value('discType'),
        discWidth: value('discWidth'),
        offsetMm: value('offsetMm'),
        boltPattern: value('boltPattern'),
        hubBore: value('hubBore'),
        brand: value('brand'),
        model: value('model'),
        discBrand: value('discBrand'),
        discModel: value('discModel'),
        tyreType: value('tyreType'),
        construction: value('construction'),
        markingType: value('markingType'),
        treadType: value('treadType'),
        // Флажок: снятый — это «нет», а не «не знаю». RunFlat видно
        // по надписи на боковине, и приёмщик на неё смотрит.
        runFlat: kind === 'TYRE' ? field.runFlat === 'on' : null,
        lightTruck: kind === 'TYRE' ? field.lightTruck === 'on' : null,
        speedIndex: value('speedIndex'),
        loadIndex: value('loadIndex'),
        price: value('price'),
      };
      const created = await createSet(body);
      setField({});
      onCreated(created.title, created.setNo);
    } catch (cause) {
      onError(describe(cause, 'Комплект не заведён'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <div className="filter-row">
        <label className="field">
          Товар
          <select
            value={kind}
            onChange={(e) => setKind(e.target.value as 'TYRE' | 'DISC' | 'ASSEMBLY')}
          >
            {WHEEL_KINDS.map((k) => (
              <option key={k.code} value={k.code}>{k.name}</option>
            ))}
          </select>
        </label>
        <label className="field">
          Сколько
          <input
            inputMode="numeric"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
          />
        </label>
        <label className="field">
          Склад
          <select value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
            {warehouses.map((warehouse) => (
              <option key={warehouse.id} value={warehouse.id}>
                {warehouse.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {kind !== 'DISC' && (
        <>
          <div className="filter-row">
            <Field name="tyreWidth" label="Ширина" hint="195" set={set} field={field} />
            <Field name="tyreHeight" label="Профиль" hint="65" set={set} field={field} />
            <Field name="diameter" label="Диаметр" hint="15" set={set} field={field} />
          </div>
          <div className="filter-row">
            <label className="field">
              Сезон
              <select
                value={field.season ?? ''}
                onChange={(e) => set('season', e.target.value)}
              >
                <option value="">не указан</option>
                {SEASONS.map((season) => (
                  <option key={season.code} value={season.code}>
                    {season.name}
                  </option>
                ))}
              </select>
            </label>
            {/* Миллиметры остатка протектора, а не проценты: покупатель
                мерил глубиномером, а «осталось 25 %» он не пересчитает. */}
            <Field name="wearMm" label="Протектор, мм" hint="5" set={set} field={field} />
            <Field name="madeYear" label="Год" hint="2022" set={set} field={field} />
          </div>
          <div className="filter-row">
            <Field name="tyreType" label="Тип шины" hint="Легковая" set={set} field={field} />
            <Field name="construction" label="Конструкция" hint="R" set={set} field={field} />
            <label className="field">
              Маркировка
              <select
                value={field.markingType ?? ''}
                onChange={(e) => set('markingType', e.target.value)}
              >
                <option value="">не указана</option>
                <option value="METRIC">метрическая</option>
                <option value="INCH">дюймовая</option>
                <option value="FLOTATION">флотационная</option>
              </select>
            </label>
          </div>
          <div className="filter-row">
            <label className="field">
              Протектор
              <select
                value={field.treadType ?? ''}
                onChange={(e) => set('treadType', e.target.value)}
              >
                <option value="">не указан</option>
                <option value="STANDARD">стандартный</option>
                <option value="ASYMMETRIC">асимметричный</option>
                <option value="DIRECTIONAL">направленный</option>
              </select>
            </label>
            {/* Буква и число с боковины: по ним подбирают шину
                по документам на машину. */}
            <Field name="speedIndex" label="Индекс скорости" hint="H" set={set} field={field} />
            <Field name="loadIndex" label="Индекс нагрузки" hint="91" set={set} field={field} />
          </div>
          <div className="filter-row">
            <label className="field field--check">
              <input
                type="checkbox"
                checked={field.runFlat === 'on'}
                onChange={(e) => set('runFlat', e.target.checked ? 'on' : '')}
              />
              RunFlat
            </label>
            <label className="field field--check">
              <input
                type="checkbox"
                checked={field.lightTruck === 'on'}
                onChange={(e) => set('lightTruck', e.target.checked ? 'on' : '')}
              />
              Легкогрузовая (LT)
            </label>
          </div>
        </>
      )}
      {kind !== 'TYRE' && (
        <>
          <div className="filter-row">
            <Field name="discType" label="Тип" hint="Литой" set={set} field={field} />
            <Field name="discWidth" label="Ширина" hint="6.0" set={set} field={field} />
            <Field name="diameter" label="Диаметр" hint="15" set={set} field={field} />
          </div>
          <div className="filter-row">
            <Field name="boltPattern" label="Сверловка" hint="5x100" set={set} field={field} />
            <Field name="offsetMm" label="Вылет ET" hint="45" set={set} field={field} />
            <Field name="hubBore" label="Диаметр ЦО" hint="54.1" set={set} field={field} />
          </div>
        </>
      )}

      {/* У сборки производители разные — шина Dunlop на диске Mitsubishi, —
          и одним полем «марка» их не записать. */}
      {kind !== 'DISC' && (
        <div className="filter-row">
          <Field name="brand" label={kind === 'ASSEMBLY' ? 'Марка шины' : 'Марка'}
                 hint="Goodyear" set={set} field={field} />
          <Field name="model" label={kind === 'ASSEMBLY' ? 'Модель шины' : 'Модель'}
                 hint="EfficientGrip" set={set} field={field} />
        </div>
      )}
      {kind !== 'TYRE' && (
        <div className="filter-row">
          <Field name="discBrand" label={kind === 'ASSEMBLY' ? 'Марка диска' : 'Марка'}
                 hint="Enkei" set={set} field={field} />
          <Field name="discModel" label={kind === 'ASSEMBLY' ? 'Модель диска' : 'Модель'}
                 hint="RPF1" set={set} field={field} />
        </div>
      )}
      <div className="filter-row">
        <Field name="price" label="Цена, ₽" hint="3500" set={set} field={field} />
      </div>

      <button type="button" disabled={busy} onClick={() => void submit()}>
        Завести
      </button>
    </div>
  );
}

function Field({
  name,
  label,
  hint,
  set,
  field,
}: {
  name: string;
  label: string;
  hint: string;
  set: (name: string, value: string) => void;
  field: Record<string, string>;
}) {
  return (
    <label className="field">
      {label}
      <input
        value={field[name] ?? ''}
        placeholder={hint}
        onChange={(e) => set(name, e.target.value)}
      />
    </label>
  );
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}
