import { useEffect, useState } from 'react';
import { count, plural, shown } from '../ui/plural';
import { ApiError } from '../api/client';
import { donorTitle, listDonors } from '../intake/donors';
import type { DonorEntry } from '../intake/donors';
import {
  customerSettlements,
  day,
  dayOf,
  donorItems,
  donorProfitability,
  managerSales,
  money,
  originFooter,
  paymentsBySource,
  pieces,
  reportSupplies,
  salesBySource,
  soldFilterSet,
  soldFooter,
  soldItems,
  soldItemsExportUrl,
  summary,
  supplyItems,
  unknownShare,
  monthName,
  monthOf,
  shiftMonth,
  NO_SOLD_FILTER,
} from '../reports/reports';
import type {
  DonorReport,
  ManagerReport,
  OriginItem,
  OriginPage,
  OriginTab,
  PaymentReport,
  SettlementReport,
  SoldItem,
  SoldItemsFilter,
  SoldItemsPage,
  SourceReport,
  Summary,
  SupplyOption,
} from '../reports/reports';
import { OriginCharts } from './OriginCharts';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import { paymentSourceTypeLabel } from '../sales/sales';
import { useMounted } from '../ui/useMounted';

/**
 * Отчёты владельца.
 *
 * <p>Два вопроса, ради которых их открывают: сколько платить менеджерам
 * и стоит ли брать такие машины. Оба — то, на что прямо жалуются пользователи
 * системы, с которой к нам переходят.
 *
 * <p>Продажи — за месяц, а не за всё время: премию считают за период, и цифра
 * с начала работы для этого бесполезна.
 *
 * <p>У доноров убыточные сверху, но «убыток» у только что купленной машины
 * ничего не значит — с неё ещё не сняли. Поэтому в строке видно и сколько ещё
 * лежит на складе, и сколько позиций из общего продано: по ним отличают
 * плохую машину от свежей.
 */
interface Props {
  canRead: boolean;
}

/**
 * Что выбрано в разрезе: машина или партия.
 *
 * <p>Партия с пустым номером — «поставка не указана»: товар, заведённый
 * без партии. Это отдельный разрез, а не «все подряд».
 */
type Origin =
  | { kind: 'donor'; id: number }
  | { kind: 'supply'; id: number | null };

/** Вкладки в том же порядке и теми же словами, что у ориентира. */
const TABS: Array<[OriginTab, string]> = [
  ['received', 'Поступило'],
  ['sold', 'Продано'],
  ['written-off', 'Списано'],
  ['remaining', 'Остатки'],
];

export function ReportsScreen({ canRead }: Props) {
  const [month, setMonth] = useState(monthOf(new Date()));
  const [managers, setManagers] = useState<ManagerReport | null>(null);
  const [donors, setDonors] = useState<DonorReport | null>(null);
  const [sources, setSources] = useState<SourceReport | null>(null);
  // Платежи по источникам: чем платили и сколько этим способом прошло.
  const [payments, setPayments] = useState<PaymentReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Расчёты с клиентами: авансы, долги и сверка. Число обязательств без
  // ответа «сходится ли» — спокойствие без основания.
  const [settlements, setSettlements] = useState<SettlementReport | null>(null);
  // Сводно: сколько лежит на складе и сколько висит в незакрытых сделках.
  // Единственный блок экрана без периода — оба числа существуют «сейчас».
  const [overview, setOverview] = useState<Summary | null>(null);

  // Разрез по машине и по партии: что поступило, что продано, что списано
  // и что лежит до сих пор — позициями, а не числами.
  const [origin, setOrigin] = useState<Origin | null>(null);
  const [tab, setTab] = useState<OriginTab>('received');
  const [page, setPage] = useState<OriginPage | null>(null);
  // Строки копятся: «Показать ещё» дописывает страницу, а не заменяет её.
  const [items, setItems] = useState<OriginItem[]>([]);
  const [loadingItems, setLoadingItems] = useState(false);
  const [itemsError, setItemsError] = useState<string | null>(null);
  // Графики окупаемости во времени: окно поверх экрана, а не вкладка.
  // «Окупилась ли» таблица отвечает, «когда» — только график.
  const [charts, setCharts] = useState(false);
  const [donorList, setDonorList] = useState<DonorEntry[]>([]);
  const [supplyList, setSupplyList] = useState<SupplyOption[]>([]);
  // У переехавшего клиента 441 машина: списком их не пролистать.
  const [donorFind, setDonorFind] = useState('');

  // Проданные позиции строками: за сколько ушла, во сколько обошлась,
  // сколько на ней заработали. Набранное живёт отдельно от отправленного —
  // отбор уходит в запрос по «Показать», а не по каждой цифре в поле даты.
  const [soldDraft, setSoldDraft] = useState<SoldItemsFilter>(NO_SOLD_FILTER);
  const [soldFilter, setSoldFilter] = useState<SoldItemsFilter>(NO_SOLD_FILTER);
  const [soldPage, setSoldPage] = useState<SoldItemsPage | null>(null);
  const [sold, setSold] = useState<SoldItem[]>([]);
  const [soldManagers, setSoldManagers] = useState<Array<{ id: number; name: string }>>([]);
  const [loadingSold, setLoadingSold] = useState(false);
  const [soldError, setSoldError] = useState<string | null>(null);
  const [warehouseList, setWarehouseList] = useState<Warehouse[]>([]);
  // Поиск машины в отборе проданного — свой, а не общий с разрезом ниже:
  // это два разных вопроса, и набранное в одном не должно сужать другой.
  const [soldDonorFind, setSoldDonorFind] = useState('');
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void managerSales(month)
      .then((found) => { if (mounted.current) setManagers(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Отчёт по продажам не загрузился'));
      });
  }, [month, mounted]);

  useEffect(() => {
    // Тот же месяц, что и у отчёта по менеджерам: владелец смотрит их рядом,
    // и разные периоды на соседних таблицах сравнивать нельзя.
    void salesBySource(month)
      .then((found) => { if (mounted.current) setSources(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Отчёт по каналам не загрузился'));
      });
  }, [month, mounted]);

  useEffect(() => {
    // Тот же месяц: «сколько прошло наличными» владелец смотрит рядом
    // с выручкой, и разные периоды на соседних таблицах сравнивать нельзя.
    void paymentsBySource(month)
      .then((found) => { if (mounted.current) setPayments(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Платежи по источникам не загрузились'));
      });
  }, [month, mounted]);

  useEffect(() => {
    void donorProfitability()
      .then((found) => { if (mounted.current) setDonors(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Отчёт по машинам не загрузился'));
      });
    void customerSettlements()
      .then((found) => { if (mounted.current) setSettlements(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Расчёты с клиентами не загрузились'));
      });
    void summary()
      .then((found) => { if (mounted.current) setOverview(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Сводка не загрузилась'));
      });
    // Списки для выбора: машины — те же, что на экране машин, партии —
    // все, включая закрытые. Про закрытый контейнер и спрашивают
    // «окупился ли», а справочник приёмки такие прячет.
    void listDonors()
      .then((found) => { if (mounted.current) setDonorList(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Список машин не загрузился'));
      });
    void reportSupplies()
      .then((loaded) => { if (mounted.current) setSupplyList(loaded.rows); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Список поставок не загрузился'));
      });
    // Склады для отбора проданного: список сегодняшний, а не из справочника
    // приёмки, который телефон держит в IndexedDB с понедельника.
    void listWarehouses()
      .then((found) => { if (mounted.current) setWarehouseList(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Список складов не загрузился'));
      });
  }, [mounted]);

  // Проданные позиции: первая страница по нынешнему отбору. Строки прошлого
  // отбора снимаются до запроса, а не по его приходу, — иначе между нажатием
  // «Показать» и ответом экран показывает прежние строки под новым подвалом.
  useEffect(() => {
    setSoldPage(null);
    setSold([]);
    setLoadingSold(true);
    setSoldError(null);
    void soldItems(soldFilter, null)
      .then((loaded) => {
        if (!mounted.current) return;
        setSoldPage(loaded);
        setSold(loaded.rows);
        // Список продавцов едет только с первой страницей: он считается
        // по всем продажам, а не по отобранным, и меняться ему не с чего.
        setSoldManagers(loaded.managers);
      })
      .catch((cause) => {
        if (mounted.current) setSoldError(describe(cause, 'Проданные позиции не загрузились'));
      })
      .finally(() => { if (mounted.current) setLoadingSold(false); });
  }, [soldFilter, mounted]);

  /** «Показать ещё»: дописывает следующую страницу, не трогая итог. */
  function moreSold() {
    if (soldPage === null || soldPage.nextAfter === null) {
      return;
    }
    setLoadingSold(true);
    void soldItems(soldFilter, soldPage.nextAfter)
      .then((loaded) => {
        if (!mounted.current) return;
        setSoldPage(loaded);
        setSold((shownRows) => [...shownRows, ...loaded.rows]);
      })
      .catch((cause) => {
        if (mounted.current) setSoldError(describe(cause, 'Проданные позиции не загрузились'));
      })
      .finally(() => { if (mounted.current) setLoadingSold(false); });
  }

  useEffect(() => {
    if (origin === null) {
      setPage(null);
      setItems([]);
      return;
    }
    // Числа прошлой вкладки снимаются до запроса, а не по его приходу.
    // Подвал рисуется как `originFooter(tab, page.totals)`: слово берётся
    // от новой вкладки сразу, а числа оставались от старой до ответа —
    // между нажатием «Продано» и приходом страницы экран читался как
    // «162 товара: продано — 1 168 350», то есть называл остатки продажей.
    // Окно не теоретическое: подзапрос «Продано» агрегирует весь `deal_item`
    // арендатора.
    setPage(null);
    setItems([]);
    setLoadingItems(true);
    setItemsError(null);
    void loadItems(origin, tab, null)
      .then((loaded) => {
        if (!mounted.current) return;
        setPage(loaded);
        setItems(loaded.rows);
      })
      .catch((cause) => {
        if (mounted.current) setItemsError(describe(cause, 'Позиции не загрузились'));
      })
      .finally(() => { if (mounted.current) setLoadingItems(false); });
  }, [origin, tab, mounted]);

  /** «Показать ещё»: дописывает следующую страницу, не трогая итог. */
  function more() {
    if (origin === null || page === null || page.nextAfter === null) {
      return;
    }
    setLoadingItems(true);
    void loadItems(origin, tab, page.nextAfter)
      .then((loaded) => {
        if (!mounted.current) return;
        setPage(loaded);
        setItems((shownRows) => [...shownRows, ...loaded.rows]);
      })
      .catch((cause) => {
        if (mounted.current) setItemsError(describe(cause, 'Позиции не загрузились'));
      })
      .finally(() => { if (mounted.current) setLoadingItems(false); });
  }

  if (!canRead) {
    return (
      <section className="card">
        <h2>Отчёты</h2>
        <p className="note">
          Отчёты видит владелец или менеджер: в продажах по менеджерам лежит
          зарплатная база всей смены, а в окупаемости машин — себестоимость.
        </p>
      </section>
    );
  }

  return (
    <section className="card">
      <h2>Отчёты</h2>
      {error !== null && <p className="note note--error">{error}</p>}

      {/* Сводно идёт первым блоком: «сколько у меня сейчас на складе
          в деньгах» — то, с чего владелец разборки начинает день, а все
          остальные отчёты здесь про прошлое. Настроек нет намеренно:
          у остатка и незакрытых сделок нет периода, они существуют
          «сейчас», и месяц над ними был бы враньём. */}
      <h3>Сводно</h3>

      {/* «Загружаем…» — пока грузим, а не пока пусто: иначе при отказе
          надпись висит вечно, а причина лежит рядом непоказанной. */}
      {overview === null && error === null && <p className="note">Загружаем…</p>}

      {overview !== null && (
        <>
          <h4>Остаток товара</h4>
          <div className="table-scroll">
            <table className="report">
              <thead>
                <tr>
                  <th>Вид товара</th>
                  <th className="num">Количество</th>
                  <th className="num">Сумма по розничной цене</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>Запчасти</td>
                  <td className="num">{pieces(overview.parts.qty)}</td>
                  <td className="num">{money(overview.parts.amount)}</td>
                </tr>
                {/* Колёса отдельной строкой, а не в общей куче: они продаются
                    сезоном, и владелец смотрит на них отдельно. */}
                <tr>
                  <td>Шины и диски</td>
                  <td className="num">{pieces(overview.wheels.qty)}</td>
                  <td className="num">{money(overview.wheels.amount)}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <p className="note">
            Считается то, что физически лежит на полке, по всем складам вместе, —
            вместе с отложенным под клиентов: обещанная деталь со склада никуда
            не делась. Позиция без цены попадает в количество и не попадает
            в сумму.
          </p>

          <h4>Сделки в работе</h4>
          <div className="table-scroll">
            <table className="report">
              <tbody>
                <tr>
                  <td>Количество</td>
                  <td className="num">{pieces(overview.deals.count)}</td>
                </tr>
                <tr>
                  <td>На сумму</td>
                  <td className="num">{money(overview.deals.amount)}</td>
                </tr>
                <tr>
                  <td>Сумма предоплат</td>
                  <td className="num">{money(overview.deals.prepaid)}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <p className="note">
            В работе — незакрытые: собираются, отложены или ждут клиента
            на складе выдачи. Выданная сделка отсюда уходит вместе с товаром
            со склада.
          </p>
        </>
      )}

      <hr />
      <h3>Продажи по менеджерам</h3>
      <div className="row row--between">
        <button type="button" className="button--ghost" onClick={() => setMonth(shiftMonth(month, -1))}>
          ←
        </button>
        <strong>{monthName(month)}</strong>
        <button type="button" className="button--ghost" onClick={() => setMonth(shiftMonth(month, 1))}>
          →
        </button>
      </div>

      {managers !== null && managers.rows.length === 0 && (
        <p className="note">В этом месяце продаж не было.</p>
      )}

      {managers !== null && managers.rows.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Менеджер</th>
                <th className="num">Сделок</th>
                <th className="num">Выручка</th>
                <th className="num">Наценка</th>
              </tr>
            </thead>
            <tbody>
              {managers.rows.map((row) => (
                <tr key={row.managerId ?? 'none'}>
                  {/* Сделки без менеджера — из времён до учёта продавцов.
                      Прятать их нельзя: их выручка тоже настоящая. */}
                  <td>{row.displayName ?? 'без менеджера'}</td>
                  <td className="num">{row.dealsCount}</td>
                  <td className="num">{money(row.revenue)}</td>
                  {/* Прочерк, а не ноль: «себестоимость не заведена»
                      и «продали в ноль» — разные вещи, и вторая говорит
                      владельцу, что вся выручка ушла в закупку. */}
                  <td className="num">{row.margin === null ? '—' : money(row.margin)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="note">
        Возвращённое сюда не попадает: премию платят за проданное, а не
        за привезённое обратно. Наценка — по себестоимости на момент продажи.
      </p>

      {managers !== null && withoutCost(managers) > 0 && (
        <p className="note">
          Позиций без закупочной цены: {withoutCost(managers)}. В наценку они
          не вошли — склад, загруженный из таблицы, приходит без закупок,
          и посчитанная по нему прибыль была бы завышена на всю их стоимость.
        </p>
      )}

      <hr />
      <h3>Откуда пришли продажи</h3>

      {sources !== null && sources.rows.length === 0 && (
        <p className="note">За этот месяц продаж нет.</p>
      )}

      {sources !== null && sources.rows.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Канал</th>
                <th className="num">Сделок</th>
                <th className="num">Выручка</th>
                <th className="num">Наценка</th>
              </tr>
            </thead>
            <tbody>
              {sources.rows.map((row) => (
                <tr key={row.sourceId ?? 'none'}>
                  {/* Не «прочее»: это не канал, а незаполненное поле. */}
                  <td>{row.sourceName ?? 'источник не указан'}</td>
                  <td className="num">{row.dealsCount}</td>
                  <td className="num">{money(row.revenue)}</td>
                  <td className="num">{row.margin === null ? '—' : money(row.margin)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {sources !== null && unknownShare(sources) > 0.2 && (
        <p className="note note--error">
          Без указанного источника прошло{' '}
          {Math.round(unknownShare(sources) * 100)}% выручки. Пока это так,
          сравнивать каналы между собой нельзя: «Дром принёс мало» и «продавцы
          не отмечают Дром» отсюда выглядят одинаково.
        </p>
      )}

      {/* Платежи по источникам: «сколько прошло наличными, сколько картой,
          сколько осталось в долг» — до этого источник у платежа писался
          и не читался нигде. Месяц тот же, что у соседних блоков. */}
      <hr />
      <h3>Платежи по источникам</h3>

      {payments !== null && payments.rows.length === 0 && (
        <p className="note">За этот месяц платежей не было.</p>
      )}

      {payments !== null && payments.rows.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Источник</th>
                <th className="num">Платежей</th>
                <th className="num">Приход</th>
                <th className="num">Расход</th>
                <th className="num">Итог</th>
              </tr>
            </thead>
            <tbody>
              {payments.rows.map((row) => (
                <tr key={row.sourceId ?? 'none'}>
                  <td>
                    {/* Не «прочее»: способ у платежа не записан, и лечится
                        это привычкой продавца, а не переименованием строки. */}
                    {row.sourceName ?? 'источник не указан'}
                    {row.sourceType !== null && (
                      <span className="muted"> · {paymentSourceTypeLabel(row.sourceType)}</span>
                    )}
                    {/* Архивный источник из отчёта не исчезает — платежи
                        по нему были, — но сказать об этом надо: иначе
                        владелец пойдёт искать его в справочнике. */}
                    {row.archived && <span className="muted"> · в архиве</span>}
                  </td>
                  {/* С разделителем разрядов: у живого клиента платежей
                      за месяц бывает пять тысяч, и «5124» читается хуже. */}
                  <td className="num">{count(row.payments)}</td>
                  <td className="num">{money(row.incoming)}</td>
                  {/* Прочерк, а не ноль: расхода этим способом не было. */}
                  <td className="num">{row.outgoing === 0 ? '—' : money(row.outgoing)}</td>
                  <td className={row.total < 0 ? 'num negative' : 'num'}>{money(row.total)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {payments !== null && payments.totals.payments > 0 && (
        <p className="note">
          <strong>
            Всего за месяц: {count(payments.totals.payments)}{' '}
            {plural(payments.totals.payments, 'платёж', 'платежа', 'платежей')} ·
            приход {money(payments.totals.incoming)} ·
            расход {money(payments.totals.outgoing)} ·
            итог {money(payments.totals.total)}
          </strong>
        </p>
      )}

      <p className="note">
        Приход и расход разными числами: возврат денег из кассы уменьшает
        то, что этим способом осталось, но не отменяет принятого. Итог считается
        по всем платежам месяца — сумма строк обязана с ним сходиться.
      </p>

      <hr />
      <h3>Расчёты с клиентами</h3>

      {settlements !== null && (
        <>
          <p className="note">
            Авансов {settlements.totals.advances.toLocaleString('ru-RU')} ₽
            у {settlements.totals.withAdvance}{' '}
            {plural(settlements.totals.withAdvance, 'клиента', 'клиентов', 'клиентов')}
            {' '}· долгов{' '}
            {settlements.totals.debts.toLocaleString('ru-RU')} ₽
            у {settlements.totals.withDebt}
          </p>

          {/* Сверка рядом с итогом, а не отдельной вкладкой: расхождение,
              за которым надо куда-то идти, не смотрит никто. */}
          {settlements.totals.problems.length > 0 ? (
            <div className="note note--error">
              {/* Склонение: «1 расхождений» на экране, где владелец
                  проверяет деньги, читается как небрежность — а рядом стоят
                  суммы, которым он должен верить. */}
              <p>
                Деньги не сходятся — {settlements.totals.problems.length}{' '}
                {plural(settlements.totals.problems.length,
                        'расхождение', 'расхождения', 'расхождений')}:
              </p>
              <ul>
                {settlements.totals.problems.map((p, i) => (
                  <li key={i}>
                    {p.problem}
                    {p.dealId !== null && ` · сделка ${p.dealId}`}
                    {' · '}{p.amount.toLocaleString('ru-RU')} ₽
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <p className="note">Расхождений нет: деньги на счетах сходятся с их движением.</p>
          )}

          {settlements.rows.length === 0 ? (
            <p className="note">Ни авансов, ни долгов.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Клиент</th>
                  <th className="num">Аванс</th>
                  <th className="num">Долг</th>
                  <th className="num">Сделок</th>
                </tr>
              </thead>
              <tbody>
                {settlements.rows.map((row) => (
                  <tr key={row.customerId}>
                    <td>
                      {row.customerName ?? `клиент ${row.customerId}`}
                      {row.phone !== null && <span className="muted"> · {row.phone}</span>}
                      {/* Строка розничного контрагента складывает долги
                          разных людей с улицы. Без пометки владелец читает
                          её как постоянного покупателя с сотней сделок
                          и идёт звонить — а звонить некому. Выбросить её
                          нельзя: деньги в ней настоящие, и отчёт без неё
                          выглядел бы полным и не сходился с кассой. */}
                      {row.retail && (
                        <span className="muted"> · розничные продажи, не один покупатель</span>
                      )}
                    </td>
                    <td className="num">
                      {row.accountBalance === 0
                        ? '—'
                        : row.accountBalance.toLocaleString('ru-RU')}
                    </td>
                    <td className="num">
                      {row.debt === 0 ? '—' : row.debt.toLocaleString('ru-RU')}
                    </td>
                    <td className="num">{row.unpaidDeals === 0 ? '—' : row.unpaidDeals}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {settlements.rows.length < settlements.totals.customers && (
            <p className="note">
              Показаны {shown(settlements.rows.length, settlements.totals.customers,
                'клиент', 'клиентов', 'клиентов')}, сверху самые должные.
            </p>
          )}
        </>
      )}

      <hr />
      <h3>Окупаемость машин</h3>

      {donors !== null && (
        <p className="note">
          Машин: {donors.totals.donors} · вложено {money(donors.totals.totalCost)} ·
          выручено {money(donors.totals.revenue)} · ещё на складе{' '}
          {money(donors.totals.stockValue)}
        </p>
      )}

      {donors !== null && donors.rows.length === 0 && (
        <p className="note">Машин пока нет. Донора заводят на вкладке «Машина».</p>
      )}

      {/* Список обрезан пределом, и молчать об этом нельзя: у живого клиента
          441 машина против полусотни строк, а рядом стоит «Машин: 441» —
          глаз читает это как полноту и строки не пересчитывает. Сортировка
          от убыточных, поэтому окупившиеся машины не видны вовсе, и владелец,
          не найдя свою, решает, что её нет. Та же болезнь, что была у поиска
          продавца: обрезанный список обязан говорить, что он обрезан. */}
      {donors !== null && donors.rows.length > 0
        && donors.rows.length < donors.totals.donors && (
          <p className="note">
            Показаны {shown(donors.rows.length, donors.totals.donors)},
            сверху самые убыточные.
          </p>
        )}

      {donors !== null && donors.rows.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Машина</th>
                <th className="num">Вложено</th>
                <th className="num">Выручено</th>
                <th className="num">Итог</th>
                <th className="num">На складе</th>
              </tr>
            </thead>
            <tbody>
              {donors.rows.map((row) => (
                <tr key={row.donorId}>
                  <td>
                    {/* Сначала то, чем машину зовёт владелец: марка с моделью
                        или номер из предыдущей системы. Наш внутренний код
                        ему ничего не говорит — прогон на чистой ячейке
                        показал таблицу из одних шестнадцатеричных кодов. */}
                    <strong>{row.note ?? row.legacyCode ?? row.publicCode ?? row.donorId}</strong>
                    {row.year !== null && <span className="muted"> · {row.year}</span>}
                    {row.legacyCode !== null && row.note !== null && (
                      <span className="muted"> · {row.legacyCode}</span>
                    )}
                    {row.vin !== null && <div className="muted">{row.vin}</div>}
                    {/* Продано — не колонка: пять числовых столбцов не влезают
                        в ширину экрана, а без этой доли строка не читается
                        вовсе (минус у свежей машины — это ещё не убыток).

                        «Полностью» здесь не для красоты: считаются карточки
                        с обнулённым остатком, и позиция, у которой из двух
                        штук продана одна, сюда не попадает. Без уточнения
                        «продано 0 из 6» рядом с выручкой читается как ошибка. */}
                    <div className="muted">
                      полностью продано {row.partsSold} из {row.partsTotal}
                    </div>
                  </td>
                  <td className="num">{money(row.totalCost)}</td>
                  <td className="num">{money(row.revenue)}</td>
                  <td className={Number(row.profit) < 0 ? 'num negative' : 'num'}>
                    {money(row.profit)}
                  </td>
                  <td className="num">{money(row.stockValue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="note">
        Убыточные сверху. Минус у свежей машины — это ещё не убыток: смотрите,
        сколько с неё продано и на сколько осталось на складе.
      </p>

      {/* Проданные позиции строками. Себестоимость каждой строки лежит
          в снимке с самого начала, а построчно её не было видно нигде:
          «сколько мы заработали на запчастях с этого контейнера», «на чём
          мы теряем» и «кто продаёт в минус» спрашивали у разработчика
          с SQL. Свой отбор с периодом, а не месяц сверху: вопрос сюда
          приходят задавать про год и про контейнер. */}
      <hr />
      <h3>Проданные позиции</h3>

      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          setSoldFilter(soldDraft);
        }}
      >
        <label>
          Продано с
          <input
            type="date"
            value={soldDraft.from}
            onChange={(e) => setSoldDraft({ ...soldDraft, from: e.target.value })}
          />
        </label>
        <label>
          по
          <input
            type="date"
            value={soldDraft.to}
            onChange={(e) => setSoldDraft({ ...soldDraft, to: e.target.value })}
          />
        </label>
        <label>
          Склад выдачи
          <select
            value={soldDraft.warehouseId}
            onChange={(e) => setSoldDraft({ ...soldDraft, warehouseId: e.target.value })}
          >
            <option value="">— все склады —</option>
            {warehouseList.map((w) => (
              <option key={w.id} value={w.id}>{w.name}</option>
            ))}
          </select>
        </label>
        <label>
          Ответственный
          <select
            value={soldDraft.managerId}
            onChange={(e) => setSoldDraft({ ...soldDraft, managerId: e.target.value })}
          >
            <option value="">— все —</option>
            {/* Кто продавал, а не весь список сотрудников: отбор,
                предлагающий человека без единой продажи, врёт. */}
            {soldManagers.map((m) => (
              <option key={m.id} value={m.id}>{m.name}</option>
            ))}
          </select>
        </label>
        {/* Тот же поиск, что у разреза ниже, и по той же причине: у клиента
            441 машина, списком их не пролистать. Два выбора машины на одном
            экране, из которых ищет только один, — это тот, который не ищет,
            и есть недоделанный. */}
        <label>
          Найти машину
          <input
            type="search"
            value={soldDonorFind}
            placeholder="номер, марка или заметка"
            onChange={(e) => setSoldDonorFind(e.target.value)}
          />
        </label>
        <label>
          Машина
          <select
            value={soldDraft.donorId}
            onChange={(e) => setSoldDraft({ ...soldDraft, donorId: e.target.value })}
          >
            <option value="">— все машины —</option>
            {donorList.filter((d) => matches(d, soldDonorFind)).map((d) => (
              <option key={d.id} value={d.id}>{donorTitle(d)}</option>
            ))}
          </select>
        </label>
        <label>
          Поставка
          <select
            value={soldDraft.supplyId}
            onChange={(e) => setSoldDraft({ ...soldDraft, supplyId: e.target.value })}
          >
            <option value="">— все поставки —</option>
            {supplyList.map((s) => (
              <option key={s.id} value={s.id}>
                {s.supplierName ?? s.number}
                {s.supplierName !== null && ` · ${s.number}`}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">Показать</button>
        {/* Снимается весь отбор одной кнопкой: снимать шесть полей
            по одному — то же, что не снимать вовсе. */}
        {soldFilterSet(soldDraft) && (
          <button
            type="button"
            className="button--ghost"
            onClick={() => {
              setSoldDraft(NO_SOLD_FILTER);
              setSoldFilter(NO_SOLD_FILTER);
            }}
          >
            Снять отбор
          </button>
        )}
      </form>

      {soldError !== null && <p className="note note--error">{soldError}</p>}

      {/* «Загружаем…» — пока грузим, а не пока пусто. */}
      {loadingSold && soldError === null && sold.length === 0 && (
        <p className="note">Загружаем…</p>
      )}

      {soldPage !== null && sold.length === 0 && !loadingSold && soldError === null && (
        <p className="note">
          {soldFilterSet(soldFilter)
            ? 'По этому отбору продаж нет'
            : 'Продаж пока не было'}
        </p>
      )}

      {sold.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Дата выдачи</th>
                <th>Сделка</th>
                <th>Номер товара</th>
                <th>Наименование</th>
                <th>Состояние</th>
                <th className="num">Цена продажи</th>
                <th className="num">Себестоимость</th>
                <th className="num">Выгода</th>
                <th className="num">Количество</th>
                <th>Склад выдачи</th>
                <th>Ответственный</th>
                <th>Поставка</th>
                <th>Номер донора</th>
              </tr>
            </thead>
            <tbody>
              {sold.map((row) => (
                <tr key={row.itemId}>
                  <td>{dayOf(row.soldAt)}</td>
                  <td>{row.dealNumber}</td>
                  <td>{row.publicCode ?? '—'}</td>
                  <td>{row.title}</td>
                  <td>{row.condition ?? '—'}</td>
                  <td className="num">
                    {money(row.price)}
                    {/* Прежняя цена зачёркнутой — как у ориентира: продают
                        и со скидкой, и с наценкой, и по одной цене продажи
                        этого не видно вовсе. */}
                    {row.listPrice !== row.price && (
                      <div className="muted"><s>{money(row.listPrice)}</s></div>
                    )}
                  </td>
                  {/* Прочерк, а не ноль: «закупки не было» и «досталась
                      даром» — разные утверждения. */}
                  <td className="num">
                    {row.costPrice === null ? '—' : money(row.costPrice)}
                  </td>
                  <td className={row.profit !== null && row.profit < 0 ? 'num negative' : 'num'}>
                    {row.profit === null ? '—' : money(row.profit)}
                  </td>
                  <td className="num">{pieces(row.quantity)}</td>
                  <td>{row.warehouse ?? '—'}</td>
                  <td>{row.manager ?? '—'}</td>
                  <td>{row.supplyNumber ?? '—'}</td>
                  <td>{row.donorCode ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Подвал считает весь отбор, а не показанную страницу: сумма первой
          сотни, выданная за итог, — враньё тем более наглядное, чем больше
          отобранное. Поэтому рядом и сказано, сколько показано. */}
      {soldPage !== null && sold.length > 0 && (
        <p className="note">
          <strong>{soldFooter(soldPage.totals)}</strong>
          {sold.length < soldPage.totals.items && (
            <> · показаны {shown(sold.length, soldPage.totals.items,
              'товар', 'товара', 'товаров')}</>
          )}
        </p>
      )}

      {soldPage !== null && soldPage.totals.withoutCost > 0 && (
        <p className="note">
          Позиций без закупочной цены: {soldPage.totals.withoutCost}. В себестоимость
          и выгоду они не вошли — склад, загруженный из таблицы, приходит без закупок,
          и посчитанная по ним прибыль была бы завышена на всю их стоимость.
        </p>
      )}

      <div className="row">
        {soldPage !== null && soldPage.nextAfter !== null && (
          <button type="button" onClick={moreSold} disabled={loadingSold}>
            Показать ещё
          </button>
        )}
        {/* Ссылкой, а не запросом: файл качает браузер, показывая ход,
            и вкладка при этом жива. Роль не проверяется здесь отдельно —
            раздел «Отчёты» и так открыт только владельцу и менеджеру,
            и тот же список стоит в @PreAuthorize у эндпоинта.

            Обычной ссылкой, а не `button--ghost`: тот выглядит кнопкой
            только внутри `.screen--wide .filter-row`, а «Отчёты» стоят
            на `.card` — здесь от него остаётся пустая рамка с подчёркнутым
            текстом поверх. Замерено в браузере: 108×44 с текстом по верху. */}
        <a href={soldItemsExportUrl(soldFilter)} download>
          Скачать таблицу
        </a>
      </div>

      {/* Разрез до позиций: числа по машине владелец видит и так, а вот
          «что именно лежит» спрашивать было негде — за этим он уходил
          в склад и собирал отбор руками. По партии не было и чисел. */}
      <hr />
      <h3>Что поступило с машины и с поставки</h3>

      <div className="row">
        {/* 441 машина списком — это тридцать четыре экрана подряд.
            Ищется по тому же, чем машина подписана в строке. */}
        <label>
          Найти машину
          <input
            type="search"
            value={donorFind}
            placeholder="номер, марка или заметка"
            onChange={(e) => setDonorFind(e.target.value)}
          />
        </label>

        <label>
          Машина
          <select
            value={origin !== null && origin.kind === 'donor' ? String(origin.id) : ''}
            onChange={(e) => setOrigin(
              e.target.value === '' ? null : { kind: 'donor', id: Number(e.target.value) },
            )}
          >
            <option value="">— выберите машину —</option>
            {donorList.filter((d) => matches(d, donorFind)).map((d) => (
              <option key={d.id} value={d.id}>{donorTitle(d)}</option>
            ))}
          </select>
        </label>

        <label>
          Поставка
          <select
            value={supplyValue(origin)}
            onChange={(e) => setOrigin(supplyOrigin(e.target.value))}
          >
            <option value="">— выберите поставку —</option>
            {/* Товар без партии — отдельный разрез, а не «все подряд»:
                у переехавшего клиента это всё, что заводили руками. */}
            <option value="none">- не указана -</option>
            {supplyList.map((s) => (
              <option key={s.id} value={s.id}>
                {s.supplierName ?? s.number}
                {s.supplierName !== null && ` · ${s.number}`}
              </option>
            ))}
          </select>
        </label>
      </div>

      {origin === null && (
        <p className="note">
          Выберите машину или поставку — покажем, что с неё поступило, что
          продано, что списано и что лежит до сих пор.
        </p>
      )}

      {origin !== null && (
        <>
          {/* Открытая вкладка выделена тем же способом, что на экране
              этикеток: без этого по экрану не понять, какая из четырёх
              открыта, — а цифры на всех четырёх выглядят одинаково
              правдоподобно. Поймано живым прогоном. */}
          <div className="row row--between">
            <div className="tabs">
              {TABS.map(([code, title]) => (
                <button
                  key={code}
                  type="button"
                  className={code === tab ? 'tab tab--active' : 'tab'}
                  aria-pressed={code === tab}
                  onClick={() => setTab(code)}
                >
                  {title}
                </button>
              ))}
            </div>

            {/* Окно поверх, а не отдельная вкладка: владелец смотрит график
                и возвращается к позициям, не теряя выбранной машины. */}
            <button type="button" onClick={() => setCharts(true)}>Графики</button>
          </div>

          {itemsError !== null && <p className="note note--error">{itemsError}</p>}

          {/* «Загружаем…» — пока грузим, а не пока пусто. */}
          {loadingItems && itemsError === null && items.length === 0 && (
            <p className="note">Загружаем…</p>
          )}

          {page !== null && items.length === 0 && !loadingItems && itemsError === null && (
            <p className="note">Ничего не найдено</p>
          )}

          {items.length > 0 && (
            <div className="table-scroll">
              <table className="report">
                <thead>
                  <tr>
                    {/* Порядковый номер первым, как на витрине склада
                        и на вкладке колёс: владелец, увидев позицию здесь,
                        называет её работнику вслух. Соседняя колонка
                        называется «Номер товара» тем же словом, что везде, —
                        рядом с «№ позиции» прежнее «Номер» не говорит,
                        какой из двух номеров в ней стоит. */}
                    <th className="num">№ позиции</th>
                    <th>Номер товара</th>
                    <th>Тип запчасти</th>
                    <th>Наименование</th>
                    <th className="num">Количество</th>
                    {/* На «Продано» это цена сделки, а не прайс карточки,
                        и колонка обязана называться тем, что показывает:
                        подвал там считается по настоящим продажам. */}
                    <th className="num">{tab === 'sold' ? 'Цена продажи' : 'Цена'}</th>
                    <th className="num">Себестоимость</th>
                    <th>Номер поступления</th>
                    <th>Дата</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((row) => (
                    <tr key={row.partId}>
                      <td className="num">{row.number}</td>
                      <td>{row.publicCode ?? '—'}</td>
                      {/* Прочерк, а не пусто: наименование не распознано,
                          и это правда о карточке. */}
                      <td>{row.kind ?? '—'}</td>
                      <td>{row.title}</td>
                      <td className="num">{pieces(row.quantity)}</td>
                      <td className="num">{row.price === null ? '—' : money(row.price)}</td>
                      <td className="num">
                        {row.costPrice === null ? '—' : money(row.costPrice)}
                      </td>
                      <td>{row.supplyNumber ?? '—'}</td>
                      <td>{day(row.date)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Подвал считает всю вкладку, а не показанную страницу: сумма
              первой сотни, выданная за итог, — враньё тем более наглядное,
              чем больше партия. Поэтому рядом и сказано, сколько показано. */}
          {page !== null && items.length > 0 && (
            <p className="note">
              <strong>{originFooter(tab, page.totals)}</strong>
              {items.length < page.totals.items && (
                <> · показаны {shown(items.length, page.totals.items,
                  'товар', 'товара', 'товаров')}</>
              )}
            </p>
          )}

          {page !== null && page.nextAfter !== null && (
            <button type="button" onClick={more} disabled={loadingItems}>
              Показать ещё
            </button>
          )}

          {charts && (
            <OriginCharts
              origin={origin}
              title={originTitle(origin, donorList, supplyList)}
              onClose={() => setCharts(false)}
            />
          )}
        </>
      )}
    </section>
  );
}

/**
 * Чем разрез подписан в окне графиков — тем же, чем он выбран в списке.
 *
 * <p>Внутренний номер владельцу не говорит ничего: машину он знает
 * по заметке или своему номеру, партию — по поставщику.
 */
function originTitle(origin: Origin, donors: DonorEntry[], supplies: SupplyOption[]): string {
  if (origin.kind === 'donor') {
    const donor = donors.find((d) => d.id === origin.id);
    return donor === undefined ? 'машина' : donorTitle(donor);
  }
  if (origin.id === null) {
    return 'поставка не указана';
  }
  const supply = supplies.find((s) => s.id === origin.id);
  if (supply === undefined) {
    return 'поставка';
  }
  return supply.supplierName === null
    ? supply.number
    : `${supply.supplierName} · ${supply.number}`;
}

/** Машина ищется по тому же, чем подписана в строке, плюс VIN. */
function matches(donor: DonorEntry, find: string): boolean {
  const needle = find.trim().toLowerCase();
  if (needle === '') {
    return true;
  }
  return `${donorTitle(donor)} ${donor.vin ?? ''}`.toLowerCase().includes(needle);
}

function supplyValue(origin: Origin | null): string {
  if (origin === null || origin.kind !== 'supply') {
    return '';
  }
  return origin.id === null ? 'none' : String(origin.id);
}

function supplyOrigin(value: string): Origin | null {
  if (value === '') {
    return null;
  }
  return { kind: 'supply', id: value === 'none' ? null : Number(value) };
}

function loadItems(origin: Origin, tab: OriginTab, after: number | null): Promise<OriginPage> {
  return origin.kind === 'donor'
    ? donorItems(origin.id, tab, after)
    : supplyItems(origin.id, tab, after);
}

/** Сколько позиций месяца остались без себестоимости — по всем менеджерам. */
function withoutCost(report: ManagerReport): number {
  return report.rows.reduce((sum, row) => sum + row.itemsWithoutCost, 0);
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. Отчёты считаются на сервере — повторите.';
    }
    if (cause.status === 403) {
      return 'Отчёты видит владелец или менеджер';
    }
    return cause.message;
  }
  return fallback;
}
