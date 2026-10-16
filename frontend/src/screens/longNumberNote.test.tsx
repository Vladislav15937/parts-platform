import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm } from './PartEditForm';
import { IntakeScreen } from './IntakeScreen';
import type { CatalogRow } from '../inventory/catalog';
import {
  LONG_NUMBER_LIMITS, crossesLength, longNumberWarning, oemLength,
} from '../inventory/oemLength';

/**
 * Необычно длинный номер производителя и кросс-номера — предупреждение,
 * а не запрет (задача 0310, решение владельца продукта 16.10.2026: «длина
 * как сейчас, но с предупреждением»).
 *
 * <p>Замер на ПРОМ: 5000 знаков в номере и в кросс-номерах сохранялись молча
 * и уезжали в прайс Дрома дважды. Чаще это вставка не из того поля — и сказать
 * о ней можно только до сохранения, рядом с полем.
 */
describe('предупреждение о длинном номере', () => {
  let sent: Array<Record<string, unknown>>;
  let saved: Record<string, unknown>;

  beforeEach(() => {
    sent = [];
    saved = {};
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>);
        return json({ price: 27000 });
      }
      return json({ ...card(), ...saved });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('65 знаков номера — предупреждение при вводе, 64 — нет, «Сохранить» доступна', async () => {
    renderForm();
    await waitFor(() => expect(screen.getByLabelText('Изменить: Номер производителя')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Номер производителя'));
    const input = screen.getByLabelText('Номер производителя');

    fireEvent.change(input, { target: { value: 'A'.repeat(65) } });
    expect(warnings(), 'длинный номер не предупредил').toEqual([
      'Необычно длинный номер производителя — 65 знаков: проверьте, не вставили ли лишнее.'
        + ' Сохранить всё равно можно']);
    expect(save().disabled, 'предупреждение запретило сохранение').toBe(false);

    fireEvent.change(input, { target: { value: 'A'.repeat(64) } });
    expect(warnings(), 'укороченный номер всё ещё предупреждает').toEqual([]);

    fireEvent.change(input, { target: { value: 'A'.repeat(5000) } });
    fireEvent.click(save());
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['oem'], 'длинный номер не уехал на сервер').toBe('A'.repeat(5000));
  });

  it('кросс-номера: больше 1000 знаков в сумме — предупреждение, 1000 — нет', async () => {
    renderForm();
    await waitFor(() => expect(screen.getByLabelText('Изменить: Кросс-номера')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Кросс-номера'));
    const input = screen.getByLabelText('Кросс-номера');

    // Десять номеров по сто знаков — ровно порог; разделители не в счёт.
    const ten = Array.from({ length: 10 }, () => 'B'.repeat(100));
    fireEvent.change(input, { target: { value: ten.join(', ') } });
    expect(warnings(), 'разделители засчитаны в длину').toEqual([]);

    fireEvent.change(input, { target: { value: [...ten, 'C'].join(';\t') } });
    expect(warnings()).toEqual([
      'Необычно длинные кросс-номера — 1 001 знак: проверьте, не вставили ли лишнее.'
        + ' Сохранить всё равно можно']);
    expect(save().disabled).toBe(false);
  });

  it('у сохранённой длинной карточки предупреждение видно при открытии, без «Изменить»', async () => {
    saved = { oem: 'D'.repeat(70) };
    renderForm();
    await waitFor(() => expect(warnings().length).toBe(1));
    expect(warnings()[0]).toContain('70 знаков');
  });

  it('на приёмке — то же предупреждение у номера производителя', () => {
    render(<IntakeScreen reference={reference()} onSend={() => {}} />);
    const input = screen.getByLabelText('Номер производителя');

    fireEvent.change(input, { target: { value: 'E'.repeat(65) } });
    expect(warnings()).toHaveLength(1);

    fireEvent.change(input, { target: { value: 'E'.repeat(64) } });
    expect(warnings()).toEqual([]);
  });
});

/**
 * Длина — как её сохранит сервер (`OemNumbers.clean` и `list`, задача 0309):
 * срезанные края и разделители не засчитываются.
 */
describe('длина номера после приведения', () => {
  it('пробелы по краям — любые, включая неразрывный и нулевой ширины, — не в счёт', () => {
    const padded = '  ​' + 'A'.repeat(64) + ' ⁠﻿\t';
    expect(oemLength(padded)).toBe(64);
    expect(longNumberWarning('oem', padded)).toBeNull();
    // Внутри номера пробел — знак номера: сервер его хранит.
    expect(oemLength('81170 33670')).toBe(11);
  });

  it('кросс-номера делятся запятой, точкой с запятой, переводом строки и табуляцией', () => {
    expect(crossesLength(' aa111,\tBB222 ;\ndd-444\r\n\t4520Б ')).toBe(5 + 5 + 6 + 5);
    expect(crossesLength(',, ;;\n')).toBe(0);
  });

  it('пороги — одно место', () => {
    expect(LONG_NUMBER_LIMITS).toEqual({ oem: 64, crosses: 1000 });
  });
});

function renderForm(): void {
  render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
}

function warnings(): string[] {
  return [...document.querySelectorAll('.long-number-note')].map((n) => n.textContent ?? '');
}

function save(): HTMLButtonElement {
  return screen.getByText('Сохранить') as HTMLButtonElement;
}

function card(): Record<string, unknown> {
  return {
    price: 27000, condition: 'USED', qualityGrade: null, note: null, published: true,
    number: 7, code: 'B-40219', title: 'Фара левая Toyota Camry',
    sideLr: 'LEFT', sideFr: 'FRONT', oem: null, crosses: null,
  };
}

function row(): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title: 'Фара левая Toyota Camry',
    brand: 'Toyota', model: 'Camry', generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: 2007, donorCode: '261', equipment: null,
    price: 27000, oem: null, crosses: null, stock: {},
  } as unknown as CatalogRow;
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

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
