import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  createBranch,
  createCells,
  createWarehouse,
  listBranches,
  listCells,
  listWarehouses,
  unprintableCells,
  type Branch,
  type Cell,
  type Warehouse,
} from '../organization/warehouses';

/**
 * Филиалы, склады и ячейки хранения.
 *
 * <p>Экран владельца. До него завести второй склад или полку можно было
 * только запросом к API — то есть нельзя: клиент с двумя адресами
 * не настраивался без разработчика.
 *
 * <p>Провижининг заводит один филиал и один склад: арендатор без склада
 * не примет ни одной детали. Ячейки не заводит намеренно — это физические
 * полки, их коды знает только клиент, и придуманные за него адреса разойдутся
 * с тем, что написано на стеллаже.
 */
export function OrganizationScreen() {
  const [branches, setBranches] = useState<Branch[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [branchName, setBranchName] = useState('');
  const [warehouseName, setWarehouseName] = useState('');
  const [branchId, setBranchId] = useState<number | null>(null);

  const [opened, setOpened] = useState<number | null>(null);
  const [cells, setCells] = useState<Cell[]>([]);
  const [codes, setCodes] = useState('');

  const reload = useCallback(() => {
    Promise.all([listBranches(), listWarehouses()])
      .then(([foundBranches, foundWarehouses]) => {
        setBranches(foundBranches);
        setWarehouses(foundWarehouses);
        setError('');
      })
      .catch((cause) => setError(describe(cause, 'Не загрузилось')));
  }, []);

  useEffect(reload, [reload]);

  const wanted = codes.split(/[,\n]/).map((code) => code.trim()).filter((code) => code !== '');
  const unprintable = unprintableCells(wanted);

  return (
    <section className="screen">
      <h2>Филиалы и склады</h2>

      {error !== '' && <p className="note note--error">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>Склад</th>
            <th>Филиал</th>
            <th className="num">Ячеек</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {warehouses.map((warehouse) => (
            <tr key={warehouse.id}>
              <td>{warehouse.name}</td>
              <td>{warehouse.branchName ?? '—'}</td>
              <td className="num">{warehouse.cells}</td>
              <td>
                <button
                  type="button"
                  className="button--ghost"
                  onClick={() => void openCells(warehouse.id)}
                >
                  {opened === warehouse.id ? 'Свернуть' : 'Ячейки'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {opened !== null && (
        <div className="card">
          <h3>Ячейки склада «{warehouses.find((w) => w.id === opened)?.name}»</h3>

          {cells.length === 0 ? (
            <p className="note">Ячеек пока нет.</p>
          ) : (
            <div className="chips">
              {cells.map((cell) => (
                <span key={cell.id} className="chip">{cell.code}</span>
              ))}
            </div>
          )}

          <label className="field">
            Новые ячейки — через запятую или с новой строки
            <textarea
              rows={3}
              value={codes}
              onChange={(e) => setCodes(e.target.value)}
              placeholder="А-01-1, А-01-2, А-02-1"
            />
          </label>

          {/* Предупреждение здесь, а не на печати этикеток: переименовать
              десять полок в первый день дешевле, чем через месяц объяснять
              кладовщику, почему сканер их не видит. */}
          {unprintable.length > 0 && (
            <p className="note note--error">
              Эти адреса не напечатать штрихкодом: {unprintable.join(', ')}. Code128
              не знает кириллицы. «А», «В», «Е», «К» сканер сводит к латинице,
              а у «Б», «Г», «Д» двойника нет — такую ячейку не отсканировать
              никогда. Замените букву на латинскую или на ту, у которой двойник есть.
            </p>
          )}

          <button
            type="button"
            disabled={busy || wanted.length === 0}
            onClick={() => void addCells()}
          >
            {busy ? 'Заводим…' : `Завести ${wanted.length || ''}`.trim()}
          </button>
          <p className="note">
            Списком, а не по одной: стеллаж — это два-три десятка адресов подряд.
            Уже заведённые пропускаются, а не ломают запрос целиком.
          </p>
        </div>
      )}

      <div className="card">
        <h3>Новый склад</h3>
        <div className="row">
          <label className="field">
            Название
            <input value={warehouseName} onChange={(e) => setWarehouseName(e.target.value)} />
          </label>
          <label className="field">
            Филиал
            <select
              value={branchId ?? ''}
              onChange={(e) => setBranchId(e.target.value === '' ? null : Number(e.target.value))}
            >
              <option value="">—</option>
              {branches.map((branch) => (
                <option key={branch.id} value={branch.id}>{branch.name}</option>
              ))}
            </select>
          </label>
        </div>
        <button
          type="button"
          disabled={busy || warehouseName.trim() === ''}
          onClick={() => void addWarehouse()}
        >
          Завести склад
        </button>
      </div>

      <div className="card">
        <h3>Новый филиал</h3>
        <label className="field">
          Название
          <input
            value={branchName}
            onChange={(e) => setBranchName(e.target.value)}
            placeholder="Ткацкая"
          />
        </label>
        <button
          type="button"
          disabled={busy || branchName.trim() === ''}
          onClick={() => void addBranch()}
        >
          Завести филиал
        </button>
      </div>
    </section>
  );

  async function openCells(id: number): Promise<void> {
    if (opened === id) {
      setOpened(null);
      return;
    }
    setOpened(id);
    setCodes('');
    setCells(await listCells(id).catch(() => []));
  }

  async function addCells(): Promise<void> {
    if (opened === null) {
      return;
    }
    setBusy(true);
    try {
      // Эндпоинт отвечает только заведёнными, а не всем списком: уже
      // существующие он пропускает. Показать этот ответ как список ячеек
      // склада значит стереть с экрана прежние — и счётчик в таблице
      // разойдётся с тем, что видно под ней.
      await createCells(opened, wanted, null);
      setCells(await listCells(opened));
      setCodes('');
      reload();
    } catch (cause) {
      setError(describe(cause, 'Ячейки не заведены'));
    } finally {
      setBusy(false);
    }
  }

  async function addWarehouse(): Promise<void> {
    setBusy(true);
    try {
      await createWarehouse(warehouseName.trim(), branchId);
      setWarehouseName('');
      reload();
    } catch (cause) {
      setError(describe(cause, 'Склад не заведён'));
    } finally {
      setBusy(false);
    }
  }

  async function addBranch(): Promise<void> {
    setBusy(true);
    try {
      await createBranch(branchName.trim());
      setBranchName('');
      reload();
    } catch (cause) {
      setError(describe(cause, 'Филиал не заведён'));
    } finally {
      setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
