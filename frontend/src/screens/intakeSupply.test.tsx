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

  /** Склад, наоборот, обязан быть выбран: деталь должна куда-то лечь. */
  it('склад остаётся выбранным', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);

    const warehouse = [...document.querySelectorAll('select')]
      .find((s) => [...s.options].some((o) => o.textContent === 'Ткацкая'));

    expect(warehouse!.value).not.toBe('');
  });
});

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
