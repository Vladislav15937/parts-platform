import { useState } from 'react';
import { ApiError } from '../api/client';
import {
  basketTotal,
  cancelDeal,
  createCustomer,
  createDeal,
  dealsOf,
  issueDeal,
  payDeal,
  roomFor,
  searchCustomers,
  searchStock,
} from '../sales/sales';
import type { BasketLine, Customer, Deal, StockRow } from '../sales/sales';

/**
 * Рабочее место продавца.
 *
 * <p>Один экран под один разговор: звонит клиент, продавец ищет деталь,
 * называет цену и наличие, откладывает. Переключаться между разделами
 * посреди разговора нечем — трубка в руке.
 *
 * <p><b>Ничего не кэшируется и не ставится в очередь.</b> Остаток из кэша —
 * это обещание детали, которой нет; сделка из очереди — резерв, который
 * ничего не резервирует. Нет связи — продавец видит ошибку, а не мнимый успех.
 */
interface Props {
  canSell: boolean;
}

export function SellerScreen({ canSell }: Props) {
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<StockRow[]>([]);
  const [searching, setSearching] = useState(false);
  const [lines, setLines] = useState<BasketLine[]>([]);
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [deal, setDeal] = useState<Deal | null>(null);
  const [error, setError] = useState<string | null>(null);

  return (
    <section className="card">
      <h2>Продажа</h2>

      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          void find();
        }}
      >
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="фара камри, бампер приора"
          autoCapitalize="none"
        />
        <button type="submit" disabled={searching || query.trim() === ''}>
          {searching ? '…' : 'Найти'}
        </button>
      </form>

      {error !== null && <p className="note note--error">{error}</p>}

      {rows.length > 0 && (
        <ul className="stock-list">
          {rows.map((row) => (
            <StockItem
              key={`${row.partId}-${row.warehouseId}`}
              row={row}
              room={roomFor(row, lines)}
              canSell={canSell}
              onAdd={() => add(row)}
            />
          ))}
        </ul>
      )}

      {lines.length > 0 && (
        <>
          <hr />
          <h3>В сделку</h3>
          <ul className="stock-list">
            {lines.map((line, index) => (
              <li key={index} className="stock-row">
                <div>
                  {line.row.title}
                  <span className="muted">
                    {' '}
                    · {line.quantity} шт · {line.row.warehouseName}
                  </span>
                </div>
                <div className="row">
                  <input
                    type="number"
                    inputMode="numeric"
                    value={line.price}
                    // Цену правят в разговоре: скидка постоянному клиенту —
                    // обычная часть сделки, а не исключение.
                    onChange={(e) => changePrice(index, e.target.value)}
                  />
                  <button
                    type="button"
                    className="button--ghost"
                    onClick={() => setLines(lines.filter((_, i) => i !== index))}
                  >
                    убрать
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <p className="note">Итого: {basketTotal(lines).toLocaleString('ru-RU')} ₽</p>

          <CustomerPicker customer={customer} onPick={setCustomer} onError={setError} />

          <button
            type="button"
            disabled={customer === null || !canSell}
            onClick={() => void submit()}
          >
            Оформить и отложить
          </button>
          {!canSell && <p className="note">Ваша роль не позволяет продавать</p>}
        </>
      )}

      {deal !== null && (
        <DealCard
          deal={deal}
          canSell={canSell}
          onChanged={setDeal}
          onError={setError}
        />
      )}
    </section>
  );

  async function find(): Promise<void> {
    setSearching(true);
    setError(null);
    try {
      setRows(await searchStock(query.trim()));
    } catch (cause) {
      setRows([]);
      setError(describe(cause, 'Не удалось выполнить поиск'));
    } finally {
      setSearching(false);
    }
  }

  function add(row: StockRow): void {
    const room = roomFor(row, lines);
    if (room < 1) {
      return;
    }
    setLines([...lines, { row, quantity: 1, price: row.price ?? '' }]);
  }

  function changePrice(index: number, price: string): void {
    setLines(lines.map((line, i) => (i === index ? { ...line, price } : line)));
  }

  async function submit(): Promise<void> {
    if (customer === null) {
      return;
    }
    setError(null);
    try {
      const created = await createDeal(customer.id, lines);
      setDeal(created);
      setLines([]);
      // Остаток изменился — показанный список уже врёт.
      setRows([]);
    } catch (cause) {
      setError(describe(cause, 'Сделка не оформлена'));
    }
  }
}

/**
 * Строка находки.
 *
 * <p>Отложенное показывается отдельно от свободного, а не вычитается молча:
 * продавцу нужно ответить «есть, но отложена до завтра», иначе он скажет
 * «нет», и клиент уедет к соседям за деталью, которая освободится к вечеру.
 */
function StockItem({
  row,
  room,
  canSell,
  onAdd,
}: {
  row: StockRow;
  room: number;
  canSell: boolean;
  onAdd: () => void;
}) {
  const reserved = Number(row.qtyReserved);

  return (
    <li className="stock-row">
      <div>
        <strong>{row.title}</strong>
        {row.publicCode !== null && <span className="muted"> · {row.publicCode}</span>}
        <div className="muted">
          {row.warehouseName}
          {row.cellCode !== null && ` · ячейка ${row.cellCode}`} · свободно {row.qtyAvailable}
          {reserved > 0 && ` · отложено ${row.qtyReserved}`}
        </div>
      </div>
      <div className="row">
        <strong>{row.price === null ? '—' : `${Number(row.price).toLocaleString('ru-RU')} ₽`}</strong>
        <button type="button" disabled={room < 1 || !canSell} onClick={onAdd}>
          {room < 1 ? 'нет свободных' : 'в сделку'}
        </button>
      </div>
    </li>
  );
}

/** Найти позвонившего по телефону или завести его прямо в разговоре. */
function CustomerPicker({
  customer,
  onPick,
  onError,
}: {
  customer: Customer | null;
  onPick: (customer: Customer) => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<Customer[]>([]);

  if (customer !== null) {
    return (
      <p className="note">
        Клиент: {customer.name ?? 'без имени'}
        {customer.phone !== null && ` · ${customer.phone}`}
      </p>
    );
  }

  return (
    <div>
      <label>
        Клиент
        <input
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            void lookup(e.target.value);
          }}
          placeholder="имя или телефон"
        />
      </label>

      {found.length > 0 && (
        <ul className="suggestions">
          {found.map((c) => (
            <li key={c.id}>
              <button type="button" className="button--ghost" onClick={() => onPick(c)}>
                {c.name ?? 'без имени'}
                {c.phone !== null && <span className="muted"> · {c.phone}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      {query.trim() !== '' && found.length === 0 && (
        <button type="button" className="button--ghost" onClick={() => void addNew()}>
          Завести клиента «{query.trim()}»
        </button>
      )}
    </div>
  );

  async function lookup(term: string): Promise<void> {
    if (term.trim().length < 2) {
      setFound([]);
      return;
    }
    try {
      setFound(await searchCustomers(term.trim()));
    } catch {
      // Поиск клиента — не повод рушить экран: продавец заведёт нового.
      setFound([]);
    }
  }

  async function addNew(): Promise<void> {
    const term = query.trim();
    // Строка из одних цифр — это телефон, а не имя. Продавец набирает то,
    // что услышал, и раскладывать это по полям не должен.
    const digits = term.replace(/\D/g, '');
    const isPhone = digits.length >= 6 && digits.length === term.replace(/[\s+()-]/g, '').length;

    try {
      onPick(await createCustomer(isPhone ? 'Без имени' : term, isPhone ? term : ''));
    } catch (cause) {
      onError(describe(cause, 'Клиент не заведён'));
    }
  }
}

/** Оформленная сделка: что с ней можно сделать дальше. */
function DealCard({
  deal,
  canSell,
  onChanged,
  onError,
}: {
  deal: Deal;
  canSell: boolean;
  onChanged: (deal: Deal) => void;
  onError: (message: string) => void;
}) {
  const [amount, setAmount] = useState('');

  return (
    <>
      <hr />
      <h3>
        Сделка №{deal.number ?? deal.id} · {statusName(deal.status)}
      </h3>
      <p className="note">
        Сумма {Number(deal.totalAmount).toLocaleString('ru-RU')} ₽ · оплачено{' '}
        {Number(deal.paidAmount).toLocaleString('ru-RU')} ₽ · долг{' '}
        {Number(deal.debt).toLocaleString('ru-RU')} ₽
      </p>

      <div className="row">
        <input
          type="number"
          inputMode="numeric"
          value={amount}
          placeholder="принять оплату"
          onChange={(e) => setAmount(e.target.value)}
        />
        <button
          type="button"
          disabled={!canSell || amount.trim() === ''}
          onClick={() => void act(() => payDeal(deal.id, amount.trim()))}
        >
          Оплата
        </button>
      </div>

      <div className="row">
        <button
          type="button"
          disabled={!canSell || deal.status !== 'RESERVED'}
          onClick={() => void act(() => issueDeal(deal.id))}
        >
          Выдать
        </button>
        <button
          type="button"
          className="button--ghost"
          // После выдачи отменять нечего: деталь у клиента, деньги в кассе.
          // Это возврат, а он оформляется отдельным документом.
          disabled={!canSell || deal.status !== 'RESERVED'}
          onClick={() => void act(() => cancelDeal(deal.id, 'отменена продавцом'))}
        >
          Отменить
        </button>
      </div>
    </>
  );

  async function act(operation: () => Promise<unknown>): Promise<void> {
    try {
      await operation();
      // Перечитываем со стороны сервера, а не собираем состояние сами:
      // оплата меняет и долг, и лицевой счёт, и считает это сервер.
      const deals = await dealsOf(deal.customerId);
      const fresh = deals.find((d) => d.id === deal.id);
      if (fresh !== undefined) {
        onChanged(fresh);
      }
    } catch (cause) {
      onError(describe(cause, 'Операция не выполнена'));
    }
  }
}

function statusName(status: string): string {
  const names: Record<string, string> = {
    DRAFT: 'черновик',
    RESERVED: 'отложена',
    ISSUED: 'выдана',
    CANCELLED: 'отменена',
    RETURNED: 'возвращена',
  };
  return names[status] ?? status.toLowerCase();
}

/**
 * Человеческая причина отказа.
 *
 * <p>Отдельно про отсутствие связи: для продавца это не «ошибка сервера»,
 * а «ничего не произошло, повторите» — и сказать это надо прямо, иначе он
 * решит, что сделка оформилась.
 */
function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. Сделка не оформлена — повторите, когда связь появится.';
    }
    if (cause.status === 403) {
      return 'Недостаточно прав для этой операции';
    }
    return cause.message;
  }
  return fallback;
}
