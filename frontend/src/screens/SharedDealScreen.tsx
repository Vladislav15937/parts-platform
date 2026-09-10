import { useEffect, useState } from 'react';
import { request } from '../api/client';
import { sharedDealStatusName } from '../sales/dealStatus';
import { useMounted } from '../ui/useMounted';

/**
 * Сделка глазами покупателя: то, что открывается по ссылке от продавца.
 *
 * <p>Без входа: учётной записи у покупателя нет и не будет. Права даёт секрет
 * в адресе, и отсюда же — что здесь показано. Только его собственная покупка,
 * без закупочной цены, без чужих сделок и без контактов: ссылку пересылают
 * в переписке, и попасть она может кому угодно.
 *
 * <p>Главный вопрос, ради которого клиент её открывает, — «до какого числа
 * отложено». Поэтому срок стоит первым, а не в конце списка.
 */
interface SharedItem {
  title: string | null;
  quantity: string;
  price: string;
}

interface SharedDeal {
  number: number | null;
  status: string;
  reservedUntil: string | null;
  total: string;
  paid: string;
  debt: string;
  items: SharedItem[];
}

export function SharedDealScreen({ company, token }: { company: string; token: string }) {
  const [deal, setDeal] = useState<SharedDeal | null>(null);
  const [missing, setMissing] = useState(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void request<SharedDeal>(`/api/shared/${company}/${token}`)
      .then((found) => { if (mounted.current) setDeal(found); })
      // Просроченная и несуществующая ссылка неразличимы намеренно: клиенту
      // это одно и то же — «спросите у продавца», — а различие подсказывало бы
      // подбирающему, что он на верном пути.
      .catch(() => { if (mounted.current) setMissing(true); });
  }, [company, token, mounted]);

  if (missing) {
    return (
      <section className="screen">
        <h2>Ссылка не открывается</h2>
        <p className="note">
          Возможно, у неё истёк срок. Попросите продавца прислать новую.
        </p>
      </section>
    );
  }

  if (deal === null) {
    return <p className="note">Загружаем…</p>;
  }

  return (
    <section className="screen">
      <h2>Заказ {deal.number === null ? '' : `№ ${deal.number}`}</h2>

      <p className="note">
        {sharedDealStatusName(deal.status)}
        {deal.reservedUntil !== null && deal.status === 'RESERVED' && (
          <> до {new Date(deal.reservedUntil).toLocaleDateString('ru-RU')}</>
        )}
      </p>

      <ul className="suggestions">
        {deal.items.map((item, at) => (
          <li key={at}>
            {item.title ?? 'позиция'} — {Number(item.quantity)} шт. ×{' '}
            {Number(item.price).toLocaleString('ru-RU')} ₽
          </li>
        ))}
      </ul>

      <p className="note">
        Итого: {Number(deal.total).toLocaleString('ru-RU')} ₽
        {Number(deal.paid) > 0 && (
          <> · оплачено {Number(deal.paid).toLocaleString('ru-RU')} ₽</>
        )}
        {Number(deal.debt) > 0 && (
          <> · к оплате {Number(deal.debt).toLocaleString('ru-RU')} ₽</>
        )}
      </p>
    </section>
  );
}
