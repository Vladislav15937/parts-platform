import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import { createSet, listWheels, SEASONS, sizeOf } from '../inventory/wheels';
import type { SetRequest, Wheel } from '../inventory/wheels';

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
export function WheelsScreen({ canIntake }: { canIntake: boolean }) {
  const [wheels, setWheels] = useState<Wheel[] | null>(null);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(() => {
    listWheels()
      .then(setWheels)
      .catch((cause) => setError(describe(cause, 'Список не загрузился')));
  }, []);

  useEffect(load, [load]);

  useEffect(() => {
    void listWarehouses().then(setWarehouses).catch(() => setWarehouses([]));
  }, []);

  return (
    <section className="screen">
      <h2>Шины и диски</h2>

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

      {wheels === null ? (
        <p className="note">Загружаем…</p>
      ) : wheels.length === 0 ? (
        <p className="note">Колёс пока нет.</p>
      ) : (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Комплект</th>
                <th>Товар</th>
                <th>Размер</th>
                <th>Марка</th>
                <th className="num">Остаток</th>
                <th className="num">Цена</th>
              </tr>
            </thead>
            <tbody>
              {wheels.map((wheel) => (
                <tr key={wheel.id}>
                  {/* Прочерк, а не пусто: одиночная запаска — это не ошибка
                      заведения, а обычный товар. */}
                  <td>{wheel.setNo ?? '—'}</td>
                  <td>{wheel.kind === 'TYRE' ? 'Шина' : 'Диск'}</td>
                  <td>{sizeOf(wheel)}</td>
                  <td>
                    {[wheel.brand, wheel.model].filter((v) => v !== null).join(' ')}
                    {wheel.season !== null && (
                      <span className="muted">
                        {' · '}
                        {SEASONS.find((s) => s.code === wheel.season)?.name ?? wheel.season}
                      </span>
                    )}
                  </td>
                  <td className="num">{wheel.qty}</td>
                  <td className="num">
                    {wheel.price === null ? '—' : wheel.price.toLocaleString('ru-RU')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
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
  const [kind, setKind] = useState<'TYRE' | 'DISC'>('TYRE');
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
          <select value={kind} onChange={(e) => setKind(e.target.value as 'TYRE' | 'DISC')}>
            <option value="TYRE">Шина</option>
            <option value="DISC">Диск</option>
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

      {kind === 'TYRE' ? (
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
        </>
      ) : (
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

      <div className="filter-row">
        <Field name="brand" label="Марка" hint="Goodyear" set={set} field={field} />
        <Field name="model" label="Модель" hint="EfficientGrip" set={set} field={field} />
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
