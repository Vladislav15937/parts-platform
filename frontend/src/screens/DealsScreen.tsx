import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  DEAL_FUNNEL,
  dealBoard,
  listDeals,
  reservationTerm,
} from '../sales/sales';
import { dealStatusName } from '../sales/dealStatus';
import type {
  DealBoard, DealBoardCard, DealBoardOption, DealFunnelKey, DealListRow, DealsPage,
} from '../sales/sales';
import { count, shown } from '../ui/plural';
import { shortDate } from '../ui/shortDate';
import { useMounted } from '../ui/useMounted';

/**
 * Раздел «Сделки»: два взгляда на одни и те же документы.
 *
 * <p><b>Как выглядело до него.</b> На экране «Продажа» была ровно одна дорога
 * к чужой сделке — «Найти сделку клиента», — и она спрашивала **только
 * клиента**. Продавец, вышедший на смену, не видел ни что отложено вчера,
 * ни что просрочено, ни сколько сделок висит на нём самом. «Я вчера у вас
 * откладывал, фамилия Петров» работало, только если фамилия записана так же,
 * как её сейчас произнесли — записывал-то другой продавец; а «мне звонили,
 * деталь номер 111 157» не работало вовсе: пути от товара к сделке
 * не существовало.
 *
 * <p>Экран только читает — в обоих видах. Выдача, оплата, отмена и возврат
 * остаются в карточке сделки на вкладке «Продажа»: нажатие открывает её там же
 * (`onOpenDeal`), тем же приёмом, что реестр возвратов и карточка клиента.
 *
 * <p>Себестоимости и наценки здесь нет: раздел открыт продавцу, а это отчёты
 * владельца.
 */
export function DealsScreen({ onOpenDeal }: { onOpenDeal: (dealId: number) => void }) {
  // «Списком» — то, чем раздел был до доски: переключатель вида ничего
  // не меняет в поведении прежнего экрана, он даёт второй взгляд на те же
  // сделки. Открывается раздел на нём же: доска отвечает на «что делать
  // сегодня», список — на «найди мне вот эту сделку», и второе продавец
  // делает чаще.
  const [view, setView] = useState<'list' | 'board'>('list');

  return (
    <section className="screen screen--wide">
      <div className="screen-head">
        <h2>Сделки</h2>
        <nav className="tabs">
          <button
            type="button"
            className={view === 'list' ? 'tab tab--active' : 'tab'}
            onClick={() => setView('list')}
          >
            Списком
          </button>
          <button
            type="button"
            className={view === 'board' ? 'tab tab--active' : 'tab'}
            onClick={() => setView('board')}
          >
            По статусам
          </button>
        </nav>
      </div>

      {view === 'list'
        ? <DealsList onOpenDeal={onOpenDeal} />
        : <DealsBoard onOpenDeal={onOpenDeal} />}
    </section>
  );
}

/**
 * Доска сделок по состояниям (задача 0052): первый экран смены.
 *
 * <p><b>Что она отвечает и чего не отвечает список.</b> Продавец, вышедший
 * на смену, спрашивает не «покажи все сделки», а «что мне сегодня делать»:
 * сколько просрочено, сколько ждёт оплаты, что готово к выдаче. Воронка
 * списка на это не отвечает — чтобы узнать число выданных, надо переключиться
 * и потерять из виду отложенные, а стадий «ждёт оплаты» и «истёк срок»
 * у неё нет вовсе.
 *
 * <p><b>Колонки считает сервер, а не экран.</b> Счётчик над колонкой — это
 * все сделки, попавшие в неё, а не карточки, которые доехали: разложи доску
 * на клиенте по загруженной странице, и «Истек срок 58» превратилось бы
 * в «50» ровно там, где число и важно. Слова колонок приезжают оттуда же,
 * где `CASE` по ним раскладывает, — иначе два списка названий разошлись бы
 * молча.
 *
 * <p>Экран только читает: продлить резерв, принять оплату и выдать
 * по-прежнему можно в карточке сделки, куда уводит нажатие на карточку.
 */
function DealsBoard({ onOpenDeal }: { onOpenDeal: (dealId: number) => void }) {
  const mounted = useMounted();
  // Умолчание у всех трёх — «Все»: доска открывается на всём, что висит
  // на компании, а сужают её уже руками.
  const [warehouse, setWarehouse] = useState<number | null>(null);
  const [source, setSource] = useState<number | null>(null);
  const [manager, setManager] = useState<number | null>(null);
  const [board, setBoard] = useState<DealBoard | null>(null);
  // Значения отборов живут отдельно от колонок и переживают перезапрос.
  // Обнулись они вместе с доской — выбранный склад пропал бы из своего же
  // списка, пока идёт ответ: отбор поставлен, а снять его нечем, и это
  // ровно то запирание, из-за которого отбор и не прячут.
  const [choices, setChoices] = useState<Choices | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    setBoard(null);
    setError(null);
    dealBoard(warehouse, source, manager)
      .then((found) => {
        if (!ignore && mounted.current) {
          setBoard(found);
          setChoices({
            warehouses: found.warehouses,
            sources: found.sources,
            managers: found.managers,
          });
        }
      })
      .catch((cause) => {
        if (!ignore && mounted.current) {
          setError(cause instanceof ApiError ? cause.message : 'Доска не загрузилась');
        }
      });
    return () => { ignore = true; };
  }, [warehouse, source, manager, mounted]);

  return (
    <>
      {/* Отбор рисуется и на пустой доске: спрятанный вместе с колонками,
          он запирает продавца — сужено, ничего не видно, а снять нечем. */}
      <div className="filter-row">
        <Picker
          label="Склад выдачи"
          value={warehouse}
          options={choices?.warehouses ?? []}
          onPick={setWarehouse}
        />
        <Picker
          label="Источник"
          value={source}
          options={choices?.sources ?? []}
          onPick={setSource}
        />
        <Picker
          label="Ответственный"
          value={manager}
          options={choices?.managers ?? []}
          onPick={setManager}
        />
      </div>

      {/* Три состояния различимы: грузим, пусто, не смогли узнать. */}
      {error !== null && <p className="note note--error">{error}</p>}
      {board === null && error === null && <p className="note">Загружаем…</p>}
      {board !== null && (
        <div className="deal-board">
          {board.columns.map((column) => (
            <section className="deal-board__column" key={column.key}>
              <h3 className="deal-board__head">
                {column.title}
                {' '}
                <span className="deal-board__count">{count(column.count)}</span>
              </h3>
              <div className="deal-board__cards">
                {column.cards.map((card) => (
                  <BoardCard key={card.id} card={card} onOpenDeal={onOpenDeal} />
                ))}
              </div>
              {/* Колонка обрезана сотней карточек, и молчать об этом нельзя:
                  счётчик над ней говорит про все, а видно не все. */}
              {column.cards.length < column.count && (
                <p className="muted">
                  Показаны первые{' '}
                  {shown(column.cards.length, column.count, 'сделка', 'сделки', 'сделок')}
                </p>
              )}
            </section>
          ))}
        </div>
      )}
    </>
  );
}

/** Значения трёх отборов: то, что встретилось в незакрытых сделках. */
type Choices = Pick<DealBoard, 'warehouses' | 'sources' | 'managers'>;

/** Отбор доски: «Все» плюс то, что встретилось в незакрытых сделках. */
function Picker({
  label,
  value,
  options,
  onPick,
}: {
  label: string;
  value: number | null;
  options: DealBoardOption[];
  onPick: (id: number | null) => void;
}) {
  return (
    <label className="field">
      {label}
      <select
        value={value === null ? '' : String(value)}
        onChange={(e) => onPick(e.target.value === '' ? null : Number(e.target.value))}
      >
        <option value="">Все</option>
        {options.map((option) => (
          <option key={option.id} value={String(option.id)}>{option.name}</option>
        ))}
      </select>
    </label>
  );
}

/**
 * Что карточка говорит о себе — по стадии, а не по сырому статусу документа.
 *
 * <p><b>Стадия вычисляется, статус хранится, и у одной колонки они
 * расходятся.</b> Полностью оплаченная невыданная сделка стоит в «Готов
 * к выдаче», а документ у неё так и остаётся `RESERVED` со сроком резерва —
 * и подписанная сырым статусом карточка говорила «Отложена до 15 сентября»,
 * то есть «ещё не оплачена, ждём до этой даты». Ровно противоположное
 * действительности, и человек читает именно так.
 *
 * <p>Названы здесь все пять стадий, а не одна сломанная: соответствие
 * «стадия — состояние, о котором она говорит» — это то, что экран обязан
 * знать про доску, и написанное для одной колонки вернуло бы ту же ошибку
 * в соседней. Возвращать её тихо: вёрстка на месте, слова неверные.
 */
const BOARD_STAGE_STATUS: Record<string, string> = {
  NEW: 'DRAFT',
  EXPIRED: 'RESERVED',
  AWAITING_PAYMENT: 'RESERVED',
  PARTLY_PAID: 'RESERVED',
  READY: 'READY',
};

/**
 * Карточка колонки.
 *
 * <p>Внесённое показывается только у частично оплаченной: у неоплаченной это
 * ноль, у полностью оплаченной — та же сумма, что строкой выше, и в обоих
 * случаях второе число рядом с первым читается дольше, чем несёт смысла.
 * Считается оно по самим деньгам, а не по колонке, — иначе экран повторял бы
 * решение сервера своими словами.
 */
function BoardCard({
  card,
  onOpenDeal,
}: {
  card: DealBoardCard;
  onOpenDeal: (dealId: number) => void;
}) {
  const paid = Number(card.paidAmount);
  const partly = paid > 0 && paid < Number(card.totalAmount);
  // Незнакомая стадия (сервер завёл шестую колонку, экран о ней ещё не знает)
  // откатывается к состоянию документа: сырое состояние видно и объяснимо,
  // выдуманное слово — нет. Та же причина, по которой `dealStatusName`
  // возвращает незнакомый код как есть.
  const state = BOARD_STAGE_STATUS[card.stage] ?? card.status;
  const term = reservationTerm({ status: state, reservedUntil: card.reservedUntil });
  return (
    <button type="button" className="deal-card" onClick={() => onOpenDeal(card.id)}>
      <span className="deal-card__line">
        <strong>№{card.number ?? card.id}</strong>
        <span>{money(card.totalAmount)}</span>
      </span>
      <span className="deal-card__line muted">
        <span>{shortDate(card.createdAt)}</span>
        {partly && <span className="deal-card__paid">{money(card.paidAmount)}</span>}
      </span>
      {/* Состояние и срок — теми же словами и той же функцией, что в списке
          и в карточке сделки: копия правила «просроченный не показывает
          вчерашнее число» разошлась бы с оригиналом на первой правке.
          Слово при этом берётся от стадии: у готовой к выдаче сделки
          документ всё ещё «Отложена», и сроку резерва на ней взяться
          неоткуда. */}
      <span className="deal-card__state">{dealStatusName(state)}</span>
      {term !== null && (
        <span className={term.expired ? 'deal-card__state note--error' : 'deal-card__state muted'}>
          {term.expired ? 'срок истёк' : `до ${term.day}`}
        </span>
      )}
      {/* У заказа с площадки покупателя нет вовсе, и строки о нём тоже. */}
      {card.customerName !== null && (
        <span className="deal-card__customer">{card.customerName}</span>
      )}
    </button>
  );
}

/**
 * Список сделок: воронка по состоянию, поиск, отбор «мои».
 *
 * <p>Вместо курсора — растущий предел, как у реестра возвратов: список читают
 * с конца и вглубь не листают.
 */
function DealsList({ onOpenDeal }: { onOpenDeal: (dealId: number) => void }) {
  const mounted = useMounted();
  // Отложенные первыми: это то, что требует действия.
  const [funnel, setFunnel] = useState<DealFunnelKey>('RESERVED');
  const [mine, setMine] = useState(false);
  // Набранное отдельно от отправленного: список перезапрашивается по «Найти»,
  // а не на каждую букву — за ним ходят на сервер, а не отбирают загруженное.
  const [draft, setDraft] = useState('');
  const [query, setQuery] = useState('');
  const [size, setSize] = useState(PAGE);
  const [page, setPage] = useState<DealsPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    setPage(null);
    setError(null);
    listDeals(funnel, query, mine, size)
      .then((found) => {
        if (!ignore && mounted.current) {
          setPage(found);
        }
      })
      .catch((cause) => {
        if (!ignore && mounted.current) {
          setError(cause instanceof ApiError ? cause.message : 'Список не загрузился');
        }
      });
    return () => { ignore = true; };
  }, [funnel, query, mine, size, mounted]);

  const searching = query.trim() !== '';

  return (
    <>
      <form
        className="filter-row filter-row--search"
        onSubmit={(e) => {
          e.preventDefault();
          setSize(PAGE);
          setQuery(draft);
        }}
      >
        <label className="field">
          Поиск
          <input
            value={draft}
            placeholder="Номер сделки, клиент или код детали"
            onChange={(e) => setDraft(e.target.value)}
          />
        </label>
        {/* «Мои» и «все» — переключатель, а не отдельная воронка: продавец
            чаще ищет своё, но чужое ему тоже нужно — возвращают не тому,
            кто продавал. */}
        <label className="field">
          Чьи
          <select
            value={mine ? 'mine' : 'all'}
            onChange={(e) => {
              setSize(PAGE);
              setMine(e.target.value === 'mine');
            }}
          >
            <option value="all">Все продавцы</option>
            <option value="mine">Мои</option>
          </select>
        </label>
        <button type="submit">Найти</button>
      </form>

      <div className="funnel-layout">
        <nav className="funnel">
          {DEAL_FUNNEL.map((f) => (
            <button
              key={f.key}
              type="button"
              className={funnel === f.key ? 'funnel__item funnel__item--active' : 'funnel__item'}
              onClick={() => {
                setSize(PAGE);
                setFunnel(f.key);
              }}
            >
              {f.label}
            </button>
          ))}
        </nav>

        <div className="funnel-body">
          {/* Три состояния различимы: грузим, пусто, не смогли узнать.
              «Загружаем…» показывается, пока грузим, а не пока пусто. */}
          {error !== null && <p className="note note--error">{error}</p>}
          {page === null && error === null && <p className="note">Загружаем…</p>}
          {page !== null && (
            page.items.length === 0 ? (
              <p className="note">
                {searching ? 'Ничего не найдено' : 'Сделок в этом состоянии нет'}
              </p>
            ) : (
              /* Шесть колонок в телефон не помещаются, а без этой обёртки
                 вбок уезжает вся страница вместе с рельсом — записанная
                 ловушка проекта. Прокручивается таблица внутри своих границ. */
              <div className="table-scroll">
                <table className="report">
                  <thead>
                    <tr>
                      <th>Номер/дата</th>
                      <th>Клиент</th>
                      <th className="num">Сумма</th>
                      <th className="num">Оплачено</th>
                      <th>Состояние</th>
                      <th>Ответственный</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((row) => (
                      <Row key={row.id} row={row} onOpenDeal={onOpenDeal} />
                    ))}
                  </tbody>
                  <tfoot>
                    {/* Счёт по отбору целиком, а не по показанному: список
                        обрезан пределом, и счётчик, считающий строки экрана,
                        врал бы ровно на то, чего не видно. */}
                    <tr>
                      <td colSpan={6}>
                        Сделок: {count(page.total)}
                      </td>
                    </tr>
                    {page.items.length < page.total && (
                      <tr>
                        <td colSpan={6} className="muted">
                          Показаны первые{' '}
                          {shown(page.items.length, page.total, 'сделка', 'сделки', 'сделок')}
                        </td>
                      </tr>
                    )}
                  </tfoot>
                </table>
              </div>
            )
          )}

          {page !== null && page.items.length < page.total && (
            <button type="button" className="button--ghost" onClick={() => setSize(size + PAGE)}>
              Показать ещё
            </button>
          )}
        </div>
      </div>
    </>
  );
}

const PAGE = 50;

function Row({
  row,
  onOpenDeal,
}: {
  row: DealListRow;
  onOpenDeal: (dealId: number) => void;
}) {
  // Срок резерва в той же клетке, что и состояние: «отложена» без числа
  // не говорит ничего — освободится деталь завтра или через неделю, из списка
  // не понять. Просроченный красным и словами, а не вчерашним числом:
  // у живого клиента просрочена больше половины отложенных сделок, и это
  // очередь на обзвон, а не срок.
  const term = reservationTerm(row);
  return (
    <tr className="row--clickable" onClick={() => onOpenDeal(row.id)}>
      <td>
        <strong>№{row.number ?? row.id}</strong>
        <div className="muted">{shortDate(row.createdAt)}</div>
      </td>
      {/* У заказа с площадки клиента нет вовсе — покупателя она не называет. */}
      <td>{row.customerName ?? 'Без клиента'}</td>
      <td className="num">{money(row.totalAmount)}</td>
      <td className="num">{money(row.paidAmount)}</td>
      <td>
        {dealStatusName(row.status)}
        {term !== null && (
          <div className={term.expired ? 'note--error' : 'muted'}>
            {term.expired ? 'срок истёк' : `до ${term.day}`}
          </div>
        )}
      </td>
      <td>{row.managerName ?? ''}</td>
    </tr>
  );
}

/** Рубли: строка сделки на экране продавца показывает ту же сумму. */
function money(value: string): string {
  return `${Number(value).toLocaleString('ru-RU')} ₽`;
}
