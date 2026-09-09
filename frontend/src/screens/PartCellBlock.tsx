import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { useMounted } from '../ui/useMounted';
import { changePartCell, loadPartCells, type PartCell, type Warehouse } from '../inventory/catalog';
import { listCells, type Cell } from '../organization/warehouses';

/**
 * Где деталь лежит — и как переставить её на другую полку.
 *
 * <p><b>Зачем.</b> Кладовщик открывал карточку, чтобы узнать, куда идти,
 * и узнавал только склад и остаток: код ячейки не показывался нигде, кроме
 * ленты правок, — то есть узнать адрес можно было, только если деталь
 * когда-то переставляли. Переставить при этом было нечем вовсе: «Перевезти»
 * требует другого склада-приёмника, а у клиента с одним складом такого нет.
 * Ячейка, поставленная при приёмке, оставалась на карточке навсегда,
 * и через месяц адрес врал про каждую вторую деталь — вместе с ним переставали
 * работать печать этикеток и пересчёт по полке, которые на этот адрес
 * и опираются.
 *
 * <p><b>Адрес показывается по складу, а не одной строкой.</b> У позиции,
 * лежащей на двух складах, две полки, и одна строка про них соврала бы.
 * По той же причине склад в форме не подставляется, когда их несколько:
 * подставленный не тот — это тихая ошибка, деталь ищут не на той полке.
 *
 * <p>Отдельным компонентом, а не разметкой внутри карточки: карточку правят
 * из нескольких задач подряд, и блок, который можно тронуть целиком, дешевле
 * блока, размазанного по чужому файлу.
 */
export function PartCellBlock({ partId, warehouses, stock, role, onChanged }: {
  partId: number;
  warehouses: Warehouse[];
  /** Остаток по складам из строки витрины: ключ — идентификатор склада. */
  stock: Record<string, number>;
  role: string;
  /** Адрес изменился — витрину надо перечитать: в ней есть колонка «Ячейка». */
  onChanged: () => void;
}) {
  const [cells, setCells] = useState<PartCell[] | null>(null);
  // «Пусто» и «не смогли узнать» — разные вещи: без адреса деталь ищут
  // глазами, а при отказе сервера адрес есть, просто мы его не прочитали.
  const [unread, setUnread] = useState(false);
  const [placing, setPlacing] = useState(false);
  const [where, setWhere] = useState<number | null>(null);
  const [options, setOptions] = useState<Cell[] | null>(null);
  const [chosen, setChosen] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const mounted = useMounted();

  useEffect(() => {
    setCells(null);
    setUnread(false);
    void loadPartCells(partId)
      .then((found) => { if (mounted.current) setCells(found); })
      .catch(() => { if (mounted.current) setUnread(true); });
  }, [partId, mounted]);

  // Переставить можно только там, где остаток лежит: у склада, на котором
  // позиции нет, адреса нет и быть не может.
  const placeable = (cells ?? []).filter((c) => c.qty > 0);

  /** Полки выбранного склада. Пусто — их не заводили, и это надо сказать. */
  async function chooseWarehouse(id: number | null): Promise<void> {
    setWhere(id);
    setChosen(placeable.find((c) => c.warehouseId === id)?.cellId ?? null);
    setOptions(null);
    if (id === null) {
      return;
    }
    const found = await listCells(id).catch(() => null);
    if (mounted.current) {
      setOptions(found ?? []);
    }
  }

  function open(): void {
    setError('');
    setPlacing(true);
    // Склад подставляется, только когда он один: выбрать за кладовщика,
    // на какой из двух полок деталь теперь лежит, система не может.
    void chooseWarehouse(placeable.length === 1 ? (placeable[0]?.warehouseId ?? null) : null);
  }

  async function save(): Promise<void> {
    if (where === null) {
      return;
    }
    setError('');
    setSaving(true);
    try {
      await changePartCell(partId, where, chosen);
      onChanged();
    } catch (cause) {
      // 4xx со словами: «ячейки нет на складе», «нет остатка». По коду ответа
      // кладовщик пошёл бы искать поломку сервера.
      if (mounted.current) {
        setError(cause instanceof ApiError && cause.message !== ''
          ? cause.message
          : 'Переставить не вышло');
      }
    } finally {
      if (mounted.current) {
        setSaving(false);
      }
    }
  }

  return (
    <div className="card-view__stock">
      {warehouses.map((warehouse) => (
        <div key={warehouse.id}>
          <span>{warehouse.name}{addressOf(cells, warehouse.id)}</span>
          <b>{stock[String(warehouse.id)] ?? '—'}</b>
        </div>
      ))}

      {unread && <p className="note note--error">Адрес полки прочитать не удалось</p>}

      {PLACES.includes(role) && placeable.length > 0 && (placing ? (
        <>
          {placeable.length > 1 && (
            <label className="field">
              Склад
              <select
                value={where ?? ''}
                onChange={(e) => void chooseWarehouse(
                  e.target.value === '' ? null : Number(e.target.value))}
              >
                <option value="">— выберите склад —</option>
                {placeable.map((c) => (
                  <option key={c.warehouseId} value={c.warehouseId}>
                    {nameOf(warehouses, c.warehouseId)}
                  </option>
                ))}
              </select>
            </label>
          )}

          {where !== null && options !== null && options.length === 0 ? (
            // Пустой список выбора не объясняет ничего: у клиента без полок
            // ячеек нет вовсе, и это не поломка.
            <p className="note">
              На складе {nameOf(warehouses, where)} ячейки не заведены — завести
              их можно на экране «Склады».
            </p>
          ) : (
            <label className="field">
              Ячейка
              <select
                value={chosen ?? ''}
                onChange={(e) => setChosen(
                  e.target.value === '' ? null : Number(e.target.value))}
                disabled={where === null || options === null}
              >
                <option value="">без адреса</option>
                {(options ?? []).map((cell) => (
                  <option key={cell.id} value={cell.id}>{cell.code}</option>
                ))}
              </select>
            </label>
          )}

          {error !== '' && <p className="note note--error">{error}</p>}
          <div className="filter-row">
            <button
              type="button"
              disabled={where === null || saving}
              onClick={() => void save()}
            >
              {saving ? 'Переставляем…' : 'Переставить'}
            </button>
            <button type="button" className="button--ghost" onClick={() => setPlacing(false)}>
              Отмена
            </button>
          </div>
        </>
      ) : (
        <button type="button" className="button--ghost" onClick={open}>
          Переставить на полку
        </button>
      ))}
    </div>
  );
}

/**
 * Роли те же, что у «Перевезти»: деталь у кладовщика в руках, и перестановка
 * на полку — работа, а не расход. Списание — другое дело, там убыток.
 */
const PLACES = ['OWNER', 'MANAGER', 'STOREKEEPER'];

/**
 * «· А-01-1» или «· без адреса».
 *
 * <p>Прочерк тут значил бы «не знаем», а мы знаем: ячейки просто не заведены.
 * Пока адрес не прочитан, не говорится ничего — утверждать о полке раньше
 * ответа сервера нельзя.
 */
function addressOf(cells: PartCell[] | null, warehouseId: number): string {
  if (cells === null) {
    return '';
  }
  const here = cells.find((c) => c.warehouseId === warehouseId);
  if (here === undefined || here.qty <= 0) {
    return '';
  }
  return ` · ${here.cellCode ?? 'без адреса'}`;
}

function nameOf(warehouses: Warehouse[], id: number): string {
  return warehouses.find((w) => w.id === id)?.name ?? `склад ${id}`;
}
