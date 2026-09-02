import { useCallback, useEffect, useState } from 'react';
import {
  cellsOf,
  findOpenSession,
  forgetSession,
  linesOfCell,
  loadLocal,
  openSession,
} from '../inventory/inventory';
import type { InventoryLine, InventorySession, LocalCount } from '../inventory/inventory';
import { rememberCount } from '../inventory/inventory';
import type { Reference } from '../reference/reference';
import { resolveScan } from '../scan/codes';
import { ScanOverlay } from '../scan/ScanOverlay';

/**
 * Пересчёт склада с телефона.
 *
 * <p>Работа идёт по ячейкам: кладовщик сканирует этикетку полки и видит, что
 * учёт считает лежащим на ней. Это и есть весь выигрыш от телефона — иначе
 * пересчёт делают по бумажной ведомости, а потом вбивают её за компьютером.
 *
 * <p><b>Пустое поле — это «не дошли», а не «не нашли».</b> Разница в том, что
 * непосчитанное не проводится вовсе, а посчитанный ноль — это недостача,
 * которую спишут. Поэтому ноль вносится отдельной кнопкой, а не остаётся
 * значением по умолчанию: подмена одного другим списывает полсклада.
 */
interface Props {
  reference: Reference;
  onCount(sessionId: number, line: InventoryLine, qty: string, countedAt: number): void;
}

export function InventoryScreen({ reference, onCount }: Props) {
  /**
   * Склад пересчёта. В состоянии, а не чтением поля из DOM: пока значение
   * жило только в разметке, кнопки нельзя было погасить до выбора — они
   * выглядели нажимаемыми, хотя нажатие ничего не делало. В соседнем блоке
   * «Свести расхождения» кнопка при этом гасла, и два блока на одном экране
   * вели себя по-разному.
   */
  const [warehouseId, setWarehouseId] = useState('');
  const [session, setSession] = useState<InventorySession | null>(null);
  const [lines, setLines] = useState<InventoryLine[]>([]);
  const [counts, setCounts] = useState<Record<string, LocalCount>>({});
  const [cellId, setCellId] = useState<number | null>(null);
  const [scanning, setScanning] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    const local = await loadLocal();
    setSession(local.session);
    setLines(local.lines);
    setCounts(local.counts);

    // Открываемся на первой ячейке маршрута, а не на «без ячейки».
    // Позиции без адреса — редкий хвост списка, и начинать обход с него
    // значит показать кладовщику пустой экран там, где склад полон.
    setCellId((current) => {
      if (current !== null) {
        return current;
      }
      const route = cellsOf(local.lines);
      const first = route.find((cell) => cell.id !== null) ?? route[0];
      return first?.id ?? null;
    });
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  if (session === null) {
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
            onChange={(e) => { setWarehouseId(e.target.value); setNote(null); }}
          >
            <option value="">— выберите склад —</option>
            {reference.warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
        </label>

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

  const cells = cellsOf(lines);
  const visible = linesOfCell(lines, cellId);
  const done = Object.keys(counts).length;

  return (
    <section className="card">
      <h2>Инвентаризация</h2>
      <p className="note">
        Сессия {session.id} · посчитано {done} из {lines.length}
      </p>

      <button type="button" onClick={() => setScanning(true)}>
        Сканировать ячейку
      </button>

      <label>
        Ячейка
        <select
          value={cellId ?? ''}
          onChange={(e) => setCellId(e.target.value === '' ? null : Number(e.target.value))}
        >
          {cells.map((cell) => (
            <option key={String(cell.id)} value={cell.id ?? ''}>
              {cell.code}
            </option>
          ))}
        </select>
      </label>

      {note !== null && <p className="note">{note}</p>}

      <ul className="count-list">
        {visible.map((line) => (
          <CountRow
            key={line.partId}
            line={line}
            count={counts[String(line.partId)]}
            onSubmit={(qty) => void submit(line, qty)}
          />
        ))}
        {visible.length === 0 && <li className="muted">В этой ячейке учёт ничего не числит</li>}
      </ul>

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

      {scanning && (
        <ScanOverlay
          hint="Код ячейки"
          onScan={applyScan}
          onClose={() => setScanning(false)}
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

      const opened = fresh
        ? await openSession(Number(warehouseId))
        : await findOpenSession(Number(warehouseId));

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

  function applyScan(text: string): void {
    setScanning(false);
    const match = resolveScan(reference, session?.warehouseId ?? null, text);

    if (match.kind === 'cell') {
      const known = lines.some((line) => line.cellId === match.cell.id);
      setCellId(match.cell.id);
      setNote(
        known
          ? `Ячейка ${match.cell.code}`
          : `Ячейка ${match.cell.code} — учёт в ней ничего не числит`,
      );
      return;
    }
    setNote(`Код «${text}» не похож на ячейку этого склада`);
  }
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
  count,
  onSubmit,
}: {
  line: InventoryLine;
  count: LocalCount | undefined;
  onSubmit: (qty: string) => void;
}) {
  const [value, setValue] = useState('');

  return (
    <li className={count === undefined ? 'count-row' : 'count-row count-row--done'}>
      <div className="count-title">
        {line.title}
        <span className="muted"> · учёт {line.qtyExpected}</span>
        {count !== undefined && <span className="muted"> · посчитано {count.qty}</span>}
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
