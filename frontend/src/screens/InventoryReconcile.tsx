import { useState } from 'react';
import { ApiError } from '../api/client';
import {
  applySession,
  discrepanciesOf,
  finishCounting,
  findOpenSession,
  type Applied,
  type Discrepancy,
  type InventorySession,
} from '../inventory/inventory';
import type { Reference } from '../reference/reference';

/**
 * Сведение расхождений: завершить подсчёт, посмотреть, что не сошлось,
 * и провести.
 *
 * <p><b>Не с телефона.</b> Кладовщик обходит полки и вносит факт; списанная
 * недостача — это убыток, и решение принимает тот, кто отвечает за склад.
 * До этого экрана завершить и провести пересчёт можно было только запросом
 * к API, то есть с разработчиком: кладовщик считал, а дальше обход упирался
 * в пустоту.
 *
 * <p><b>Проведение построчное.</b> Недостачу по детали, обещанной покупателю,
 * списать нельзя — остаток уйдёт ниже резерва. Такие строки остаются
 * непроведёнными и приезжают списком: продавец снимет резерв, и «Провести»
 * дописывает только их. Проведённая строка второй корректировки не породит.
 */
export function InventoryReconcile({ reference }: { reference: Reference }) {
  // Склад не подставляется: пересчёт не того склада списывает недостачу
  // там, где её не считали.
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [session, setSession] = useState<InventorySession | null>(null);
  const [rows, setRows] = useState<Discrepancy[] | null>(null);
  const [applied, setApplied] = useState<Applied | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  return (
    <section className="card">
      <h3>Свести расхождения</h3>

      <div className="row">
        <label className="field">
          Склад
          <select
            value={warehouseId ?? ''}
            onChange={(e) => {
              setWarehouseId(e.target.value === '' ? null : Number(e.target.value));
              setSession(null);
              setRows(null);
              setApplied(null);
            }}
          >
            <option value="">— выберите склад —</option>
            {reference.warehouses.map((w) => (
              <option key={w.id} value={w.id}>{w.name}</option>
            ))}
          </select>
        </label>
        <button type="button" disabled={busy || warehouseId === null} onClick={() => void find()}>
          Найти пересчёт
        </button>
      </div>

      {note !== '' && <p className="note note--error">{note}</p>}

      {session !== null && (
        <>
          <p className="note">
            Сессия {session.id} · {session.status === 'OPEN' ? 'идёт подсчёт' : 'подсчёт завершён'}
            {' '}· строк {session.lines} · посчитано {session.counted}
          </p>

          {session.status === 'OPEN' ? (
            <>
              <button type="button" disabled={busy} onClick={() => void finish()}>
                Завершить подсчёт
              </button>
              {/* Непосчитанное — это «не дошли», а не «не нашли»: оно
                  не проводится вовсе, и завершение его не спишет. */}
              <p className="note">
                Непосчитанные позиции не спишутся: пустое поле означает «до полки
                не дошли», а не «детали нет».
              </p>
            </>
          ) : (
            <button type="button" disabled={busy} onClick={() => void apply()}>
              Провести
            </button>
          )}
        </>
      )}

      {rows !== null && (
        rows.length === 0 ? (
          <p className="note">Расхождений нет — факт сошёлся с учётом.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Деталь</th>
                <th className="num">Учёт на момент подсчёта</th>
                <th className="num">Факт</th>
                <th className="num">Разница</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.partId} className={r.applied ? 'muted' : undefined}>
                  <td>{r.title ?? `деталь ${r.partId}`}</td>
                  <td className="num">{r.qtyExpectedAtCount}</td>
                  <td className="num">{r.qtyCounted}</td>
                  <td className="num">{r.shortage ? '' : '+'}{r.delta}</td>
                  {/* Расхождение считается на момент подсчёта и после
                      проведения остаётся в таблице: без отметки экран
                      показывал бы «скорректировано» рядом с той же
                      минусовой строкой. */}
                  <td>{r.applied ? 'проведено' : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}

      {applied !== null && (
        <>
          <p className="note">Скорректировано позиций: {applied.adjusted}.</p>
          {applied.blocked.length > 0 && (
            <div className="note note--error">
              <p>
                Не проведено — деталь обещана покупателю, и списать её нельзя.
                Снимите резерв (отмените или перенесите позицию в сделке)
                и нажмите «Провести» ещё раз: допишется только это.
              </p>
              <ul>
                {applied.blocked.map((line) => <li key={line}>{line}</li>)}
              </ul>
            </div>
          )}
        </>
      )}
    </section>
  );

  async function find(): Promise<void> {
    if (warehouseId === null) {
      return;
    }
    setBusy(true);
    setNote('');
    setApplied(null);
    try {
      const found = await findOpenSession(warehouseId);
      setSession(found);
      if (found === null) {
        setNote('На этом складе пересчёт не открыт');
        setRows(null);
      } else if (found.status !== 'OPEN') {
        setRows(await discrepanciesOf(found.id));
      } else {
        setRows(null);
      }
    } catch (cause) {
      setNote(describe(cause, 'Не удалось найти пересчёт'));
    } finally {
      setBusy(false);
    }
  }

  async function finish(): Promise<void> {
    if (session === null) {
      return;
    }
    setBusy(true);
    try {
      setSession(await finishCounting(session.id));
      setRows(await discrepanciesOf(session.id));
      setNote('');
    } catch (cause) {
      setNote(describe(cause, 'Не удалось завершить подсчёт'));
    } finally {
      setBusy(false);
    }
  }

  async function apply(): Promise<void> {
    if (session === null) {
      return;
    }
    setBusy(true);
    try {
      const result = await applySession(session.id);
      setApplied(result);
      setRows(await discrepanciesOf(session.id));
      setNote('');
    } catch (cause) {
      setNote(describe(cause, 'Не удалось провести'));
    } finally {
      setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
