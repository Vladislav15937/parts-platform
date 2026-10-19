import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';

import { IntakeScreen } from './IntakeScreen';

/**
 * Приёмка ожидаемой позиции спрашивает, каким она приехала (задача 0326).
 *
 * <p>Решение владельца продукта 18.10.2026, «6 - в, с предвыбором прошлого
 * выбора клиента но с возможностью изменить»: заказали «б/у», пришло
 * «контрактное». До задачи приёмка состояния не передавала вовсе, и
 * поправить его можно было только потом, в карточке, — а позиция «Не
 * указано» до того не уходит на Дром.
 */
describe('состояние ожидаемой позиции на приёмке', () => {
  afterEach(() => {
    cleanup();
  });

  it('предвыбрано заведённое, «Не указано» не предлагается, и без нажатий уезжает оно же', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference('USED')} onSend={onSend} />);
    open();

    const select = conditionSelect();
    expect(select.value).toBe('USED');
    expect(selectedTitle(select)).toBe('б/у');
    expect(within(select).queryByText('Не указано')).toBeNull();
    expect(screen.queryByText(/не уйдёт на Дром/)).toBeNull();

    sendAccepted();
    expect(sentItems(onSend)).toEqual([expect.objectContaining({ partId: 5, condition: 'USED' })]);
  });

  it('пришло другое — выбранное уезжает в очередь вместе с партией', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference('USED')} onSend={onSend} />);
    open();

    fireEvent.change(conditionSelect(), { target: { value: 'CONTRACT' } });
    sendAccepted();

    expect(sentItems(onSend)).toEqual([expect.objectContaining({ partId: 5, condition: 'CONTRACT' })]);
  });

  it('у заведённой без состояния предвыбрано «Не указано», сказана цена, и выбрать можно', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference(null)} onSend={onSend} />);
    open();

    const select = conditionSelect();
    expect(select.value).toBe('');
    expect(selectedTitle(select)).toBe('Не указано');
    expect(screen.getByText('Без состояния позиция не уйдёт на Дром.')).toBeTruthy();

    fireEvent.change(select, { target: { value: 'NEW' } });
    expect(screen.queryByText(/не уйдёт на Дром/)).toBeNull();
    sendAccepted();

    expect(sentItems(onSend)).toEqual([expect.objectContaining({ partId: 5, condition: 'NEW' })]);
  });

  it('оставленное «Не указано» уходит пустым, а не умолчанием', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference(null)} onSend={onSend} />);
    open();
    sendAccepted();

    expect(sentItems(onSend)).toEqual([expect.objectContaining({ partId: 5, condition: null })]);
  });

  it('кэш телефона без поля состояния открывает экран с «Не указано»', () => {
    const old = reference(null) as unknown as { expectedParts: Record<string, unknown>[] };
    delete old.expectedParts[0]!.condition;
    render(<IntakeScreen reference={old as never} onSend={vi.fn()} />);
    open();

    expect(conditionSelect().value).toBe('');
    expect(screen.getByText('Фара левая Toyota Camry')).toBeTruthy();
  });

  it('новая карточка шлёт предложенное «б/у» (0362: поле стоит и у новой)', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference('USED')} onSend={onSend} />);
    selectByOption('— выберите склад —', '2');
    fireEvent.change(screen.getByPlaceholderText('например, фара левая'),
      { target: { value: 'капот' } });
    fireEvent.change(screen.getByLabelText('Цена'), { target: { value: '3000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Добавить в партию' }));
    fireEvent.click(screen.getByRole('button', { name: 'Отправить партию' }));

    expect(sentItems(onSend)).toEqual([expect.objectContaining({ partId: null, condition: 'USED' })]);
  });
});

function open(): void {
  selectByOption('— выберите склад —', '2');
  selectByOption('не указана', '19');
}

function conditionSelect(): HTMLSelectElement {
  // Поле «Состояние» есть и у новой карточки (0362); здесь нужно то, что у ожидаемой позиции.
  return document.querySelector('ul.suggestions li select') as HTMLSelectElement;
}

function selectedTitle(select: HTMLSelectElement): string | null {
  return select.options[select.selectedIndex]?.textContent ?? null;
}

function sendAccepted(): void {
  fireEvent.click(screen.getByRole('button', { name: 'Принять' }));
  fireEvent.click(screen.getByRole('button', { name: 'Отправить партию' }));
}

function sentItems(onSend: ReturnType<typeof vi.fn>): unknown {
  const [payload] = onSend.mock.calls[0]!;
  return (payload as { items: unknown }).items;
}

function selectByOption(optionText: string, value: string): void {
  const select = [...document.querySelectorAll('select')]
    .find((s) => [...s.options].some((o) => o.textContent === optionText))!;
  fireEvent.change(select, { target: { value } });
}

function reference(condition: string | null) {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [
      { id: 19, number: '18', supplierName: 'Иокогама', kind: 'CONTAINER',
        status: 'EXPECTED', arrivedOn: null, expectedOn: null },
    ],
    donors: [],
    partNames: [],
    expectedParts: [
      { id: 5, supplyId: 19, title: 'Фара левая Toyota Camry', condition, price: 9000, remaining: 3 },
    ],
  } as never;
}
