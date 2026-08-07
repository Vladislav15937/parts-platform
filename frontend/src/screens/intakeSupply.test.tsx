import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

import { IntakeScreen } from './IntakeScreen';

/**
 * Поставка не подставляется сама.
 *
 * <p>Список идёт от свежей поставки к старой, и первая из них
 * подставлялась как умолчание: каждая принятая деталь молча приписывалась
 * к последнему приехавшему контейнеру, к которому может не иметь никакого
 * отношения. Приёмщик поле не трогает — оно уже заполнено и выглядит
 * осмысленно.
 *
 * <p>Правильное умолчание в списке стояло первым пунктом с самого начала —
 * «не указана». Пусто здесь означает «неизвестно», и это честнее догадки:
 * поставку правят потом, а неверную не находят никогда.
 */
describe('умолчания формы приёмки', () => {
  afterEach(cleanup);

  it('поставка пуста, пока приёмщик не выбрал', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);

    const supply = [...document.querySelectorAll('select')]
      .find((s) => [...s.options].some((o) => o.textContent === 'не указана'));

    expect(supply, 'списка поставок на форме нет').toBeTruthy();
    expect(supply!.value, 'подставилась последняя приехавшая поставка').toBe('');
  });

  /**
   * Склад тоже не подставляется.
   *
   * <p>Умолчанием стоял первый склад списка, а список отсортирован по имени:
   * у клиента с тремя складами первым оказывался пустой «54 YARD», тогда как
   * весь товар лежит на «Ткацкой». Приёмщик поле не смотрит — оно заполнено, —
   * и партия уходит не туда. Ошибка тихая: деталь заведена, остаток сходится,
   * ничего не падает; находят её, когда деталь ищут на полке.
   *
   * <p>Какой склад правильный, система знать не может — знает тот, кто стоит
   * у стеллажа. Значит спрашиваем.
   */
  it('склад пуст, пока приёмщик не выбрал', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);

    const warehouse = [...document.querySelectorAll('select')]
      .find((s) => [...s.options].some((o) => o.textContent === 'Ткацкая'));

    expect(warehouse!.value, 'подставился первый склад списка').toBe('');
  });

  /**
   * И до выбора склада позицию в партию не добавить: остановить приёмщика
   * надо до того, как он наберёт двадцать штук, а не после.
   */
  it('без склада позиция в партию не добавляется', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);

    const name = [...document.querySelectorAll('input')]
      .find((i) => i.placeholder?.includes('фара'))!;
    const price = [...document.querySelectorAll('input')]
      .find((i) => i.inputMode === 'decimal' || i.type === 'number');
    set(name, 'Фара левая');
    if (price) set(price, '5000');

    const add = [...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Добавить в партию')!;
    expect(add.hasAttribute('disabled'), 'партию можно набирать без склада').toBe(true);
  });
});

/** React слушает нативный сеттер, а не присваивание value. */
function set(input: HTMLInputElement, value: string): void {
  Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')!
    .set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [
      { id: 17, number: '17', supplierName: 'Onteco 6', kind: 'CONTAINER' },
      { id: 16, number: '16', supplierName: 'DDI-22', kind: 'CONTAINER' },
    ],
    donors: [],
    partNames: [],
  } as never;
}
