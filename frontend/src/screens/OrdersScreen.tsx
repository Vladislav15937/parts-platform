import { useCallback, useEffect, useState } from 'react';
import {
  acceptOrder,
  hoursUntilDeadline,
  ordersAwaitingReply,
  type Deal,
} from '../sales/sales';

/**
 * Заказы с площадок, по которым продавец ещё не ответил.
 *
 * <p>Отдельный экран, а не отметка в общем списке сделок: у Дрома
 * по защищённой сделке трое рабочих суток, после чего деньги возвращаются
 * покупателю. Заказ, потерявшийся среди сотни сделок, — это потерянные
 * деньги и рейтинг у площадки, а рейтинг ниже тысячи баллов отключает
 * защищённые сделки целиком.
 *
 * <p><b>Показывается остаток времени, а не дата.</b> «До 4 авг» продавец
 * сопоставляет с сегодняшним числом сам и ошибается; «осталось 2 часа»
 * считать не надо.
 *
 * <p><b>Заказ без резерва выделен отдельно.</b> Он записан, но товара
 * на складе нет — отвечать площадке «отправлю» по нему нельзя, и это
 * единственное, что продавцу нужно увидеть с первого взгляда.
 */
export function OrdersScreen({ canSell }: { canSell: boolean }) {
  const [orders, setOrders] = useState<Deal[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState<number | null>(null);

  const load = useCallback(() => {
    ordersAwaitingReply()
      .then((found) => {
        setOrders(found);
        setError('');
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(load, [load]);

  async function accept(deal: Deal) {
    setBusy(deal.id);
    try {
      await acceptOrder(deal.id);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  if (orders === null) {
    return <p className="note">Загружаем заказы…</p>;
  }

  return (
    <section className="screen">
      <h2>Заказы с площадок</h2>
      <p className="note">
        Заказ уже оплачен покупателем. Не ответить вовремя — значит вернуть ему
        деньги и потерять баллы рейтинга у площадки.
      </p>

      {error && <p className="note note--error">{error}</p>}

      {orders.length === 0 && (
        <p className="note">Заказов, ждущих ответа, нет.</p>
      )}

      <ul className="cards">
        {orders.map((deal) => {
          const hours = hoursUntilDeadline(deal);
          // Черновик здесь означает ровно одно: обеспечить заказ нечем.
          // Обеспеченный резервируется в момент приёма.
          const unfulfilled = deal.status === 'DRAFT';
          return (
            <li key={deal.id} className={unfulfilled ? 'card card--alert' : 'card'}>
              <div className="order-head">
                <strong>
                  {deal.marketplace === 'AVITO' ? 'Авито' : 'Дром'} № {deal.externalOrderNo}
                </strong>
                <span>{Number(deal.totalAmount).toLocaleString('ru-RU')} ₽</span>
              </div>

              <p className={hours !== null && hours < 3 ? 'note note--error' : 'note'}>
                {deadlineLabel(hours)}
              </p>

              {unfulfilled && (
                <p className="note note--error">
                  Обеспечить нечем: товара нет на складе. Заказ придётся отклонить
                  или найти замену.
                </p>
              )}

              <ul className="counts">
                {deal.items.map((item) => (
                  <li key={item.id}>
                    {item.title ?? `деталь ${item.partId}`} — {item.quantity} шт.
                  </li>
                ))}
              </ul>

              {deal.deliveryNote && <p className="muted">{deal.deliveryNote}</p>}

              {canSell && !unfulfilled && (
                <button
                  type="button"
                  disabled={busy === deal.id}
                  onClick={() => accept(deal)}
                >
                  Подтвердил площадке
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/**
 * Остаток времени словами.
 *
 * <p>Просроченный срок не прячется и не подсвечивается как «скоро»: деньги
 * покупателю площадка уже вернула, и продавцу нужно знать это, а не гадать,
 * почему заказ висит.
 */
function deadlineLabel(hours: number | null): string {
  if (hours === null) {
    return 'Срок ответа площадкой не указан';
  }
  if (hours < 0) {
    return 'Срок ответа прошёл — площадка вернула деньги покупателю';
  }
  if (hours < 1) {
    return `Ответить осталось меньше часа`;
  }
  if (hours < 24) {
    return `Ответить осталось ${Math.floor(hours)} ч`;
  }
  return `Ответить осталось ${Math.floor(hours / 24)} сут`;
}
