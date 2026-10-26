import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DONOR_LOCK_NOTE, DONOR_UNKNOWN_NOTE, PartEditForm } from './PartEditForm';
import type { CatalogRow } from '../inventory/catalog';

/**
 * У позиции с машиной-донором поля машины под замком, правится только
 * «Номер донора» (задача 0365, решение владельца продукта 19.10.2026
 * «Всё как у bazon»). Проверяется видимое человеку — какие строки
 * без «Изменить» и с замком — и то, что уехало на сервер.
 */
const LOCKED = ['Машина из справочника', 'Марка', 'Модель', 'Модель кузова', 'Модель двигателя',
  'Год выпуска', 'Комплектация', 'Поколение'];

const DONORS = [
  { id: 5, code: '261', brand: 'Toyota', model: 'Camry', year: 2007, vin: null,
    status: 'DISMANTLING', note: null, location: null },
  { id: 6, code: '262', brand: 'Nissan', model: 'Teana', year: 2008, vin: null,
    status: 'DISMANTLING', note: null, location: null },
];

describe('машина позиции с донором под замком', () => {
  let calls: Array<{ method: string; url: string; body: Record<string, unknown> }>;
  let donorAnswer: () => Response;

  beforeEach(() => {
    calls = [];
    donorAnswer = () => json({ id: 5, code: '261', partsCount: 3 });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (method !== 'GET') {
        calls.push({ method, url, body: JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown> });
        return json({ id: 7, title: 'Фара', price: 27000 });
      }
      if (url.endsWith('/api/parts/7/donor')) return donorAnswer();
      if (url.endsWith('/api/intake/donors')) return json(DONORS);
      return json(card());
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('восемь строк машины без «Изменить» и с замком, «Номер донора» правится', async () => {
    render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
    await screen.findByText(DONOR_LOCK_NOTE);

    for (const label of LOCKED) {
      expect(screen.queryByLabelText(`Изменить: ${label}`), `«${label}» правится`).toBeNull();
      expect(screen.getByRole('img', { name: `${label}: из машины-донора, не правится` }))
        .toBeTruthy();
    }
    expect(screen.getByLabelText('Изменить: Номер донора')).toBeTruthy();
    expect(screen.getByLabelText('Изменить: Цена'), 'замок задел соседний раздел').toBeTruthy();
    // Значения видны: замок прячет правку, а не машину.
    expect(line('Марка').textContent).toContain('Toyota');
  });

  it('смена номера донора уходит своим запросом, поля машины в правку не уходят', async () => {
    let saved = false;
    render(<PartEditForm partId={7} row={row()}
                         onSaved={() => { saved = true; }} onCancel={() => {}} />);
    fireEvent.click(await screen.findByLabelText('Изменить: Номер донора'));
    fireEvent.change(await screen.findByLabelText('Найти машину'), { target: { value: 'teana' } });
    fireEvent.change(screen.getByLabelText('Номер донора'), { target: { value: '6' } });
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'полка у окна' } });
    fireEvent.click(screen.getByText('Сохранить'));

    await waitFor(() => expect(saved).toBe(true));
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'PUT /api/parts/7/donor', 'PUT /api/parts/7']);
    expect(calls[0]!.body).toEqual({ donorId: 6 });
    expect(calls[1]!.body).not.toHaveProperty('donorCode');
    expect(calls[1]!.body['note']).toBe('полка у окна');
  });

  it('раскрытый и не сменённый номер донора не шлёт ничего', async () => {
    let saved = false;
    render(<PartEditForm partId={7} row={row()}
                         onSaved={() => { saved = true; }} onCancel={() => {}} />);
    fireEvent.click(await screen.findByLabelText('Изменить: Номер донора'));
    await screen.findByLabelText('Номер донора');
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(saved).toBe(true));
    expect(calls).toEqual([]);
  });

  it('не смогли узнать донора — замок стоит, причина названа', async () => {
    donorAnswer = () => new Response('{}', { status: 503 });
    render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
    await screen.findByText(DONOR_UNKNOWN_NOTE);
    expect(screen.queryByLabelText('Изменить: Марка')).toBeNull();
    expect(screen.queryByLabelText('Изменить: Номер донора')).toBeNull();
  });

  it('позиция без донора: замка нет, марка правится', async () => {
    donorAnswer = () => new Response('{}', { status: 404 });
    render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
    expect(await screen.findByLabelText('Изменить: Марка')).toBeTruthy();
    expect(screen.queryByText(DONOR_LOCK_NOTE)).toBeNull();
    expect(screen.queryByRole('img', { name: /из машины-донора/ })).toBeNull();
  });
});

function line(label: string): HTMLElement {
  const found = [...document.querySelectorAll('.card-edit__row')]
    .find((r) => r.querySelector('.card-edit__label')?.textContent === label);
  if (found === undefined) throw new Error(`строки «${label}» нет`);
  return found as HTMLElement;
}

function card(): Record<string, unknown> {
  return {
    price: 27000, minPrice: null, costPrice: null, installationPrice: null,
    condition: 'USED',
    qualityGrade: 'NO_DEFECTS', description: 'Фара в сборе', note: 'скол на креплении',
    textBlock: null, videoUrl: null, marking: null, manufacturer: 'Toyota',
    color: null, section: null, barcode: null, weightKg: null, lengthMm: null,
    widthMm: null, heightMm: null, packageLengthMm: null, packageWidthMm: null,
    packageHeightMm: null, packageWeightKg: null, storageCellId: null,
    published: true,
    warranty: 'Обговаривается индивидуально',
    pickup: 'Самовывоз — улица Георгия Исакова 9',
    deliveryPayment: 'Доставка по городу — 300 р.\n\nОплата при получении',
    dromKind: 'Карданный вал Lexus GX400',
    dromSection: 'Автозапчасти → Трансмиссия',
    applicability: null,
    characteristics: null,
    mayFit: null,
    adDate: '2026-09-28',
    // Задача 0302: то, что раньше стояло с замком. Машины своей у позиции нет.
    number: 7, code: 'B-40219', title: 'Фара левая Toyota Camry',
    sideLr: 'LEFT', sideFr: 'FRONT', authenticity: null, oem: null, crosses: null,
    legacyCode: null, dromAdNumber: null, donorCode: null, brand: null, model: null,
    generation: null, body: null, engine: null, year: null, equipment: null,
  };
}

function row(): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title: 'Фара левая Toyota Camry',
    qualityGrade: 'NO_DEFECTS', condition: 'USED',
    brand: 'Toyota', model: 'Camry', generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: 2007, donorCode: '261',
    price: 27000, installationPrice: null, color: null, description: 'Фара в сборе',
    note: 'скол на креплении',
    manufacturer: 'Toyota', marking: null, section: null, cellCode: 'А-01-1',
    sideLr: 'LEFT', sideFr: 'FRONT',
    qty: 1, oem: null, crosses: null, photoUrl: null, supply: null, equipment: null,
    partName: 'фара', published: true, barcode: null, legacyCode: null, dromAdNumber: null,
    videoUrl: null, textBlock: null, weightKg: null, dimensions: null,
    packageDimensions: null, packageWeightKg: null, createdAt: null, updatedAt: null,
    updatedByName: null, priceChangedAt: null, priceChangedByName: null,
    photoCount: 0, stock: {},
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
