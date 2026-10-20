import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import type { CatalogRow, Warehouse } from '../inventory/catalog';

/**
 * Именованный текстовый блок в карточке (задача 0337, «Всё как у bazon»):
 * в просмотре — имя блока, в правке — выбор из списка, новый блок заводится
 * тут же. Номер блока человеку не показывается.
 */
describe('текстовый блок в карточке позиции', () => {
  let sent: Array<{ method: string; url: string; body: Record<string, unknown> }>;
  let blocks: Array<{ id: number; name: string; body: string }>;
  let chosen: number | null;

  beforeEach(() => {
    sent = [];
    chosen = 3;
    blocks = [
      { id: 3, name: 'Наличные', body: 'ОПЛАТА В МАГАЗИНЕ ТОЛЬКО НАЛИЧНЫМИ!' },
      { id: 4, name: 'Предоплата', body: 'Предоплата 100%' },
    ];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (method !== 'GET') {
        const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
        sent.push({ method, url, body });
        if (url.endsWith('/api/text-blocks') && method === 'POST') {
          return json({ id: 9, name: body['name'], body: body['body'] });
        }
        return json({});
      }
      if (url.includes('/editable')) return json(card(chosen));
      if (url.endsWith('/api/text-blocks')) return json(blocks);
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('закрытая строка называет блок по имени, а не номером', async () => {
    render(<PartCard {...props()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(formLine('Текстовый блок')).toBe('Наличные'));
    expect(formLine('Текстовый блок')).not.toContain('3');
  });

  it('выбор другого блока уходит ключом textBlockId, снятие — null', async () => {
    render(<PartCard {...props()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(screen.getByLabelText('Изменить: Текстовый блок')).toBeTruthy());
    await waitFor(() => expect(formLine('Текстовый блок')).toBe('Наличные'));

    fireEvent.click(screen.getByLabelText('Изменить: Текстовый блок'));
    fireEvent.change(screen.getByLabelText('Текстовый блок'), { target: { value: '4' } });
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!.body['textBlockId']).toBe(4);
    expect('textBlock' in sent[0]!.body, 'старый текст уехал вместе с выбором').toBe(false);
  });

  it('новый блок заводится в форме и встаёт в поле выбранным', async () => {
    chosen = null;
    render(<PartCard {...props()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(screen.getByLabelText('Изменить: Текстовый блок')).toBeTruthy());
    await waitFor(() => expect(formLine('Текстовый блок')).toBe('не выбран'));

    fireEvent.click(screen.getByLabelText('Изменить: Текстовый блок'));
    fireEvent.click(screen.getByText('Новый блок'));
    fireEvent.change(screen.getByLabelText('Название блока'), { target: { value: 'Самовывоз' } });
    fireEvent.change(screen.getByLabelText('Текст блока'), { target: { value: 'Только самовывоз' } });
    fireEvent.click(screen.getByText('Создать блок'));
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]).toMatchObject({ method: 'POST', body: { name: 'Самовывоз', body: 'Только самовывоз' } });

    // Заведённый встал выбранным; в позицию он ляжет вместе с «Сохранить».
    await waitFor(() =>
      expect((screen.getByLabelText('Текстовый блок') as HTMLSelectElement).value).toBe('9'));
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(sent.length).toBe(2));
    expect(sent[1]!.body['textBlockId']).toBe(9);
  });
});

describe('удаление блока', () => {
  it('занятый блок не удаляется — слова сервера на экране, блок остаётся в списке', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if ((init?.method ?? 'GET') === 'DELETE') {
        return new Response(JSON.stringify({ message: 'Блок «Наличные» выбран у позиций: 2' }),
          { status: 400, headers: { 'Content-Type': 'application/json' } });
      }
      if (url.includes('/editable')) return json(card(3));
      if (url.endsWith('/api/text-blocks')) {
        return json([{ id: 3, name: 'Наличные', body: 'ОПЛАТА' }]);
      }
      return json([]);
    }));
    render(<PartCard {...props()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Изменить' }));
    await waitFor(() => expect(formLine('Текстовый блок')).toBe('Наличные'));
    fireEvent.click(screen.getByLabelText('Изменить: Текстовый блок'));
    fireEvent.click(screen.getByText('Изменить блок'));
    fireEvent.click(screen.getByText('Удалить блок'));
    await waitFor(() => expect(screen.getByText('Блок «Наличные» выбран у позиций: 2')).toBeTruthy());
    expect((screen.getByLabelText('Название блока') as HTMLInputElement).value).toBe('Наличные');
    cleanup();
    vi.unstubAllGlobals();
  });
});

/** Значение нераскрытой строки формы правки по подписи. */
function formLine(label: string): string | null | undefined {
  const found = [...document.querySelectorAll('.card-edit__row')].find(
    (r) => r.querySelector('.card-edit__label')?.textContent === label);
  if (found === undefined) throw new Error(`строки «${label}» в форме нет`);
  return found.querySelector('.card-edit__value')?.textContent;
}

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Ткацкая' }];

function props() {
  return { row: row(), warehouses: WAREHOUSES, role: 'OWNER', onClose: () => {}, onChanged: () => {} };
}

function row(): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title: 'Фара Toyota Camry 2007 лев. (б/у)',
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

function card(textBlockId: number | null): Record<string, unknown> {
  return {
    price: 7500, minPrice: null, costPrice: null, installationPrice: null,
    condition: 'USED', qualityGrade: null, description: null, note: null,
    textBlock: textBlockId === null ? null : 'ОПЛАТА В МАГАЗИНЕ ТОЛЬКО НАЛИЧНЫМИ!',
    textBlockId, videoUrl: null, marking: null, manufacturer: null,
    color: null, section: null, barcode: null, weightKg: null, lengthMm: null,
    widthMm: null, heightMm: null, packageLengthMm: null, packageWidthMm: null,
    packageHeightMm: null, packageWeightKg: null, storageCellId: null, published: true,
    warranty: null, pickup: null, deliveryPayment: null, dromKind: null, dromSection: null,
    applicability: null, characteristics: null, mayFit: null, adDate: null,
    number: 7, code: 'B-40219', title: 'Фара Toyota Camry 2007 лев. (б/у)',
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
