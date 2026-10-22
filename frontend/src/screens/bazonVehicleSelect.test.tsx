import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm } from './PartEditForm';
import { BazonVehicleSelect } from './BazonVehicleSelect';
import { NO_BAZON_VEHICLE, type BazonSelection } from '../catalog/bazonVehicles';
import type { CatalogRow } from '../inventory/catalog';
import { useState } from 'react';

/**
 * Выбор машины из общего справочника (задача 0377): марка → модель → кузов → двигатель,
 * списки сужаются выбранным, смена вышестоящего сбрасывает нижестоящее, пусто допустимо;
 * в карточке выбор уезжает целой четвёркой и только когда строку раскрыли.
 */
describe('машина из справочника', () => {
  let sent: Array<Record<string, unknown>>;

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>);
        return json({ price: 27000 });
      }
      if (url.endsWith('/api/catalog/bazon/marks')) {
        return json([{ id: 1, name: 'Lexus' }, { id: 2, name: 'Toyota' }]);
      }
      if (url.endsWith('/marks/2/models')) return json([{ id: 20, name: 'Camry' }]);
      if (url.endsWith('/marks/1/models')) return json([{ id: 10, name: 'ES' }]);
      if (url.endsWith('/models/20/bodies')) {
        return json([{ id: 200, name: 'XV70', description: null, generation: '8 поколение', years: '2017—...' }]);
      }
      if (url.endsWith('/marks/2/motors')) {
        return json([{ id: 300, name: '2ARFE', volumeCc: 2500, fuel: 'gasoline' }]);
      }
      if (url.endsWith('/marks/1/motors')) return json([]);
      return json(card());
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function Harness() {
    const [value, setValue] = useState<BazonSelection>(NO_BAZON_VEHICLE);
    return (
      <>
        <BazonVehicleSelect value={value} onChange={setValue} />
        <output aria-label="выбрано">{JSON.stringify(value)}</output>
      </>
    );
  }

  const chosen = (): BazonSelection =>
    JSON.parse(screen.getByLabelText('выбрано').textContent ?? '{}') as BazonSelection;

  it('списки сужаются выбранным, смена марки сбрасывает модель, кузов и двигатель', async () => {
    render(<Harness />);
    await waitFor(() => expect(screen.getByText('Toyota')).toBeTruthy());
    expect((screen.getByLabelText('Модель (справочник)') as HTMLSelectElement).disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('Марка (справочник)'), { target: { value: '2' } });
    await waitFor(() => expect(screen.getByText('Camry')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Модель (справочник)'), { target: { value: '20' } });
    await waitFor(() => expect(screen.getByText(/XV70/)).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Кузов (справочник)'), { target: { value: '200' } });
    await waitFor(() => expect(screen.getByText(/2ARFE/)).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Двигатель (справочник)'), { target: { value: '300' } });
    expect(chosen()).toEqual({ markId: 2, modelId: 20, bodyId: 200, motorId: 300 });

    fireEvent.change(screen.getByLabelText('Марка (справочник)'), { target: { value: '1' } });
    expect(chosen(), 'после смены марки остались модель, кузов или двигатель прежней')
      .toEqual({ markId: 1, modelId: null, bodyId: null, motorId: null });

    fireEvent.change(screen.getByLabelText('Марка (справочник)'), { target: { value: '' } });
    expect(chosen(), 'пусто допустимо на любом шаге').toEqual(NO_BAZON_VEHICLE);
  });

  it('в карточке выбор уезжает целой четвёркой, только когда строку раскрыли', async () => {
    render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Машина из справочника')).toBeTruthy());
    expect(line('Машина из справочника').textContent).toContain('не заполнено');

    fireEvent.click(screen.getByLabelText('Изменить: Машина из справочника'));
    await waitFor(() => expect(screen.getByText('Toyota')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Марка (справочник)'), { target: { value: '2' } });
    await waitFor(() => expect(screen.getByText('Camry')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Модель (справочник)'), { target: { value: '20' } });
    fireEvent.click(screen.getByText('Сохранить'));

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['bazonVehicle']).toEqual({ markId: 2, modelId: 20, bodyId: null, motorId: null });
    expect(Object.keys(sent[0]!).sort(), 'ушло больше, чем правили')
      .toEqual(['bazonVehicle', 'seen']);
  });

  it('нераскрытая строка машины в запрос не попадает', async () => {
    render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Заметка')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'трещина' } });
    fireEvent.click(screen.getByText('Сохранить'));

    await waitFor(() => expect(sent.length).toBe(1));
    expect(Object.keys(sent[0]!)).not.toContain('bazonVehicle');
  });
});

function line(label: string): HTMLElement {
  const found = [...document.querySelectorAll('.card-edit__row')].find(
    (r) => r.querySelector('.card-edit__label')?.textContent === label);
  if (found === undefined) throw new Error(`строки «${label}» на экране нет`);
  return found as HTMLElement;
}

function card(): Record<string, unknown> {
  return {
    price: 27000, minPrice: null, costPrice: null, installationPrice: null,
    condition: 'USED', qualityGrade: null, description: null, note: null,
    textBlock: null, videoUrl: null, marking: null, manufacturer: null,
    color: null, section: null, barcode: null, weightKg: null, lengthMm: null,
    widthMm: null, heightMm: null, packageLengthMm: null, packageWidthMm: null,
    packageHeightMm: null, packageWeightKg: null, storageCellId: null, published: true,
    warranty: null, pickup: null, deliveryPayment: null, dromKind: null, dromSection: null,
    applicability: null, characteristics: null, mayFit: null, adDate: null,
    number: 7, code: 'B-40219', title: 'Фара левая', sideLr: null, sideFr: null,
    authenticity: null, oem: null, crosses: null, legacyCode: null, dromAdNumber: null,
    donorCode: null, brand: null, model: null, generation: null, body: null, engine: null,
    year: null, equipment: null,
    bazonVehicle: { markId: null, modelId: null, bodyId: null, motorId: null },
  };
}

function row(): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title: 'Фара левая',
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null, price: 27000, stock: {},
  } as unknown as CatalogRow;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
