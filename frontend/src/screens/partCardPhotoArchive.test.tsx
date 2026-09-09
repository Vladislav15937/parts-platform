import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import type { CatalogRow, Warehouse } from '../inventory/catalog';

/**
 * «Скачать все фото» — одно действие вместо девяти сохранений правой кнопкой.
 *
 * <p><b>Зачем.</b> Разговор продавца с покупателем на разборке почти всегда
 * кончается «скиньте фото», и снимков у позиции шесть-девять. Пачкой их было
 * не взять: продавец девять раз открывал снимок стрелкой и девять раз
 * сохранял его правой кнопкой браузера — а ссылки подписанные
 * и короткоживущие, то есть сохранять надо сразу и по одной. Минута-полторы
 * механической работы на каждый разговор, на глазах у ждущего на линии.
 *
 * <p>Проверяется то, что видит человек: действие в карточке, куда оно ведёт,
 * и что оно погашено с причиной у позиции без снимков.
 */
describe('скачивание всех снимков из карточки позиции', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/photos')) {
        return json(PHOTOS);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('ведёт на архив этой позиции', async () => {
    render(<PartCard {...props()} />);

    const action = await screen.findByRole('link', { name: 'Скачать все фото' });
    expect(action.getAttribute('href'), 'ссылка ведёт не на архив снимков позиции')
      .toBe('/api/parts/42/photos/archive');
    // Собирает архив сервер, браузер его сохраняет: без `download` вкладка
    // ушла бы открывать zip вместо того, чтобы положить его в «Загрузки».
    expect(action.hasAttribute('download'), 'файл не сохраняется, а открывается').toBe(true);
  });

  it('у позиции без снимков погашено и называет причину', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([])));

    render(<PartCard {...props()} />);

    const action = await screen.findByRole('button', { name: 'Скачать все фото' });
    expect((action as HTMLButtonElement).disabled, 'нажимается на нечего скачивать')
      .toBe(true);
    expect(screen.getByText(/Снимков нет/), 'причина не названа').toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Скачать все фото' }),
      'пустой архив всё-таки скачивается').toBeNull();
  });

  it('видно «Просмотру»: это те же снимки, что он и так смотрит', async () => {
    render(<PartCard {...props()} role="VIEWER" />);

    await waitFor(() => expect(screen.getByText('Фара левая Toyota Camry')).toBeTruthy());
    expect(screen.queryByRole('link', { name: 'Скачать все фото' }),
      'роли, которой снимки показаны, скачать их нечем').toBeTruthy();
  });
});

const PHOTOS = [
  { photoId: 1, main: false, url: 'https://s3/1.jpg' },
  { photoId: 2, main: true, url: 'https://s3/2.jpg' },
];

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Ткацкая' }];

function props(role = 'SELLER') {
  return {
    row: row(),
    warehouses: WAREHOUSES,
    role,
    onClose: () => {},
    onChanged: () => {},
  };
}

function row(): CatalogRow {
  return {
    id: 42,
    code: 'B-40219',
    title: 'Фара левая Toyota Camry',
    qualityGrade: null,
    condition: null,
    brand: 'Toyota',
    model: 'Camry',
    generation: null,
    yearFrom: null,
    yearTo: null,
    body: null,
    engine: null,
    year: null,
    donorCode: null,
    price: 12500,
    installationPrice: null,
    color: null,
    description: null,
    note: null,
    manufacturer: null,
    marking: null,
    section: null,
    cellCode: null,
    sideLr: null,
    sideFr: null,
    qty: 1,
    oem: null,
    crosses: null,
    photoUrl: null,
    supply: null,
    equipment: null,
    partName: null,
    published: null,
    barcode: null,
    legacyCode: null,
    videoUrl: null,
    textBlock: null,
    weightKg: null,
    dimensions: null,
    packageDimensions: null,
    packageWeightKg: null,
    createdAt: null,
    updatedAt: null,
    updatedByName: null,
    priceChangedAt: null,
    priceChangedByName: null,
    photoCount: 2,
    stock: { '2': 1 },
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
