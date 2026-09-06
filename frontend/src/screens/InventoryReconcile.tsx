import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import {
  applySession,
  cancelSession as cancelOnServer,
  discrepanciesOf,
  finishCounting,
  findOpenSession,
  listSessions,
  noteEditable,
  saveSessionNote,
  sessionSummary,
  SESSION_FUNNEL,
  SESSION_STATUS_LABEL,
  type Applied,
  type Discrepancy,
  type InventorySession,
  type SessionFunnelKey,
  type SessionPage,
} from '../inventory/inventory';
import { count as num, shown } from '../ui/plural';
import { shortDate } from '../ui/shortDate';
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
  // Комментарий пишет тот же, кто сводит расхождения: то же разделение,
  // что на сервере (`InventoryController.COMMENTS`). «Просмотр» его только
  // читает. Кладовщика тут нет — у него нет поверхности, откуда писать,
  // и права на сервере тоже нет; см. комментарий у `COMMENTS`.
  const canComment = canReconcile;

  /*
   * Сторож размонтирования — тот же приём, что в `InventoryScreen`, и та же
   * причина: `main` краснела на этом дважды. Ответ сервера приходит после
   * того, как человек ушёл с вкладки, и `setState` в размонтированном
   * компоненте валит прогон необработанным отказом («window is not defined»),
   * а не проверкой.
   *
   * Значение возвращается в `true` в теле эффекта, а не только гасится
   * в уборке: `StrictMode` в разработке прогоняет эффекты дважды
   * (setup → cleanup → setup), и без этого экран остался бы мёртвым
   * навсегда — «Загружаем…» без конца и погашенные кнопки.
   */
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  // --- журнал: воронка слева, список справа ---
  const [funnel, setFunnel] = useState<SessionFunnelKey>('OPEN');
  const [page, setPage] = useState<SessionPage | null>(null);
  const [listNote, setListNote] = useState('');

  useEffect(() => {
    let ignore = false;
    setPage(null);
    setListNote('');
    listSessions(funnel)
      .then((found) => { if (!ignore) setPage(found); })
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
  // Черновик комментария отдельно от сохранённого: пока не нажали
  // «Сохранить», набранное ещё не факт, а кнопка гаснет ровно тогда,
  // когда сохранять нечего.
  const [noteDraft, setNoteDraft] = useState('');
  const [noteSaved, setNoteSaved] = useState('');
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
          {page === null && listNote === '' && <p className="note">Загружаем…</p>}
          {page !== null && (
            page.rows.length === 0 ? (
              <p className="note">Пересчётов пока не было</p>
            ) : (
              /* Пять колонок рядом с воронкой на телефоне не помещаются,
                 а без этой обёртки вбок уезжает вся страница вместе
                 с рельсом — записанная ловушка проекта. Прокручивается
                 таблица внутри своих границ. */
              <div className="table-scroll">
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
                    {page.rows.map((row) => (
                      <tr key={row.id} className="row--clickable"
                          onClick={() => void openRow(row.id)}>
                        <td>
                          {row.id}<br />
                          <span className="muted">{shortDate(row.startedAt)}</span>
                        </td>
                        <td>{row.selection}</td>
                        <td>{SESSION_STATUS_LABEL[row.status]}</td>
                        <td>{row.counted} из {row.lines}</td>
                        {/* Ради него в журнал и заходят: номер с датой говорят,
                            что документ был, а «83619 не найден» — зачем его
                            открывали. Пусто остаётся пустым, а не прочерком:
                            «не писали» тут не вопрос, на который мы не знаем
                            ответа, а обычное состояние половины строк. */}
                        <td>{row.note ?? ''}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    {/* Счёт по воронке целиком, а не по показанному: журнал
                        отдаётся страницей, и счётчик, считающий строки экрана,
                        врал бы ровно на то, чего не видно. */}
                    <tr>
                      <td colSpan={5}>Пересчётов: {num(page.total)}</td>
                    </tr>
                    {page.rows.length < page.total && (
                      <tr>
                        <td colSpan={5} className="muted">
                          Показаны первые {shown(page.rows.length, page.total,
                            'пересчёт', 'пересчёта', 'пересчётов')}
                        </td>
                      </tr>
                    )}
                  </tfoot>
                </table>
              </div>
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
            Сессия {session.id} · {SESSION_STATUS_LABEL[session.status]}
            {' '}· строк {session.lines} · посчитано {session.counted}
          </p>

          {/*
            * Комментарий — под шапкой сведений, как и в задаче.
            *
            * Правится, пока пересчёт не проведён и не отменён: закрытый
            * документ показывается текстом, потому что приписка задним
            * числом объясняла бы уже случившееся не тем, что видел писавший.
            * Условие то же, что на сервере (`noteEditable`), — поле, которое
            * сервер отобьёт, хуже отсутствующего.
            *
            * У закрытого без комментария блока нет вовсе: пустая подпись
            * «Комментарий» с прочерком — это строка, которая ничего
            * не сообщает, а таких на экране и так хватает.
            */}
          {canComment && noteEditable(session.status) ? (
            <div className="field">
              <label htmlFor="session-note">Комментарий</label>
              <textarea
                id="session-note"
                rows={2}
                value={noteDraft}
                disabled={busy}
                onChange={(e) => {
                  setNoteDraft(e.target.value);
                  // «Комментарий сохранён» рядом с изменённым текстом
                  // говорит неправду о том, что лежит на сервере.
                  setNoteSaved('');
                }}
              />
              <div className="row">
                <button
                  type="button"
                  disabled={busy || noteDraft === (session.note ?? '')}
                  onClick={() => void saveNote()}
                >
                  Сохранить комментарий
                </button>
                {noteSaved !== '' && <span className="note">{noteSaved}</span>}
              </div>
            </div>
          ) : (
            session.note !== null && (
              <p className="note">Комментарий: {session.note}</p>
            )
          )}

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
    setNoteSaved('');
    try {
      const found = await sessionSummary(id);
      const discrepancies = found.status !== 'OPEN' ? await discrepanciesOf(found.id) : null;
      if (!mounted.current) {
        return;
      }
      setSession(found);
      setNoteDraft(found.note ?? '');
      setWarehouseId(found.warehouseId);
      setRows(discrepancies);
    } catch (cause) {
      if (mounted.current) {
        setNote(describe(cause, 'Не удалось открыть пересчёт'));
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
    }
  }

  /** Перечитывает журнал после действия, сменившего статус сессии. */
  async function refreshList(): Promise<void> {
    try {
      const found = await listSessions(funnel);
      if (mounted.current) {
        setPage(found);
      }
    } catch {
      // Список — не главный путь сразу после успешного действия: если
      // страница действия уже сказала «готово», молчаливый повтор при случае
      // следующей смены воронки дешевле второй красной строки поверх первой.
    }
  }

  /**
   * Сохраняет комментарий и перечитывает журнал.
   *
   * <p>Список обновляется здесь, а не молча: колонка «Комментарий» — это
   * то, ради чего в журнал заходят, и оставить в ней прежний текст рядом
   * с новым в поле значило бы показать два ответа на один вопрос.
   */
  async function saveNote(): Promise<void> {
    if (session === null) {
      return;
    }
    setBusy(true);
    setNote('');
    try {
      const saved = await saveSessionNote(session.id, noteDraft);
      if (mounted.current) {
        setSession(saved);
        // Сервер стирает пустое в null и срезает пробелы по краям —
        // черновик берётся из его ответа, иначе кнопка осталась бы
        // нажимаемой на том, что уже сохранено.
        setNoteDraft(saved.note ?? '');
        setNoteSaved('Комментарий сохранён');
        void refreshList();
      }
    } catch (cause) {
      if (mounted.current) {
        setNote(describe(cause, 'Не удалось сохранить комментарий'));
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
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
      if (mounted.current) {
        setSession(null);
        setRows(null);
        setApplied(null);
        setCancelling(false);
        setNote('');
        setDone('Пересчёт отменён — склад не изменился. Можно открыть новый.');
        void refreshList();
      }
    } catch (cause) {
      if (mounted.current) {
        setNote(cause instanceof ApiError ? cause.message : 'Отменить не удалось');
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
    }
  }

  async function find(): Promise<void> {
    if (warehouseId === null) {
      return;
    }
    setBusy(true);
    setNote('');
    setDone('');
    setNoteSaved('');
    setApplied(null);
    try {
      const found = await findOpenSession(warehouseId);
      const discrepancies = found !== null && found.status !== 'OPEN'
        ? await discrepanciesOf(found.id)
        : null;
      if (!mounted.current) {
        return;
      }
      setSession(found);
      setNoteDraft(found?.note ?? '');
      setRows(discrepancies);
      if (found === null) {
        setNote('На этом складе пересчёт не открыт');
      }
    } catch (cause) {
      if (mounted.current) {
        setNote(describe(cause, 'Не удалось найти пересчёт'));
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
    }
  }

  async function finish(): Promise<void> {
    if (session === null) {
      return;
    }
    setBusy(true);
    try {
      const finished = await finishCounting(session.id);
      const discrepancies = await discrepanciesOf(session.id);
      if (mounted.current) {
        setSession(finished);
        setRows(discrepancies);
        setNote('');
        void refreshList();
      }
    } catch (cause) {
      if (mounted.current) {
        setNote(describe(cause, 'Не удалось завершить подсчёт'));
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
    }
  }

  async function apply(): Promise<void> {
    if (session === null) {
      return;
    }
    setBusy(true);
    try {
      const result = await applySession(session.id);
      const discrepancies = await discrepanciesOf(session.id);
      // Статус перечитывается, а не выводится из успеха: проведение
      // построчное, и застрявшая на резерве строка оставляет сессию
      // «завершённой». Без этого шапка и комментарий говорили бы
      // «подсчёт завершён, правьте» о документе, который сервер уже закрыл.
      const after = await sessionSummary(session.id);
      if (mounted.current) {
        setApplied(result);
        setRows(discrepancies);
        setSession(after);
        setNoteDraft(after.note ?? '');
        setNote('');
        void refreshList();
      }
    } catch (cause) {
      if (mounted.current) {
        setNote(describe(cause, 'Не удалось провести'));
      }
    } finally {
      if (mounted.current) {
        setBusy(false);
      }
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
