import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { count } from '../ui/plural';
import { useMounted } from '../ui/useMounted';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import {
  accountOf,
  correctAccount,
  createCustomer,
  customerOf,
  customerPayments,
  dealsOf,
  defaultPaymentSource,
  listCustomers,
  listReturns,
  paymentSources,
  rememberPaymentSource,
  topUpAccount,
  updateCustomer,
  withdrawFromAccount,
} from '../sales/sales';
import type {
  CustomerAccount,
  CustomerDetail,
  CustomersPage,
  Deal,
  PaymentRow,
  PaymentSourceEntry,
  ReturnListRow,
  ReturnsPage,
} from '../sales/sales';

/**
 * Раздел «Клиенты»: список с балансом и карточка — кто клиент, что брал,
 * что вернул, сколько должен.
 *
 * <p>До этой задачи карточки не было вовсе: клиент существовал только как
 * поиск в разговоре ({@code CustomerPicker}) и как «Найти сделку клиента»
 * ({@code DealFinder}) в {@link SellerScreen}. Поправить телефон, записанный
 * с ошибкой, было негде, а список клиентов не открывался ниоткуда.
 *
 * <p>Компонент не переиспользует {@code DealFinder} и не трогает
 * {@code SellerScreen}: там кнопки «Положить»/«Выдать» нужны продавцу
 * посреди разговора, здесь та же операция доступна из другого места ради
 * другой задачи — разобраться, сколько клиент должен.
 */
export function CustomersScreen({
  role,
  company,
  memberId,
  onOpenDeal,
}: {
  role: string;
  /** Схема арендатора и сотрудник — ключ, которым помнится источник платежа. */
  company: string;
  memberId: number;
  onOpenDeal: (dealId: number) => void;
}) {
  const canManage = role === 'OWNER' || role === 'MANAGER';
  const [selected, setSelected] = useState<number | null>(null);

  if (selected !== null) {
    return (
      <CustomerCard
        customerId={selected}
        role={role}
        company={company}
        memberId={memberId}
        canManage={canManage}
        onBack={() => setSelected(null)}
        onOpenDeal={onOpenDeal}
      />
    );
  }

  return <CustomerList onOpen={setSelected} />;
}

const PAGE = 50;

function CustomerList({ onOpen }: { onOpen: (id: number) => void }) {
  const mounted = useMounted();
  const [query, setQuery] = useState('');
  const [size, setSize] = useState(PAGE);
  const [page, setPage] = useState<CustomersPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState('');
  const [newPhone, setNewPhone] = useState('');

  useEffect(() => {
    void load(size);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [size]);

  const filtered = query.trim() !== '';

  return (
    <section className="screen screen--wide">
      <h2>Клиенты</h2>

      <form
        className="filter-row filter-row--search"
        onSubmit={(e) => {
          e.preventDefault();
          setSize(PAGE);
          void load(PAGE);
        }}
      >
        <label className="field">
          Поиск
          <input
            value={query}
            placeholder="Поиск клиента"
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <button type="submit">Найти</button>
        <button type="button" className="button--ghost" onClick={() => setAdding(!adding)}>
          Добавить клиента
        </button>
      </form>

      {adding && (
        <div className="card">
          <label className="field">
            Имя или название
            <input value={newName} onChange={(e) => setNewName(e.target.value)} />
          </label>
          <label className="field">
            Телефон
            <input value={newPhone} onChange={(e) => setNewPhone(e.target.value)} />
          </label>
          <div className="row">
            <button
              type="button"
              disabled={newName.trim() === ''}
              onClick={() => void addCustomer()}
            >
              Завести
            </button>
            <button type="button" className="button--ghost" onClick={() => setAdding(false)}>
              Отмена
            </button>
          </div>
        </div>
      )}

      {error !== null && <p className="note note--error">{error}</p>}

      {page === null ? (
        error === null && <p className="note">Загружаем…</p>
      ) : page.items.length === 0 ? (
        <p className="note">
          {filtered ? 'Клиентов с таким именем или телефоном нет' : 'Клиентов пока нет'}
        </p>
      ) : (
        <>
          <div className="table-scroll">
            <table className="report">
              <thead>
                <tr>
                  <th>Тип</th>
                  <th>Имя или название</th>
                  <th>Телефон</th>
                  <th>Почта</th>
                  <th>Примечание</th>
                  <th>Заметка</th>
                  <th className="num">Баланс</th>
                  <th>ИНН</th>
                  <th>Организация</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((c) => (
                  <tr key={c.id} className="row--clickable" onClick={() => onOpen(c.id)}>
                    <td>{customerTypeName(c.customerType)}</td>
                    <td>{c.name ?? 'Без имени'}</td>
                    <td>{c.phone ?? ''}</td>
                    <td>{c.email ?? ''}</td>
                    <td>{c.publicNote ?? ''}</td>
                    <td>{c.note ?? ''}</td>
                    <td className="num">
                      <BalanceValue balance={c.balance} />
                    </td>
                    <td>{c.inn ?? ''}</td>
                    <td>{c.companyName ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {page.items.length < page.total && (
            <button type="button" className="button--ghost" onClick={() => setSize(size + PAGE)}>
              Показать ещё
            </button>
          )}

          <p className="note">Клиентов: {count(page.total)}</p>
        </>
      )}
    </section>
  );

  async function load(limit: number): Promise<void> {
    try {
      const loaded = await listCustomers(query, limit);
      if (!mounted.current) {
        return;
      }
      setPage(loaded);
      setError(null);
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError ? cause.message : 'Список не загрузился');
      }
    }
  }

  async function addCustomer(): Promise<void> {
    try {
      const created = await createCustomer(newName.trim(), newPhone.trim());
      if (!mounted.current) {
        return;
      }
      setAdding(false);
      setNewName('');
      setNewPhone('');
      onOpen(created.id);
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError ? cause.message : 'Клиент не заведён');
      }
    }
  }
}

/** «Юр. лицо» / «Физ. лицо» — их слова, а не внутренний код `COMPANY`/`PERSON`. */
function customerTypeName(customerType: string): string {
  return customerType === 'COMPANY' ? 'Юр. лицо' : 'Физ. лицо';
}

/** Положительный — чёрным, ноль — серым «0», отрицательный (долг) — красным. */
function BalanceValue({ balance }: { balance: number }) {
  if (balance === 0) {
    return <span className="muted">0</span>;
  }
  return (
    <span className={balance < 0 ? 'note--error' : undefined}>
      {balance.toLocaleString('ru-RU')} ₽
    </span>
  );
}

type CardTab = 'client' | 'deals' | 'returns' | 'payments';

function CustomerCard({
  customerId,
  role,
  company,
  memberId,
  canManage,
  onBack,
  onOpenDeal,
}: {
  customerId: number;
  role: string;
  company: string;
  memberId: number;
  canManage: boolean;
  onBack: () => void;
  onOpenDeal: (dealId: number) => void;
}) {
  const mounted = useMounted();
  const [customer, setCustomer] = useState<CustomerDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<CardTab>('client');

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId]);

  return (
    <section className="screen">
      <button type="button" className="button--ghost" onClick={onBack}>
        ← Клиенты
      </button>

      {/* Заголовок ждёт ответа сервера: «Без имени» — это настоящий клиент,
          заведённый в разговоре одним телефоном, и писать те же слова, пока
          имя ещё едет, значит утверждать про клиента то, чего не знаем. */}
      {customer !== null && <h2>{customer.name ?? 'Без имени'}</h2>}

      {error !== null && <p className="note note--error">{error}</p>}

      <div className="tabs">
        <button
          type="button"
          className={tab === 'client' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('client')}
        >
          Клиент
        </button>
        <button
          type="button"
          className={tab === 'deals' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('deals')}
        >
          Сделки
        </button>
        <button
          type="button"
          className={tab === 'returns' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('returns')}
        >
          Возвраты
        </button>
        <button
          type="button"
          className={tab === 'payments' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('payments')}
        >
          Платежи
        </button>
      </div>

      {customer === null ? (
        error === null && <p className="note">Загружаем…</p>
      ) : (
        <>
          {tab === 'client' && (
            <ClientTab
              customer={customer}
              canManage={canManage}
              role={role}
              company={company}
              memberId={memberId}
              onSaved={setCustomer}
            />
          )}
          {tab === 'deals' && <DealsTab customerId={customerId} onOpenDeal={onOpenDeal} />}
          {tab === 'returns' && <ReturnsTab customerId={customerId} onOpenDeal={onOpenDeal} />}
          {tab === 'payments' && <PaymentsTab customerId={customerId} />}
        </>
      )}
    </section>
  );

  async function load(): Promise<void> {
    try {
      const loaded = await customerOf(customerId);
      if (!mounted.current) {
        return;
      }
      setCustomer(loaded);
      setError(null);
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError ? cause.message : 'Клиент не загрузился');
      }
    }
  }
}

/**
 * Вкладка «Клиент»: форма правки слева, лицевой счёт справа.
 *
 * <p>Продавец карточку видит, но не правит: телефон в чужой сделке поправить
 * он не должен — поля показаны текстом, а не полем ввода.
 */
function ClientTab({
  customer,
  canManage,
  role,
  company,
  memberId,
  onSaved,
}: {
  customer: CustomerDetail;
  canManage: boolean;
  role: string;
  company: string;
  memberId: number;
  onSaved: (customer: CustomerDetail) => void;
}) {
  const mounted = useMounted();
  const [name, setName] = useState(customer.name ?? '');
  const [phone, setPhone] = useState(customer.phone ?? '');
  const [email, setEmail] = useState(customer.email ?? '');
  const [publicNote, setPublicNote] = useState(customer.publicNote ?? '');
  const [note, setNote] = useState(customer.note ?? '');
  const [isCompany, setIsCompany] = useState(customer.customerType === 'COMPANY');
  const [inn, setInn] = useState(customer.inn ?? '');
  const [companyName, setCompanyName] = useState(customer.companyName ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <div className="customer-layout">
      <div className="customer-main">
        <h3>Основная информация</h3>

        <Field label="Имя или название" value={name} editable={canManage} onChange={setName} />
        <Field label="Телефон" value={phone} editable={canManage} onChange={setPhone} />
        <Field
          label="Электронная почта"
          value={email}
          editable={canManage}
          onChange={setEmail}
        />
        <Field
          label="Примечание (видно клиентам)"
          value={publicNote}
          editable={canManage}
          onChange={setPublicNote}
          placeholder="Введите текст"
          caption="Выводится при печати накладных и других документов"
          multiline
        />
        <Field
          label="Заметка (видно только вам)"
          value={note}
          editable={canManage}
          onChange={setNote}
          placeholder="Введите текст"
          caption="Нигде не выводится. Доступна к просмотру только вам."
          multiline
        />

        <h3>Данные юридического лица</h3>
        <label className="field field--check">
          <input
            type="checkbox"
            checked={isCompany}
            disabled={!canManage}
            onChange={(e) => setIsCompany(e.target.checked)}
          />
          Юридическое лицо
        </label>
        {isCompany && (
          <>
            <Field label="ИНН" value={inn} editable={canManage} onChange={setInn} />
            <Field
              label="Название организации"
              value={companyName}
              editable={canManage}
              onChange={setCompanyName}
            />
          </>
        )}

        {error !== null && <p className="note note--error">{error}</p>}

        {canManage && (
          <button type="button" disabled={saving || name.trim() === ''} onClick={() => void save()}>
            {saving ? 'Сохраняем…' : 'Сохранить'}
          </button>
        )}
      </div>

      <div className="customer-account">
        <AccountPanel
          customerId={customer.id}
          role={role}
          company={company}
          memberId={memberId}
        />
      </div>
    </div>
  );

  async function save(): Promise<void> {
    setError(null);
    setSaving(true);
    try {
      const saved: CustomerDetail = await updateCustomer(customer.id, {
        name: name.trim(),
        phone: phone.trim(),
        email: email.trim(),
        publicNote,
        note,
        customerType: isCompany ? 'COMPANY' : 'PERSON',
        inn: isCompany ? inn.trim() : '',
        companyName: isCompany ? companyName.trim() : '',
      });
      if (mounted.current) {
        onSaved(saved);
      }
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError ? cause.message : 'Не удалось сохранить');
      }
    } finally {
      if (mounted.current) {
        setSaving(false);
      }
    }
  }
}

/** Поле формы: ввод для того, кто правит, текст — для того, кто только смотрит. */
function Field({
  label,
  value,
  editable,
  onChange,
  placeholder,
  caption,
  multiline = false,
}: {
  label: string;
  value: string;
  editable: boolean;
  onChange: (value: string) => void;
  placeholder?: string;
  caption?: string;
  multiline?: boolean;
}) {
  return (
    <label className="field">
      {label}
      {editable ? (
        multiline ? (
          <textarea
            rows={2}
            value={value}
            placeholder={placeholder}
            onChange={(e) => onChange(e.target.value)}
          />
        ) : (
          <input value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
        )
      ) : (
        <span className="muted">{value === '' ? '—' : value}</span>
      )}
      {caption !== undefined && <span className="muted">{caption}</span>}
    </label>
  );
}

/**
 * Лицевой счёт клиента: остаток и кнопка «Пополнить / Списать».
 *
 * <p>Открывает ту же пару операций, что кнопки «Положить» / «Выдать»
 * в {@code DealFinder} на экране продавца — тот же счёт, другое место входа.
 * Правка остатка со знаком и причиной — владельцу и менеджеру, как и там.
 *
 * <p>«В резерве» система не считает: у нас нет понятия денег, отложенных
 * под заказ, — резерв в системе только товарный ({@code Deal.reservedUntil}).
 * Прочерк здесь честнее нуля: ноль утверждал бы, что резерва нет, а мы
 * этого не знаем и знать не можем без такого понятия в модели.
 *
 * <p><b>Источник платежа спрашивается здесь так же, как в {@code DealFinder}
 * после задачи 0024.</b> «Положить» и «Выдать» создают настоящий платёж
 * ({@code new Payment(...)} в {@code SalesService}), и без способа владелец
 * не сведёт кассу: приняли переводом, вернули наличными — по журналу
 * это неотличимо. Правка остатка платежа не создаёт вовсе (деньги
 * не двигались), поэтому она идёт с {@code withSource = false} и выбранный
 * источник не запоминает.
 */
function AccountPanel({
  customerId,
  role,
  company,
  memberId,
}: {
  customerId: number;
  role: string;
  company: string;
  memberId: number;
}) {
  const mounted = useMounted();
  const [account, setAccount] = useState<CustomerAccount | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [cash, setCash] = useState('');
  const [sources, setSources] = useState<PaymentSourceEntry[]>([]);
  const [paymentSourceId, setPaymentSourceId] = useState<number | null>(null);
  const [fixing, setFixing] = useState(false);
  const [fixAmount, setFixAmount] = useState('');
  const [fixReason, setFixReason] = useState('');

  const activeSources = sources.filter((s) => !s.archived);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId]);

  useEffect(() => {
    void paymentSources()
      .then((loaded) => {
        if (!mounted.current) {
          return;
        }
        setSources(loaded);
        setPaymentSourceId(defaultPaymentSource(loaded, company, memberId));
      })
      // Молчаливый отказ намеренно: без справочника операция идёт как раньше,
      // с `paymentSourceId: null`, и красная строка про источники посреди
      // разбора долга сказала бы не о том, зачем сюда пришли.
      .catch(() => {
        if (mounted.current) {
          setSources([]);
        }
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [company, memberId]);

  return (
    <div className="card">
      <h4>Лицевой счёт</h4>

      {error !== null && <p className="note note--error">{error}</p>}

      <p className="note">На счету {account === null ? '…' : `${account.balance.toLocaleString('ru-RU')} ₽`}</p>
      <p className="note muted">В резерве —</p>

      {!acting ? (
        <button type="button" className="button--ghost" onClick={() => setActing(true)}>
          Пополнить / Списать
        </button>
      ) : (
        <div className="row">
          <input
            type="number"
            inputMode="numeric"
            value={cash}
            placeholder="сумма"
            onChange={(e) => setCash(e.target.value)}
          />
          {/* Списка нет вовсе, если источников не заведено ни одного:
              операция работает как раньше, без способа. */}
          {activeSources.length > 0 && (
            <select
              aria-label="Источник платежа"
              value={paymentSourceId ?? ''}
              onChange={(e) =>
                setPaymentSourceId(e.target.value === '' ? null : Number(e.target.value))
              }
            >
              <option value="">не указан</option>
              {activeSources.map((source) => (
                <option key={source.id} value={source.id}>
                  {source.name}
                </option>
              ))}
            </select>
          )}
          <button
            type="button"
            disabled={cash.trim() === ''}
            onClick={() => void money(() =>
              topUpAccount(customerId, cash.trim(), paymentSourceId))}
          >
            Положить
          </button>
          <button
            type="button"
            className="button--ghost"
            disabled={cash.trim() === '' || account === null || account.balance <= 0}
            onClick={() => void money(() =>
              withdrawFromAccount(customerId, cash.trim(), paymentSourceId))}
          >
            Выдать
          </button>
        </div>
      )}

      {['OWNER', 'MANAGER'].includes(role) && (
        fixing ? (
          <div className="row">
            <input
              type="number"
              inputMode="numeric"
              value={fixAmount}
              placeholder="+ или −"
              onChange={(e) => setFixAmount(e.target.value)}
            />
            <input
              value={fixReason}
              placeholder="почему правим"
              onChange={(e) => setFixReason(e.target.value)}
            />
            <button
              type="button"
              disabled={fixAmount.trim() === '' || fixReason.trim() === ''}
              onClick={() => void money(async () => {
                await correctAccount(customerId, fixAmount.trim(), fixReason.trim());
                setFixing(false);
                setFixAmount('');
                setFixReason('');
              }, false)}
            >
              Поправить
            </button>
            <button type="button" className="button--ghost" onClick={() => setFixing(false)}>
              Отмена
            </button>
          </div>
        ) : (
          <button type="button" className="button--ghost" onClick={() => setFixing(true)}>
            Поправить остаток
          </button>
        )
      )}
    </div>
  );

  async function load(): Promise<void> {
    try {
      const loaded = await accountOf(customerId);
      if (!mounted.current) {
        return;
      }
      setAccount(loaded);
      setError(null);
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError ? cause.message : 'Счёт не загрузился');
      }
    }
  }

  /**
   * @param withSource операция создала платёж, и способ у него есть. У правки
   *                   остатка платежа нет вовсе (деньги не двигались), и
   *                   запоминать при ней выбранный источник значит подставлять
   *                   продавцу умолчание, которым он не платил, — то же
   *                   правило, что в `DealFinder`.
   */
  async function money(action: () => Promise<unknown>, withSource = true): Promise<void> {
    try {
      await action();
      if (withSource && paymentSourceId !== null) {
        rememberPaymentSource(company, memberId, paymentSourceId);
      }
      if (!mounted.current) {
        return;
      }
      setCash('');
      await load();
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError ? cause.message : 'Операция по счёту не прошла');
      }
    }
  }
}

/** Вкладка «Сделки» — то же, что сегодня показывает `DealFinder`, только полнее. */
function DealsTab({
  customerId,
  onOpenDeal,
}: {
  customerId: number;
  onOpenDeal: (dealId: number) => void;
}) {
  const mounted = useMounted();
  const [deals, setDeals] = useState<Deal[] | null>(null);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void Promise.all([dealsOf(customerId), listWarehouses()])
      .then(([loaded, houses]) => {
        if (!mounted.current) {
          return;
        }
        setDeals(loaded);
        setWarehouses(houses);
        setError(null);
      })
      .catch((cause) => {
        if (!mounted.current) {
          return;
        }
        setDeals([]);
        setError(cause instanceof ApiError ? cause.message : 'Сделки не загрузились');
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId]);

  if (deals === null) {
    return error === null ? <p className="note">Загружаем…</p> : <p className="note note--error">{error}</p>;
  }

  if (deals.length === 0) {
    return <p className="note">У этого клиента сделок нет</p>;
  }

  const warehouseName = (id: number | null): string =>
    id === null ? '' : warehouses.find((w) => w.id === id)?.name ?? '';

  const total = deals.reduce((sum, d) => sum + Number(d.totalAmount), 0);

  return (
    <>
      {error !== null && <p className="note note--error">{error}</p>}
      <div className="table-scroll">
        <table className="report">
          <thead>
            <tr>
              <th>Номер/дата</th>
              <th>Склад выдачи</th>
              <th className="num">Сумма</th>
              <th className="num">Оплачено</th>
              <th>Статус</th>
              <th>Ответственный</th>
            </tr>
          </thead>
          <tbody>
            {deals.map((d) => (
              <tr key={d.id} className="row--clickable" onClick={() => onOpenDeal(d.id)}>
                <td>
                  <strong>№{d.number ?? d.id}</strong>
                  <div className="muted">{new Date(d.createdAt).toLocaleDateString('ru-RU')}</div>
                </td>
                <td>{warehouseName(d.warehouseId)}</td>
                <td className="num">{Number(d.totalAmount).toLocaleString('ru-RU')} ₽</td>
                <td className="num">{Number(d.paidAmount).toLocaleString('ru-RU')} ₽</td>
                <td>{dealStatusName(d.status)}</td>
                <td>{d.managerName ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="note">
        Сделок: {count(deals.length)} на сумму {count(Math.round(total))}
      </p>
    </>
  );
}

function dealStatusName(status: string): string {
  switch (status) {
    case 'DRAFT': return 'Черновик';
    case 'RESERVED': return 'Отложена';
    case 'ISSUED': return 'Выдана';
    case 'CANCELLED': return 'Отменена';
    default: return status;
  }
}

/**
 * Вкладка «Возвраты» — реестр задачи 0021, отобранный по этому клиенту.
 *
 * <p>Те же колонки, только без «Клиент»: он и так открыт в заголовке карточки.
 */
function ReturnsTab({
  customerId,
  onOpenDeal,
}: {
  customerId: number;
  onOpenDeal: (dealId: number) => void;
}) {
  const mounted = useMounted();
  const [size, setSize] = useState(PAGE);
  const [page, setPage] = useState<ReturnsPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void load(size);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId, size]);

  if (page === null) {
    return error === null ? <p className="note">Загружаем…</p> : <p className="note note--error">{error}</p>;
  }

  if (page.items.length === 0) {
    return <p className="note">Возвратов у этого клиента не было</p>;
  }

  return (
    <>
      {error !== null && <p className="note note--error">{error}</p>}
      <div className="table-scroll">
        <table className="report">
          <thead>
            <tr>
              <th>Номер/дата</th>
              <th>По сделке</th>
              <th>Склад возврата</th>
              <th className="num">Сумма</th>
              <th>Статус</th>
              <th>Причина</th>
            </tr>
          </thead>
          <tbody>
            {page.items.map((row) => (
              <ReturnRow key={row.id} row={row} onOpenDeal={onOpenDeal} />
            ))}
          </tbody>
        </table>
      </div>

      {page.items.length < page.total && (
        <button type="button" className="button--ghost" onClick={() => setSize(size + PAGE)}>
          Показать ещё
        </button>
      )}

      <p className="note">
        Возвратов: {count(page.total)} на сумму {count(Math.round(Number(page.totalAmount)))}
      </p>
    </>
  );

  async function load(limit: number): Promise<void> {
    try {
      const loaded = await listReturns('', '', '', limit, customerId);
      if (!mounted.current) {
        return;
      }
      setPage(loaded);
      setError(null);
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError ? cause.message : 'Возвраты не загрузились');
      }
    }
  }
}

function ReturnRow({
  row,
  onOpenDeal,
}: {
  row: ReturnListRow;
  onOpenDeal: (dealId: number) => void;
}) {
  return (
    <tr className={row.status === 'CANCELLED' ? 'muted' : undefined}>
      <td>
        <strong>{row.number ?? row.id}</strong>
        <div className="muted">{new Date(row.createdAt).toLocaleDateString('ru-RU')}</div>
      </td>
      <td>
        <button type="button" className="button--ghost" onClick={() => onOpenDeal(row.dealId)}>
          {row.dealNumber ?? row.dealId}
        </button>
      </td>
      <td>{row.restocked ? row.warehouseName ?? '—' : 'Брак, на склад не ставили'}</td>
      <td className="num">{Number(row.amount).toLocaleString('ru-RU')} ₽</td>
      <td>{row.status === 'DONE' ? 'Выполнен' : 'Отменён'}</td>
      <td>{row.reason ?? ''}</td>
    </tr>
  );
}

type PaymentsSubTab = 'payments' | 'movements';
type PaymentsFilter = 'ALL' | 'IN' | 'OUT';

/** Вкладка «Платежи»: подменю «Платежи» / «Движения по счёту». */
function PaymentsTab({ customerId }: { customerId: number }) {
  const [sub, setSub] = useState<PaymentsSubTab>('payments');

  return (
    <div className="customer-layout">
      <div className="tabs tabs--vertical">
        <button
          type="button"
          className={sub === 'payments' ? 'tab tab--active' : 'tab'}
          onClick={() => setSub('payments')}
        >
          Платежи
        </button>
        <button
          type="button"
          className={sub === 'movements' ? 'tab tab--active' : 'tab'}
          onClick={() => setSub('movements')}
        >
          Движения по счёту
        </button>
      </div>

      <div className="customer-main">
        {sub === 'payments' ? (
          <PaymentsList customerId={customerId} />
        ) : (
          <AccountMovements customerId={customerId} />
        )}
      </div>
    </div>
  );
}

function PaymentsList({ customerId }: { customerId: number }) {
  const mounted = useMounted();
  const [rows, setRows] = useState<PaymentRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<PaymentsFilter>('ALL');

  useEffect(() => {
    void customerPayments(customerId)
      .then((loaded) => {
        if (!mounted.current) {
          return;
        }
        setRows(loaded);
        setError(null);
      })
      .catch((cause) => {
        if (!mounted.current) {
          return;
        }
        setRows([]);
        setError(cause instanceof ApiError ? cause.message : 'Платежи не загрузились');
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId]);

  if (rows === null) {
    return error === null ? <p className="note">Загружаем…</p> : <p className="note note--error">{error}</p>;
  }

  const shown = rows.filter((r) => filter === 'ALL' || r.direction === filter);
  const total = shown.reduce((sum, r) => sum + Number(r.amount), 0);

  return (
    <>
      {error !== null && <p className="note note--error">{error}</p>}

      <div className="tabs">
        <button
          type="button"
          className={filter === 'ALL' ? 'tab tab--active' : 'tab'}
          onClick={() => setFilter('ALL')}
        >
          Все
        </button>
        <button
          type="button"
          className={filter === 'IN' ? 'tab tab--active' : 'tab'}
          onClick={() => setFilter('IN')}
        >
          Приходные
        </button>
        <button
          type="button"
          className={filter === 'OUT' ? 'tab tab--active' : 'tab'}
          onClick={() => setFilter('OUT')}
        >
          Расходные
        </button>
      </div>

      {shown.length === 0 ? (
        <p className="note">Платежей нет</p>
      ) : (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Дата</th>
                <th>По сделке</th>
                <th className="num">Сумма</th>
                <th>Направление</th>
                <th>Комментарий</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row) => (
                <tr key={row.id}>
                  <td>{new Date(row.paidAt).toLocaleDateString('ru-RU')}</td>
                  <td>{row.dealNumber ?? ''}</td>
                  <td className="num">{Number(row.amount).toLocaleString('ru-RU')} ₽</td>
                  <td>{row.direction === 'IN' ? 'Приход' : 'Расход'}</td>
                  <td>{row.comment ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="note">
        Платежей: {count(shown.length)} на сумму {count(Math.round(total))}
      </p>
    </>
  );
}

/**
 * «Движения по счёту» — журнал целиком, а не восемь последних.
 *
 * <p>{@code SellerScreen} режет ту же ленту {@code slice(0, 8)}: она там
 * подсказка посреди разговора, а не разбор счёта. Здесь показывают всё,
 * что вернул сервер (сервер тоже не режет — счёт клиента не дорастает
 * до тысяч строк).
 */
function AccountMovements({ customerId }: { customerId: number }) {
  const mounted = useMounted();
  const [account, setAccount] = useState<CustomerAccount | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void accountOf(customerId)
      .then((loaded) => {
        if (!mounted.current) {
          return;
        }
        setAccount(loaded);
        setError(null);
      })
      .catch((cause) => {
        if (!mounted.current) {
          return;
        }
        setAccount(null);
        setError(cause instanceof ApiError ? cause.message : 'Движения не загрузились');
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId]);

  if (account === null) {
    return error === null ? <p className="note">Загружаем…</p> : <p className="note note--error">{error}</p>;
  }

  if (account.entries.length === 0) {
    return <p className="note">Движений по счёту не было</p>;
  }

  return (
    <>
      {error !== null && <p className="note note--error">{error}</p>}
      <div className="table-scroll">
        <table className="report">
          <thead>
            <tr>
              <th>Дата</th>
              <th>Тип операции</th>
              <th>По сделке</th>
              <th className="num">Сумма</th>
              <th>Кто</th>
            </tr>
          </thead>
          <tbody>
            {account.entries.map((entry) => (
              <tr key={entry.id}>
                <td>{new Date(entry.createdAt).toLocaleDateString('ru-RU')}</td>
                <td>{entryTypeName(entry.entryType)}</td>
                <td>{entry.dealNumber ?? ''}</td>
                <td className="num">
                  {entry.signedAmount > 0 ? '+' : ''}
                  {entry.signedAmount.toLocaleString('ru-RU')} ₽
                </td>
                <td>{entry.authorName ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="note">Операций: {count(account.entries.length)}</p>
    </>
  );
}

/**
 * Слова операции — те же, что уже используются в `DealFinder` («пополнение»,
 * «выдача» …), только с заглавной буквы: там строка сливается в предложение,
 * здесь это отдельная ячейка таблицы.
 */
function entryTypeName(type: string): string {
  switch (type) {
    case 'TOP_UP': return 'Пополнение';
    case 'WITHDRAW': return 'Выдача';
    case 'DEAL_PAYMENT': return 'Оплата сделки';
    case 'DEAL_REFUND': return 'Возврат по сделке';
    default: return 'Правка';
  }
}
