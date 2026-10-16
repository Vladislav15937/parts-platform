import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import type { CatalogRow, Warehouse } from '../inventory/catalog';

/**
 * Наименование позиции в шапке карточки, в просмотре и в правке — одна строка
 * (задача 0311, решение владельца 16.10.2026: «показывать одинаково», без «(б/у)»).
 *
 * <p><b>Зачем.</b> Замер на ПРОМ: в просмотре «фара левая», а в шапке и в поле
 * «Наименование» формы правки — «фара левая (б/у)». Строка «Наименование»
 * просмотра показывала написание вида детали, а шапка и форма — собранное
 * наименование с пометкой состояния; правя название руками, человек не знал,
 * какое из двух уйдёт дальше.
 *
 * <p>Проверяется то, что видит человек: текст шапки, строки «Наименование»
 * просмотра, строки и поля ввода формы, и что уехало на сервер.
 */
describe('наименование в карточке позиции', () => {
  let sent: Array<Record<string, unknown>>;
  let title: string;

  beforeEach(() => {
    sent = [];
    title = 'Фара Toyota Camry 2007 лев. (б/у) 81170-33670';
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>);
        return json({});
      }
      if (url.includes('/editable')) return json(card(title));
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('шапка, просмотр и правка показывают одно и то же — без «(б/у)»', async () => {
    const expected = 'Фара Toyota Camry 2007 лев. 81170-33670';
    render(<PartCard {...props(row(title))} />);

    const head = screen.getByRole('heading', { level: 2 }).textContent;
    expect(head, 'шапка').toBe(expected);
    expect(viewed('Наименование'), 'строка «Наименование» просмотра').toBe(expected);
    // Состояние видно своим полем, как и было.
    expect(viewed('Состояние')).toBe('б/у');
    // Написание вида детали не пропало: по нему разбирают нераспознанные.
    expect(viewed('Наименование в справочнике')).toBe('фара лев.');

    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(screen.getByLabelText('Изменить: Наименование')).toBeTruthy());
    expect(formLine('Наименование'), 'строка «Наименование» формы правки').toBe(expected);

    fireEvent.click(screen.getByLabelText('Изменить: Наименование'));
    expect((screen.getByLabelText('Наименование') as HTMLInputElement).value,
      'поле ввода наименования').toBe(expected);
  });

  it('раскрытое и нетронутое наименование не уезжает, правленое уезжает набранным', async () => {
    render(<PartCard {...props(row(title))} />);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(screen.getByLabelText('Изменить: Наименование')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Наименование'));
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'скол' } });
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['note']).toBe('скол');
    expect('title' in sent[0]!, 'нетронутое наименование уехало без пометки — то есть изменённым')
      .toBe(false);
  });

  it('правленое наименование уезжает тем, что набрал человек', async () => {
    render(<PartCard {...props(row(title))} />);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(screen.getByLabelText('Изменить: Наименование')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Наименование'));
    fireEvent.change(screen.getByLabelText('Наименование'),
      { target: { value: 'Фара Toyota Camry 2007 левая' } });
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['title']).toBe('Фара Toyota Camry 2007 левая');
    // Увиденным уезжает ответ сервера как есть: сервер сравнивает с базой.
    expect((sent[0]!['seen'] as Record<string, unknown>)['title']).toBe(title);
  });

  it('вписанное руками показывается как сохранено, ничего не дописывается', async () => {
    title = 'Моя фара на Камри, левая';
    render(<PartCard {...props(row(title))} />);

    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe(title);
    expect(viewed('Наименование')).toBe(title);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(screen.getByLabelText('Изменить: Наименование')).toBeTruthy());
    expect(formLine('Наименование')).toBe(title);
  });
});

/** Значение строки просмотра по подписи — точной, а не по началу. */
function viewed(label: string): string | null | undefined {
  const dt = [...document.querySelectorAll('.card-view__fields dt')]
    .find((d) => d.textContent === label);
  if (dt === undefined) throw new Error(`строки «${label}» в просмотре нет`);
  return dt.nextElementSibling?.textContent;
}

/** Значение нераскрытой строки формы правки по подписи. */
function formLine(label: string): string | null | undefined {
  const found = [...document.querySelectorAll('.card-edit__row')].find(
    (r) => r.querySelector('.card-edit__label')?.textContent === label);
  if (found === undefined) throw new Error(`строки «${label}» в форме нет`);
  return found.querySelector('.card-edit__value')?.textContent;
}

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Ткацкая' }];

function props(row: CatalogRow) {
  return { row, warehouses: WAREHOUSES, role: 'OWNER', onClose: () => {}, onChanged: () => {} };
}

function row(title: string): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title,
    qualityGrade: null, condition: 'USED',
    brand: 'Toyota', model: 'Camry', generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: 2007, donorCode: '261',
    price: 7500, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, cellCode: null,
    sideLr: 'LEFT', sideFr: null,
    qty: 1, oem: '81170-33670', crosses: null, photoUrl: null, supply: null, equipment: null,
    partName: 'фара лев.', published: true, barcode: null, legacyCode: null, dromAdNumber: null,
    videoUrl: null, textBlock: null, weightKg: null, dimensions: null,
    packageDimensions: null, packageWeightKg: null, createdAt: null, updatedAt: null,
    updatedByName: null, priceChangedAt: null, priceChangedByName: null,
    photoCount: 0, stock: {},
  };
}

function card(title: string): Record<string, unknown> {
  return {
    price: 7500, minPrice: null, costPrice: null, installationPrice: null,
    condition: 'USED', qualityGrade: null, description: null, note: null,
    textBlock: null, videoUrl: null, marking: null, manufacturer: null,
    color: null, section: null, barcode: null, weightKg: null, lengthMm: null,
    widthMm: null, heightMm: null, packageLengthMm: null, packageWidthMm: null,
    packageHeightMm: null, packageWeightKg: null, storageCellId: null, published: true,
    warranty: null, pickup: null, deliveryPayment: null, dromKind: null, dromSection: null,
    applicability: null, characteristics: null, mayFit: null, adDate: null,
    number: 7, code: 'B-40219', title,
    sideLr: 'LEFT', sideFr: null, authenticity: null, oem: '81170-33670', crosses: null,
    legacyCode: null, dromAdNumber: null, donorCode: null, brand: null, model: null,
    generation: null, body: null, engine: null, year: null, equipment: null,
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
