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
  defaultPaymentSource,
  endOfDay,
  extendReservation,
  historyOf,
  paymentSources,
  rememberPaymentSource,
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
  retailCustomer,
  changeDealCustomer,
  searchCustomers,
  searchStock,
  NO_STOCK_FILTER,
  transferable,
  transferItems,
} from '../sales/sales';
import {
  customerName, dealItemStatusName, dealStageStatus, dealStatusNameLower,
} from '../sales/dealStatus';
import { useMounted } from '../ui/useMounted';
import type { CustomerAccount,
  HistoryEntry,
  DealSource as DealSourceRow,
  PaymentSourceEntry,
  ServiceLine,
  BasketLine,
  Customer,
  Deal,
  DealItem,
  ReturnDoc,
  ReturnLine,
  StockFacets,
  StockFilter,
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
  /** Схема арендатора — часть ключа, которым запоминается источник платежа. */
  company: string;
  /** Сотрудник в сессии — вторая часть того же ключа: за кассой стоят разные
   *  продавцы, и умолчание одного не должно навязываться другому. */
  memberId: number;
  /**
   * Сделка, которую надо открыть сразу, — реестр возвратов ведёт сюда
   * нажатием на номер в колонке «По сделке». Тот же путь, что и «Найти
   * сделку клиента», только найдена она не здесь, а на другой вкладке.
   */
  openDealId?: number | null;
  /** Сделка открыта — вкладке возвратов больше нечего просить. */
  onDealOpened?: () => void;
}

export function SellerScreen({
  canSell, role, company, memberId, openDealId = null, onDealOpened,
}: Props) {
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<StockRow[]>([]);
  /**
   * Сколько нашлось всего. Больше показанного — список обрезан, и сказать
   * об этом обязательно: продавец, глядя на полсотни строк из семисот,
   * отвечает покупателю «нет такого» с уверенностью, что посмотрел всё.
   */
  const [found, setFound] = useState(0);
  const [searching, setSearching] = useState(false);
  /**
   * Искали ли вообще. Пустой список получается двумя способами, и «Ничего
   * не найдено» до первого запроса — утверждение о складе, которого никто
   * не делал.
   */
  const [searched, setSearched] = useState(false);
  // Чем сужено найденное. Отбор живёт рядом с запросом, а не внутри формы:
  // от него зависит и то, что показано, и то, что написано в счётчике.
  const [filter, setFilter] = useState<StockFilter>(NO_STOCK_FILTER);
  // Из чего выбирать — считает сервер по найденному: марки всего склада
  // это предложение выбрать то, чего в выдаче нет.
  const [facets, setFacets] = useState<StockFacets | null>(null);
  // Склады берутся целиком, а не из найденного: продавец спрашивает
  // «а на Ткацкой есть?» и тогда, когда там ничего не нашлось.
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [lines, setLines] = useState<BasketLine[]>([]);
  const [customer, setCustomer] = useState<Customer | null>(null);
  /**
   * Контрагент розничной продажи — тот, что подставлен в поле клиента,
   * пока покупатель не назвался.
   *
   * <p>Держится отдельно от {@link customer}, потому что после каждой
   * оформленной сделки поле возвращается к нему: следующий разговор
   * начинается с чистого листа, а оставшийся прежний покупатель — это
   * чужая фамилия в сделке, которую никто не перечитывает.
   */
  const [retail, setRetail] = useState<Customer | null>(null);
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
  // Источники платежей — для оплаты, возврата денег из кассы и операций
  // по лицевому счёту разом: справочник один на все три места, где спрашивают
  // «чем заплатили».
  const [paymentSourceList, setPaymentSourceList] = useState<PaymentSourceEntry[]>([]);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void serviceKinds()
      .then((kinds) => {
        if (mounted.current) setServices(kinds.map((kind) => ({ kind, price: '' })));
      })
      // Молча: без справочника услуг продавать всё ещё можно, а красный
      // текст на весь экран из-за доставки — это про неверные приоритеты.
      .catch(() => { if (mounted.current) setServices([]); });
    void dealSources()
      .then((found) => { if (mounted.current) setSources(found); })
      .catch(() => { if (mounted.current) setSources([]); });
    // Молча и здесь: источников платежей может не быть ни одного, и оплата
    // тогда работает как раньше — без выпадающего списка.
    void paymentSources()
      .then((found) => { if (mounted.current) setPaymentSourceList(found); })
      .catch(() => { if (mounted.current) setPaymentSourceList([]); });
    // Молча и здесь: без списка складов отбор по складу просто не предлагается,
    // а поиск товара работает как раньше.
    void listWarehouses()
      .then((found) => { if (mounted.current) setWarehouses(found); })
      .catch(() => { if (mounted.current) setWarehouses([]); });
    // Контрагент розничной продажи — только тем, кто продаёт: остальным
    // ролям экран открыт ради цены и наличия, и отказ по правам на запросе,
    // которым они не пользуются, был бы красной строкой ни о чём.
    if (canSell) {
      void retailCustomer()
        .then((found) => {
          if (!mounted.current) return;
          setRetail(found);
          // Подставляем, только если продавец ещё никого не выбрал: ответ
          // мог прийти позже, чем он начал набирать фамилию.
          setCustomer((chosen) => chosen ?? found);
        })
        // Молча: без него продажа работает как раньше — клиента выбирают
        // руками, и кнопка оформления скажет, что его не хватает.
        .catch(() => { if (mounted.current) setRetail(null); });
    }
  }, [mounted, canSell]);
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
        if (!mounted.current) return;
        setDeal(found);
        setFinding(false);
        // Корзина от прежнего разговора к чужой сделке отношения не имеет —
        // то же самое, что делает выбор в DealFinder.
        setLines([]);
        forgetSearch();
        setError(null);
      })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Сделка не открылась'));
      })
      .finally(() => { if (mounted.current) onDealOpened?.(); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openDealId]);

  // Что мешает оформить. Считается один раз и используется дважды —
  // условием кнопки и текстом под ней.
  const orderBlock = orderObstacle(canSell, marketplace, orderNo, customer);

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

      {/* Кнопка, которая ничего не сделает, не показывается: история сделок
          клиента (`GET /api/deals?customerId=`) закрыта теми же ролями, что
          и раздел «Клиенты», — кладовщик и «Просмотр» получили бы отказ
          на первом же выбранном клиенте. Поиск товара выше при этом остаётся
          всем: цена и наличие нужны и приёмщику. */}
      {canSell && (
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
      )}

      {error !== null && <p className="note note--error">{error}</p>}

      {finding && (
        <DealFinder
          role={role}
          company={company}
          memberId={memberId}
          paymentSourceList={paymentSourceList}
          onPick={(found) => {
            setDeal(found);
            setFinding(false);
            // Корзина от прежнего разговора к чужой сделке отношения не имеет.
            setLines([]);
            forgetSearch();
          }}
          onError={setError}
        />
      )}

      {/* Отбор показывается, как только искали, — и остаётся на месте при
          пустой выдаче. Спрятать его вместе со строками значит запереть
          продавца: отбор действует, «Ничего не найдено» ничего не объясняет,
          а снять его нечем. Ровно это уже случилось на витрине склада, где
          отборы снимались только в шапке таблицы. */}
      {searched && (
        <StockFilters
          filter={filter}
          facets={facets}
          warehouses={warehouses}
          onNarrow={narrow}
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

      {/* А когда всё найденное видно, счётчик говорит именно это: сузив
          отбором 181 позицию до шести, продавец обязан увидеть «Найдено 6»,
          а не остаться с прежним «первые 50 из 181». */}
      {rows.length > 0 && found <= rows.length && (
        <p className="note">Найдено {count(found)}</p>
      )}

      {/* «Пусто» и «не смогли узнать» — разные вещи: причина отказа стоит
          выше своей строкой, и повторять её словами про склад нельзя. */}
      {searched && !searching && rows.length === 0 && error === null && (
        <p className="note">Ничего не найдено</p>
      )}

      {/* Почему у всех строк кнопка серая. Экран открыт каждой роли — цену
          и наличие спрашивают и у приёмщика, — а продавать могут не все,
          и без этой строки кладовщик видел полсотни погашенных кнопок
          без единого слова о причине. Сказано один раз над списком,
          а не в каждой строке: пятьдесят одинаковых упрёков читаются
          как поломка. */}
      {rows.length > 0 && !canSell && (
        <p className="note">
          Ваша роль не позволяет продавать — цену и наличие видно, а положить
          товар в сделку нельзя.
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

          <CustomerPicker
            customer={customer}
            onPick={setCustomer}
            onClear={() => setCustomer(null)}
            onError={setError}
          />

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

          {/* Условие кнопки и текст под ней считает одно выражение: разойдись
              они, кнопка снова начала бы молчать или называть не ту причину.
              Тот же приём, что у «Оплаты» в карточке сделки. */}
          <button
            type="button"
            disabled={orderBlock !== null}
            onClick={() => void submit()}
          >
            {marketplace === '' ? 'Оформить и отложить' : 'Принять заказ'}
          </button>
          {orderBlock !== null && <p className="note">{orderBlock}</p>}
        </>
      )}

      {deal !== null && (
        <DealCard
          deal={deal}
          canSell={canSell}
          company={company}
          memberId={memberId}
          paymentSourceList={paymentSourceList}
          onChanged={setDeal}
          onError={setError}
        />
      )}
    </section>
  );

  /**
   * Отбор уходит в запрос, а не сужает уже показанные строки.
   *
   * <p>Показано пятьдесят, а «фара» на живом складе находит 181: сузив
   * показанное, продавец, которому сказали «фара на Ниссан», получил бы
   * пустоту при полной полке ниссановских фар.
   */
  async function find(narrowing: StockFilter = filter): Promise<void> {
    setSearching(true);
    setError(null);
    try {
      const result = await searchStock(query.trim(), narrowing);
      if (mounted.current) {
        setRows(result.rows);
        setFound(result.total);
        setFacets(result.facets);
        setSearched(true);
      }
    } catch (cause) {
      if (mounted.current) {
        setRows([]);
        setFound(0);
        setError(describe(cause, 'Не удалось выполнить поиск'));
      }
    } finally {
      if (mounted.current) setSearching(false);
    }
  }

  /** Смена отбора — это новый запрос: сузили в базе, а не на экране. */
  function narrow(next: StockFilter): void {
    setFilter(next);
    void find(next);
  }

  /**
   * Список убран — значит убран и отбор с подписями про найденное.
   *
   * <p>Иначе экран пишет «Ничего не найдено» там, где не искали, или
   * «Показаны первые 0 из 17» при пустом месте: и то и другое —
   * утверждение о складе, которого никто не делал.
   */
  function forgetSearch(): void {
    setRows([]);
    setFound(0);
    setSearched(false);
    setFilter(NO_STOCK_FILTER);
    setFacets(null);
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
    if (orderBlock !== null) {
      return;
    }
    setError(null);
    try {
      if (marketplace !== '') {
        const result = await receiveOrder(
          marketplace, orderNo.trim(), customer?.id ?? null, lines, null, note, services,
          sourceId === '' ? null : Number(sourceId));
        if (!mounted.current) return;
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
        const created = await createDeal(customer?.id ?? null, lines, services,
          sourceId === '' ? null : Number(sourceId));
        if (!mounted.current) return;
        setDeal(created);
      }
      setLines([]);
      // Следующий разговор начинается с чистого листа: оставшийся в поле
      // покупатель предыдущей сделки уехал бы в следующую молча — поле
      // заполнено и выглядит осмысленно, а смотрят на него как раз тогда,
      // когда клиент назвался.
      setCustomer(retail);
      setServices(services.map((line) => ({ ...line, price: '' })));
      // Остаток изменился — показанный список уже врёт.
      forgetSearch();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Сделка не оформлена'));
    }
  }
}

/**
 * Отбор найденного: марка, модель, год, стороны, склад, оценка и цена.
 *
 * <p><b>Зачем.</b> «Фара» на живом складе — это 181 позиция и полсотни
 * показанных строк, отличающихся одним кодом: «Фара Toyota Camry 2007
 * (б/у) 1 500 ₽» пятьдесят раз подряд. Уточнять было нечем — поле одно,
 * — и продавцу, которому сказали «фара на Камри 2007, левая», оставалось
 * угадывать, какими словами это записано на складе, либо листать.
 *
 * <p><b>Марка и модель приходят с сервера, стороны заданы здесь.</b>
 * Первые — то, что встретилось в найденном, и список их считает тот же
 * запрос, что собрал выдачу: два списка разошлись бы молча. Стороны
 * и порядок сортировки перечислены на месте — это не данные склада,
 * а два значения перечисления, и спрашивать их у сервера незачем.
 *
 * <p>Списки применяются нажатием, «от–до» — по уходу из поля и по Enter:
 * запрос на каждую цифру означал бы четыре похода на сервер, пока
 * набирают «2007».
 */
function StockFilters({
  filter, facets, warehouses, onNarrow,
}: {
  filter: StockFilter;
  facets: StockFacets | null;
  warehouses: Warehouse[];
  onNarrow: (next: StockFilter) => void;
}) {
  // Набранное живёт отдельно от отправленного — тот же приём, что
  // у поиска в реестре сделок (`draft` против `query`).
  const [draft, setDraft] = useState<StockFilter>(filter);
  useEffect(() => {
    setDraft(filter);
  }, [filter]);

  const vehicles = facets?.vehicles ?? [];
  const brands = [...new Set(vehicles.map((vehicle) => vehicle.brand))];
  const models = [...new Set(vehicles
    .filter((vehicle) => vehicle.brand === filter.brand && vehicle.model !== null)
    .map((vehicle) => vehicle.model as string))];
  const grades = facets?.grades ?? [];

  // Порядок и направление — одно поле на экране: «по возрастанию цены»
  // это один ответ на один вопрос, а не два.
  const order = filter.sort === '' ? '' : `${filter.sort}${filter.desc ? '-desc' : ''}`;

  return (
    <form
      className="filter-row"
      onSubmit={(e) => {
        e.preventDefault();
        onNarrow(draft);
      }}
    >
      <label className="field">
        Марка
        <select
          value={filter.brand}
          disabled={brands.length === 0}
          // Модель принадлежит марке: оставленная от прежней, она отдала бы
          // пустую выдачу, и продавец решил бы, что ничего нет.
          onChange={(e) => onNarrow({ ...filter, brand: e.target.value, model: '' })}
        >
          <option value="">Любая</option>
          {brands.map((brand) => <option key={brand} value={brand}>{brand}</option>)}
        </select>
      </label>

      <label className="field">
        Модель
        <select
          value={filter.model}
          disabled={filter.brand === '' || models.length === 0}
          onChange={(e) => onNarrow({ ...filter, model: e.target.value })}
        >
          <option value="">Любая</option>
          {models.map((model) => <option key={model} value={model}>{model}</option>)}
        </select>
      </label>

      <label className="field">
        Год выпуска, от
        <input
          type="number"
          inputMode="numeric"
          value={draft.yearFrom}
          onChange={(e) => setDraft({ ...draft, yearFrom: e.target.value })}
          onBlur={() => applyDraft()}
        />
      </label>
      <label className="field">
        Год выпуска, до
        <input
          type="number"
          inputMode="numeric"
          value={draft.yearTo}
          onChange={(e) => setDraft({ ...draft, yearTo: e.target.value })}
          onBlur={() => applyDraft()}
        />
      </label>

      <label className="field">
        Сторона
        <select
          value={filter.side}
          onChange={(e) => onNarrow({ ...filter, side: e.target.value })}
        >
          <option value="">Любой</option>
          <option value="LEFT">Левый</option>
          <option value="RIGHT">Правый</option>
        </select>
      </label>

      <label className="field">
        Перед/зад
        <select
          value={filter.position}
          onChange={(e) => onNarrow({ ...filter, position: e.target.value })}
        >
          <option value="">Любой</option>
          <option value="FRONT">Передний</option>
          <option value="REAR">Задний</option>
        </select>
      </label>

      <label className="field">
        Склад
        <select
          value={filter.warehouseId}
          disabled={warehouses.length === 0}
          onChange={(e) => onNarrow({ ...filter, warehouseId: e.target.value })}
        >
          <option value="">Любой</option>
          {warehouses.map((warehouse) => (
            <option key={warehouse.id} value={String(warehouse.id)}>{warehouse.name}</option>
          ))}
        </select>
      </label>

      <label className="field">
        Состояние
        <select
          value={filter.grade}
          disabled={grades.length === 0}
          onChange={(e) => onNarrow({ ...filter, grade: e.target.value })}
        >
          <option value="">Любое</option>
          {grades.map((grade) => <option key={grade} value={grade}>{grade}</option>)}
        </select>
      </label>

      <label className="field">
        Цена, от
        <input
          type="number"
          inputMode="numeric"
          value={draft.priceFrom}
          onChange={(e) => setDraft({ ...draft, priceFrom: e.target.value })}
          onBlur={() => applyDraft()}
        />
      </label>
      <label className="field">
        Цена, до
        <input
          type="number"
          inputMode="numeric"
          value={draft.priceTo}
          onChange={(e) => setDraft({ ...draft, priceTo: e.target.value })}
          onBlur={() => applyDraft()}
        />
      </label>

      <label className="field">
        Сортировка
        <select
          value={order}
          onChange={(e) => {
            const chosen = e.target.value;
            onNarrow({
              ...filter,
              sort: chosen.replace('-desc', ''),
              desc: chosen.endsWith('-desc'),
            });
          }}
        >
          <option value="">Сначала подходящие</option>
          <option value="price">Цена: сначала дешёвые</option>
          <option value="price-desc">Цена: сначала дорогие</option>
          <option value="intake">Приняты: сначала давние</option>
          <option value="intake-desc">Приняты: сначала свежие</option>
        </select>
      </label>

      <button type="submit">Показать</button>
      <button
        type="button"
        className="button--ghost"
        onClick={() => onNarrow(NO_STOCK_FILTER)}
      >
        Сбросить отбор
      </button>
    </form>
  );

  /** Отправляется только изменившееся: иначе уход из поля перезапрашивает то же. */
  function applyDraft(): void {
    if (draft.yearFrom !== filter.yearFrom || draft.yearTo !== filter.yearTo
        || draft.priceFrom !== filter.priceFrom || draft.priceTo !== filter.priceTo) {
      onNarrow(draft);
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

/**
 * Найти позвонившего по телефону или завести его прямо в разговоре.
 *
 * <p>Поле не пустое: в нём стоит «Частное лицо», пока покупатель
 * не назвался. Поэтому у выбранного клиента есть «Изменить» — иначе
 * подставленного было бы не заменить вовсе, и продажа человеку с именем
 * стала бы невозможной.
 */
function CustomerPicker({
  customer,
  onPick,
  onClear,
  onError,
}: {
  customer: Customer | null;
  onPick: (customer: Customer) => void;
  /** Снять выбранного и вернуться к поиску. */
  onClear: () => void;
  onError: (message: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<Customer[]>([]);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  if (customer !== null) {
    return (
      <p className="note">
        Клиент: {customer.name ?? 'без имени'}
        {customer.phone !== null && ` · ${customer.phone}`}
        {' '}
        <button type="button" className="button--ghost" onClick={onClear}>
          Изменить
        </button>
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
      const matched = await searchCustomers(term.trim());
      if (mounted.current) setFound(matched);
    } catch {
      // Поиск клиента — не повод рушить экран: продавец заведёт нового.
      if (mounted.current) setFound([]);
    }
  }

  async function addNew(): Promise<void> {
    const term = query.trim();
    // Строка из одних цифр — это телефон, а не имя. Продавец набирает то,
    // что услышал, и раскладывать это по полям не должен.
    const digits = term.replace(/\D/g, '');
    const isPhone = digits.length >= 6 && digits.length === term.replace(/[\s+()-]/g, '').length;

    try {
      const created = await createCustomer(
        isPhone ? 'Без имени' : term, isPhone ? term : '');
      if (mounted.current) onPick(created);
    } catch (cause) {
      if (mounted.current) onError(describe(cause, 'Клиент не заведён'));
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
  company,
  memberId,
  paymentSourceList,
}: {
  onPick: (deal: Deal) => void;
  onError: (message: string) => void;
  role: string;
  company: string;
  memberId: number;
  paymentSourceList: PaymentSourceEntry[];
}) {
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [deals, setDeals] = useState<Deal[] | null>(null);
  // Счёт показывается здесь, а не только в сделке: клиент приходит за своими
  // деньгами и без покупки — «верните, что осталось».
  const [account, setAccount] = useState<CustomerAccount | null>(null);
  const [cash, setCash] = useState('');
  const activeSources = paymentSourceList.filter((s) => !s.archived);
  const [paymentSourceId, setPaymentSourceId] = useState<number | null>(() =>
    defaultPaymentSource(paymentSourceList, company, memberId));
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    setPaymentSourceId(defaultPaymentSource(paymentSourceList, company, memberId));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paymentSourceList]);
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
        onClear={() => {
          // Сделки и счёт — про прежнего клиента: оставшись на экране,
          // они приписали бы следующему чужие покупки и чужие деньги.
          setCustomer(null);
          setDeals(null);
          setAccount(null);
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
                topUpAccount(account.customerId, cash.trim(), paymentSourceId))}
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
              onClick={() => void money(() =>
                withdrawFromAccount(account.customerId, cash.trim(), paymentSourceId))}
            >
              Выдать
            </button>
          </div>
          {/* Почему «Положить» и «Выдать» серые. До этой правки обе просто
              гасли: продавец жмёт, ничего не происходит, и догадаться,
              что мешает — пустое поле или пустой счёт, — можно только
              перебором. */}
          {accountObstacle(cash, account.balance) !== null && (
            <p className="note">{accountObstacle(cash, account.balance)}</p>
          )}

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
                  }, false)}
                >
                  Поправить
                </button>
                <button type="button" className="button--ghost" onClick={() => setFixing(false)}>
                  Отмена
                </button>
                {/* Причина обязательна, и серая кнопка обязана это сказать:
                    правка остатка — единственная операция, меняющая деньги
                    клиента одним решением, и без «почему» через месяц её
                    не отличить от ошибки. */}
                {correctionObstacle(fixAmount, fixReason) !== null && (
                  <p className="note">{correctionObstacle(fixAmount, fixReason)}</p>
                )}
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
            // Слово — по стадии, а не по сырому статусу: оплаченная целиком
            // и не выданная сделка остаётся `RESERVED` со сроком резерва,
            // и строка про неё говорила «отложена · до 15 сентября», то есть
            // «ещё не оплачена, ждём до этой даты» — ровно наоборот.
            const state = dealStageStatus(d.stage, d.status);
            // Срок резерва в той же строке, что и статус: «отложена» без
            // числа не говорит ничего — освободится деталь завтра или через
            // неделю, из списка не понять. Просроченных у живого клиента
            // больше половины, и красное здесь — это очередь на обзвон.
            // Считается он от того же слова: у готовой к выдаче дату брать
            // неоткуда, иначе поправка вернула бы половину прежнего обмана.
            const line = reservationTerm({ status: state, reservedUntil: d.reservedUntil });
            return (
              <li key={d.id}>
                <button type="button" className="button--ghost" onClick={() => onPick(d)}>
                  №{d.number ?? d.id} · {dealStatusNameLower(state)}
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
      const found = await dealsOf(picked.id);
      if (mounted.current) setDeals(found);
    } catch (cause) {
      if (mounted.current) {
        setDeals([]);
        onError(describe(cause, 'Сделки не загрузились'));
      }
    }
    // Счёт грузим отдельно: сделок может не быть вовсе, а деньги на счету
    // при этом лежать — за ними и пришли.
    const balance = await accountOf(picked.id).catch(() => null);
    if (mounted.current) setAccount(balance);
  }

  /**
   * @param withSource операция создала платёж и способ у него есть.
   *                   У правки остатка платежа нет вовсе (деньги не двигались),
   *                   и запоминать при ней выбранный в списке источник значит
   *                   подставлять продавцу умолчание, которым он не платил.
   */
  async function money(action: () => Promise<unknown>, withSource = true): Promise<void> {
    if (customer === null) {
      return;
    }
    try {
      await action();
      if (withSource && paymentSourceId !== null) {
        rememberPaymentSource(company, memberId, paymentSourceId);
      }
      const balance = await accountOf(customer.id);
      if (mounted.current) {
        setCash('');
        setAccount(balance);
      }
    } catch (cause) {
      if (mounted.current) onError(describe(cause, 'Операция по счёту не прошла'));
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
  company,
  memberId,
  paymentSourceList,
  onChanged,
  onError,
}: {
  deal: Deal;
  canSell: boolean;
  company: string;
  memberId: number;
  paymentSourceList: PaymentSourceEntry[];
  onChanged: (deal: Deal) => void;
  onError: (message: string) => void;
}) {
  const [amount, setAmount] = useState('');
  const activePaymentSources = paymentSourceList.filter((s) => !s.archived);
  const [paymentSourceId, setPaymentSourceId] = useState<number | null>(() =>
    defaultPaymentSource(paymentSourceList, company, memberId));
  // До какого числа продлить резерв. Пусто до выбора: подставленная дата
  // означала бы, что о ней кто-то договорился с клиентом вместо продавца.
  const [until, setUntil] = useState('');
  const [picked, setPicked] = useState<number[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [docs, setDocs] = useState<ReturnDoc[]>([]);
  // Открыт ли подбор нового контрагента. Сделка заводится на «Частном лице»,
  // а имя покупателя выясняется по ходу разговора — это обычный шаг,
  // а не исправление ошибки.
  const [changingCustomer, setChangingCustomer] = useState(false);
  // История подтягивается по раскрытию, а не с карточкой: на неё смотрят
  // при разборе спора, а не при каждой продаже.
  const [history, setHistory] = useState<HistoryEntry[] | null>(null);
  const [share, setShare] = useState<string | null>(null);
  // Остаток лицевого счёта: переплата ложится на него сама, и без показа
  // деньги клиента остаются в системе невидимыми — при следующем приезде
  // про свою тысячу помнит только он.
  const [account, setAccount] = useState<CustomerAccount | null>(null);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

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
  // Чем подписан заголовок: стадией, а не сырым статусом. Нажатие на карточку
  // «Готов к выдаче» на доске ведёт именно сюда, и до правки продавец читал
  // исправленное там слово, а через одно движение — прежний обман: оплаченная
  // целиком и не выданная сделка остаётся `RESERVED` со сроком резерва,
  // то есть заголовок говорил «Сделка №20 · отложена», а строкой ниже стояло
  // «Отложено до 15 сентября». Цифр рядом нет — сумма и оплата ниже
  // по карточке, — и проверить это слово человеку было нечем.
  const state = dealStageStatus(deal.stage, deal.status);
  // Срок резерва: у выданной и отменённой его нет вовсе — товар либо
  // у клиента, либо снова на полке, и дата рядом с ними обещала бы то,
  // чего никто не обещал. У готовой к выдаче — по той же причине: срок
  // резерва рядом со словом «готова» читается как ожидание оплаты.
  const term = reservationTerm({ status: state, reservedUntil: deal.reservedUntil });
  // А продление остаётся доступным, пока резерв стоит на самом документе:
  // товар и у оплаченной сделки лежит отложенным до этого числа, и убрать
  // вместе со словом ещё и кнопку значило бы отнять возможность, о которой
  // задача не говорит ничего.
  const reserveOnDocument = reservationTerm(deal) !== null;

  // Когда контрагента ещё можно сменить: то же условие, что у сервера
  // (`Deal.changeCustomer`) — документ не закрыт и денег по сделке
  // не проходило. Платёж записан на прежнего клиента, и, переписав
  // контрагента, мы оставили бы деньги одного человека в документе другого.
  const customerChangeable = deal.status !== 'CANCELLED' && deal.status !== 'RETURNED'
    && Number(deal.paidAmount) === 0;

  const debt = Number(deal.debt);
  const entered = amount.trim();
  const payment = Number(entered);
  // Почему принять оплату нельзя — одним выражением на кнопку и на подпись
  // под ней: разойдись они, серая кнопка снова начнёт молчать о своей
  // причине или назовёт не ту.
  const obstacle = paymentObstacle(deal, debt, entered, payment);
  const overpayment = obstacle === null ? payment - debt : 0;

  // Сумма к оплате подставляется остатком долга: полная оплата — самый
  // частый случай на разборке (человек приехал и забрал), и продавец
  // набирал руками те же пять цифр, которые строкой выше уже прочитал.
  // Пересчитывается и после частичной оплаты — иначе в поле осталась бы
  // внесённая тысяча, и следующее нажатие приняло бы её второй раз.
  useEffect(() => {
    setAmount(debt > 0 ? String(debt) : '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deal.id, debt]);

  useEffect(() => {
    // Прежние возвраты по сделке: без них продавец оформит второй возврат
    // на ту же деталь и узнает об отказе сервера вместо ответа клиенту.
    void returnsOf(deal.id)
      .then((found) => { if (mounted.current) setDocs(found); })
      .catch(() => { if (mounted.current) setDocs([]); });
    // История — про прежнюю сделку: оставшись на экране, она приписала бы
    // этой сделке чужие действия.
    setHistory(null);
    setShare(null);
    // Сообщение и отметки — про прежнюю сделку. Оставшись на экране чужой,
    // «Возврат №1 оформлен» читается как возврат по ней.
    setNotice(null);
    setPicked([]);
    // Открытый подбор клиента — про прежнюю сделку: оставшись, он сменил бы
    // контрагента не у той, которую открыли.
    setChangingCustomer(false);
    // Набранная дата — про прежнюю сделку: оставшись, она продлила бы
    // чужой резерв до числа, которого по нему никто не называл.
    setUntil('');
    // Умолчание пересчитывается на каждую сделку — оно детерминировано
    // (прошлый выбор продавца или первый по алфавиту), и держать выбор
    // от чужой сделки незачем.
    setPaymentSourceId(defaultPaymentSource(paymentSourceList, company, memberId));

    // Счёт принадлежит клиенту, а не сделке: у сделки без клиента его нет.
    setAccount(null);
    if (deal.customerId !== null) {
      void accountOf(deal.customerId)
        .then((found) => { if (mounted.current) setAccount(found); })
        .catch(() => { if (mounted.current) setAccount(null); });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deal.id, deal.customerId]);

  return (
    <>
      <hr />
      <h3>
        Сделка №{deal.number ?? deal.id} · {dealStatusNameLower(state)}
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

      {/* Почему в карточке всё серое. Роль, не умеющая продавать, гасит
          здесь каждую кнопку разом — «Выдать», «Отменить», «Продлить»,
          «Оплата», «Зачесть», перенос и возврат, — и сказать об этом надо
          один раз сверху, а не молчать у каждой. */}
      {!canSell && (
        <p className="note">
          Ваша роль не позволяет продавать — по этой сделке доступен только
          просмотр.
        </p>
      )}

      {/* Контрагент и его смена. Порядок разговора на разборке — сначала
          товар, потом (если покупатель назвался) клиент: сделка открывается
          на «Частном лице», и заменить его настоящим покупателем надо
          оттуда же, где сделку и открыли.

          Пустой клиент (заказ с площадки, сделка, заведённая до задачи 0011)
          зовётся здесь «Частным лицом», как и везде: своё «не указан» было
          третьим словом об одном и том же и читалось как незаполненное поле
          (задача 0062).

          Кнопка показывается, только когда сервер такую смену примет, —
          по тому же правилу, что и «Найти сделку клиента»: кнопка, которая
          ничего не сделает, не показывается. Отказал бы он в двух случаях,
          и оба про деньги: закрытый документ и сделка, по которой уже
          проходили платежи (они записаны на прежнего клиента). */}
      <p className="note">
        Клиент: {customerName(deal.customerName)}
        {canSell && customerChangeable && !changingCustomer && (
          <>
            {' '}
            <button
              type="button"
              className="button--ghost"
              onClick={() => setChangingCustomer(true)}
            >
              Изменить клиента
            </button>
          </>
        )}
      </p>

      {changingCustomer && (
        <div className="row">
          <CustomerPicker
            customer={null}
            onPick={(picked) => void changeTo(picked)}
            onClear={() => setChangingCustomer(false)}
            onError={onError}
          />
          <button
            type="button"
            className="button--ghost"
            onClick={() => setChangingCustomer(false)}
          >
            Отменить
          </button>
        </div>
      )}

      {/* Продление — здесь же, где срок и прочитан: клиент звонит и просит
          подержать ещё, и уводить продавца за этим на другой экран значит
          не продлить вовсе. Дата не подставляется: до какого числа держим,
          знает только тот, кто говорил с клиентом. */}
      {reserveOnDocument && (
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
          {/* Дата не подставляется намеренно, поэтому кнопка при открытии
              карточки всегда серая — и обязана сказать, чего ждёт. */}
          {canSell && until === '' && (
            <p className="note">Выберите дату — до какого числа держим товар.</p>
          )}
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
                · {Number(item.quantity)} шт · {dealItemStatusName(item.status)}
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
        {/* Списка нет вовсе, если источников не заведено ни одного — оплата
            тогда работает как раньше, с paymentSourceId: null. Он не
            обязателен: кнопка «Оплата» из-за него не гаснет. */}
        {activePaymentSources.length > 0 && (
          <select
            aria-label="Источник платежа"
            value={paymentSourceId ?? ''}
            onChange={(e) =>
              setPaymentSourceId(e.target.value === '' ? null : Number(e.target.value))
            }
          >
            <option value="">не указан</option>
            {activePaymentSources.map((source) => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
          </select>
        )}
        <button
          type="button"
          disabled={!canSell || obstacle !== null}
          onClick={() => void act(async () => {
            await payDeal(deal.id, entered, paymentSourceId);
            if (paymentSourceId !== null) {
              rememberPaymentSource(company, memberId, paymentSourceId);
            }
          })}
        >
          Оплата
        </button>
      </div>

      {/* Серая кнопка обязана говорить, почему она серая: продавец стоит
          перед покупателем и второй раз нажимает ровно потому, что первое
          нажатие не ответило ничем. */}
      {obstacle !== null && <p className="note">{obstacle}</p>}

      {/* Переплата — законная операция (округлили вверх, отдали лишнюю
          тысячу), поэтому кнопка не гаснет. Но узнать о ней продавец должен
          до нажатия, а не из выросшего остатка на счёте клиента. У сделки
          без клиента счёта нет — говорить про него там значило бы обещать
          то, чего не будет. */}
      {overpayment > 0 && deal.customerId !== null && (
        <p className="note">
          Это больше долга на {overpayment.toLocaleString('ru-RU')} ₽ —
          лишнее уйдёт на лицевой счёт клиента.
        </p>
      )}

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
                const balance = await accountOf(account.customerId);
                if (mounted.current) setAccount(balance);
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
      {/* Обе кнопки гаснут на закрытом документе, и это не поломка:
          выданную сделку возвращают, а не отменяют, а отменённую
          и возвращённую трогать нечем. Раньше две серые кнопки стояли
          рядом молча. */}
      {canSell && !open && (
        <p className="note">
          {deal.status === 'ISSUED'
            ? 'Товар уже выдан — отменить сделку нельзя, оформляется возврат ниже.'
            : `Сделка ${dealStatusNameLower(deal.status)} — выдавать и отменять нечего.`}
        </p>
      )}

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
          paymentSourceList={activePaymentSources}
          defaultPaymentSourceId={paymentSourceId}
          onReturn={(warehouseId, lines, reason, refundToAccount, returnSourceId) =>
            void act(async () => {
              const doc = await registerReturn(deal.id, warehouseId, lines, reason,
                refundToAccount, returnSourceId);
              if (!refundToAccount && returnSourceId !== null) {
                rememberPaymentSource(company, memberId, returnSourceId);
              }
              const left = await returnsOf(deal.id);
              if (!mounted.current) return;
              setPicked([]);
              setNotice(
                `Возврат №${doc.number ?? doc.id} на `
                  + `${Number(doc.amount).toLocaleString('ru-RU')} ₽ оформлен.`,
              );
              setDocs(left);
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
      const link = (await shareDeal(deal.id)).path;
      if (mounted.current) setShare(link);
    } catch (cause) {
      if (mounted.current) onError(describe(cause, 'Ссылка не выдана'));
    }
  }

  async function showHistory(): Promise<void> {
    if (history !== null) {
      return;
    }
    try {
      const found = await historyOf(deal.id);
      if (mounted.current) setHistory(found);
    } catch (cause) {
      if (mounted.current) onError(describe(cause, 'История не загрузилась'));
    }
  }

  /**
   * Смена контрагента — мимо {@link act}, и это не небрежность.
   *
   * <p>Тот перечитывает сделку списком сделок **прежнего** клиента,
   * а после смены её там уже нет: карточка осталась бы с прежним именем
   * при изменённом документе. Сервер отдаёт изменённую сделку в ответе,
   * и брать её оттуда — единственный способ не соврать.
   */
  async function changeTo(picked: Customer): Promise<void> {
    try {
      const fresh = await changeDealCustomer(deal.id, picked.id);
      if (!mounted.current) return;
      setChangingCustomer(false);
      // Отдельного сообщения нет намеренно: имя стоит строкой выше
      // («Клиент: Евгений Гридин»), и второе то же самое рядом ничего
      // не добавляет. Проверено живым прогоном — там оно ещё и не доживает
      // до экрана: эффект на смену клиента сбрасывает `notice` следующим
      // тиком, как и всё прочее «про прежнюю сделку».
      onChanged(fresh);
    } catch (cause) {
      if (mounted.current) onError(describe(cause, 'Клиент сделки не изменён'));
    }
  }

  async function act(operation: () => Promise<unknown>): Promise<void> {
    try {
      await operation();
      // Перечитываем со стороны сервера, а не собираем состояние сами:
      // оплата меняет и долг, и лицевой счёт, и считает это сервер.
      const deals = await dealsOf(deal.customerId);
      if (!mounted.current) return;
      const fresh = deals.find((d) => d.id === deal.id);
      if (fresh !== undefined) {
        onChanged(fresh);
      }
    } catch (cause) {
      if (!mounted.current) return;
      onError(describe(cause, 'Операция не выполнена'));
      // Сделку изменил кто-то ещё — показываем, во что она превратилась,
      // а не оставляем на экране состояние, которого уже нет. Иначе продавец
      // жмёт ту же кнопку второй раз и получает тот же отказ.
      if (cause instanceof ApiError && cause.status === 409) {
        const deals = await dealsOf(deal.customerId).catch(() => []);
        if (!mounted.current) return;
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
  paymentSourceList,
  defaultPaymentSourceId,
  onReturn,
}: {
  chosen: DealItem[];
  canSell: boolean;
  defaultWarehouseId: number | null;
  /** Неархивные источники платежей — тот же список, что и у оплаты. */
  paymentSourceList: PaymentSourceEntry[];
  /** Умолчание: тот же источник, что выбран сейчас у оплаты сделки. */
  defaultPaymentSourceId: number | null;
  onReturn: (warehouseId: number, lines: ReturnLine[], reason: string,
             refundToAccount: boolean, paymentSourceId: number | null) => void;
}) {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [warehouseId, setWarehouseId] = useState<number | null>(defaultWarehouseId);
  const [reason, setReason] = useState('');
  const [toAccount, setToAccount] = useState(false);
  const [broken, setBroken] = useState(false);
  const [confirming, setConfirming] = useState(false);
  // Источник имеет смысл только для денег из кассы: зачисление на счёт
  // платежа не создаёт (см. sales/CLAUDE.md — «Зачёт с лицевого счёта
  // не создаёт платежа», то же верно для возврата на счёт).
  const [paymentSourceId, setPaymentSourceId] =
    useState<number | null>(defaultPaymentSourceId);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void listWarehouses()
      .then((loaded) => {
        if (!mounted.current) return;
        setWarehouses(loaded);
        // Склад выдачи мог быть закрыт с тех пор, а список не загрузиться
        // вовсе. И то и другое — пустое поле на экране; считать его
        // выбранным значит оформить возврат туда, чего продавец не видит.
        setWarehouseId((current) =>
          current !== null && loaded.some((w) => w.id === current) ? current : null);
      })
      .catch(() => {
        if (!mounted.current) return;
        setWarehouses([]);
        setWarehouseId(null);
      });
  }, [mounted]);

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

      {/* Источник платежа — только для денег из кассы: зачисление на счёт
          платежа не создаёт, и спрашивать здесь нечего. Списка нет вовсе,
          если источников не заведено ни одного. */}
      {!toAccount && paymentSourceList.length > 0 && (
        <label>
          Источник платежа
          <select
            value={paymentSourceId ?? ''}
            onChange={(e) =>
              setPaymentSourceId(e.target.value === '' ? null : Number(e.target.value))
            }
          >
            <option value="">не указан</option>
            {paymentSourceList.map((source) => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
          </select>
        </label>
      )}

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
      toAccount ? null : paymentSourceId,
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

/**
 * Что мешает принять оплату — словами, или `null`, если ничто не мешает.
 *
 * <p>Одно место на кнопку и на подпись под ней: пока условие кнопки жило
 * само по себе, серая кнопка не говорила ничего — ни при пустом поле,
 * ни у закрытой сделки, ни у закрытого долга. Причины разведены, потому
 * что читаются они по-разному: у отменённой сделки долга нет не потому,
 * что за неё заплатили, и «долг закрыт» там было бы неправдой.
 */
/**
 * Что мешает оформить продажу или принять заказ.
 *
 * <p>До этой правки кнопка просто гасла: клиент не выбран — серая
 * и молчит. Продавец жмёт, ничего не происходит, и почему — он должен
 * догадаться сам. Теперь клиент и не обязателен (в поле стоит «Частное
 * лицо»), но причины остались: роль, не позволяющая продавать, очищенное
 * руками поле клиента и заказ площадки без номера.
 *
 * <p>Возвращает `null`, когда оформлять можно, — тем же способом, что
 * {@link paymentObstacle}: одно выражение на условие кнопки и на подпись
 * под ней.
 */
function orderObstacle(
  canSell: boolean, marketplace: string, orderNo: string, customer: Customer | null,
): string | null {
  if (!canSell) {
    return 'Ваша роль не позволяет продавать.';
  }
  if (marketplace !== '') {
    return orderNo.trim() === ''
      ? 'Впишите номер заказа у площадки — по нему заказ и опознаётся.'
      : null;
  }
  // Обычная продажа: клиент подставлен «Частным лицом» и обязателен только
  // в том смысле, что поле нельзя оставить пустым, — нажав «Изменить»,
  // продавец его очищает, и оформлять становится не на кого.
  //
  // Про дорогу назад сказано прямо: «оставьте „Частное лицо“» было бы
  // неправдой — поля с ним на экране уже нет, — а найти его поиском можно,
  // он такой же контрагент, как остальные. Поймано живым прогоном.
  return customer === null
    ? 'Выберите клиента — оформлять не на кого. «Частное лицо» найдётся тем же поиском.'
    : null;
}

/** Что мешает положить деньги на счёт или выдать их. */
function accountObstacle(cash: string, balance: number): string | null {
  if (cash.trim() === '') {
    return 'Впишите сумму — без неё ни положить, ни выдать нельзя.';
  }
  if (balance <= 0) {
    return 'Выдавать нечего: на счету пусто. Положить можно.';
  }
  return null;
}

/** Что мешает поправить остаток счёта руками. */
function correctionObstacle(amount: string, reason: string): string | null {
  if (amount.trim() === '') {
    return 'Впишите сумму правки — со знаком плюс или минус.';
  }
  if (reason.trim() === '') {
    return 'Напишите причину: правка остатка без неё не проходит — через месяц '
      + 'её не отличить от ошибки.';
  }
  return null;
}

function paymentObstacle(
  deal: Deal, debt: number, entered: string, payment: number,
): string | null {
  if (deal.status === 'CANCELLED' || deal.status === 'RETURNED') {
    return `Сделка ${dealStatusNameLower(deal.status)} — платить по ней не за что.`;
  }
  if (!(debt > 0)) {
    return 'Долг закрыт — принимать по этой сделке нечего.';
  }
  if (entered === '') {
    return 'Впишите сумму — по пустому полю оплата не пройдёт.';
  }
  if (!Number.isFinite(payment) || payment <= 0) {
    return 'Оплата на ноль ничего не меняет — впишите сумму больше нуля.';
  }
  return null;
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
