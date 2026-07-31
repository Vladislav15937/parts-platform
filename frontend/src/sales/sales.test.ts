import { describe, expect, it } from 'vitest';
import {
  basketTotal,
  hoursUntilDeadline,
  returnable,
  roomFor,
  transferable,
} from './sales';
import type { BasketLine, Deal, StockRow } from './sales';

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
    marketplace: null,
    externalOrderNo: null,
    replyDeadline: null,
    orderAcceptedAt: null,
    deliveryNote: null,
    services: [],
    items: statuses.map((status, at) => ({
      id: at + 1,
      partId: 100 + at,
      title: `деталь ${at}`,
      quantity: '1',
      price: '5000',
      discount: null,
      warehouseId: 10,
      status,
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
