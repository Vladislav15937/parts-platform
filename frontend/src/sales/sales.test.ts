import { describe, expect, it } from 'vitest';
import { basketTotal, roomFor } from './sales';
import type { BasketLine, StockRow } from './sales';

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
});
