import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { scannable } from '../labels/labels';
import {
  availabilitySummary,
  createBranch,
  createCells,
  createWarehouse,
  listBranches,
  listCells,
  listWarehouses,
  setWarehouseAvailability,
  unprintableCells,
  type Branch,
  type Cell,
  type Warehouse,
} from '../organization/warehouses';
import { useMounted } from '../ui/useMounted';

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

  // Наличие склада в объявлении: текст и вилка дней заказа (задача 0008).
  const [availabilityFor, setAvailabilityFor] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [daysFrom, setDaysFrom] = useState('');
  const [daysTo, setDaysTo] = useState('');
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  const reload = useCallback(() => {
    Promise.all([listBranches(), listWarehouses()])
      .then(([foundBranches, foundWarehouses]) => {
        if (mounted.current) {
          setBranches(foundBranches);
          setWarehouses(foundWarehouses);
          setError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Не загрузилось'));
      });
  }, [mounted]);

  useEffect(reload, [reload]);

  const wanted = codes.split(/[,\n]/).map((code) => code.trim()).filter((code) => code !== '');
  const unprintable = unprintableCells(wanted);

  // Пусто — «не задано», а не ноль: склад без вилки ведёт себя как раньше.
  const fromValue = daysFrom.trim() === '' ? null : Number(daysFrom);
  const toValue = daysTo.trim() === '' ? null : Number(daysTo);
  const badDays = (value: number | null) =>
    value !== null && (!Number.isInteger(value) || value < 0);
  // Серая кнопка обязана называть причину — те же слова, которыми отвечает
  // сервер: иначе владелец читает два разных отказа на одну ошибку.
  const availabilityObstacle = badDays(fromValue) || badDays(toValue)
    ? 'Дни заказа — целое число, не меньше нуля'
    : fromValue !== null && toValue !== null && toValue < fromValue
      ? 'Верхняя граница вилки дней меньше нижней'
      : null;

  return (
    <section className="screen">
      <h2>Филиалы и склады</h2>

      {error !== '' && <p className="note note--error">{error}</p>}

      {/* Третий табличный экран того же класса: на 390 он укладывается
          впритык, а на раннере CI с другим шрифтом давал 415 — то есть
          до края его доводит смена шрифта или название склада подлиннее.
          Прокручивается таблица, а не страница. */}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Склад</th>
              <th>Филиал</th>
              <th className="num">Ячеек</th>
              <th>Наличие в объявлении</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {warehouses.map((warehouse) => (
              <tr key={warehouse.id}>
                <td>{warehouse.name}</td>
                <td>{warehouse.branchName ?? '—'}</td>
                <td className="num">{warehouse.cells}</td>
                {/* «не задано» словами, а не пустой клеткой: пустая читается
                    как «не знаем», а мы знаем — владелец ничего не задавал. */}
                <td>{availabilitySummary(warehouse)}</td>
                <td>
                  <button
                    type="button"
                    className="button--ghost"
                    onClick={() => void openCells(warehouse.id)}
                  >
                    {opened === warehouse.id ? 'Свернуть' : 'Ячейки'}
                  </button>
                  <button
                    type="button"
                    className="button--ghost"
                    aria-label={`Наличие: ${warehouse.name}`}
                    onClick={() => openAvailability(warehouse)}
                  >
                    {availabilityFor === warehouse.id ? 'Свернуть' : 'Наличие'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {opened !== null && (
        <div className="card">
          <h3>Ячейки склада «{warehouses.find((w) => w.id === opened)?.name}»</h3>

          {cells.length === 0 ? (
            <p className="note">Ячеек пока нет.</p>
          ) : (
            <div className="chips">
              {cells.map((cell) => (
                <span
                  key={cell.id}
                  className={scannable(cell.code) ? 'chip' : 'chip chip--warn'}
                  title={scannable(cell.code) ? undefined : 'Не печатается штрихкодом'}
                >
                  {cell.code}
                  {!scannable(cell.code) && <span className="muted"> · не печатается</span>}
                </span>
              ))}
            </div>
          )}

          {/* Проверка смотрела только на то, что вводят сейчас, — а «Б-02-1»
              у клиента заведена давно, переездом или руками до появления
              этой проверки. В списке она выглядела обычной, и владелец
              узнавал о ней на печати этикеток: ровно тот случай, которого
              предупреждение ниже и должно избегать. */}
          {cells.some((cell) => !scannable(cell.code)) && (
            <p className="note note--error">
              Заведённые адреса выше, помеченные «не печатается», штрихкодом
              не напечатать: Code128 не знает кириллицы, а у «Б», «Г», «Д»
              латинского двойника нет. Пока стеллаж не подписан, переименовать
              дешевле всего.
            </p>
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

      {availabilityFor !== null && (
        <div className="card">
          <h3>
            Наличие склада «{warehouses.find((w) => w.id === availabilityFor)?.name}»
            в объявлении
          </h3>
          <p className="note">
            Это покупатель читает на площадке вместо одного «есть». Склады
            в разных местах, и деталь с дальнего едет несколько дней — без
            срока он узнаёт про дорогу только по телефону.
          </p>

          <div className="row">
            <label className="field">
              Текст наличия
              <input
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="в наличии"
              />
            </label>
            <label className="field">
              Дней заказа, от
              <input
                type="number"
                min={0}
                value={daysFrom}
                onChange={(e) => setDaysFrom(e.target.value)}
              />
            </label>
            <label className="field">
              Дней заказа, до
              <input
                type="number"
                min={0}
                value={daysTo}
                onChange={(e) => setDaysTo(e.target.value)}
              />
            </label>
          </div>

          {availabilityObstacle !== null && (
            <p className="note note--error">{availabilityObstacle}</p>
          )}

          <button
            type="button"
            disabled={busy || availabilityObstacle !== null}
            onClick={() => void saveAvailability()}
          >
            Сохранить наличие
          </button>
          <p className="note">
            Пустой текст и пустые дни — «не задано»: такой склад уезжает
            в прайс как раньше, одним признаком наличия, и ничего лишнего
            покупателю мы не обещаем. Ноль дней — это «забрать можно сегодня».
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
    const found = await listCells(id).catch(() => []);
    if (mounted.current) setCells(found);
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
      const found = await listCells(opened);
      if (mounted.current) {
        setCells(found);
        setCodes('');
        reload();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Ячейки не заведены'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  /**
   * Открывает форму наличия, подставив то, что у склада уже стоит.
   *
   * <p>Иначе владелец с тремя складами не знает, у какого из них срок уже
   * задан, и решает это заново каждый раз.
   */
  function openAvailability(warehouse: Warehouse): void {
    if (availabilityFor === warehouse.id) {
      setAvailabilityFor(null);
      return;
    }
    setAvailabilityFor(warehouse.id);
    setNote(warehouse.availabilityNote ?? '');
    setDaysFrom(warehouse.orderDaysFrom === null ? '' : String(warehouse.orderDaysFrom));
    setDaysTo(warehouse.orderDaysTo === null ? '' : String(warehouse.orderDaysTo));
  }

  async function saveAvailability(): Promise<void> {
    if (availabilityFor === null || availabilityObstacle !== null) {
      return;
    }
    setBusy(true);
    try {
      // Пустое поле уезжает null, а не пустой строкой и не нулём: на сервере
      // это разные вещи — «не задано» против «забрать сегодня».
      await setWarehouseAvailability(
        availabilityFor, note.trim() === '' ? null : note.trim(), fromValue, toValue,
      );
      if (mounted.current) {
        setAvailabilityFor(null);
        reload();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Наличие не сохранено'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function addWarehouse(): Promise<void> {
    setBusy(true);
    try {
      await createWarehouse(warehouseName.trim(), branchId);
      if (mounted.current) {
        setWarehouseName('');
        reload();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Склад не заведён'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function addBranch(): Promise<void> {
    setBusy(true);
    try {
      await createBranch(branchName.trim());
      if (mounted.current) {
        setBranchName('');
        reload();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Филиал не заведён'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
