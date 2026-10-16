import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm } from './PartEditForm';
import { put, STORE_REFERENCE } from '../storage/db';
import type { CatalogRow } from '../inventory/catalog';

/** Объявлено здесь, а не через `@types/node`: см. `inventoryMountedGuard.test.tsx`. */
declare const process: {
  on(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
  off(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
};

/**
 * Сторож размонтирования у справочника подсказок марки (задача 0308,
 * `useVehicleCatalog`).
 *
 * <p>Справочник качается по первому раскрытию «Марки», а форму закрывают
 * когда угодно — в том числе пока он едет. Состояние пишется после двух
 * ожиданий (IndexedDB, запрос), и без сторожа колбэк догоняет снесённую
 * среду: `ReferenceError: window is not defined` из чужого файла. Полный
 * прогон этого не видит (`docs/frontend-rules.md` §9), поэтому гонка
 * устраивается руками: `cleanup()` сразу после раскрытия, снятый `window`,
 * ожидание.
 *
 * <p>Вторая сторона — `StrictMode`: сторож, который только снимается,
 * после двойного прогона эффектов гасит подсказки навсегда.
 */
describe('сторож размонтирования у подсказок марки', () => {
  beforeEach(async () => {
    await put(STORE_REFERENCE, null, 'vehicles');
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/catalog/vehicles')) {
        // Справочник едет дольше, чем человек держит форму открытой.
        await sleep(100);
        return json({
          brands: [{ id: 1, slug: 'toyota', name: 'Toyota', nameRu: null }],
          models: [], generations: [],
        });
      }
      return json(card());
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('закрытая посреди загрузки справочника форма не будит снесённую среду', async () => {
    const escaped: unknown[] = [];
    const collect = (reason: unknown): void => { escaped.push(reason); };
    process.on('unhandledRejection', collect);

    try {
      render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
      await waitFor(() => expect(screen.getByLabelText('Изменить: Марка')).toBeTruthy());

      // Раскрыли «Марку» и в тот же тик закрыли форму: справочник ещё в пути.
      fireEvent.click(screen.getByLabelText('Изменить: Марка'));
      cleanup();
      // Поле марки встаёт с фокусом, и jsdom шлёт `selectionchange` следом,
      // отдельной задачей; React ловит его на документе и без `window` падает
      // сам — чужая для проверки ошибка. Даём ей пройти: справочник едет
      // 100 мс, гонка за ним остаётся.
      await sleep(20);

      const globals = globalThis as unknown as { window?: unknown };
      const savedWindow = globals.window;
      delete globals.window;
      await sleep(500);
      globals.window = savedWindow;

      expect(escaped.map(String),
        'колбэк справочника записал состояние закрытой формы').toEqual([]);
    } finally {
      process.off('unhandledRejection', collect);
    }
  });

  it('в StrictMode подсказка приезжает, а не висит на «Загружаем подсказки…»', async () => {
    render(
      <StrictMode>
        <PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />
      </StrictMode>,
    );
    await waitFor(() => expect(screen.getByLabelText('Изменить: Марка')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Марка'));
    fireEvent.change(screen.getByLabelText('Марка'), { target: { value: 'toy' } });

    await waitFor(() => expect(screen.queryByText(/Загружаем подсказки/),
      'справочник так и не приехал: сторож погас после двойного прогона эффектов')
      .toBeNull(), { timeout: 5000 });
    expect(screen.getByRole('button', { name: 'Toyota' })).toBeTruthy();
  });
});

/** Пауза без Node API. */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

function card(): Record<string, unknown> {
  return {
    price: 27000, condition: 'USED', published: true, storageCellId: null,
    number: 7, code: 'B-40219', title: 'Фара левая', brand: null, model: null,
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
