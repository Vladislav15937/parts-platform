import { useEffect, useState } from 'react';
import { plural, shown } from '../ui/plural';
import { ApiError } from '../api/client';
import { donorTitle, listDonors } from '../intake/donors';
import type { DonorEntry } from '../intake/donors';
import {
  customerSettlements,
  day,
  donorItems,
  donorProfitability,
  managerSales,
  money,
  originFooter,
  pieces,
  reportSupplies,
  salesBySource,
  summary,
  supplyItems,
  unknownShare,
  monthName,
  monthOf,
  shiftMonth,
} from '../reports/reports';
import type {
  DonorReport,
  ManagerReport,
  OriginItem,
  OriginPage,
  OriginTab,
  SettlementReport,
  SourceReport,
  Summary,
  SupplyOption,
} from '../reports/reports';

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
  const [donorList, setDonorList] = useState<DonorEntry[]>([]);
  const [supplyList, setSupplyList] = useState<SupplyOption[]>([]);
  // У переехавшего клиента 441 машина: списком их не пролистать.
  const [donorFind, setDonorFind] = useState('');

  useEffect(() => {
    void managerSales(month)
      .then(setManagers)
      .catch((cause) => setError(describe(cause, 'Отчёт по продажам не загрузился')));
  }, [month]);

  useEffect(() => {
    // Тот же месяц, что и у отчёта по менеджерам: владелец смотрит их рядом,
    // и разные периоды на соседних таблицах сравнивать нельзя.
    void salesBySource(month)
      .then(setSources)
      .catch((cause) => setError(describe(cause, 'Отчёт по каналам не загрузился')));
  }, [month]);

  useEffect(() => {
    void donorProfitability()
      .then(setDonors)
      .catch((cause) => setError(describe(cause, 'Отчёт по машинам не загрузился')));
    void customerSettlements()
      .then(setSettlements)
      .catch((cause) => setError(describe(cause, 'Расчёты с клиентами не загрузились')));
    void summary()
      .then(setOverview)
      .catch((cause) => setError(describe(cause, 'Сводка не загрузилась')));
    // Списки для выбора: машины — те же, что на экране машин, партии —
    // все, включая закрытые. Про закрытый контейнер и спрашивают
    // «окупился ли», а справочник приёмки такие прячет.
    void listDonors()
      .then(setDonorList)
      .catch((cause) => setError(describe(cause, 'Список машин не загрузился')));
    void reportSupplies()
      .then((loaded) => setSupplyList(loaded.rows))
      .catch((cause) => setError(describe(cause, 'Список поставок не загрузился')));
  }, []);

  useEffect(() => {
    if (origin === null) {
      setPage(null);
      setItems([]);
      return;
    }
    setLoadingItems(true);
    setItemsError(null);
    void loadItems(origin, tab, null)
      .then((loaded) => {
        setPage(loaded);
        setItems(loaded.rows);
      })
      .catch((cause) => setItemsError(describe(cause, 'Позиции не загрузились')))
      .finally(() => setLoadingItems(false));
  }, [origin, tab]);

  /** «Показать ещё»: дописывает следующую страницу, не трогая итог. */
  function more() {
    if (origin === null || page === null || page.nextAfter === null) {
      return;
    }
    setLoadingItems(true);
    void loadItems(origin, tab, page.nextAfter)
      .then((loaded) => {
        setPage(loaded);
        setItems((shownRows) => [...shownRows, ...loaded.rows]);
      })
      .catch((cause) => setItemsError(describe(cause, 'Позиции не загрузились')))
      .finally(() => setLoadingItems(false));
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
                    <th>Номер</th>
                    <th>Тип запчасти</th>
                    <th>Наименование</th>
                    <th className="num">Количество</th>
                    <th className="num">Цена</th>
                    <th className="num">Себестоимость</th>
                    <th>Номер поступления</th>
                    <th>Дата</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((row) => (
                    <tr key={row.partId}>
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
        </>
      )}
    </section>
  );
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
