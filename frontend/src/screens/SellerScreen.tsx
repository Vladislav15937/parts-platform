import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import {
  basketTotal,
  cancelDeal,
  createCustomer,
  createDeal,
  historyOf,
  shareDeal,
  receiveOrder,
  serviceKinds,
  dealSources,
  dealsOf,
  issueDeal,
  payDeal,
  registerReturn,
  returnable,
  returnsOf,
  roomFor,
  searchCustomers,
  searchStock,
  transferable,
  transferItems,
} from '../sales/sales';
import type {
  HistoryEntry,
  DealSource as DealSourceRow,
  ServiceLine,
  BasketLine,
  Customer,
  Deal,
  DealItem,
  ReturnDoc,
  ReturnLine,
  StockRow,
} from '../sales/sales';

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
  // Заказ с площадки оформляется здесь же, а не отдельным экраном с той же
  // корзиной: продавец уже нашёл детали и выбрал клиента, и второй такой же
  // экран отличался бы двумя полями.
  // Услуги подтягиваются один раз: справочник из двух строк, и меняется
  // он с релизом, а не в течение дня.
  const [services, setServices] = useState<ServiceLine[]>([]);
  // Откуда пришла продажа. Спрашивается при каждой сделке, а не только
  // у заказа с площадки: отчёт по каналам, в котором половина выручки
  // без источника, не отвечает ни на один вопрос.
  const [sources, setSources] = useState<DealSourceRow[]>([]);
  const [sourceId, setSourceId] = useState('');
  const [marketplace, setMarketplace] = useState('');
  const [orderNo, setOrderNo] = useState('');
  const [note, setNote] = useState('');

  useEffect(() => {
    void serviceKinds()
      .then((kinds) => setServices(kinds.map((kind) => ({ kind, price: '' }))))
      // Молча: без справочника услуг продавать всё ещё можно, а красный
      // текст на весь экран из-за доставки — это про неверные приоритеты.
      .catch(() => setServices([]));
    void dealSources()
      .then(setSources)
      .catch(() => setSources([]));
  }, []);
  const [deal, setDeal] = useState<Deal | null>(null);
  // Возврат и перенос случаются не в тот же разговор, что продажа: клиент
  // приезжает через неделю. Без поиска по клиенту до его сделки не добраться.
  const [finding, setFinding] = useState(false);
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

      <button
        type="button"
        className="button--ghost"
        onClick={() => {
          setFinding(!finding);
          setError(null);
        }}
      >
        {finding ? 'Скрыть поиск сделки' : 'Найти сделку клиента'}
      </button>

      {error !== null && <p className="note note--error">{error}</p>}

      {finding && (
        <DealFinder
          onPick={(found) => {
            setDeal(found);
            setFinding(false);
            // Корзина от прежнего разговора к чужой сделке отношения не имеет.
            setLines([]);
            setRows([]);
          }}
          onError={setError}
        />
      )}

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
                <div className="stock-info">
                  {line.row.title}
                  <span className="muted">
                    {' '}
                    · {line.quantity} шт · {line.row.warehouseName}
                  </span>
                </div>
                <div className="stock-action">
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
          {services.length > 0 && (
            <div className="services">
              {services.map((line, index) => (
                <label key={line.kind.id} className="field">
                  {line.kind.name}, ₽
                  <input
                    inputMode="decimal"
                    value={line.price}
                    placeholder={line.kind.price ?? ''}
                    onChange={(e) =>
                      setServices(services.map((s, i) =>
                        i === index ? { ...s, price: e.target.value } : s))
                    }
                  />
                </label>
              ))}
            </div>
          )}

          <p className="note">
            Итого: {basketTotal(lines, services).toLocaleString('ru-RU')} ₽
          </p>

          <CustomerPicker customer={customer} onPick={setCustomer} onError={setError} />

          {sources.length > 0 && (
            <label className="field">
              Откуда пришла продажа
              <select value={sourceId} onChange={(e) => setSourceId(e.target.value)}>
                <option value="">не указан</option>
                {sources.map((source) => (
                  <option key={source.id} value={source.id}>
                    {source.name}
                  </option>
                ))}
              </select>
            </label>
          )}

          <label className="field">
            Заказ с площадки
            <select
              value={marketplace}
              onChange={(e) => setMarketplace(e.target.value)}
            >
              <option value="">нет, обычная продажа</option>
              <option value="DROM">Дром</option>
              <option value="AVITO">Авито</option>
            </select>
          </label>

          {marketplace !== '' && (
            <>
              <label className="field">
                Номер заказа у площадки
                <input
                  value={orderNo}
                  onChange={(e) => setOrderNo(e.target.value)}
                  placeholder="301-516-98"
                />
              </label>
              <label className="field">
                Доставка
                <input
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  placeholder="ТК СДЭК, адрес и получатель"
                />
              </label>
              <p className="note">
                Заказ уже оплачен покупателем. Ответить площадке нужно
                в её срок — иначе деньги вернутся ему.
              </p>
            </>
          )}

          <button
            type="button"
            disabled={customer === null || !canSell
              || (marketplace !== '' && orderNo.trim() === '')}
            onClick={() => void submit()}
          >
            {marketplace === '' ? 'Оформить и отложить' : 'Принять заказ'}
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
      if (marketplace !== '') {
        const result = await receiveOrder(
          marketplace, orderNo.trim(), customer.id, lines, null, note, services,
          sourceId === '' ? null : Number(sourceId));
        setDeal(result.deal);
        if (result.replayed) {
          // Не ошибка: продавец мог завести заказ дважды. Правильный ответ —
          // «этот заказ уже заведён, вот он», а не красный текст про отказ.
          setError('Этот заказ уже был заведён — открыта прежняя сделка');
        } else if (result.missing.length > 0) {
          // Товара нет: подтверждать площадке нечего, и узнать об этом
          // продавец должен сейчас, а не когда придёт время отгружать.
          setError('Обеспечить нечем: ' + result.missing.join('; '));
        }
        setMarketplace('');
        setOrderNo('');
        setNote('');
      } else {
        setDeal(await createDeal(customer.id, lines, services,
          sourceId === '' ? null : Number(sourceId)));
      }
      setLines([]);
      setServices(services.map((line) => ({ ...line, price: '' })));
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
      <div className="stock-info">
        <strong>{row.title}</strong>
        {row.publicCode !== null && <span className="muted"> · {row.publicCode}</span>}
        <div className="muted">
          {row.warehouseName}
          {row.cellCode !== null && ` · ячейка ${row.cellCode}`} · свободно {row.qtyAvailable}
          {reserved > 0 && ` · отложено ${row.qtyReserved}`}
        </div>
      </div>
      <div className="stock-action">
        <strong className="stock-price">
          {row.price === null ? '—' : `${Number(row.price).toLocaleString('ru-RU')} ₽`}
        </strong>
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

/**
 * Поиск сделки клиента.
 *
 * <p>Возврат и перенос происходят не в тот разговор, в который продали:
 * клиент приезжает через неделю с «не подошло» или забирает половину сейчас,
 * а половину потом. Дверь в сделку — клиент, а не номер документа: номер
 * приезжающий не помнит, телефон называет сразу.
 */
function DealFinder({
  onPick,
  onError,
}: {
  onPick: (deal: Deal) => void;
  onError: (message: string) => void;
}) {
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [deals, setDeals] = useState<Deal[] | null>(null);

  return (
    <div className="finder">
      <CustomerPicker
        customer={customer}
        onPick={(picked) => {
          setCustomer(picked);
          void load(picked);
        }}
        onError={onError}
      />

      {deals !== null && deals.length === 0 && (
        <p className="note">У этого клиента сделок нет</p>
      )}

      {deals !== null && deals.length > 0 && (
        <ul className="suggestions">
          {deals.map((d) => (
            <li key={d.id}>
              <button type="button" className="button--ghost" onClick={() => onPick(d)}>
                №{d.number ?? d.id} · {statusName(d.status)}
                <span className="muted">
                  {' '}
                  · {Number(d.totalAmount).toLocaleString('ru-RU')} ₽ ·{' '}
                  {new Date(d.createdAt).toLocaleDateString('ru-RU')}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );

  async function load(picked: Customer): Promise<void> {
    try {
      setDeals(await dealsOf(picked.id));
    } catch (cause) {
      setDeals([]);
      onError(describe(cause, 'Сделки не загрузились'));
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
  const [picked, setPicked] = useState<number[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [docs, setDocs] = useState<ReturnDoc[]>([]);
  // История подтягивается по раскрытию, а не с карточкой: на неё смотрят
  // при разборе спора, а не при каждой продаже.
  const [history, setHistory] = useState<HistoryEntry[] | null>(null);
  const [share, setShare] = useState<string | null>(null);

  const reserved = transferable(deal);
  const issued = returnable(deal);
  // Выбирать можно только то, с чем в этом состоянии вообще что-то делают:
  // отложенное переносят, выданное возвращают.
  const selectable = reserved.length > 0 ? reserved : issued;
  // Черновик тоже открыт: так выглядит сделка, в которую перенесли позиции
  // из старой версии сервера. Не дать её выдать — оставить продавца
  // с товаром, который обещан клиенту и никуда не денется.
  const open = deal.status === 'RESERVED' || deal.status === 'DRAFT';
  const chosen = selectable.filter((item) => picked.includes(item.id));

  useEffect(() => {
    // Прежние возвраты по сделке: без них продавец оформит второй возврат
    // на ту же деталь и узнает об отказе сервера вместо ответа клиенту.
    void returnsOf(deal.id).then(setDocs).catch(() => setDocs([]));
    // История — про прежнюю сделку: оставшись на экране, она приписала бы
    // этой сделке чужие действия.
    setHistory(null);
    setShare(null);
    // Сообщение и отметки — про прежнюю сделку. Оставшись на экране чужой,
    // «Возврат №1 оформлен» читается как возврат по ней.
    setNotice(null);
    setPicked([]);
  }, [deal.id]);

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
      {notice !== null && <p className="note">{notice}</p>}

      <ul className="stock-list">
        {deal.items.map((item) => (
          <li key={item.id} className="stock-row">
            <label className="stock-info pick">
              {selectable.some((s) => s.id === item.id) && (
                <input
                  type="checkbox"
                  checked={picked.includes(item.id)}
                  onChange={(e) =>
                    setPicked(
                      e.target.checked
                        ? [...picked, item.id]
                        : picked.filter((id) => id !== item.id),
                    )
                  }
                />
              )}{' '}
              {item.title ?? `деталь ${item.partId}`}
              <span className="muted">
                {' '}
                · {Number(item.quantity)} шт · {itemStatusName(item.status)}
              </span>
            </label>
            <div className="stock-action">
              <strong className="stock-price">
                {Number(item.price).toLocaleString('ru-RU')} ₽
              </strong>
            </div>
          </li>
        ))}
      </ul>

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
          disabled={!canSell || !open}
          onClick={() => void act(() => issueDeal(deal.id))}
        >
          Выдать
        </button>
        <button
          type="button"
          className="button--ghost"
          // После выдачи отменять нечего: деталь у клиента, деньги в кассе.
          // Это возврат, а он оформляется отдельным документом.
          disabled={!canSell || !open}
          onClick={() => void act(() => cancelDeal(deal.id, 'отменена продавцом'))}
        >
          Отменить
        </button>
      </div>

      {reserved.length > 0 && (
        <TransferPanel
          chosen={chosen}
          total={reserved.length}
          canSell={canSell}
          onTransfer={() =>
            void act(async () => {
              const created = await transferItems(
                deal.id,
                chosen.map((item) => item.id),
              );
              setPicked([]);
              setNotice(
                `Перенесено в сделку №${created.number ?? created.id}. `
                  + 'Резерв сохранён — товар просто в другом документе.',
              );
            })
          }
        />
      )}

      {issued.length > 0 && (
        <ReturnPanel
          chosen={chosen}
          canSell={canSell}
          onReturn={(warehouseId, lines, reason) =>
            void act(async () => {
              const doc = await registerReturn(deal.id, warehouseId, lines, reason);
              setPicked([]);
              setNotice(
                `Возврат №${doc.number ?? doc.id} на `
                  + `${Number(doc.amount).toLocaleString('ru-RU')} ₽ оформлен.`,
              );
              setDocs(await returnsOf(deal.id));
            })
          }
        />
      )}

      <button type="button" className="button--ghost" onClick={() => void makeShare()}>
        Ссылка клиенту
      </button>
      {share !== null && (
        <p className="note">
          {/* Полный адрес: продавец копирует его в переписку целиком,
              а не собирает из куска и домена в голове. */}
          {window.location.origin}{share}
        </p>
      )}

      <details onToggle={(e) => e.currentTarget.open && void showHistory()}>
        <summary>История документа</summary>
        {history === null ? (
          <p className="note">Загружаем…</p>
        ) : history.length === 0 ? (
          <p className="note">Записей нет.</p>
        ) : (
          <ul className="suggestions">
            {history.map((entry, at) => (
              <li key={at}>
                {entry.message}
                <span className="muted">
                  {' · '}
                  {/* Автор словом, а не номером: историю разбирают через
                      недели, когда «автор 3» не говорит ничего. */}
                  {entry.authorName ?? 'система'}
                  {' · '}
                  {new Date(entry.createdAt).toLocaleString('ru-RU')}
                </span>
              </li>
            ))}
          </ul>
        )}
      </details>

      {docs.length > 0 && (
        <>
          <h4>Возвраты по сделке</h4>
          <ul className="suggestions">
            {docs.map((doc) => (
              <li key={doc.id}>
                №{doc.number ?? doc.id} · {Number(doc.amount).toLocaleString('ru-RU')} ₽
                <span className="muted">
                  {' '}
                  · {new Date(doc.createdAt).toLocaleDateString('ru-RU')}
                  {doc.reason !== null && doc.reason !== '' && ` · ${doc.reason}`}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );

  async function makeShare(): Promise<void> {
    try {
      setShare((await shareDeal(deal.id)).path);
    } catch (cause) {
      onError(describe(cause, 'Ссылка не выдана'));
    }
  }

  async function showHistory(): Promise<void> {
    if (history !== null) {
      return;
    }
    try {
      setHistory(await historyOf(deal.id));
    } catch (cause) {
      onError(describe(cause, 'История не загрузилась'));
    }
  }

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
      // Сделку изменил кто-то ещё — показываем, во что она превратилась,
      // а не оставляем на экране состояние, которого уже нет. Иначе продавец
      // жмёт ту же кнопку второй раз и получает тот же отказ.
      if (cause instanceof ApiError && cause.status === 409) {
        const deals = await dealsOf(deal.customerId).catch(() => []);
        const fresh = deals.find((d) => d.id === deal.id);
        if (fresh !== undefined) {
          onChanged(fresh);
        }
      }
    }
  }
}

/**
 * Перенос отложенного в новую сделку.
 *
 * <p>Клиент забирает половину сейчас, остальное оставляет на потом. Резерв
 * не снимается — товар меняет документ, и вторая половина остаётся обещанной
 * тому же клиенту, а не уезжает на общий склад.
 */
function TransferPanel({
  chosen,
  total,
  canSell,
  onTransfer,
}: {
  chosen: DealItem[];
  total: number;
  canSell: boolean;
  onTransfer: () => void;
}) {
  // Перенести всё — это не разделение, а пустой документ и вторая сделка
  // с тем же составом. Сервер такое пропустит, поэтому останавливаем здесь.
  const everything = chosen.length === total;

  return (
    <>
      <hr />
      <h4>Перенести в новую сделку</h4>
      <p className="note">
        Отметьте то, что клиент оставляет на потом. Отмеченное уедет в отдельную
        сделку, эту выдадите сейчас.
      </p>
      <button
        type="button"
        disabled={!canSell || chosen.length === 0 || everything}
        onClick={onTransfer}
      >
        {chosen.length === 0 ? 'Отметьте позиции' : `Перенести (${chosen.length})`}
      </button>
      {everything && (
        <p className="note">
          Отмечено всё — переносить нечего. Оставьте в этой сделке то, что клиент
          забирает сейчас.
        </p>
      )}
    </>
  );
}

/**
 * Возврат выданного.
 *
 * <p><b>Подтверждение в два нажатия, а не сразу.</b> Возврат проводится
 * мгновенно и обратно не отыгрывается: деталь встаёт на склад, деньги уходят
 * клиенту. Сервер отмену завершённого возврата отклонит, и исправлять ошибку
 * придётся встречной продажей.
 *
 * <p>Брак — один флажок на весь документ, а не на строку. Смешанный возврат
 * (часть на склад, часть в утиль) оформляют двумя документами: так видно,
 * что именно списали, а сам случай редкий.
 */
function ReturnPanel({
  chosen,
  canSell,
  onReturn,
}: {
  chosen: DealItem[];
  canSell: boolean;
  onReturn: (warehouseId: number, lines: ReturnLine[], reason: string) => void;
}) {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [broken, setBroken] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    void listWarehouses()
      .then((loaded) => {
        setWarehouses(loaded);
        setWarehouseId((current) => current ?? loaded[0]?.id ?? null);
      })
      .catch(() => setWarehouses([]));
  }, []);

  const ready = canSell && chosen.length > 0 && warehouseId !== null;

  return (
    <>
      <hr />
      <h4>Возврат</h4>
      <p className="note">
        Отметьте, что клиент привёз обратно. Отдельный документ со своим номером:
        отменить выданную сделку уже нельзя — деталь была у клиента.
      </p>

      <label>
        Склад возврата
        <select
          value={warehouseId ?? ''}
          onChange={(e) => setWarehouseId(Number(e.target.value))}
        >
          {warehouses.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name}
            </option>
          ))}
        </select>
      </label>
      <p className="note">
        Не обязан совпадать со складом выдачи: клиент приезжает туда, куда ему
        удобно, а деталь встаёт на ту полку, где он её оставил.
      </p>

      <label>
        Причина
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="не подошла, привёз обратно"
        />
      </label>

      <label className="pick">
        <input
          type="checkbox"
          checked={broken}
          onChange={(e) => {
            setBroken(e.target.checked);
            setConfirming(false);
          }}
        />{' '}
        Брак — деньги вернуть, в остаток не ставить
      </label>

      {confirming ? (
        <div className="row">
          <button type="button" disabled={!ready} onClick={submit}>
            Да, оформить: {chosen.length} поз.{broken && ', в утиль'}
          </button>
          <button
            type="button"
            className="button--ghost"
            onClick={() => setConfirming(false)}
          >
            Не надо
          </button>
        </div>
      ) : (
        <button type="button" disabled={!ready} onClick={() => setConfirming(true)}>
          {chosen.length === 0 ? 'Отметьте позиции' : `Оформить возврат (${chosen.length})`}
        </button>
      )}
      {confirming && (
        <p className="note">
          Возврат не отменяется: деталь встанет на склад, деньги уйдут клиенту.
        </p>
      )}
    </>
  );

  function submit(): void {
    if (warehouseId === null) {
      return;
    }
    setConfirming(false);
    onReturn(
      warehouseId,
      chosen.map((item) => ({ dealItemId: item.id, restocked: !broken })),
      reason.trim(),
    );
  }
}

function itemStatusName(status: string): string {
  const names: Record<string, string> = {
    RESERVED: 'отложена',
    ISSUED: 'выдана',
    RETURNED: 'возвращена',
    CANCELLED: 'снята',
  };
  return names[status] ?? status.toLowerCase();
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
