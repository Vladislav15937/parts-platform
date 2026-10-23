import { rightsAs } from '../test/rights';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import type { CatalogRow, Warehouse } from '../inventory/catalog';

/**
 * Наименование позиции в карточке — как хранится, с пометкой «(б/у)», и руками
 * не правится (задача 0336, решение владельца 19.10.2026: «всё как в базон»).
 *
 * <p>У Bazon поля «Наименование» в форме правки нет: название собирается
 * из вида, машины, года, положения, состояния и номера. Прежняя запись 0311
 * («показывать без „(б/у)“») отменена тем же решением: у Bazon пометка видна
 * везде, где видно название.
 *
 * <p>Проверяется то, что видит человек: текст шапки, строка просмотра,
 * отсутствие строки в форме и что уехало на сервер.
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

  it('шапка и просмотр показывают наименование как хранится — с «(б/у)»', () => {
    render(<PartCard {...props(row(title))} />);

    expect(screen.getByRole('heading', { level: 2 }).textContent, 'шапка').toBe(title);
    expect(viewed('Наименование'), 'строка «Наименование» просмотра').toBe(title);
    // Состояние видно и своим полем (серия 0314 в силе).
    expect(viewed('Состояние')).toBe('б/у');
    // Написание вида детали не пропало: по нему разбирают нераспознанные.
    expect(viewed('Наименование в справочнике')).toBe('фара лев.');
  });

  it('в форме правки строки «Наименование» нет, и сохранение его не шлёт', async () => {
    render(<PartCard {...props(row(title))} />);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(screen.getByLabelText('Изменить: Заметка')).toBeTruthy());

    expect(screen.queryByLabelText('Изменить: Наименование'), 'кнопка правки наименования')
      .toBeNull();
    expect(() => formLine('Наименование')).toThrow();

    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'скол' } });
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['note']).toBe('скол');
    expect('title' in sent[0]!, 'наименование уехало в правке').toBe(false);
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
  return { row, warehouses: WAREHOUSES, rights: rightsAs('OWNER'), onClose: () => {}, onChanged: () => {} };
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
