import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { count, plural } from '../ui/plural';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import {
  basketTotal,
  cancelDeal,
  createCustomer,
  createDeal,
  deal as fetchDealById,
  endOfDay,
  extendReservation,
  historyOf,
  reservationTerm,
  shareDeal,
  receiveOrder,
  serviceKinds,
  dealSources,
  dealsOf,
  issueDeal,
  accountOf,
  correctAccount,
  payDeal,
  topUpAccount,
  withdrawFromAccount,
  payDealFromAccount,
  registerReturn,
  returnable,
  returnWarehouseDefault,
  returnsOf,
  roomFor,
  searchCustomers,
  searchStock,
  transferable,
  transferItems,
} from '../sales/sales';
import type { CustomerAccount,
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
  /** Роль вошедшего: правку остатка делает не продавец. */
  role: string;
  /**
   * Сделка, которую надо открыть сразу, — реестр возвратов ведёт сюда
   * нажатием на номер в колонке «По сделке». Тот же путь, что и «Найти
   * сделку клиента», только найдена она не здесь, а на другой вкладке.
   */
  openDealId?: number | null;
  /** Сделка открыта — вкладке возвратов больше нечего просить. */
  onDealOpened?: () => void;
}

export function SellerScreen({ canSell, role, openDealId = null, onDealOpened }: Props) {
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<StockRow[]>([]);
  /**
   * Сколько нашлось всего. Больше показанного — список обрезан, и сказать
   * об этом обязательно: продавец, глядя на полсотни строк из семисот,
   * отвечает покупателю «нет такого» с уверенностью, что посмотрел всё.
   */
  const [found, setFound] = useState(0);
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
  // Куда уводит нажатие на счётчик. Блок оформления лежит под списком
  // находок, и добираться до него прокруткой — то самое, ради чего
  // счётчик и заводили.
  const basket = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (openDealId === null) {
      return;
    }
    void fetchDealById(openDealId)
      .then((found) => {
        setDeal(found);
        setFinding(false);
        // Корзина от прежнего разговора к чужой сделке отношения не имеет —
        // то же самое, что делает выбор в DealFinder.
        setLines([]);
        setRows([]);
        setFound(0);
        setError(null);
      })
      .catch((cause) => setError(describe(cause, 'Сделка не открылась')))
      .finally(() => onDealOpened?.());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openDealId]);

  return (
    <section className="card">
      {/* Шапка экрана не уезжает вместе со списком: «фара» — это полсотни
          показанных строк, то есть два экрана прокрутки вниз и столько же
          обратно за каждой следующей деталью. Всё это время продавец
          не знал, сколько набрал и на сколько: единственным подтверждением
          была смена слова на кнопке строки. */}
      <div className="seller-head">
        <h2>Продажа</h2>
        <BasketBadge
          lines={lines}
          services={services}
          onOpen={() => basket.current?.scrollIntoView?.({ block: 'start' })}
        />
      </div>

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
          role={role}
          onPick={(found) => {
            setDeal(found);
            setFinding(false);
            // Корзина от прежнего разговора к чужой сделке отношения не имеет.
            setLines([]);
            setRows([]);
            setFound(0);
          }}
          onError={setError}
        />
      )}

      {/* Подпись про обрезку не появляется без самого списка: она о том,
          что видно не всё, а когда не видно ничего, она превращается
          в «Показаны первые 0 из 17». Поймано живым прогоном — после
          оформления сделки список убирают, потому что остаток изменился
          и показанное уже врёт. */}
      {rows.length > 0 && found > rows.length && (
        <p className="note">
          Показаны первые {rows.length} из {found} — уточните запрос,
          иначе нужная деталь может остаться за списком.
        </p>
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
          <h3 ref={basket}>В сделку</h3>
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
            /* Клиент обязателен обычной продаже — она ведётся с человеком,
               который стоит у прилавка. У заказа с площадки клиента нет:
               покупателя она не называет, и назначить его задним числом
               нечем. Пока клиент требовался и здесь, принять заказ с экрана
               было нельзя вовсе — продавец заводил фиктивного, чтобы кнопка
               ожила, и в справочнике клиентов появлялся «Дром». */
            disabled={!canSell
              || (marketplace === '' && customer === null)
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
      const result = await searchStock(query.trim());
      setRows(result.rows);
      setFound(result.total);
    } catch (cause) {
      setRows([]);
      setFound(0);
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
    if (customer === null && marketplace === '') {
      return;
    }
    setError(null);
    try {
      if (marketplace !== '') {
        const result = await receiveOrder(
          marketplace, orderNo.trim(), customer?.id ?? null, lines, null, note, services,
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
        setDeal(await createDeal(customer!.id, lines, services,
          sourceId === '' ? null : Number(sourceId)));
      }
      setLines([]);
      setServices(services.map((line) => ({ ...line, price: '' })));
      // Остаток изменился — показанный список уже врёт.
      setRows([]);
      setFound(0);
    } catch (cause) {
      setError(describe(cause, 'Сделка не оформлена'));
    }
  }
}

/**
 * Счётчик корзины в шапке экрана.
 *
 * <p>Пустая корзина не молчит и не показывает ноль: ноль читается как
 * «система чего-то не знает», а продавцу нужно понять, что делать. Слова
 * те же, что у ориентира, — переходящий клиент читает их не задумываясь.
 *
 * <p>Сумма считается тем же `basketTotal`, что и «Итого» под списком.
 * Два числа на одном экране, посчитанные разными выражениями, рано или
 * поздно разойдутся — и разойдутся молча, в момент разговора с клиентом.
 * Значит и услуги входят в обе: продавец называет то, что клиент заплатит.
 */
function BasketBadge({
  lines,
  services,
  onOpen,
}: {
  lines: BasketLine[];
  services: ServiceLine[];
  onOpen: () => void;
}) {
  if (lines.length === 0) {
    return (
      <span className="basket-badge basket-badge--empty">
        Список пуст
        <span className="muted"> · Выберите товары для продажи</span>
      </span>
    );
  }

  return (
    <button type="button" className="basket-badge" onClick={onOpen}>
      В сделку: {count(lines.length)}{' '}
      {plural(lines.length, 'позиция', 'позиции', 'позиций')}
      {' · '}
      {count(basketTotal(lines, services))} ₽
    </button>
  );
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
  // Сколько этой позиции уже лежит в корзине. Без этого «нет свободных»
  // появлялось и тогда, когда свободное есть, но всё оно взято в сделку,
  // — а рядом, в той же строке, написано «свободно 1». На складе б/у
  // запчастей остаток почти всегда единица, значит противоречие видно
  // при каждом нажатии.
  const taken = Number(row.qtyAvailable) - room;

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
          {room >= 1 ? 'в сделку' : taken > 0 ? 'уже в сделке' : 'нет свободных'}
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
  role,
}: {
  onPick: (deal: Deal) => void;
  onError: (message: string) => void;
  role: string;
}) {
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [deals, setDeals] = useState<Deal[] | null>(null);
  // Счёт показывается здесь, а не только в сделке: клиент приходит за своими
  // деньгами и без покупки — «верните, что осталось».
  const [account, setAccount] = useState<CustomerAccount | null>(null);
  const [cash, setCash] = useState('');
  // Правка — отдельно от денег: она ни на что не опирается, кроме решения,
  // и отвечает за неё тот, кто отвечает за деньги.
  const [fixing, setFixing] = useState(false);
  const [fixAmount, setFixAmount] = useState('');
  const [fixReason, setFixReason] = useState('');

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

      {account !== null && (
        <div className="card">
          <h4>Лицевой счёт</h4>
          <p className="note">
            Остаток {account.balance.toLocaleString('ru-RU')} ₽
          </p>

          <div className="row">
            <input
              type="number"
              inputMode="numeric"
              value={cash}
              placeholder="сумма"
              onChange={(e) => setCash(e.target.value)}
            />
            <button
              type="button"
              disabled={cash.trim() === ''}
              onClick={() => void money(() => topUpAccount(account.customerId, cash.trim()))}
            >
              Положить
            </button>
            {/* Выдача уносит деньги из кассы, поэтому она отдельной кнопкой,
                а не знаком минус в той же сумме: перепутать их значит выдать
                клиенту то, что он собирался оставить. */}
            <button
              type="button"
              className="button--ghost"
              disabled={cash.trim() === '' || account.balance <= 0}
              onClick={() => void money(() => withdrawFromAccount(account.customerId, cash.trim()))}
            >
              Выдать
            </button>
          </div>

          {/* Правка остатка — владельцу и менеджеру. Продавец делает
              операции, опирающиеся на факт: принял, выдал, зачёл. Правка
              не опирается ни на что, кроме решения. */}
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
                    await correctAccount(account.customerId, fixAmount.trim(), fixReason.trim());
                    setFixing(false);
                    setFixAmount('');
                    setFixReason('');
                  })}
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

          {account.entries.length > 0 && (
            <ul className="suggestions">
              {account.entries.slice(0, 8).map((entry) => (
                <li key={entry.id}>
                  <span className={entry.signedAmount < 0 ? 'muted' : undefined}>
                    {entry.signedAmount > 0 ? '+' : ''}
                    {entry.signedAmount.toLocaleString('ru-RU')} ₽
                    {' · '}
                    {entry.comment ?? entryName(entry.entryType)}
                    {' · '}
                    {new Date(entry.createdAt).toLocaleDateString('ru-RU')}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {deals !== null && deals.length === 0 && (
        <p className="note">У этого клиента сделок нет</p>
      )}

      {deals !== null && deals.length > 0 && (
        <ul className="suggestions">
          {deals.map((d) => {
            // Срок резерва в той же строке, что и статус: «отложена» без
            // числа не говорит ничего — освободится деталь завтра или через
            // неделю, из списка не понять. Просроченных у живого клиента
            // больше половины, и красное здесь — это очередь на обзвон.
            const line = reservationTerm(d);
            return (
              <li key={d.id}>
                <button type="button" className="button--ghost" onClick={() => onPick(d)}>
                  №{d.number ?? d.id} · {statusName(d.status)}
                  {line !== null && (
                    <span className={line.expired ? 'note--error' : 'muted'}>
                      {line.expired ? ' · срок истёк' : ` · до ${line.day}`}
                    </span>
                  )}
                  <span className="muted">
                    {' '}
                    · {Number(d.totalAmount).toLocaleString('ru-RU')} ₽ ·{' '}
                    {new Date(d.createdAt).toLocaleDateString('ru-RU')}
                  </span>
                </button>
              </li>
            );
          })}
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
    // Счёт грузим отдельно: сделок может не быть вовсе, а деньги на счету
    // при этом лежать — за ними и пришли.
    setAccount(await accountOf(picked.id).catch(() => null));
  }

  async function money(action: () => Promise<unknown>): Promise<void> {
    if (customer === null) {
      return;
    }
    try {
      await action();
      setCash('');
      setAccount(await accountOf(customer.id));
    } catch (cause) {
      onError(describe(cause, 'Операция по счёту не прошла'));
    }
  }
}

/** Название операции, когда комментария нет. */
function entryName(type: string): string {
  switch (type) {
    case 'TOP_UP': return 'пополнение';
    case 'WITHDRAW': return 'выдача';
    case 'DEAL_PAYMENT': return 'оплата сделки';
    case 'DEAL_REFUND': return 'возврат по сделке';
    default: return 'правка';
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
  // До какого числа продлить резерв. Пусто до выбора: подставленная дата
  // означала бы, что о ней кто-то договорился с клиентом вместо продавца.
  const [until, setUntil] = useState('');
  const [picked, setPicked] = useState<number[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [docs, setDocs] = useState<ReturnDoc[]>([]);
  // История подтягивается по раскрытию, а не с карточкой: на неё смотрят
  // при разборе спора, а не при каждой продаже.
  const [history, setHistory] = useState<HistoryEntry[] | null>(null);
  const [share, setShare] = useState<string | null>(null);
  // Остаток лицевого счёта: переплата ложится на него сама, и без показа
  // деньги клиента остаются в системе невидимыми — при следующем приезде
  // про свою тысячу помнит только он.
  const [account, setAccount] = useState<CustomerAccount | null>(null);

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
  // Срок резерва: у выданной и отменённой его нет вовсе — товар либо
  // у клиента, либо снова на полке, и дата рядом с ними обещала бы то,
  // чего никто не обещал.
  const term = reservationTerm(deal);

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
    // Набранная дата — про прежнюю сделку: оставшись, она продлила бы
    // чужой резерв до числа, которого по нему никто не называл.
    setUntil('');

    // Счёт принадлежит клиенту, а не сделке: у сделки без клиента его нет.
    setAccount(null);
    if (deal.customerId !== null) {
      void accountOf(deal.customerId).then(setAccount).catch(() => setAccount(null));
    }
  }, [deal.id, deal.customerId]);

  return (
    <>
      <hr />
      <h3>
        Сделка №{deal.number ?? deal.id} · {statusName(deal.status)}
      </h3>

      {/* Срок резерва — сразу под номером, как у ориентира. Без него карточка
          говорила «отложена» и всё: продавец не знал, освободится деталь
          завтра или через неделю, и клиенту ответить не мог. Просроченный
          красным и словами: вчерашнее число рядом со словом «отложено»
          читается как срок, а это очередь на обзвон. */}
      {term !== null && (
        <p className={term.expired ? 'note note--error' : 'note'}>
          {term.expired ? 'Отложено · срок истёк' : `Отложено до ${term.day}`}
        </p>
      )}

      {/* Продление — здесь же, где срок и прочитан: клиент звонит и просит
          подержать ещё, и уводить продавца за этим на другой экран значит
          не продлить вовсе. Дата не подставляется: до какого числа держим,
          знает только тот, кто говорил с клиентом. */}
      {term !== null && (
        <div className="row">
          <input
            type="date"
            aria-label={`Продлить резерв по сделке №${deal.number ?? deal.id} до`}
            min={todayISO()}
            value={until}
            onChange={(e) => setUntil(e.target.value)}
          />
          <button
            type="button"
            className="button--ghost"
            disabled={!canSell || until === ''}
            onClick={() => void act(async () => {
              await extendReservation(deal.id, endOfDay(until));
              setUntil('');
            })}
          >
            Продлить
          </button>
        </div>
      )}

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

        {/* Услуги — наравне с деталями. Без них сумма строк не сходится
            с итогом документа: «итого 7 500» под деталями на 7 000, и спор
            об этом начинается в момент оплаты. Отмечать их галочкой нельзя:
            услуга не переносится в другую сделку — доставка уже состоялась. */}
        {deal.services.map((line) => (
          <li key={`service-${line.id}`} className="stock-row">
            <span className="stock-info">
              {line.name ?? `услуга ${line.serviceId}`}
              <span className="muted"> · {Number(line.quantity)} шт</span>
            </span>
            <div className="stock-action">
              <strong className="stock-price">
                {Number(line.price).toLocaleString('ru-RU')} ₽
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

      {/* Зачёт с лицевого счёта — отдельной кнопкой, а не галочкой в оплате:
          денег в кассу при нём не поступает, они получены раньше. Сумма
          предлагается наименьшая из остатка и долга: зачесть больше нельзя
          ни того, ни другого. */}
      {account !== null && account.balance > 0 && (
        <div className="row">
          <span className="muted">
            На счету клиента {account.balance.toLocaleString('ru-RU')} ₽
          </span>
          {Number(deal.debt) > 0 && (
            <button
              type="button"
              className="button--ghost"
              disabled={!canSell}
              onClick={() => void act(async () => {
                const take = Math.min(account.balance, Number(deal.debt));
                await payDealFromAccount(deal.id, String(take));
                setAccount(await accountOf(account.customerId));
              })}
            >
              Зачесть {Math.min(account.balance, Number(deal.debt)).toLocaleString('ru-RU')} ₽
            </button>
          )}
        </div>
      )}

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
          // Ключ по сделке: панель стоит на одном месте дерева, и без него
          // выбранный склад, причина и брак переезжают в чужую сделку —
          // ровно как переезжали отметки и сообщение до сброса в useEffect.
          key={deal.id}
          chosen={chosen}
          defaultWarehouseId={returnWarehouseDefault(deal)}
          canSell={canSell}
          onReturn={(warehouseId, lines, reason, refundToAccount) =>
            void act(async () => {
              const doc = await registerReturn(deal.id, warehouseId, lines, reason,
                refundToAccount);
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
 *
 * @param defaultWarehouseId склад, откуда деталь выдали. Пусто — выдавали
 *                           с разных, и угадывать нельзя: продавец жмёт
 *                           «Оформить» не глядя, а деталь потом ищут
 *                           по прежнему адресу
 */
function ReturnPanel({
  chosen,
  canSell,
  defaultWarehouseId,
  onReturn,
}: {
  chosen: DealItem[];
  canSell: boolean;
  defaultWarehouseId: number | null;
  onReturn: (warehouseId: number, lines: ReturnLine[], reason: string,
             refundToAccount: boolean) => void;
}) {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [warehouseId, setWarehouseId] = useState<number | null>(defaultWarehouseId);
  const [reason, setReason] = useState('');
  const [toAccount, setToAccount] = useState(false);
  const [broken, setBroken] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    void listWarehouses()
      .then((loaded) => {
        setWarehouses(loaded);
        // Склад выдачи мог быть закрыт с тех пор, а список не загрузиться
        // вовсе. И то и другое — пустое поле на экране; считать его
        // выбранным значит оформить возврат туда, чего продавец не видит.
        setWarehouseId((current) =>
          current !== null && loaded.some((w) => w.id === current) ? current : null);
      })
      .catch(() => {
        setWarehouses([]);
        setWarehouseId(null);
      });
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
          onChange={(e) =>
            setWarehouseId(e.target.value === '' ? null : Number(e.target.value))
          }
        >
          {/* Пустая строка нужна, только пока выбирать обязан человек:
              выбрав склад, вернуться в «ничего» он уже не должен. */}
          {warehouseId === null && <option value="">— выберите склад —</option>}
          {warehouses.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name}
            </option>
          ))}
        </select>
      </label>
      {warehouseId === null && (
        <p className="note">
          Склад не подставлен — выберите, куда клиент привёз деталь.
        </p>
      )}
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

      {/* Деньги наличными или на счёт. Запись о выдаче создаётся независимо
          от того, есть ли они в кассе, — а утром её может не быть, и тогда
          касса к вечеру не сойдётся ровно на сумму возврата. На счёт —
          это «мы должны», и клиент заберёт их или зачтёт в следующую покупку. */}
      <label className="pick">
        <input
          type="checkbox"
          checked={toAccount}
          onChange={(e) => {
            setToAccount(e.target.checked);
            setConfirming(false);
          }}
        />{' '}
        Деньги на лицевой счёт, а не из кассы
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
      toAccount,
    );
  }
}

/**
 * Сегодня для нижней границы выбора даты.
 *
 * <p>Резерв продлевают вперёд: вчерашнее число сервер отклонит, и узнавать
 * об этом после нажатия — значит терять разговор с клиентом на линии.
 */
function todayISO(): string {
  const now = new Date();
  // Местная дата, а не UTC: `toISOString` восточнее Гринвича вечером даёт
  // завтрашний день, и «сегодня» в поле оказалось бы недоступно.
  return [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, '0'),
    String(now.getDate()).padStart(2, '0'),
  ].join('-');
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
