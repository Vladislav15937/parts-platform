import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { movePartsBulk, type CatalogRow, type Warehouse } from '../inventory/catalog';
import { listCells, type Cell } from '../organization/warehouses';
import { positions } from '../ui/plural';

/** Склады, на которых у позиции лежит остаток. */
function warehousesWithStock(row: CatalogRow): number[] {
  return Object.entries(row.stock)
    .filter(([, qty]) => Number(qty) > 0)
    .map(([id]) => Number(id));
}

/**
 * Склад, общий у всех отмеченных позиций.
 *
 * <p>Пересечение складов, где у каждой отмеченной позиции лежит остаток.
 * Если оно из одного склада — это и есть «Откуда», подставленное само.
 * Если позиции лежат на разных складах, пересечение пусто или больше
 * одного — общего склада нет, и владелец выбирает сам.
 */
function commonWarehouseId(rows: CatalogRow[]): number | null {
  let common: number[] | null = null;
  for (const row of rows) {
    const rowWarehouses = warehousesWithStock(row);
    if (common === null) {
      common = rowWarehouses;
    } else {
      const rowSet = new Set(rowWarehouses);
      common = common.filter((id) => rowSet.has(id));
    }
    if (common.length === 0) {
      return null;
    }
  }
  return common !== null && common.length === 1 ? common[0] ?? null : null;
}

/**
 * Перевозка пачкой из витрины склада.
 *
 * <p><b>Количество не спрашивается.</b> Пачкой везут весь остаток позиции
 * на складе-источнике; частичную перевозку («две из пяти») по-прежнему
 * делают из карточки.
 *
 * <p><b>Отмеченные позиции с разных складов перевезти нельзя.</b> Если
 * общего склада нет, «Откуда» остаётся пустым, и число в кнопке — только
 * то, что лежит на выбранном складе: остальные отмеченные останутся
 * на месте, форма их не трогает и не жалуется на них.
 */
export function BulkMoveForm({ rows, warehouses, onDone, onCancel }: {
  rows: CatalogRow[];
  warehouses: Warehouse[];
  onDone: (notice: string) => void;
  onCancel: () => void;
}) {
  const [fromWarehouseId, setFromWarehouseId] = useState<number | null>(
    () => commonWarehouseId(rows),
  );
  // Общий склад найден один — поле подставлено и не меняется: другого
  // источника у этой пачки быть не может.
  const [locked] = useState(fromWarehouseId !== null);
  const [toWarehouseId, setToWarehouseId] = useState<number | null>(null);
  const [cells, setCells] = useState<Cell[]>([]);
  const [toCellId, setToCellId] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (toWarehouseId === null) {
      setCells([]);
      setToCellId(null);
      return;
    }
    setToCellId(null);
    void listCells(toWarehouseId).then(setCells).catch(() => setCells([]));
  }, [toWarehouseId]);

  const movable = fromWarehouseId === null
    ? []
    : rows.filter((row) => Number(row.stock[String(fromWarehouseId)] ?? 0) > 0);

  async function submit(): Promise<void> {
    if (fromWarehouseId === null || toWarehouseId === null || movable.length === 0) {
      return;
    }
    setError('');
    setSaving(true);
    try {
      const items = movable.map((row) => ({
        partId: row.id,
        quantity: Number(row.stock[String(fromWarehouseId)]),
        toCellId,
      }));
      const outcome = await movePartsBulk(
        fromWarehouseId, toWarehouseId, items, note.trim() === '' ? null : note.trim());
      const notice = outcome.notMoved.length === 0
        ? `Перевезено: ${outcome.items}.`
        : `Перевезено: ${outcome.items}. Не поехали: ${outcome.notMoved.length} — `
          + `обещаны покупателю (${outcome.notMoved.map((s) => s.publicCode).join(', ')}).`;
      onDone(notice);
    } catch (cause) {
      setError(cause instanceof ApiError && cause.message ? cause.message : 'Перевезти не вышло');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card-edit">
      <h4>Перевозка пачкой</h4>

      <label className="field">
        Откуда
        <select
          value={fromWarehouseId ?? ''}
          disabled={locked}
          onChange={(e) =>
            setFromWarehouseId(e.target.value === '' ? null : Number(e.target.value))}
        >
          <option value="">— выберите склад —</option>
          {warehouses.map((w) => (
            <option key={w.id} value={w.id}>{w.name}</option>
          ))}
        </select>
      </label>
      {!locked && (
        <p className="note">
          Перевозятся только позиции с выбранного склада: остальные отмеченные
          останутся на месте.
        </p>
      )}

      <label className="field">
        Куда
        <select
          value={toWarehouseId ?? ''}
          onChange={(e) =>
            setToWarehouseId(e.target.value === '' ? null : Number(e.target.value))}
        >
          <option value="">— выберите склад —</option>
          {warehouses.filter((w) => w.id !== fromWarehouseId).map((w) => (
            <option key={w.id} value={w.id}>{w.name}</option>
          ))}
        </select>
      </label>

      <label className="field">
        Ячейка на новом складе
        <select
          value={toCellId ?? ''}
          disabled={toWarehouseId === null}
          onChange={(e) => setToCellId(e.target.value === '' ? null : Number(e.target.value))}
        >
          <option value="">без адреса</option>
          {cells.map((cell) => (
            <option key={cell.id} value={cell.id}>{cell.code}</option>
          ))}
        </select>
      </label>

      <label className="field">
        Примечание
        <input value={note} onChange={(e) => setNote(e.target.value)} />
      </label>

      {error !== '' && <p className="note note--error">{error}</p>}

      <div className="filter-row">
        <button
          type="button"
          disabled={saving || fromWarehouseId === null || toWarehouseId === null
            || movable.length === 0}
          onClick={() => void submit()}
        >
          {saving ? 'Перевозим…' : `Перевезти ${positions(movable.length)}`}
        </button>
        <button type="button" className="button--ghost" onClick={onCancel}>
          Отменить
        </button>
      </div>
    </div>
  );
}
