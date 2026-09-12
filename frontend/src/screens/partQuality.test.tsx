import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import type { CatalogRow, Warehouse } from '../inventory/catalog';

/**
 * Оценка состояния ставится с самой карточки и показана словом.
 *
 * <p><b>Зачем.</b> Поставить оценку было нельзя ни одним способом: форма
 * правки предлагала четыре значения, которых сервер не знает, и отвечала
 * «Запрос не разобран» — унося с собой всю остальную правку. Уже
 * поставленная (приёмкой с телефона) показывалась внутренним именем:
 * владелец видел в карточке `NO_DEFECTS` рядом с переведённым «б/у».
 *
 * <p>Проверяется то, что видит человек: плашка со словом, список
 * с пояснениями, ушедшее на сервер значение и то, что карточка при этом
 * не закрылась, — а не то, что компонент отрисовался.
 */
describe('оценка состояния в карточке позиции', () => {
  let sent: Array<{ url: string; method: string; body: unknown }>;

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      sent.push({
        url,
        method: init?.method ?? 'GET',
        body: init?.body === undefined ? null : JSON.parse(String(init.body)),
      });
      if (url.includes('/api/parts/bulk')) {
        return json({ changed: 1, skipped: 0, rejected: 0, rejectedCodes: [], rejectedReason: null });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('неоценённая деталь зовёт себя оценить, и выбор уходит на сервер', async () => {
    const closed: string[] = [];
    render(<PartCard {...props(row())} onClose={() => closed.push('close')} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Оценить запчасть' }));

    // Пояснение у каждого пункта — это и есть признак, по которому выбирают:
    // «С дефектами» от «Требует ремонт» словарь не отличает.
    expect(screen.getByText(/Износ минимальный/), 'пункт без пояснения').toBeTruthy();
    expect(screen.getByText(/передаются|уходит в объявления/),
      'не сказано, что оценку читает покупатель').toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Без дефектов/ }));

    await waitFor(() => {
      const post = sent.find((r) => r.method === 'POST');
      expect(post, 'оценка на сервер не уехала').toBeTruthy();
      expect(post?.body).toEqual({
        partIds: [42], changes: { qualityGrade: 'NO_DEFECTS' }, operations: {},
      });
    });

    // Карточка осталась открытой и показывает выбранное: закрывшееся окно
    // вместо плашки читается как «не сохранилось». Слово стоит и плашкой
    // над снимком, и строкой в списке полей — отсюда findAllByText.
    const shown = await screen.findAllByText('Без дефектов');
    expect(shown.length, 'плашка не встала').toBeGreaterThan(0);
    expect(closed, 'карточка закрылась вместо показа оценки').toEqual([]);
  });

  it('оценённая показана словом, а не внутренним именем', async () => {
    render(<PartCard {...props(row({ qualityGrade: 'WITH_DEFECTS' }))} />);

    const shown = await screen.findAllByText('С дефектами');
    expect(shown.length, 'оценка не названа словом').toBeGreaterThan(0);
    expect(screen.queryByText('WITH_DEFECTS'), 'на экране внутреннее имя').toBeNull();
  });

  it('оценку можно снять, и снимается она в NULL, а не в пустую строку', async () => {
    render(<PartCard {...props(row({ qualityGrade: 'NO_DEFECTS' }))} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Изменить оценку' }));
    fireEvent.click(screen.getByRole('button', { name: /Снять оценку/ }));

    await waitFor(() => {
      const post = sent.find((r) => r.method === 'POST');
      // Пустая строка нарушила бы CHECK схемы и означала бы значение,
      // а пусто здесь — «не оценена».
      expect(post?.body).toEqual({
        partIds: [42], changes: { qualityGrade: null }, operations: {},
      });
    });

    await waitFor(() => expect(screen.queryAllByText('Без дефектов'),
      'снятая оценка осталась на экране').toEqual([]));
  });

  it('продавцу оценка показана, но менять её нечем', async () => {
    render(<PartCard {...props(row({ qualityGrade: 'NO_DEFECTS' }))} role="SELLER" />);

    // Оценка уходит в объявление, и правят её те же, кто правит карточку.
    // Кнопка, отвечающая отказом, хуже отсутствующей.
    const shown = await screen.findAllByText('Без дефектов');
    expect(shown.length, 'оценка не показана').toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /оценк|Оценить/ }),
      'роли без права показана кнопка оценки').toBeNull();
  });

  it('отказ сервера назван словами, а не проглочен', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ message: 'Это поле нельзя править списком: qualityGrade' }),
      { status: 400, headers: { 'Content-Type': 'application/json' } },
    )));

    render(<PartCard {...props(row())} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Оценить запчасть' }));
    fireEvent.click(screen.getByRole('button', { name: /Как новая/ }));

    expect(await screen.findByText(/нельзя править списком/),
      'отказ сервера не доехал до человека').toBeTruthy();
  });
});

const WAREHOUSES: Warehouse[] = [{ id: 2, name: 'Основной' }];

function props(row: CatalogRow) {
  return {
    row,
    warehouses: WAREHOUSES,
    role: 'OWNER',
    onClose: () => {},
    onChanged: () => {},
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body),
    { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function row(overrides: Partial<CatalogRow> = {}): CatalogRow {
  return {
    id: 42, number: 42, code: 'B-40219', title: 'Фара левая Toyota Camry',
    qualityGrade: null, condition: 'USED',
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: 12500, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, cellCode: null,
    sideLr: null, sideFr: null,
    qty: 1, oem: null, crosses: null, photoUrl: null, supply: null, equipment: null,
    partName: null, published: null, barcode: null, legacyCode: null,
    videoUrl: null, textBlock: null, weightKg: null, dimensions: null,
    packageDimensions: null, packageWeightKg: null,
    createdAt: null, updatedAt: null, updatedByName: null,
    priceChangedAt: null, priceChangedByName: null, photoCount: 0,
    stock: { '2': 1 },
    ...overrides,
  };
}
