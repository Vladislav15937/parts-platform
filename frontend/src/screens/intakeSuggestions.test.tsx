import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';

import { IntakeScreen } from './IntakeScreen';

/**
 * Выбранная подсказка закрывает список.
 *
 * <p>Список считается от текста поля, поэтому выбранное написание оставалось
 * в нём и после нажатия: приёмщик не видел, что выбор засчитан, а список
 * продолжал стоять между полем и ценой. На телефоне, где эта форма и живёт,
 * шесть подсказок отжимают «Цена» и «Добавить в партию» за край экрана —
 * и так на каждой детали из тридцати.
 */
describe('подсказки наименования', () => {
  afterEach(cleanup);

  it('после выбора список закрывается, а при наборе возвращается', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);

    const name = [...document.querySelectorAll('input')]
      .find((i) => i.placeholder?.includes('фара'))!;
    set(name, 'фар');

    const list = () => document.querySelector('.suggestions');
    expect(list(), 'подсказок нет вовсе').toBeTruthy();

    const pick = [...document.querySelectorAll('.suggestions button')]
      .find((b) => b.textContent?.startsWith('Фара'))!;
    fireEvent.click(pick);

    expect(list(), 'список остался стоять между полем и ценой').toBeNull();
    expect(name.value).toBe('Фара');

    // Набирают дальше — значит выбранное больше не подходит.
    set(name, 'Фара п');
    expect(list(), 'подсказки не вернулись при наборе').toBeTruthy();
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
    supplies: [],
    donors: [],
    partNames: [
      { id: 1, name: 'Фара', matched: true, usageCount: 500 },
      { id: 2, name: 'Фара противотуманная', matched: true, usageCount: 40 },
    ],
  } as never;
}
