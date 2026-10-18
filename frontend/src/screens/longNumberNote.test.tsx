import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm } from './PartEditForm';
import { IntakeScreen } from './IntakeScreen';
import type { CatalogRow } from '../inventory/catalog';
import {
  LONG_NUMBER_LIMITS, OEM_FIELD_NOTE, OEM_STORE_LIMIT, crossesLength, longNumberWarning,
  normalizedLength, numberTooLong, oemLength,
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

    // До 0312 здесь уезжали 5000 знаков; теперь предел хранилища — 1000,
    // и ровно предел по-прежнему уезжает.
    fireEvent.change(input, { target: { value: 'A'.repeat(OEM_STORE_LIMIT) } });
    expect(save().disabled, 'номер ровно в предел запрещён').toBe(false);
    fireEvent.click(save());
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['oem'], 'номер в предел не уехал на сервер').toBe('A'.repeat(OEM_STORE_LIMIT));
  });

  it('сверх предела хранилища (задача 0312) — «сохранить нельзя», «Сохранить» гаснет, укоротили — снова можно', async () => {
    renderForm();
    await waitFor(() => expect(screen.getByLabelText('Изменить: Номер производителя')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Номер производителя'));
    const input = screen.getByLabelText('Номер производителя');

    fireEvent.change(input, { target: { value: 'A'.repeat(OEM_STORE_LIMIT + 1) } });
    const refusal = 'Номер производителя длиннее 1000 знаков: сохранить нельзя.'
      + ' Похоже, вставлено не то — проверьте номер';
    expect(warnings(), 'сверх предела — не предупреждение, а отказ').toEqual([refusal]);
    expect(save().disabled, '«Сохранить» не погасла').toBe(true);
    expect(screen.getAllByText(refusal).length, 'у погашенной кнопки нет причины')
      .toBeGreaterThanOrEqual(2);
    fireEvent.click(save());
    expect(sent.length, 'погашенная кнопка всё-таки отправила').toBe(0);

    fireEvent.change(input, { target: { value: 'A'.repeat(OEM_STORE_LIMIT) } });
    expect(save().disabled).toBe(false);
  });

  it('один кросс-номер сверх предела — отказ с его началом; сумма без длинного — только предупреждение', async () => {
    renderForm();
    await waitFor(() => expect(screen.getByLabelText('Изменить: Кросс-номера')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Кросс-номера'));
    const input = screen.getByLabelText('Кросс-номера');

    fireEvent.change(input, { target: { value: `AB-1, ${'Z'.repeat(1001)}, CD-2` } });
    expect(warnings()).toEqual([`Кросс-номер «${'Z'.repeat(20)}…» длиннее 1000 знаков:`
      + ' сохранить нельзя. Похоже, вставлено не то — проверьте номер']);
    expect(save().disabled).toBe(true);

    // Две тысячи знаков в сумме, но каждый номер — по пятьсот: хранилище примет.
    fireEvent.change(input, { target: { value: `${'Y'.repeat(500)}, ${'X'.repeat(500)}, ${'W'.repeat(500)}, ${'V'.repeat(500)}` } });
    expect(warnings()[0]).toContain('Сохранить всё равно можно');
    expect(save().disabled).toBe(false);
  });

  it('длина — приведённого номера: разделители внутри не в счёт', () => {
    expect(normalizedLength('81170-33 670')).toBe(10);
    expect(numberTooLong('oem', `${'1-'.repeat(1000)}`)).toBeNull();
    expect(numberTooLong('oem', 'А'.repeat(1001)), 'русский двойник не засчитан').not.toBeNull();
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

  it('на приёмке сверх предела — «сохранить нельзя», «Добавить в партию» гаснет (задача 0312)', () => {
    render(<IntakeScreen reference={reference()} onSend={() => {}} />);
    fireEvent.change(screen.getByLabelText('Склад'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('Вид детали'), { target: { value: 'стартер' } });
    fireEvent.change(screen.getByLabelText('Цена'), { target: { value: '5000' } });
    const add = screen.getByText('Добавить в партию') as HTMLButtonElement;
    expect(add.disabled, 'заполненная форма не даёт добавить').toBe(false);

    fireEvent.change(screen.getByLabelText('Номер производителя'),
      { target: { value: 'E'.repeat(1001) } });
    expect(warnings()[0]).toContain('сохранить нельзя');
    expect(add.disabled, 'номер сверх предела ушёл бы в очередь').toBe(true);
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

/**
 * У детали один номер производителя (задача 0323, решение владельца 18.10.2026):
 * «A, B» сервер делит на основной и кросс-номер, и предел у каждого свой —
 * экран мерит так же, а не строку целиком.
 */
describe('номер производителя с разделителем', () => {
  afterEach(() => cleanup());

  it('отказ сверх предела — по каждому номеру, а не по сумме', () => {
    const half = 'A'.repeat(600);
    expect(numberTooLong('oem', `${half}, ${'B'.repeat(600)}`),
      'два номера по 600 померены строкой целиком').toBeNull();
    expect(numberTooLong('oem', `AB-1; ${'Z'.repeat(OEM_STORE_LIMIT + 1)}`))
      .toBe('Номер производителя длиннее 1000 знаков: сохранить нельзя.'
        + ' Похоже, вставлено не то — проверьте номер');
  });

  it('предупреждение о длине — по самому длинному номеру, а не по сумме', () => {
    expect(longNumberWarning('oem', `${'A'.repeat(60)}, ${'B'.repeat(60)}`),
      'два номера по 60 предупредили как один в 122 знака').toBeNull();
    expect(longNumberWarning('oem', `AB-1\t${'C'.repeat(65)}`)).toContain('65 знаков');
  });

  it('подсказка у поля — на приёмке и в «Изменить»', async () => {
    render(<IntakeScreen reference={reference()} onSend={() => {}} />);
    expect(screen.getByText(OEM_FIELD_NOTE)).toBeTruthy();
    cleanup();

    vi.stubGlobal('fetch', vi.fn(async () => json(card())));
    renderForm();
    await waitFor(() => expect(screen.getByLabelText('Изменить: Номер производителя')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Номер производителя'));
    expect(screen.getByText(OEM_FIELD_NOTE, { exact: false })).toBeTruthy();
    vi.unstubAllGlobals();
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
