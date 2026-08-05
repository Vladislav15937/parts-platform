import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { LabelSheet } from '../labels/LabelSheet';
import {
  cellLabel,
  cellsOf,
  LABEL_HEIGHT_MM,
  LABEL_WIDTH_MM,
  partLabel,
  scannable,
} from '../labels/labels';
import type { Cell, Label } from '../labels/labels';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import { searchStock } from '../sales/sales';

/**
 * Печать этикеток.
 *
 * <p>Сканер ячеек работал, а печатать коды было нечем: клиент подписывал полки
 * сам, и написанное от руки сканер не читает вовсе. Стеллаж — это два-три
 * десятка адресов подряд, поэтому печать пачкой, а не по одной.
 *
 * <p><b>Печатает браузер.</b> Диалог печати откроется на выбранный принтер,
 * размер этикетки выставляется в нём один раз. Отдавать принтеру команды
 * напрямую нельзя: у клиентов они разные и заранее неизвестные.
 */
interface Props {
  canPrint: boolean;
}

type Source = 'cells' | 'parts';

export function LabelsScreen({ canPrint }: Props) {
  const [source, setSource] = useState<Source>('cells');
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [cells, setCells] = useState<Cell[]>([]);
  const [picked, setPicked] = useState<number[]>([]);
  const [query, setQuery] = useState('');
  const [partLabels, setPartLabels] = useState<Label[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void listWarehouses()
      .then((loaded) => {
        setWarehouses(loaded);
        setWarehouseId((current) => current ?? loaded[0]?.id ?? null);
      })
      .catch((cause) => setError(describe(cause, 'Склады не загрузились')));
  }, []);

  useEffect(() => {
    if (warehouseId === null) {
      return;
    }
    void cellsOf(warehouseId)
      .then((loaded) => {
        setCells(loaded);
        setPicked([]);
      })
      .catch((cause) => setError(describe(cause, 'Ячейки не загрузились')));
  }, [warehouseId]);

  if (!canPrint) {
    return (
      <section className="card">
        <h2>Этикетки</h2>
        <p className="note">Печатать этикетки может владелец, менеджер или кладовщик.</p>
      </section>
    );
  }

  const warehouse = warehouses.find((w) => w.id === warehouseId);
  const labels: Label[] =
    source === 'cells'
      ? cells
          .filter((cell) => picked.includes(cell.id))
          .map((cell) => cellLabel(cell, warehouse?.name ?? ''))
      : partLabels;

  return (
    <>
      <section className="card no-print">
        <h2>Этикетки</h2>

        {error !== null && <p className="note note--error">{error}</p>}

        <div className="tabs">
          <button
            type="button"
            className={source === 'cells' ? 'tab tab--active' : 'tab'}
            onClick={() => setSource('cells')}
          >
            Ячейки
          </button>
          <button
            type="button"
            className={source === 'parts' ? 'tab tab--active' : 'tab'}
            onClick={() => setSource('parts')}
          >
            Детали
          </button>
        </div>

        {source === 'cells' && (
          <>
            <label>
              Склад
              <select
                value={warehouseId ?? ''}
                onChange={(e) => setWarehouseId(Number(e.target.value))}
              >
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name}
                  </option>
                ))}
              </select>
            </label>

            {cells.length === 0 && (
              <p className="note">
                На этом складе ячеек нет. Их заводят списком — стеллаж целиком,
                а не по одной.
              </p>
            )}

            {cells.length > 0 && (
              <>
                <div className="row">
                  <button
                    type="button"
                    className="button--ghost"
                    onClick={() =>
                      setPicked(
                        cells.filter((cell) => scannable(cell.code)).map((cell) => cell.id),
                      )
                    }
                  >
                    Выбрать все ({cells.filter((cell) => scannable(cell.code)).length})
                  </button>
                  <button
                    type="button"
                    className="button--ghost"
                    onClick={() => setPicked([])}
                  >
                    Снять выбор
                  </button>
                </div>

                <ul className="suggestions">
                  {cells.map((cell) => (
                    <li key={cell.id}>
                      <label className="pick">
                        <input
                          type="checkbox"
                          disabled={!scannable(cell.code)}
                          checked={picked.includes(cell.id)}
                          onChange={(e) =>
                            setPicked(
                              e.target.checked
                                ? [...picked, cell.id]
                                : picked.filter((id) => id !== cell.id),
                            )
                          }
                        />{' '}
                        {cell.code}
                        {cell.zone !== null && <span className="muted"> · {cell.zone}</span>}
                        {!cell.active && <span className="muted"> · закрыта</span>}
                        {!scannable(cell.code) && (
                          <span className="muted"> · не кодируется</span>
                        )}
                      </label>
                    </li>
                  ))}
                </ul>

                {cells.some((cell) => !scannable(cell.code)) && (
                  <p className="note note--error">
                    Часть адресов не кодируется штрихкодом: Code128 не знает
                    кириллицы. «А», «В», «Е», «К», «М», «Н», «О», «Р», «С», «Т», «У», «Х»
                    совпадают с латинскими и работают, а «Б», «Г», «Д», «Ж», «З» и прочие —
                    нет. Переименуйте такие адреса латиницей или цифрами: подставить
                    вместо «Б» похожую «B» нельзя — она сольётся с настоящей «В»,
                    и деталь ляжет на другой стеллаж.
                  </p>
                )}
              </>
            )}
          </>
        )}

        {source === 'parts' && (
          <>
            <form
              className="row"
              onSubmit={(e) => {
                e.preventDefault();
                void findParts();
              }}
            >
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="фара камри, бампер приора"
                autoCapitalize="none"
              />
              <button type="submit" disabled={query.trim() === ''}>
                Найти
              </button>
            </form>
            <p className="note">
              На этикетку детали идёт неугадываемый код карточки, а не её номер:
              этикетка уезжает к покупателю вместе с деталью.
            </p>
          </>
        )}

        <hr />

        <p className="note">
          К печати: {labels.length}. Размер этикетки — {LABEL_WIDTH_MM}×{LABEL_HEIGHT_MM} мм;
          выставьте его в диалоге печати и уберите поля, иначе принтер ужмёт
          штрихкод и сканер перестанет его брать.
        </p>

        <button type="button" disabled={labels.length === 0} onClick={() => window.print()}>
          {labels.length === 0 ? 'Нечего печатать' : `Печать (${labels.length})`}
        </button>
      </section>

      {/* Предпросмотр он же то, что уйдёт в печать: отдельная страница печати
          разошлась бы с тем, что человек видит, и разошлась бы незаметно. */}
      {labels.length > 0 && <LabelSheet labels={labels} />}
    </>
  );

  async function findParts(): Promise<void> {
    setError(null);
    try {
      const found = await searchStock(query.trim());
      setPartLabels(
        found.rows
          .filter((row) => row.publicCode !== null)
          .map((row) => partLabel(row.publicCode!, row.title, row.price)),
      );
    } catch (cause) {
      setError(describe(cause, 'Поиск не сработал'));
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. Коды берутся из базы — печатать нечего.';
    }
    return cause.message;
  }
  return fallback;
}
