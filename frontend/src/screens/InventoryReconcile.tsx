import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  applySession,
  cancelSession as cancelOnServer,
  discrepanciesOf,
  finishCounting,
  findOpenSession,
  listSessions,
  sessionSummary,
  SESSION_FUNNEL,
  SESSION_STATUS_LABEL,
  type Applied,
  type Discrepancy,
  type InventorySession,
  type SessionFunnelKey,
  type SessionSummary,
} from '../inventory/inventory';
import type { Reference } from '../reference/reference';

/**
 * Журнал пересчётов и сведение расхождений.
 *
 * <p><b>Список сверху находит любой пересчёт, не только открытый.</b>
 * До этого экран умел искать сессию только по складу
 * ({@link findOpenSession}), то есть исключительно открытую: проведённый
 * вчера документ нельзя было открыть ни списком, ни по номеру, хотя журнал
 * склада на него ссылается («Пересчёт №4»). Воронка слева — «В работе»,
 * «Выполненные», «Отменённые», «Все пересчёты» — и нажатие на строку
 * открывает тот же блок сведения, что и раньше, для любого статуса.
 *
 * <p><b>Не с телефона.</b> Кладовщик обходит полки и вносит факт; списанная
 * недостача — это убыток, и решение принимает тот, кто отвечает за склад.
 * До появления этого экрана завершить и провести пересчёт можно было только
 * запросом к API, то есть с разработчиком.
 *
 * <p><b>Проведение построчное.</b> Недостачу по детали, обещанной покупателю,
 * списать нельзя — остаток уйдёт ниже резерва. Такие строки остаются
 * непроведёнными и приезжают списком: продавец снимет резерв, и «Провести»
 * дописывает только их. Проведённая строка второй корректировки не породит.
 *
 * @param role роль вошедшего. Не передана — доступ полный, как было до
 *             появления списка (так вызывает этот компонент существующий
 *             тест). «Просмотр» видит журнал и сведения, но не сводит
 *             расхождения: то же разделение, что и на сервере (`RECONCILES`)
 */
export function InventoryReconcile({ reference, role }: { reference: Reference; role?: string }) {
  const canReconcile = role === undefined || role === 'OWNER' || role === 'MANAGER';

  // --- журнал: воронка слева, список справа ---
  const [funnel, setFunnel] = useState<SessionFunnelKey>('OPEN');
  const [list, setList] = useState<SessionSummary[] | null>(null);
  const [listNote, setListNote] = useState('');

  useEffect(() => {
    let ignore = false;
    setList(null);
    setListNote('');
    listSessions(funnel)
      .then((found) => { if (!ignore) setList(found); })
      .catch((cause) => {
        if (!ignore) setListNote(describe(cause, 'Не удалось загрузить список пересчётов'));
      });
    return () => { ignore = true; };
  }, [funnel]);

  // --- сведения одной сессии: по складу (владелец и менеджер) или по строке списка ---
  // Склад не подставляется: пересчёт не того склада списывает недостачу
  // там, где её не считали.
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [session, setSession] = useState<InventorySession | null>(null);
  const [rows, setRows] = useState<Discrepancy[] | null>(null);
  const [applied, setApplied] = useState<Applied | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  // Отмена выбрасывает работу смены — спрашиваем вторым нажатием.
  const [cancelling, setCancelling] = useState(false);
  // Удача и отказ показываются по-разному: «пересчёт отменён» красным
  // читается как поломка.
  const [done, setDone] = useState('');

  return (
    <section className="card">
      <h3>Пересчёты</h3>

      <div className="funnel-layout">
        <nav className="funnel">
          {SESSION_FUNNEL.map((f) => (
            <button
              key={f.key}
              type="button"
              className={funnel === f.key ? 'funnel__item funnel__item--active' : 'funnel__item'}
              onClick={() => setFunnel(f.key)}
            >
              {f.label}
            </button>
          ))}
        </nav>

        <div className="funnel-body">
          {listNote !== '' && <p className="note note--error">{listNote}</p>}
          {list === null && listNote === '' && <p className="note">Загружаем…</p>}
          {list !== null && (
            list.length === 0 ? (
              <p className="note">Пересчётов пока не было</p>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Номер/дата</th>
                    <th>Выборка</th>
                    <th>Статус</th>
                    <th>Посчитано</th>
                    <th>Комментарий</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((row) => (
                    <tr key={row.id} className="row--clickable" onClick={() => void openRow(row.id)}>
                      <td>{row.id}<br /><span className="muted">{shortDate(row.startedAt)}</span></td>
                      <td>{row.selection}</td>
                      <td>{SESSION_STATUS_LABEL[row.status]}</td>
                      <td>{row.counted} из {row.lines}</td>
                      {/* Комментарий пока негде хранить — своей колонки
                          у сессии нет, задача 0020 закрыта наполовину,
                          подробности в отчёте по ней. */}
                      <td />
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <td colSpan={5}>Пересчётов: {list.length}</td>
                  </tr>
                </tfoot>
              </table>
            )
          )}
        </div>
      </div>

      {canReconcile && (
        <>
          <hr />

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
        </>
      )}

      {note !== '' && <p className="note note--error">{note}</p>}
      {done !== '' && <p className="note">{done}</p>}

      {session !== null && (
        <>
          <p className="note">
            Сессия {session.id} · {SESSION_STATUS_LABEL[session.status as SessionSummary['status']]
              ?? session.status}
            {' '}· строк {session.lines} · посчитано {session.counted}
          </p>

          {canReconcile && session.status === 'OPEN' && (
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
          )}

          {canReconcile && session.status === 'COUNTED' && (
            <button type="button" disabled={busy} onClick={() => void apply()}>
              Провести
            </button>
          )}

          {/*
            * Отмена — единственный выход из ошибочно открытой сессии.
            *
            * Вторую инвентаризацию на складе открыть нельзя: две дадут
            * двойную корректировку, и сервер это отбивает. Значит
            * кладовщик, выбравший не тот склад, запирал пересчёт на нём
            * навсегда — `POST /sessions/{id}/cancel` был написан и закрыт
            * ролью, но не звала его ни одна строка фронтенда, и штатного
            * выхода не было вовсе.
            *
            * Вторым нажатием: отмена выбрасывает лист обхода вместе
            * с посчитанным, а это работа смены. Склад при этом не меняется
            * ничем — корректировки делает только проведение.
            *
            * Показана только для «В работе»/«Выполненные»: проведённую
            * или уже отменённую сессию отменить нельзя — сервер отобьёт,
            * а кнопка без дела читалась бы как рабочая.
            */}
          {canReconcile && (session.status === 'OPEN' || session.status === 'COUNTED') && (
            <button
              type="button"
              className="button--ghost"
              disabled={busy}
              onClick={() => void cancelSession()}
            >
              {cancelling
                ? `Точно отменить? Посчитанное (${session.counted}) пропадёт`
                : 'Отменить пересчёт'}
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

  /** Открывает сведения по строке списка — любого статуса, не только открытого. */
  async function openRow(id: number): Promise<void> {
    setBusy(true);
    setNote('');
    setDone('');
    setApplied(null);
    setCancelling(false);
    try {
      const found = await sessionSummary(id);
      setSession(found);
      setWarehouseId(found.warehouseId);
      setRows(found.status !== 'OPEN' ? await discrepanciesOf(found.id) : null);
    } catch (cause) {
      setNote(describe(cause, 'Не удалось открыть пересчёт'));
    } finally {
      setBusy(false);
    }
  }

  /** Перечитывает журнал после действия, сменившего статус сессии. */
  async function refreshList(): Promise<void> {
    try {
      setList(await listSessions(funnel));
    } catch {
      // Список — не главный путь сразу после успешного действия: если
      // страница действия уже сказала «готово», молчаливый повтор при случае
      // следующей смены воронки дешевле второй красной строки поверх первой.
    }
  }

  async function cancelSession(): Promise<void> {
    if (session === null) {
      return;
    }
    if (!cancelling) {
      setCancelling(true);
      return;
    }
    setBusy(true);
    try {
      await cancelOnServer(session.id);
      setSession(null);
      setRows(null);
      setApplied(null);
      setCancelling(false);
      setNote('');
      setDone('Пересчёт отменён — склад не изменился. Можно открыть новый.');
      void refreshList();
    } catch (cause) {
      setNote(cause instanceof ApiError ? cause.message : 'Отменить не удалось');
    } finally {
      setBusy(false);
    }
  }

  async function find(): Promise<void> {
    if (warehouseId === null) {
      return;
    }
    setBusy(true);
    setNote('');
    setDone('');
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
      void refreshList();
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
      void refreshList();
    } catch (cause) {
      setNote(describe(cause, 'Не удалось провести'));
    } finally {
      setBusy(false);
    }
  }
}

/**
 * Дата коротким словом месяца: «05 сен 26».
 *
 * <p>Своя таблица месяцев, а не `Intl`: короткое имя в `ru-RU` зависит
 * от сборки ICU («сент.» против «сен»), и экран показывал бы разное
 * в браузере и в тестах.
 */
const MONTHS = ['янв', 'фев', 'мар', 'апр', 'май', 'июн',
  'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

function shortDate(iso: string): string {
  const at = new Date(iso);
  const day = String(at.getDate()).padStart(2, '0');
  const year = String(at.getFullYear()).slice(-2);
  return `${day} ${MONTHS[at.getMonth()]} ${year}`;
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
