import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { PAYMENT_FUNNEL, endOfDay, listPayments, startOfDay } from '../sales/sales';
import { customerName } from '../sales/dealStatus';
import type { PaymentFunnelKey, PaymentListRow, PaymentsPage } from '../sales/sales';
import { count, shown } from '../ui/plural';
import { shortDate } from '../ui/shortDate';
import { useMounted } from '../ui/useMounted';

/**
 * Реестр платежей: все деньги компании одним списком (задача 0045).
 *
 * <p><b>Как выглядело до него.</b> Владелец сводит кассу по вечерам, и ответить
 * ему было нечем: платежи видны были только внутри сделки и в карточке
 * клиента, то есть по одному и по знакомому имени. Источник платежа система
 * пишет с задачи 0024, а отчёт по источникам отвечает суммами за месяц —
 * «что это за расход в четверг» суммой не объясняется.
 *
 * <p>Экран только читает. Принять оплату, вернуть деньги, пополнить счёт
 * по-прежнему можно там же, где и раньше, — в сделке и в карточке клиента:
 * второй способ делать то же самое разошёлся бы с первым.
 *
 * <p><b>Приход и расход — разные колонки, а не сумма со знаком.</b> Сумма
 * платежа в базе всегда положительная, знак несёт направление, и «−1 500»
 * в общей колонке пришлось бы читать глазами; здесь строка сама говорит,
 * в какую сторону двигались деньги.
 *
 * <p>Подвал считает по <b>всей выборке отбора</b>, а не по показанной
 * странице: верное число под неверным списком хуже отсутствующего. Обрезка
 * названа отдельной строкой — то же правило, что у реестра возвратов
 * и у поиска продавца.
 */
export function PaymentsScreen({ onOpenDeal }: { onOpenDeal: (dealId: number) => void }) {
  const mounted = useMounted();
  const [funnel, setFunnel] = useState<PaymentFunnelKey>('ALL');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  // Набранное отдельно от отправленного: период применяется по «Показать»,
  // а не на каждое нажатие в поле даты — за ним идут на сервер.
  const [period, setPeriod] = useState({ from: '', to: '' });
  const [size, setSize] = useState(PAGE);
  const [page, setPage] = useState<PaymentsPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    setPage(null);
    setError(null);
    listPayments(
      funnel,
      period.from === '' ? '' : startOfDay(period.from),
      period.to === '' ? '' : endOfDay(period.to),
      size,
    )
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
  }, [funnel, period, size, mounted]);

  // Воронка — часть отбора, а не отдельная от него вещь: пусто в «Расходных»
  // означает «расходов по этому отбору нет», а не «платежей ещё не было».
  const filtered = funnel !== 'ALL' || period.from !== '' || period.to !== '';

  return (
    <section className="screen screen--wide">
      <h2>Платежи</h2>

      <form
        className="filter-row filter-row--search"
        onSubmit={(e) => {
          e.preventDefault();
          setSize(PAGE);
          setPeriod({ from: fromDate, to: toDate });
        }}
      >
        <label className="field">
          с
          <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
        </label>
        <label className="field">
          по
          <input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} />
        </label>
        <button type="submit">Показать</button>
      </form>

      <div className="funnel-layout">
        <nav className="funnel">
          {PAYMENT_FUNNEL.map((f) => (
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
                {filtered ? 'По этому отбору платежей нет' : 'Платежей ещё не было'}
              </p>
            ) : (
              /* Семь колонок в телефон не помещаются, а без этой обёртки вбок
                 уезжает вся страница вместе с рельсом — записанная ловушка
                 проекта. Прокручивается таблица внутри своих границ. */
              <div className="table-scroll">
                <table className="report">
                  <thead>
                    <tr>
                      <th>Дата</th>
                      <th>Клиент</th>
                      <th>По сделке</th>
                      <th>Способ оплаты</th>
                      <th>Комментарий</th>
                      <th className="num">Приход</th>
                      <th className="num">Расход</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((row) => (
                      <Row key={row.id} row={row} onOpenDeal={onOpenDeal} />
                    ))}
                  </tbody>
                  <tfoot>
                    {/* Приход, расход и итог посчитаны сервером по всему
                        отбору — не по показанным строкам: список обрезан
                        пределом, и сложенные строки врали бы ровно на то,
                        чего не видно. */}
                    <tr>
                      <td colSpan={7}>
                        Платежей: {count(page.total)} · приход {money(page.income)} · расход{' '}
                        {money(page.expense)} · итого {money(page.net)}
                      </td>
                    </tr>
                    {page.items.length < page.total && (
                      <tr>
                        <td colSpan={7} className="muted">
                          Показаны первые{' '}
                          {shown(page.items.length, page.total, 'платёж', 'платежа', 'платежей')}
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
  row: PaymentListRow;
  onOpenDeal: (dealId: number) => void;
}) {
  const income = row.direction === 'IN';
  const dealId = row.dealId;
  return (
    <tr>
      <td>{shortDate(row.paidAt)}</td>
      {/* Платёж без клиента — это деньги по сделке, которую не оформляли
          на определённого покупателя (розница, возврат по заказу площадки).
          Зовётся он тем же словом, что и везде: «Частное лицо» (решение
          владельца от 12 сентября 2026, задача 0062). Прежнее «Без клиента»
          владелец, сводящий кассу, читал как другой смысл, чем «Частное
          лицо» в реестре возвратов, — и шёл искать разницу, которой нет. */}
      <td>{customerName(row.customerName)}</td>
      <td>
        {dealId === null ? (
          /* Платёж без сделки — это пополнение или выдача с лицевого счёта,
             и прочерк тут утверждение, а не пустая клетка. */
          '—'
        ) : (
          <button type="button" className="button--ghost" onClick={() => onOpenDeal(dealId)}>
            {row.dealNumber ?? dealId}
          </button>
        )}
      </td>
      {/* Пусто — способ не записан: до задачи 0024 источник не писали вовсе,
          и у переехавшего клиента такова вся история. Это незаполненное поле,
          а не «прочее», — тем же словом его называет отчёт по источникам. */}
      <td>{row.sourceName ?? 'Источник не указан'}</td>
      <td>{row.comment ?? ''}</td>
      <td className="num">{income ? money(row.amount) : ''}</td>
      <td className="num">{income ? '' : money(row.amount)}</td>
    </tr>
  );
}

/**
 * Рубли: та же сумма и тем же видом, что в карточке сделки.
 *
 * <p>Принимает число, а не строку: `numeric` Jackson отдаёт числом JSON,
 * и объявить его строкой значит соврать в типе — на этом уже падал экран
 * выгрузок.
 */
function money(value: number): string {
  return `${value.toLocaleString('ru-RU')} ₽`;
}
