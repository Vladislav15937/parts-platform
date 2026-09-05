import { useCallback, useEffect, useRef, useState } from 'react';
import {
  countPositions,
  findOpenSession,
  forgetSession,
  loadLocal,
  NO_CELL_ID,
  openSession,
  rememberUnlistedLine,
  resolvePartScan,
  statusOf,
} from '../inventory/inventory';
import type {
  InventoryLine,
  InventorySession,
  LineStatus,
  LocalCount,
  WarehouseCode,
} from '../inventory/inventory';
import { rememberCount } from '../inventory/inventory';
import { ApiError } from '../api/client';
import type { Reference } from '../reference/reference';
import { resolveScan } from '../scan/codes';
import { ScanOverlay } from '../scan/ScanOverlay';

/**
 * Пересчёт склада с телефона — одной полки или всего склада.
 *
 * <p>Работа идёт по ячейкам: открывая пересчёт, кладовщик выбирает не только
 * склад, но и ячейку — это и даёт весь выигрыш от телефона. Документ
 * на тридцать три тысячи позиций никто не закрывает: обход идёт неделями,
 * а без «Завершить подсчёт» нельзя провести расхождения. Одна полка —
 * это минуты.
 *
 * <p><b>Пустое поле — это «не дошли», а не «не нашли».</b> Разница в том, что
 * непосчитанное не проводится вовсе, а посчитанный ноль — это недостача,
 * которую спишут. Поэтому ноль вносится отдельной кнопкой, а не остаётся
 * значением по умолчанию: подмена одного другим списывает полсклада.
 *
 * <p><b>Скан детали прибавляет единицу, а не выставляет факт равным учёту.</b>
 * На разборке товар в основном штучный, но колёса и метизы бывают парами,
 * и «сканирую столько раз, сколько штук нашёл» — единственное правило,
 * которое одинаково работает и там, и там.
 */
interface Props {
  reference: Reference;
  onCount(sessionId: number, line: InventoryLine, qty: string, countedAt: number): void;
}

type Tab = 'all' | 'unscanned' | 'problem' | 'scanned';

const TABS: { key: Tab; label: string }[] = [
  { key: 'all', label: 'Все' },
  { key: 'unscanned', label: 'Не сканировались' },
  { key: 'problem', label: 'С проблемами' },
  { key: 'scanned', label: 'Отсканированы' },
];

export function InventoryScreen({ reference, onCount }: Props) {
  const [warehouseId, setWarehouseId] = useState('');
  /**
   * Выборка формы открытия: '' — «Любая», 'none' — «Без адреса», иначе
   * строковый id ячейки. Отдельно от состояния уже открытой сессии — там
   * своей выборки для навигации больше нет, её заменили вкладки по статусу.
   */
  const [scopeCell, setScopeCell] = useState('');
  const [found, setFound] = useState<
    { status: 'idle' } | { status: 'loading' } | { status: 'ok'; value: number }
    | { status: 'error'; message: string }
  >({ status: 'idle' });

  const [session, setSession] = useState<InventorySession | null>(null);
  const [lines, setLines] = useState<InventoryLine[]>([]);
  const [counts, setCounts] = useState<Record<string, LocalCount>>({});
  const [codes, setCodes] = useState<WarehouseCode[]>([]);
  const [tab, setTab] = useState<Tab>('all');
  const [scanning, setScanning] = useState<'cell' | 'part' | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    const local = await loadLocal();
    setSession(local.session);
    setLines(local.lines);
    setCounts(local.counts);
    setCodes(local.codes);
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  // Счётчик «Найдено товаров» пересчитывается при каждой смене склада или
  // ячейки — сторож на случай, если ответ придёт не в том порядке, в котором
  // ушёл запрос.
  const requestId = useRef(0);
  useEffect(() => {
    if (warehouseId === '') {
      setFound({ status: 'idle' });
      return;
    }
    const id = ++requestId.current;
    setFound({ status: 'loading' });
    void countPositions(Number(warehouseId), resolveCellId(scopeCell))
      .then((value) => {
        if (requestId.current === id) {
          setFound({ status: 'ok', value });
        }
      })
      .catch((cause: unknown) => {
        if (requestId.current === id) {
          setFound({
            status: 'error',
            message: cause instanceof ApiError && cause.message !== ''
              ? cause.message
              : 'Не удалось посчитать',
          });
        }
      });
  }, [warehouseId, scopeCell]);

  if (session === null) {
    const cells = reference.warehouses.find((w) => w.id === Number(warehouseId))?.cells ?? [];

    return (
      <section className="card">
        <h2>Инвентаризация</h2>
        <p className="note">
          Лист обхода скачивается целиком при открытии — сделайте это там, где есть связь.
          В ангаре пересчёт работает без неё, но начать без связи нельзя.
        </p>

        <label>
          Склад
          {/* Без умолчания: пересчёт не того склада — это обход, который
              кладовщик сделает целиком и который спишет недостачу там,
              где ничего не считали. Какой склад считают, знает он. */}
          <select
            value={warehouseId}
            onChange={(e) => {
              setWarehouseId(e.target.value);
              setScopeCell('');
              setNote(null);
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
          Ячейка
          <select
            value={scopeCell}
            disabled={warehouseId === ''}
            onChange={(e) => { setScopeCell(e.target.value); setNote(null); }}
          >
            <option value="">Любая</option>
            {cells.map((c) => (
              <option key={c.id} value={String(c.id)}>{c.code}</option>
            ))}
            <option value="none">Без адреса</option>
          </select>
        </label>

        {warehouseId !== '' && (
          <p className="note">
            {found.status === 'loading' && 'Загружаем…'}
            {found.status === 'error' && found.message}
            {found.status === 'ok' && `Найдено товаров: ${found.value}`}
          </p>
        )}

        <button type="button" disabled={busy || warehouseId === ''}
                onClick={() => void start(false)}>
          Продолжить начатую
        </button>
        <button type="button" disabled={busy || warehouseId === ''}
                onClick={() => void start(true)}>
          Открыть новую
        </button>
        {note !== null && <p className="note">{note}</p>}
      </section>
    );
  }

  const groups = groupBy(lines, counts);
  const shown = groups[tab === 'all' ? 'unscanned' : tab];

  return (
    <section className="card">
      <h2>Инвентаризация</h2>
      <p className="note">
        Сессия {session.id} · посчитано {session.counted} из {lines.length}
      </p>

      <div className="row">
        <button type="button" onClick={() => setScanning('cell')}>
          Сканировать ячейку
        </button>
        <button type="button" onClick={() => setScanning('part')}>
          Сканировать деталь
        </button>
      </div>

      {note !== null && <p className="note">{note}</p>}

      <div className="tabs">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            className={tab === t.key ? 'tab tab--active' : 'tab'}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'all' ? (
        <>
          <h4>Не сканировались</h4>
          <CountGroup lines={groups.unscanned} status="unscanned" counts={counts} onSubmit={submit} />
          <h4>С проблемами</h4>
          <CountGroup lines={groups.problem} status="problem" counts={counts} onSubmit={submit} />
          <h4>Отсканированы</h4>
          <CountGroup lines={groups.scanned} status="scanned" counts={counts} onSubmit={submit} />
        </>
      ) : (
        <CountGroup lines={shown} status={tab} counts={counts} onSubmit={submit} />
      )}

      <hr />
      <button
        type="button"
        className="button--ghost"
        onClick={() => {
          // Локальный лист убираем, сессию на сервере не трогаем: завершает
          // её тот, кто сводит расхождения, и делает это не с телефона.
          void forgetSession().then(reload);
        }}
      >
        Убрать лист с телефона
      </button>

      {scanning !== null && (
        <ScanOverlay
          hint={scanning === 'cell' ? 'Код ячейки' : 'Штрихкод детали'}
          onScan={scanning === 'cell' ? applyCellScan : applyPartScan}
          onClose={() => setScanning(null)}
        />
      )}
    </section>
  );

  async function start(fresh: boolean): Promise<void> {
    setBusy(true);
    setNote(null);
    try {
      // Ни выбора, ни подстановки: без склада пересчёт не начинается вовсе.
      // Кнопки до выбора погашены, так что сюда попадают только с выбранным;
      // проверка остаётся сторожем на случай, если кнопку разблокируют.
      if (warehouseId === '') {
        setNote('Выберите склад: пересчёт идёт по одному складу, и какой — знаете вы');
        return;
      }

      const cellId = resolveCellId(scopeCell);
      const opened = fresh
        ? await openSession(Number(warehouseId), cellId)
        : await findOpenSession(Number(warehouseId), cellId);

      if (opened === null) {
        setNote('На этом складе инвентаризация не открыта');
        return;
      }
      await reload();
    } catch (error) {
      setNote(error instanceof Error ? error.message : 'Не удалось открыть');
    } finally {
      setBusy(false);
    }
  }

  async function submit(line: InventoryLine, qty: string): Promise<void> {
    if (session === null) {
      return;
    }
    // Сначала локально: экран обязан показать пройденную полку немедленно,
    // не дожидаясь связи, которой в ангаре нет.
    const count = await rememberCount(line.partId, qty);
    setCounts((prev) => ({ ...prev, [String(line.partId)]: count }));
    onCount(session.id, line, qty, count.countedAt);
  }

  /** Скан прибавляет к факту единицу — сколько раз нашли, столько и посчитали. */
  async function scanLine(line: InventoryLine, noteText: string): Promise<void> {
    const current = counts[String(line.partId)];
    const next = String((current === undefined ? 0 : Number(current.qty)) + 1);
    await submit(line, next);
    setNote(noteText);
  }

  function applyCellScan(text: string): void {
    setScanning(null);
    const match = resolveScan(reference, session?.warehouseId ?? null, text);
    if (match.kind === 'cell') {
      setNote(`Ячейка ${match.cell.code}`);
      return;
    }
    setNote(`Код «${text}» не похож на ячейку этого склада`);
  }

  function applyPartScan(text: string): void {
    setScanning(null);
    const match = resolvePartScan(codes, lines, text);

    if (match.kind === 'not-found') {
      setNote(`Код «${text}» не найден на этом складе`);
      return;
    }
    if (match.kind === 'listed') {
      const line = lines.find((l) => l.partId === match.partId);
      if (line !== undefined) {
        void scanLine(line, 'Отсканирован');
      }
      return;
    }

    // Деталь есть на складе, но не в текущем листе обхода — «вне списка»,
    // а не «неизвестно»: она лежит не на своей полке, и это надо увидеть,
    // а не потерять.
    const newLine: InventoryLine = {
      partId: match.code.partId,
      title: match.code.title,
      cellId: match.code.cellId,
      cellCode: match.code.cellCode,
      qtyExpected: '0',
      qtyCounted: null,
    };
    void rememberUnlistedLine(newLine).then(() => {
      setLines((prev) => (prev.some((l) => l.partId === newLine.partId) ? prev : [...prev, newLine]));
      void scanLine(newLine, 'Вне списка');
    });
  }
}

/** Разбирает выбор ячейки формы открытия в то, что ждёт сервер. */
function resolveCellId(scopeCell: string): number | undefined {
  if (scopeCell === '') {
    return undefined;
  }
  return scopeCell === 'none' ? NO_CELL_ID : Number(scopeCell);
}

function groupBy(
  lines: InventoryLine[],
  counts: Record<string, LocalCount>,
): Record<LineStatus, InventoryLine[]> {
  const groups: Record<LineStatus, InventoryLine[]> = { unscanned: [], problem: [], scanned: [] };
  for (const line of lines) {
    groups[statusOf(line, counts)].push(line);
  }
  return groups;
}

function CountGroup({
  lines,
  status,
  counts,
  onSubmit,
}: {
  lines: InventoryLine[];
  status: LineStatus;
  counts: Record<string, LocalCount>;
  onSubmit: (line: InventoryLine, qty: string) => void;
}) {
  if (lines.length === 0) {
    return <p className="muted">Ничего не найдено</p>;
  }
  return (
    <ul className="count-list">
      {lines.map((line) => (
        <CountRow
          key={line.partId}
          line={line}
          status={status}
          count={counts[String(line.partId)]}
          onSubmit={(qty) => onSubmit(line, qty)}
        />
      ))}
    </ul>
  );
}

/**
 * Строка полки.
 *
 * <p>Учётное количество показывается: скрывать его — популярный приём против
 * подгонки, но на разборке пересчитывают штучный товар, где подгонять нечего,
 * а слепой ввод даёт опечатки, которые потом проводятся как расхождения.
 */
function CountRow({
  line,
  status,
  count,
  onSubmit,
}: {
  line: InventoryLine;
  status: LineStatus;
  count: LocalCount | undefined;
  onSubmit: (qty: string) => void;
}) {
  const [value, setValue] = useState('');

  return (
    <li className={status === 'scanned' ? 'count-row count-row--done' : 'count-row'}>
      <div className="count-title">
        {line.title}
        {status !== 'problem' && <span className="muted"> · учёт {line.qtyExpected}</span>}
        {count !== undefined && <span className="muted"> · посчитано {count.qty}</span>}
        {' '}
        <StatusBadge status={status} />
      </div>
      <div className="row">
        <input
          type="number"
          inputMode="numeric"
          min="0"
          value={value}
          placeholder="факт"
          onChange={(e) => setValue(e.target.value)}
        />
        <button
          type="button"
          disabled={value.trim() === ''}
          onClick={() => {
            onSubmit(value.trim());
            setValue('');
          }}
        >
          Внести
        </button>
        <button
          type="button"
          className="button--ghost"
          // Отдельной кнопкой намеренно: посчитанный ноль — это недостача,
          // и она должна вноситься осознанным действием, а не пустым полем.
          onClick={() => onSubmit('0')}
        >
          Нет на полке
        </button>
      </div>
    </li>
  );
}

function StatusBadge({ status }: { status: LineStatus }) {
  if (status === 'scanned') {
    return <span className="badge badge--online">Отсканирован</span>;
  }
  if (status === 'problem') {
    return <span className="badge badge--offline">Вне списка</span>;
  }
  return <span className="badge badge--muted">Не сканирован</span>;
}
