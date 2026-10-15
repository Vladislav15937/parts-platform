import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

import { IntakeScreen } from './IntakeScreen';

/**
 * Номер производителя вписывают на приёмке (задача 0302).
 *
 * <p>Сервер принимал `oemNumber` в позиции приёмки с самого начала, а экран
 * его не отправлял ни одной строкой: номер можно было вписать только потом,
 * в карточке, — а там до 0302 стоял замок. Без номера позиция выпадает
 * из подбора замен, и вписывают его на каждой второй-третьей детали.
 */
describe('номер производителя на приёмке', () => {
  afterEach(cleanup);

  it('уходит в позицию партии, пустой — не уходит вовсе', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference()} onSend={onSend} />);

    const warehouse = [...document.querySelectorAll('select')]
      .find((s) => [...s.options].some((o) => o.textContent === 'Ткацкая'))!;
    fireEvent.change(warehouse, { target: { value: '2' } });

    fill('Фара левая', '81170-33670 ');
    fill('Бампер', '');

    fireEvent.click(screen.getByText(/Отправить/));

    expect(onSend).toHaveBeenCalledTimes(1);
    const payload = onSend.mock.calls[0]![0] as { items: Array<{ oemNumber: string | null }> };
    expect(payload.items.map((i) => i.oemNumber)).toEqual(['81170-33670', null]);
  });
});

function fill(name: string, oem: string): void {
  const nameInput = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder?.includes('фара'))!;
  fireEvent.change(nameInput, { target: { value: name } });
  fireEvent.change(screen.getByLabelText('Номер производителя'), { target: { value: oem } });
  const price = [...document.querySelectorAll('input')].find((i) => i.type === 'number')!;
  fireEvent.change(price, { target: { value: '5000' } });
  fireEvent.click(screen.getByText('Добавить в партию'));
}

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [],
    donors: [],
    partNames: [],
  } as never;
}
