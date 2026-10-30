import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError } from '../api/client';
import type { Reference } from '../reference/reference';
import {
  INCOME_TABS, addIncomeItem, addSupply, cancelIncome, createIncome, incomeStatusWord,
  loadIncome, loadIncomes, newRequestId, postIncome, unpostIncome,
  type Income, type IncomeHistoryEntry, type IncomePage, type IncomeTab,
} from '../intake/incomes';
import { conditionTitle } from '../inventory/catalog';
import { count, plural } from '../ui/plural';
import { MONTHS } from '../ui/shortDate';
import { useMounted } from '../ui/useMounted';

/**
 * Раздел «Поступления» (задача 0360): список документов, форма нового
 * и сам документ — по описанию кабинета ориентира, слова оттуда дословно.
 *
 * <p>Три вида внутри одного раздела, а не три вкладки рельса: из списка
 * открывают документ, из формы — только что созданный черновик, и обратно
 * ведёт «К списку». Роутера в приложении нет намеренно.
 */
export function IncomesScreen({ reference, onSupplyAdded }: {
  reference: Reference;
  /** Новая поставка заведена — справочники приёмки перечитываются. */
  onSupplyAdded: () => void;
}) {
  const [view, setView] = useState<
    { kind: 'list' } | { kind: 'new' } | { kind: 'doc'; id: number }
  >({ kind: 'list' });

  if (view.kind === 'new') {
    return (
      <NewIncomeForm
        reference={reference}
        onSupplyAdded={onSupplyAdded}
        onCancel={() => setView({ kind: 'list' })}
        onCreated={(id) => setView({ kind: 'doc', id })}
      />
    );
  }
  if (view.kind === 'doc') {
    return <IncomeDocument id={view.id} onBack={() => setView({ kind: 'list' })} />;
  }
  return (
    <IncomeList
      onNew={() => setView({ kind: 'new' })}
      onOpen={(id) => setView({ kind: 'doc', id })}
    />
  );
}

function IncomeList({ onNew, onOpen }: { onNew: () => void; onOpen: (id: number) => void }) {
  const [tab, setTab] = useState<IncomeTab>('drafts');
  const [page, setPage] = useState<IncomePage | null>(null);
  const [error, setError] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const mounted = useMounted();

  useEffect(() => {
    setPage(null);
    setError('');
    loadIncomes(tab)
      .then((found) => { if (mounted.current) setPage(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Список поступлений не загрузился'));
      });
  }, [tab, mounted]);

  async function more(): Promise<void> {
    const last = page?.rows[page.rows.length - 1];
    if (page === null || last === undefined) return;
    setLoadingMore(true);
    try {
      const next = await loadIncomes(tab, last.number);
      if (!mounted.current) return;
      setPage({ rows: [...page.rows, ...next.rows], counts: next.counts, more: next.more });
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Следующая страница не загрузилась'));
    } finally {
      if (mounted.current) setLoadingMore(false);
    }
  }

  const counts = page?.counts;
  const total = counts === undefined ? null : counts[tab];

  return (
    <section className="screen screen--wide">
      <div className="screen-head">
        <h2>Поступления</h2>
        <button type="button" onClick={onNew}>Новое поступление</button>
      </div>

      <div className="funnel-layout">
        <nav className="funnel">
          {INCOME_TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              className={tab === t.key ? 'funnel__item funnel__item--active' : 'funnel__item'}
              onClick={() => setTab(t.key)}
            >
              {t.label}
              {/* Счётчик — только у черновиков, как у ориентира: это то,
                  что ещё ждёт действия. */}
              {t.key === 'drafts' && counts !== undefined && ` ${count(counts.drafts)}`}
            </button>
          ))}
        </nav>

        <div className="funnel-body">
          {/* Три состояния: грузим, пусто, не смогли узнать. */}
          {error !== '' && <p className="note note--error">{error}</p>}
          {page === null && error === '' && <p className="note">Загружаем…</p>}
          {page !== null && (
            page.rows.length === 0 ? (
              <p className="note">Документов нет</p>
            ) : (
              <>
                <div className="table-scroll">
                  <table className="report">
                    <thead>
                      <tr>
                        <th>Номер/дата</th>
                        <th>Поставка</th>
                        <th>Склад оприходования</th>
                        <th className="num">Сумма</th>
                        <th>Статус</th>
                        <th>Комментарий</th>
                      </tr>
                    </thead>
                    <tbody>
                      {page.rows.map((row) => (
                        <tr key={row.id} className="row--clickable" onClick={() => onOpen(row.id)}>
                          <td>
                            № {row.number}
                            <div className="muted">{dayTime(row.createdAt)}</div>
                          </td>
                          <td>{row.supply ?? ''}</td>
                          <td>{row.warehouse}</td>
                          <td className="num">{money(row.amount)}</td>
                          <td>{incomeStatusWord(row.status, row.deleted)}</td>
                          <td>{row.note ?? ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {page.more && (
                  <button type="button" className="button--ghost" disabled={loadingMore}
                    onClick={() => void more()}>
                    Показать ещё
                  </button>
                )}
              </>
            )
          )}
          {total !== null && (
            <p className="note">
              {count(total)} {plural(total, 'документ', 'документа', 'документов')}
              {page !== null && page.more && ` · показаны ${count(page.rows.length)}`}
            </p>
          )}
        </div>
      </div>
    </section>
  );
}

function NewIncomeForm({ reference, onSupplyAdded, onCancel, onCreated }: {
  reference: Reference;
  onSupplyAdded: () => void;
  onCancel: () => void;
  onCreated: (id: number) => void;
}) {
  // Ключ формы — при её открытии, и не меняется при повторе нажатия.
  const [requestId] = useState(newRequestId);
  const [supplyId, setSupplyId] = useState<number | null>(null);
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // Заведённые здесь поставки видны в списке сразу, не дожидаясь справочников.
  const [added, setAdded] = useState<{ id: number; number: string }[]>([]);
  const [supplyOpen, setSupplyOpen] = useState(false);
  const mounted = useMounted();

  const supplies = [
    ...added.filter((a) => !reference.supplies.some((s) => s.id === a.id)),
    ...reference.supplies.map((s) => ({ id: s.id, number: s.number })),
  ];
  // Причина серой кнопки — одним выражением на кнопку и на подпись.
  const obstacle = warehouseId === null ? 'Выберите склад оприходования' : null;

  async function create(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      const created = await createIncome({
        warehouseId, supplyId, note: note.trim() === '' ? null : note.trim(), requestId,
      });
      if (mounted.current) onCreated(created.id);
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Поступление не создано'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  return (
    <section className="screen screen--wide">
      <h2>Новое поступление</h2>
      <div className="income-layout">
        <div className="income-main">
          <div className="income-tiles">
            <div className="income-tile"><span className="muted">Сумма</span> 0</div>
            <div className="income-tile"><span className="muted">Наклад. расх.</span> 0</div>
          </div>

          <label className="field">
            Поставка
            <span className="income-supply">
              <select value={supplyId ?? ''}
                onChange={(e) => setSupplyId(e.target.value === '' ? null : Number(e.target.value))}>
                <option value="">- Не выбрана -</option>
                {supplies.map((s) => <option key={s.id} value={s.id}>{s.number}</option>)}
              </select>
              <button type="button" className="button--ghost" onClick={() => setSupplyOpen(true)}>
                + Новая поставка
              </button>
            </span>
          </label>

          <label className="field">
            Склад оприходования
            <select value={warehouseId ?? ''}
              onChange={(e) => setWarehouseId(e.target.value === '' ? null : Number(e.target.value))}>
              <option value="">- Не выбран -</option>
              {reference.warehouses.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
          </label>

          <label className="field">
            Комментарий к поступлению
            <textarea value={note} onChange={(e) => setNote(e.target.value)} />
          </label>

          {error !== '' && <p className="note note--error">{error}</p>}
          <div className="row">
            <button type="button" disabled={busy || obstacle !== null} onClick={() => void create()}>
              Создать поступление
            </button>
            {/* Отменить не оставляет документа: черновик заводит только
                «Создать поступление» (критерий 4). */}
            <button type="button" className="button--ghost" onClick={onCancel}>Отменить</button>
          </div>
          {obstacle !== null && <p className="note">{obstacle}</p>}

          <p className="note">Создайте поступление, чтобы добавить товары</p>
        </div>
        <aside className="income-side">
          <h3>История документа</h3>
          <p className="note">Нет истории</p>
        </aside>
      </div>

      {supplyOpen && (
        <NewSupplyDialog
          onClose={() => setSupplyOpen(false)}
          onAdded={(supply) => {
            setAdded((list) => [supply, ...list]);
            setSupplyId(supply.id);
            setSupplyOpen(false);
            onSupplyAdded();
          }}
        />
      )}
    </section>
  );
}

function NewSupplyDialog({ onClose, onAdded }: {
  onClose: () => void;
  onAdded: (supply: { id: number; number: string }) => void;
}) {
  const [name, setName] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const mounted = useMounted();

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (name.trim() === '') return;
    setBusy(true);
    setError('');
    try {
      const supply = await addSupply(name.trim(), note.trim() === '' ? null : note.trim());
      if (mounted.current) onAdded(supply);
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Поставка не добавлена'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-label="Новая поставка">
      <form className="modal modal--short" onSubmit={(e) => void submit(e)}>
        <h3>Новая поставка</h3>
        <label className="field">
          Название
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </label>
        <label className="field">
          Описание
          <textarea value={note} onChange={(e) => setNote(e.target.value)} />
        </label>
        {error !== '' && <p className="note note--error">{error}</p>}
        <div className="row">
          <button type="button" className="button--ghost" onClick={onClose}>Закрыть</button>
          <button type="submit" disabled={busy || name.trim() === ''}>Добавить</button>
        </div>
      </form>
    </div>
  );
}

function IncomeDocument({ id, onBack }: { id: number; onBack: () => void }) {
  const [doc, setDoc] = useState<Income | null>(null);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false);
  const [askCancel, setAskCancel] = useState(false);
  const [adding, setAdding] = useState(false);
  const mounted = useMounted();

  const reload = useCallback(() => {
    loadIncome(id)
      .then((found) => { if (mounted.current) { setDoc(found); setError(''); } })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Поступление не загрузилось'));
      });
  }, [id, mounted]);

  useEffect(() => { reload(); }, [reload]);

  async function act(action: (id: number) => Promise<Income>, failure: string): Promise<void> {
    setBusy(true);
    setActionError('');
    setAskCancel(false);
    try {
      const next = await action(id);
      if (mounted.current) setDoc(next);
    } catch (cause) {
      if (mounted.current) setActionError(describe(cause, failure));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  if (doc === null) {
    return (
      <section className="screen screen--wide">
        <button type="button" className="button--ghost" onClick={onBack}>К списку</button>
        {error !== '' ? <p className="note note--error">{error}</p> : <p className="note">Загружаем…</p>}
      </section>
    );
  }

  const draft = doc.status === 'DRAFT';
  const done = doc.status === 'DONE' && !doc.deleted;

  return (
    <section className="screen screen--wide">
      <button type="button" className="button--ghost" onClick={onBack}>К списку</button>
      <div className="screen-head">
        <div>
          <h2>Поступление № {doc.number}</h2>
          <p className="muted">{stateLine(doc)}</p>
        </div>
        <div className="row">
          {draft && (
            <>
              <button type="button" disabled={busy}
                onClick={() => void act(postIncome, 'Провести документ не удалось')}>
                Провести документ
              </button>
              <button type="button" className="button--ghost" disabled={busy}
                onClick={() => setAskCancel(true)}>
                Отменить поступление
              </button>
            </>
          )}
          {done && (
            <button type="button" className="button--ghost" disabled={busy}
              onClick={() => void act(unpostIncome, 'Проводка не отменена')}>
              Отменить проводку
            </button>
          )}
        </div>
      </div>

      {askCancel && (
        <p className="history__confirm">
          <span className="muted">
            Отменить поступление № {doc.number}? Документ уйдёт во вкладку «Отменённые».
          </span>
          <button type="button" disabled={busy}
            onClick={() => void act(cancelIncome, 'Поступление не отменено')}>
            Отменить поступление
          </button>
          <button type="button" className="button--ghost" onClick={() => setAskCancel(false)}>
            Не отменять
          </button>
        </p>
      )}
      {actionError !== '' && <p className="note note--error" role="alert">{actionError}</p>}

      <div className="income-layout">
        <div className="income-main">
          <div className="income-tiles">
            <div className="income-tile"><span className="muted">Сумма</span> {money(doc.amount)}</div>
            <div className="income-tile"><span className="muted">Наклад. расх.</span> 0</div>
          </div>
          <dl className="income-fields">
            <dt>Поставка</dt><dd>{doc.supply ?? '- Не выбрана -'}</dd>
            <dt>Склад оприходования</dt><dd>{doc.warehouse}</dd>
            <dt>Комментарий к поступлению</dt><dd>{doc.note ?? ''}</dd>
          </dl>

          {draft && !adding && (
            <button type="button" className="button--ghost" onClick={() => setAdding(true)}>
              + Добавить
            </button>
          )}
          {draft && adding && (
            <AddItemForm
              incomeId={doc.id}
              onAdded={(next) => setDoc(next)}
              onClose={() => setAdding(false)}
            />
          )}

          {doc.lines.length === 0 ? (
            <p className="note">Товаров в документе нет</p>
          ) : (
            <div className="table-scroll">
              <table className="report">
                <thead>
                  <tr>
                    <th className="num">Номер</th>
                    <th>Наименование</th>
                    <th className="num">Кол-во</th>
                    <th className="num">Закупочная цена</th>
                    <th className="num">Накладные расходы</th>
                  </tr>
                </thead>
                <tbody>
                  {doc.lines.map((line) => (
                    <tr key={line.partId}>
                      <td className="num">{line.number}</td>
                      <td>
                        {line.title}
                        {line.condition !== null && (
                          <span className="muted"> · {conditionTitle(line.condition) ?? ''}</span>
                        )}
                      </td>
                      <td className="num">{line.qty}</td>
                      <td className="num">{line.cost === null ? '' : money(line.cost)}</td>
                      <td className="num">0</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        <aside className="income-side">
          <h3>История документа</h3>
          <History entries={doc.history} />
        </aside>
      </div>
    </section>
  );
}

function History({ entries }: { entries: IncomeHistoryEntry[] }) {
  if (entries.length === 0) return <p className="note">Нет истории</p>;
  return (
    <ul className="income-history">
      {entries.map((entry, i) => (
        <li key={i}>
          <div>{entry.text}{entry.partTitle !== null && <span className="muted"> · {entry.partTitle}</span>}</div>
          <div className="muted">{dayTime(entry.at)}{entry.author !== null && ` · ${entry.author}`}</div>
        </li>
      ))}
    </ul>
  );
}

/**
 * Позиция черновика: то, без чего строки в документе нет. Поля формы «Б/У
 * товары» целиком — задача 0362; здесь наименование, количество, цены, заметка.
 */
function AddItemForm({ incomeId, onAdded, onClose }: {
  incomeId: number;
  onAdded: (next: Income) => void;
  onClose: () => void;
}) {
  const [rawName, setRawName] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [price, setPrice] = useState('');
  const [costPrice, setCostPrice] = useState('');
  const [note, setNote] = useState('');
  // Ключ позиции: новый на каждую добавленную, прежний при повторе после отказа.
  const [requestId, setRequestId] = useState(newRequestId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const mounted = useMounted();

  const qty = Number(quantity);
  const obstacle = rawName.trim() === '' ? 'Впишите наименование'
    : !Number.isFinite(qty) || qty <= 0 ? 'Количество — число больше нуля'
      : null;

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (obstacle !== null) return;
    setBusy(true);
    setError('');
    try {
      const next = await addIncomeItem(incomeId, {
        rawName: rawName.trim(),
        quantity: qty,
        price: price.trim() === '' ? null : Number(price),
        costPrice: costPrice.trim() === '' ? null : Number(costPrice),
        note: note.trim() === '' ? null : note.trim(),
        requestId,
      });
      if (!mounted.current) return;
      onAdded(next);
      setRawName('');
      setQuantity('1');
      setPrice('');
      setCostPrice('');
      setNote('');
      setRequestId(newRequestId());
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Позиция не добавлена'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={(e) => void submit(e)} aria-label="Новая позиция">
      <div className="row">
        <label className="field">Наименование
          <input value={rawName} onChange={(e) => setRawName(e.target.value)} />
        </label>
        <label className="field">Кол-во
          <input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </label>
        <label className="field">Цена
          <input inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} />
        </label>
        <label className="field">Закупочная цена
          <input inputMode="decimal" value={costPrice} onChange={(e) => setCostPrice(e.target.value)} />
        </label>
        <label className="field">Заметка
          <input value={note} onChange={(e) => setNote(e.target.value)} />
        </label>
      </div>
      {error !== '' && <p className="note note--error">{error}</p>}
      <div className="row">
        <button type="submit" disabled={busy || obstacle !== null}>Добавить</button>
        <button type="button" className="button--ghost" onClick={onClose}>Закрыть</button>
      </div>
      {obstacle !== null && <p className="note">{obstacle}</p>}
    </form>
  );
}

/** «Черновик от 16 окт в 13:27», «Выполнен 16 окт в 13:27», «Отменён». */
function stateLine(doc: Income): string {
  if (doc.status === 'DRAFT') return `Черновик от ${dayTime(doc.createdAt)}`;
  if (doc.status === 'DONE') {
    const word = incomeStatusWord(doc.status, doc.deleted);
    return doc.completedAt === null ? word : `${word} ${dayTime(doc.completedAt)}`;
  }
  return incomeStatusWord(doc.status);
}

/** «16 окт в 13:27» — момент с часовым поясом, поэтому `new Date`. */
export function dayTime(iso: string): string {
  const at = new Date(iso);
  const hh = String(at.getHours()).padStart(2, '0');
  const mm = String(at.getMinutes()).padStart(2, '0');
  return `${at.getDate()} ${MONTHS[at.getMonth()]} в ${hh}:${mm}`;
}

function money(value: number): string {
  return count(Math.round(value * 100) / 100);
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}
