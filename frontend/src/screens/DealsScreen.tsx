import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  DEAL_FUNNEL,
  dealStatusName,
  listDeals,
  reservationTerm,
} from '../sales/sales';
import type { DealFunnelKey, DealListRow, DealsPage } from '../sales/sales';
import { count, shown } from '../ui/plural';
import { shortDate } from '../ui/shortDate';
import { useMounted } from '../ui/useMounted';

/**
 * Список сделок продавца.
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
 * <p>Экран только читает. Выдача, оплата, отмена и возврат остаются в карточке
 * сделки на вкладке «Продажа» — нажатие на строку открывает её там же
 * (`onOpenDeal`), тем же приёмом, что реестр возвратов и карточка клиента.
 *
 * <p>Себестоимости и наценки здесь нет: список открыт продавцу, а это отчёты
 * владельца.
 *
 * <p>Вместо курсора — растущий предел, как у реестра возвратов: список читают
 * с конца и вглубь не листают.
 */
export function DealsScreen({ onOpenDeal }: { onOpenDeal: (dealId: number) => void }) {
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
    <section className="screen screen--wide">
      <h2>Сделки</h2>

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
    </section>
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
