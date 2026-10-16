import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm } from './PartEditForm';
import { put, STORE_REFERENCE } from '../storage/db';
import type { CatalogRow } from '../inventory/catalog';
import type { Brand, Model } from '../catalog/vehicles';

/**
 * Марка и модель в «Изменить» подсказываются из справочника, а набранное
 * остаётся как набрано (задача 0308).
 *
 * <p><b>Зачем.</b> Замер на ПРОМ 16.10.2026: «тойота» и «Лексус» сохранялись
 * как набраны и расходились с написаниями справочника в отборе выгрузки,
 * подборе и списке марок продавца — позиция уходила в другую марку, чем
 * у остальных. Решение владельца продукта: подсказка из справочника,
 * «но также должна быть возможность свободного текста»; русские названия
 * со справочником не сопоставлять.
 *
 * <p>Проверяется то, что видит человек (кнопки подсказок, слова под полем)
 * и что уехало на сервер, — а не состояние компонента.
 */
describe('подсказки марки и модели в правке карточки', () => {
  let sent: Array<Record<string, unknown>>;
  let catalogRequests: number;
  let catalogFails: boolean;

  beforeEach(async () => {
    sent = [];
    catalogRequests = 0;
    catalogFails = false;
    // Кэш устройства пуст: справочник обязан приехать запросом.
    await put(STORE_REFERENCE, null, 'vehicles');
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>);
        return json({});
      }
      if (url.includes('/api/catalog/vehicles')) {
        catalogRequests += 1;
        if (catalogFails) return new Response('{}', { status: 503 });
        return json({ brands: BRANDS, models: MODELS, generations: [] });
      }
      return json(card());
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('«toy» в марке предлагает Toyota, выбор ставит написание справочника и закрывает список', async () => {
    // Машина позиции без донора: иначе выбранная Toyota совпала бы с показанной
    // донорской, и уезжать было бы нечему.
    const saved = await openForm('Марка', { brand: null, model: null, donorCode: null });
    typeInto('Марка', 'toy');

    const pick = await screen.findByRole('button', { name: 'Toyota' }, WAIT);
    expect(suggestions(), 'Mazda не содержит «toy»').toEqual(['Toyota']);
    fireEvent.click(pick);

    expect((screen.getByLabelText('Марка') as HTMLInputElement).value).toBe('Toyota');
    expect(suggestions(), 'после выбора список остался стоять').toEqual([]);

    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(saved()).toBe(true), WAIT);
    expect(sent[0]?.['brand']).toBe('Toyota');
  });

  it('регистр не важен: «MAZ» находит Mazda', async () => {
    await openForm('Марка');
    typeInto('Марка', 'MAZ');
    expect(await screen.findByRole('button', { name: 'Mazda' }, WAIT)).toBeTruthy();
  });

  it('«Лексус», которого нет в справочнике, сохраняется как есть — без замены и без отказа', async () => {
    const saved = await openForm('Марка');
    typeInto('Марка', 'Лексус');

    expect(await screen.findByText(/Нет в справочнике — сохранится как вписано/, {}, WAIT)).toBeTruthy();
    expect(suggestions()).toEqual([]);

    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(saved()).toBe(true), WAIT);
    expect(sent[0]?.['brand']).toBe('Лексус');
  });

  it('русское «тойота» не сопоставляется с Toyota — ни подсказкой, ни при сохранении', async () => {
    const saved = await openForm('Марка');
    typeInto('Марка', 'тойо');
    await screen.findByText(/Нет в справочнике/, {}, WAIT);
    expect(screen.queryByRole('button', { name: 'Toyota' }),
      'русское написание сопоставилось со справочником').toBeNull();

    typeInto('Марка', 'тойота');
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(saved()).toBe(true), WAIT);
    expect(sent[0]?.['brand']).toBe('тойота');
  });

  it('модель подсказывается из моделей марки, стоящей в строке «Марка»', async () => {
    // Своей марки у позиции нет — в строке стоит донорская Toyota.
    await openForm('Модель');
    typeInto('Модель', 'co');

    await screen.findByRole('button', { name: 'Corolla' }, WAIT);
    expect(suggestions(), 'подсказана модель чужой марки').toEqual(['Corolla']);
  });

  it('марка « toyota » узнаётся как сервер — без регистра и пробелов по краям', async () => {
    // Сервер узнаёт свою марку позиции `lower(cb.name) = lower(btrim(manual_brand))`
    // (`PartVehicle.brandId`). Поле обязано узнавать её так же: иначе у марки,
    // которую сервер считает Toyota, модели не подсказывались бы.
    await openForm('Марка', { brand: null, model: null, donorCode: null });
    typeInto('Марка', ' toyota ');
    fireEvent.click(screen.getByLabelText('Изменить: Модель'));
    typeInto('Модель', 'co');

    await waitFor(() => expect(
      document.querySelector(MODEL_LIST) ?? screen.queryByText(/Марки .* нет в справочнике/),
      'поле модели не ответило ни подсказкой, ни словами').toBeTruthy(), WAIT);
    expect(screen.queryByText(/Марки «toyota» нет в справочнике/),
      'марка « toyota » не узнана: поле сравнивает не так, как сервер').toBeNull();
    expect([...document.querySelectorAll(`${MODEL_LIST} button`)].map((b) => b.textContent))
      .toEqual(['Corolla']);
  });

  it('марка набрана текстом и в справочнике её нет — у модели подсказок нет, поле свободное', async () => {
    const saved = await openForm('Марка');
    typeInto('Марка', 'Лексус');
    fireEvent.click(screen.getByLabelText('Изменить: Модель'));
    typeInto('Модель', 'co');

    expect(await screen.findByText(/Марки «Лексус» нет в справочнике — подсказок нет/, {}, WAIT)).toBeTruthy();
    expect(suggestions()).toEqual([]);

    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(saved()).toBe(true), WAIT);
    expect(sent[0]?.['model']).toBe('co');
  });

  it('список не длиннее восьми строк, а справочник качается один раз на форму', async () => {
    await openForm('Марка');
    typeInto('Марка', 'a');

    await screen.findByRole('button', { name: 'Mazda' }, WAIT);
    expect(suggestions().length).toBe(8);

    typeInto('Марка', 'Toyota');
    fireEvent.click(screen.getByLabelText('Изменить: Модель'));
    typeInto('Модель', 'c');
    await screen.findByRole('button', { name: 'Camry' }, WAIT);
    expect(catalogRequests, 'справочник запрошен повторно').toBe(1);
  });

  it('справочник не загрузился — сказано словами, поле работает свободным', async () => {
    catalogFails = true;
    const saved = await openForm('Марка');

    expect(await screen.findByText(/Подсказок нет: справочник машин не загрузился/, {}, WAIT)).toBeTruthy();
    typeInto('Марка', 'Лексус');
    fireEvent.click(screen.getByText('Сохранить'));
    await waitFor(() => expect(saved()).toBe(true), WAIT);
    expect(sent[0]?.['brand']).toBe('Лексус');
  });

  it('пока строку машины не раскрыли, справочник не качается', async () => {
    await openForm('Цена');
    expect(catalogRequests).toBe(0);
  });
});

/**
 * Запас на цепочку «IndexedDB → запрос справочника → подсказки»: умолчание
 * `findBy*` — секунда, и на загруженной машине полного прогона её не хватило
 * (тот же урок, что у `feedsMountedGuard.test.tsx`).
 */
const WAIT = { timeout: 5000 };

/** Список подсказок поля «Модель» — у «Марки» свой, и он может стоять рядом. */
const MODEL_LIST = 'ul[aria-label="Подсказки: Модель"]';

async function openForm(label: string, over: Partial<CatalogRow> = {}): Promise<() => boolean> {
  let saved = false;
  render(<PartEditForm partId={7} row={{ ...row(), ...over }}
                       onSaved={() => { saved = true; }} onCancel={() => {}} />);
  await waitFor(() => expect(screen.getByLabelText(`Изменить: ${label}`)).toBeTruthy());
  fireEvent.click(screen.getByLabelText(`Изменить: ${label}`));
  return () => saved;
}

function typeInto(label: string, value: string): void {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function suggestions(): string[] {
  return [...document.querySelectorAll('.suggestions button')].map((b) => b.textContent ?? '');
}

const BRANDS: Brand[] = [
  { id: 1, slug: 'toyota', name: 'Toyota', nameRu: 'Тойота' },
  { id: 2, slug: 'mazda', name: 'Mazda', nameRu: 'Мазда' },
  // Десяток марок с «a»: подсказок больше предела.
  ...['Acura', 'Alfa Romeo', 'Aston Martin', 'Audi', 'Chevrolet', 'Daewoo', 'Honda',
    'Hyundai', 'Kia', 'Lada'].map((name, i): Brand => (
    { id: 10 + i, slug: name.toLowerCase(), name, nameRu: null })),
];

const MODELS: Model[] = [
  { id: 100, slug: 'camry', name: 'Camry', brandId: 1 },
  { id: 101, slug: 'corolla', name: 'Corolla', brandId: 1 },
  { id: 200, slug: 'cx-5', name: 'CX-5', brandId: 2 },
  { id: 201, slug: 'mazda6', name: 'Mazda6 Coupe', brandId: 2 },
];

function card(): Record<string, unknown> {
  return {
    price: 27000, condition: 'USED', published: true, storageCellId: null,
    number: 7, code: 'B-40219', title: 'Фара левая Toyota Camry',
    brand: null, model: null,
  };
}

function row(): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title: 'Фара левая Toyota Camry',
    brand: 'Toyota', model: 'Camry', generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: 2007, donorCode: '261', price: 27000,
    stock: {},
  } as unknown as CatalogRow;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
