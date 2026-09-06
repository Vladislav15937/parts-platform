import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  basketTotal,
  defaultPaymentSource,
  hoursUntilDeadline,
  paymentSourceTypeLabel,
  rememberPaymentSource,
  returnable,
  returnWarehouseDefault,
  roomFor,
  transferable,
} from './sales';
import type { BasketLine, Deal, PaymentSourceEntry, StockRow } from './sales';

/**
 * Расчёты экрана продавца.
 *
 * <p>Оба ошибаются тихо. Перебор свободного остатка отклонит сервер, но узнать
 * об этом продавец должен в момент нажатия, а не при оформлении, когда клиент
 * ждёт на линии. Итог корзины продавец называет вслух — разойдись он
 * с серверным, спорить придётся с клиентом.
 */

function row(overrides: Partial<StockRow> = {}): StockRow {
  return {
    partId: 1,
    publicCode: 'A-1',
    title: 'Фара левая',
    price: '5000',
    status: 'IN_STOCK',
    warehouseId: 10,
    warehouseName: 'Ткацкая',
    cellCode: 'А-01-1',
    qty: '3',
    qtyReserved: '0',
    qtyAvailable: '3',
    ...overrides,
  };
}

function line(source: StockRow, quantity: number, price = '5000'): BasketLine {
  return { row: source, quantity, price };
}

describe('свободный остаток под корзину', () => {
  it('пустая корзина не занимает ничего', () => {
    expect(roomFor(row(), [])).toBe(3);
  });

  it('уже отложенное в этой же корзине вычитается', () => {
    const source = row();
    expect(roomFor(source, [line(source, 2)])).toBe(1);
  });

  it('исчерпанное даёт ноль, а не отрицательное', () => {
    const source = row();
    expect(roomFor(source, [line(source, 5)])).toBe(0);
  });

  it('одна деталь на разных складах считается раздельно', () => {
    // Иначе продавец не сможет собрать две штуки из двух складов —
    // на разборке это обычное дело.
    const first = row({ warehouseId: 10, qtyAvailable: '1' });
    const second = row({ warehouseId: 20, qtyAvailable: '1' });

    expect(roomFor(second, [line(first, 1)])).toBe(1);
  });

  it('чужая деталь корзину не занимает', () => {
    const other = row({ partId: 2 });
    expect(roomFor(row(), [line(other, 3)])).toBe(3);
  });

  it('полностью отложенное показывается, но добавить нельзя', () => {
    // Продавцу нужно ответить «есть, но отложена до завтра», а не «нет»:
    // клиент перезвонит, а деталь освободится.
    const reserved = row({ qty: '1', qtyReserved: '1', qtyAvailable: '0' });
    expect(roomFor(reserved, [])).toBe(0);
  });
});

describe('итог корзины', () => {
  it('складывает цену на количество', () => {
    const source = row();
    expect(basketTotal([line(source, 2, '5000'), line(source, 1, '3000')])).toBe(13000);
  });

  it('пустая цена считается нулём, а не NaN', () => {
    // У детали без цены поле пустое; NaN превратил бы весь итог в «NaN ₽».
    expect(basketTotal([line(row({ price: null }), 1, '')])).toBe(0);
  });

  it('пустая корзина стоит ноль', () => {
    expect(basketTotal([])).toBe(0);
  });

  it('доставка входит в итог', () => {
    // Продавец называет клиенту одну сумму, и она обязана совпасть с той,
    // что окажется в документе, — иначе разговор про доставку начнётся
    // после оплаты. У заказа с площадки хуже: перевод придёт с доставкой,
    // а сделка будет на цену детали.
    const delivery = { kind: { id: 1, name: 'Доставка', price: null }, price: '300' };

    expect(basketTotal([line(row(), 1, '4500')], [delivery])).toBe(4800);
  });

  it('незаполненная услуга ничего не добавляет', () => {
    // Пустое поле — «доставки не было», а не «доставка бесплатная».
    const delivery = { kind: { id: 1, name: 'Доставка', price: '300' }, price: '' };

    expect(basketTotal([line(row(), 1, '4500')], [delivery])).toBe(4500);
  });
});

/**
 * Что продавцу дают отметить в сделке.
 *
 * <p>Ошибка здесь тихая: лишняя строка в списке — это возврат уже
 * возвращённого, то есть деталь на складе дважды и деньги клиенту дважды.
 * Отказ придёт от сервера, но объяснять его будет продавец клиенту.
 */
function dealWith(...statuses: string[]): Deal {
  return dealFrom(statuses.map((status) => ({ status, warehouseId: 10 })));
}

function dealFrom(items: { status: string; warehouseId: number }[],
                  warehouseId: number | null = null): Deal {
  return {
    id: 1,
    number: 7,
    customerId: 2,
    managerId: 3,
    status: 'ISSUED',
    reservedUntil: null,
    totalAmount: '5000',
    paidAmount: '0',
    debt: '5000',
    createdAt: '2026-07-30T10:00:00Z',
    issuedAt: null,
    warehouseId,
    marketplace: null,
    externalOrderNo: null,
    replyDeadline: null,
    orderAcceptedAt: null,
    deliveryNote: null,
    services: [],
    items: items.map((item, at) => ({
      id: at + 1,
      partId: 100 + at,
      title: `деталь ${at}`,
      quantity: '1',
      price: '5000',
      discount: null,
      warehouseId: item.warehouseId,
      status: item.status,
    })),
  };
}

describe('строки сделки, доступные к действию', () => {
  it('переносят только отложенное', () => {
    expect(transferable(dealWith('RESERVED', 'ISSUED', 'CANCELLED')).map((i) => i.id))
      .toEqual([1]);
  });

  it('возвращают только выданное', () => {
    expect(returnable(dealWith('RESERVED', 'ISSUED', 'CANCELLED')).map((i) => i.id))
      .toEqual([2]);
  });

  it('уже возвращённое второй раз не предлагается', () => {
    // Частичный возврат оставляет сделку выданной, и в ней лежат строки
    // обоих видов. Попади возвращённая в список — деталь встанет на склад
    // дважды, а деньги уйдут клиенту дважды.
    expect(returnable(dealWith('ISSUED', 'RETURNED')).map((i) => i.id)).toEqual([1]);
  });
});

/**
 * Склад возврата подставляется тем, откуда деталь выдали.
 *
 * <p>Подставлялся первый склад ответа сервера, а тот отсортирован
 * по названию: сделка, выданная с «Основного», открывала возврат
 * с «Дальним». Ошибка тихая — поле заполнено и выглядит осмысленно, —
 * а находят её, когда деталь ищут на прежней полке и не находят.
 */
describe('склад возврата по умолчанию', () => {
  it('берётся со склада выдачи сделки', () => {
    expect(returnWarehouseDefault(dealFrom([{ status: 'ISSUED', warehouseId: 10 }], 7)))
      .toBe(7);
  });

  it('без него — со склада, откуда ушли выданные позиции', () => {
    expect(returnWarehouseDefault(dealFrom([
      { status: 'ISSUED', warehouseId: 10 },
      { status: 'ISSUED', warehouseId: 10 },
      // Снятая позиция никуда не уезжала и на склад возврата не влияет.
      { status: 'CANCELLED', warehouseId: 20 },
    ]))).toBe(10);
  });

  it('позиции с разных складов не дают умолчания вовсе', () => {
    // Пусто честнее, чем наугад: промах будет таким же тихим.
    expect(returnWarehouseDefault(dealFrom([
      { status: 'ISSUED', warehouseId: 10 },
      { status: 'ISSUED', warehouseId: 20 },
    ]))).toBeNull();
  });
});

/**
 * Срок ответа площадке.
 *
 * <p>У Дрома по защищённой сделке это трое рабочих суток: не ответили —
 * деньги вернулись покупателю. Поэтому экран показывает остаток времени,
 * а не дату: «до 4 авг» продавец сопоставляет с сегодняшним числом сам
 * и ошибается.
 */
describe('срок ответа площадке', () => {
  const now = Date.parse('2026-07-31T12:00:00Z');

  it('считает остаток в часах', () => {
    const deal = dealWith();
    deal.replyDeadline = '2026-07-31T14:00:00Z';

    expect(hoursUntilDeadline(deal, now)).toBeCloseTo(2);
  });

  it('просроченный срок даёт отрицательное, а не ноль', () => {
    // Ноль или пустое значение здесь означали бы «срок не задан», и заказ,
    // по которому площадка уже вернула деньги, выглядел бы как обычный.
    const deal = dealWith();
    deal.replyDeadline = '2026-07-31T09:00:00Z';

    expect(hoursUntilDeadline(deal, now)).toBeLessThan(0);
  });

  it('без срока — ничего, а не ноль', () => {
    // Ноль прочитался бы как «время вышло»: у обычной продажи срока ответа
    // нет вовсе, и торопить продавца по ней не с чем.
    expect(hoursUntilDeadline(dealWith(), now)).toBeNull();
  });
});

/**
 * Умолчание источника платежа: тот, которым продавец платил в прошлый раз,
 * а при первой в жизни оплате — первый неархивный по алфавиту.
 *
 * <p>Ключ на арендатора и на сотрудника: за кассой стоит один и тот же
 * человек, и девять оплат из десяти идут одним способом, но другая компания
 * или другой продавец на этом же устройстве не должны видеть чужое умолчание.
 */
describe('умолчание источника платежа', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  function source(id: number, name: string, archived = false): PaymentSourceEntry {
    return { id, name, sourceType: null, archived };
  }

  it('первая оплата — первый неархивный источник по алфавиту', () => {
    const sources = [source(2, 'Карта Сбер'), source(1, 'ККМ')];
    expect(defaultPaymentSource(sources, 't_1', 5)).toBe(2);
  });

  it('архивный источник в умолчание не попадает', () => {
    const sources = [source(1, 'Авито доставка', true), source(2, 'ККМ')];
    expect(defaultPaymentSource(sources, 't_1', 5)).toBe(2);
  });

  it('без источников умолчания нет', () => {
    expect(defaultPaymentSource([], 't_1', 5)).toBeNull();
  });

  it('запомненный источник побеждает первый по алфавиту', () => {
    const sources = [source(1, 'ККМ'), source(2, 'Карта Сбер')];
    rememberPaymentSource('t_1', 5, 2);
    expect(defaultPaymentSource(sources, 't_1', 5)).toBe(2);
  });

  it('запомненный источник, ушедший в архив, не подставляется', () => {
    // Архивная строка сохраняется у прежних платежей, но предлагать её
    // как умолчание для новой оплаты нельзя — списка для выбора она уже
    // не входит.
    const sources = [source(1, 'ККМ'), source(2, 'Карта Сбер', true)];
    rememberPaymentSource('t_1', 5, 2);
    expect(defaultPaymentSource(sources, 't_1', 5)).toBe(1);
  });

  it('ключ на компанию: чужая компания на том же устройстве не видит умолчание', () => {
    const sources = [source(1, 'Авито доставка'), source(2, 'ККМ')];
    rememberPaymentSource('t_1', 5, 2);
    expect(defaultPaymentSource(sources, 't_2', 5)).toBe(1);
  });

  it('ключ на сотрудника: другой продавец на том же устройстве не видит умолчание', () => {
    const sources = [source(1, 'Авито доставка'), source(2, 'ККМ')];
    rememberPaymentSource('t_1', 5, 2);
    expect(defaultPaymentSource(sources, 't_1', 6)).toBe(1);
  });
});

describe('подпись типа источника платежа', () => {
  it('пять значений схемы и пусто — дословно из задачи 0024', () => {
    expect(paymentSourceTypeLabel('CASH')).toBe('Наличный расчёт');
    expect(paymentSourceTypeLabel('BANK_ACCOUNT')).toBe('Расчётный счёт');
    expect(paymentSourceTypeLabel('ACQUIRING')).toBe('Интернет-эквайринг');
    expect(paymentSourceTypeLabel('CREDIT')).toBe('В долг');
    expect(paymentSourceTypeLabel('MARKETPLACE')).toBe('Площадка');
    expect(paymentSourceTypeLabel(null)).toBe('Не указан');
  });
});
