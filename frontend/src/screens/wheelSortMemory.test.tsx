import { rightsAs } from '../test/rights';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { WheelsScreen } from './WheelsScreen';
import type { Wheel } from '../inventory/wheels';

/**
 * «Шины и диски» помнят выбранную сортировку за сотрудником, как склад
 * (задача 0379).
 *
 * <p><b>Что было для человека.</b> Склад помнит порядок на сервере
 * (`member_setting`, задача 0060), а вкладка колёс открывалась умолчанием
 * при каждом заходе: отсортировал по цене — вернулся завтра и сортируй
 * снова. Решение владельца 27.10.2026: «Шины и диски» запоминают выбранную
 * сортировку, как склад, а «Сбросить сортировку» ведёт к новому умолчанию
 * (новые сверху).
 *
 * <p>Механизм тот же, что у склада, — `GET`/`PUT /api/me/settings/{screen}`,
 * а не `localStorage`: «даже если он закрыл браузер, … открыл в другом
 * браузере» (решение владельца от 11.09.2026).
 */
describe('память порядка вкладки «Шины и диски»', () => {
  /** Что «лежит на сервере» у этого сотрудника. */
  let stored: Record<string, unknown> | null = null;
  /** Тела записей настройки — по ним видно, что запомнилось. */
  let written: Array<Record<string, unknown>> = [];
  /** Запросы самой вкладки — по ним виден применённый порядок. */
  let asked: string[] = [];

  beforeEach(() => {
    stored = null;
    written = [];
    asked = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/me/settings/wheels')) {
        if (init?.method === 'PUT') {
          const body = JSON.parse(String(init.body)) as Record<string, unknown>;
          written.push(body);
          stored = body;
          return json({ screen: 'wheels', value: body });
        }
        return json({ screen: 'wheels', value: stored });
      }
      if (url.includes('/api/wheels?')) {
        asked.push(url);
        // Строка нужна: при пустой выдаче таблицы, а с ней и меню колонок, нет.
        return json({
          total: 1,
          warehouses: [{ id: 1, name: 'Ткацкая' }],
          filterable: [],
          rows: [{ wheel: wheel(), photoUrl: null }],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('выбранный порядок уезжает на сервер и восстанавливается при следующем открытии',
    async () => {
      const first = render(<WheelsScreen canIntake={false} rights={rightsAs('OWNER')} />);
      await waitFor(() => expect(screen.getByText(/Goodyear/)).toBeTruthy());

      fireEvent.click(menuOf('Цена'));
      fireEvent.click(screen.getByText(/по возрастанию/));

      await waitFor(() => expect(written.length,
        'выбранная сортировка не уехала на сервер — завтра её не будет').toBeGreaterThan(0));
      expect(written[written.length - 1]).toEqual({ sort: 'price', desc: false });

      first.unmount();
      asked = [];
      render(<WheelsScreen canIntake={false} rights={rightsAs('OWNER')} />);
      await waitFor(() => expect(asked.length).toBeGreaterThan(0));

      // Ровно один запрос: умолчанием до восстановленного вкладка не грузится,
      // иначе владелец видит мелькнувший чужой порядок.
      expect(asked, 'вкладку успели запросить умолчанием до восстановления').toHaveLength(1);
      expect(asked[0], 'вкладка открылась умолчанием, а не запомненным порядком')
        .toContain('sort=price');
      expect(asked[0]).toContain('desc=false');
    });

  it('запомненное «по возрастанию» не меняется, а «Сбросить сортировку» ведёт к новым сверху',
    async () => {
      // Так лежит настройка у того, кто выбрал прежний порядок «1, 2, 3…».
      stored = { sort: 'number', desc: false };
      render(<WheelsScreen canIntake={false} rights={rightsAs('OWNER')} />);
      await waitFor(() => expect(asked.length).toBeGreaterThan(0));

      expect(asked[0], 'запомненная сортировка заменена умолчанием').toContain('desc=false');

      const reset = screen.queryByRole('button', { name: 'Сбросить сортировку' });
      expect(reset, 'порядок не умолчание, а вернуть умолчание нечем').toBeTruthy();
      fireEvent.click(reset!);

      await waitFor(() => expect(asked.length).toBeGreaterThan(1));
      const last = asked[asked.length - 1];
      expect(last).toContain('sort=number');
      expect(last, '«Сбросить сортировку» вернуло прежнее «по возрастанию»').toContain('desc=true');
      await waitFor(() => expect(written[written.length - 1],
        'сброс не запомнился — завтра вкладка снова откроется «1, 2, 3…»')
        .toEqual({ sort: 'number', desc: true }));
      expect(screen.queryByRole('button', { name: 'Сбросить сортировку' }),
        'кнопка осталась, хотя сбрасывать уже нечего').toBeNull();
    });

  it('у новой учётной записи кнопки сброса порядка нет, и в базу ничего не пишется',
    async () => {
      render(<WheelsScreen canIntake={false} rights={rightsAs('OWNER')} />);
      await waitFor(() => expect(asked.length).toBeGreaterThan(0));

      expect(asked[0]).toContain('desc=true');
      expect(screen.queryByRole('button', { name: 'Сбросить сортировку' })).toBeNull();
      expect(written, 'открытие вкладки само записало настройку').toHaveLength(0);
    });

  it('незнакомая сохранённая сортировка берётся умолчанием, а не уходит на сервер', async () => {
    // Колонку могли убрать новой версией: сохранённое пережило выкат.
    stored = { sort: 'nosuchcolumn', desc: false };
    render(<WheelsScreen canIntake={false} rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(asked.length).toBeGreaterThan(0));

    expect(asked[0]).toContain('sort=number');
    expect(asked[0]).toContain('desc=true');
  });
});

/** Колесо со всеми полями строки: вкладка читает их без разбора. */
function wheel(): Wheel {
  return {
    id: 7, number: 347, publicCode: 'W1A2B3C4D5E6',
    title: 'Шина 195/65 R15 Goodyear EfficientGrip летняя',
    price: 3500, status: 'IN_STOCK', qty: 4,
    kind: 'TYRE', setNo: 12, diameter: 15, tyreWidth: 195, tyreHeight: 65,
    construction: 'R', tyreType: 'Легковая', season: 'SUMMER', wearPercent: 40,
    madeYear: 2022, discType: null, discWidth: null, offsetMm: null,
    boltPattern: null, hubBore: null,
    brand: 'Goodyear', model: 'EfficientGrip', discBrand: null, discModel: null,
    markingType: 'METRIC', treadType: 'DIRECTIONAL', runFlat: false, lightTruck: false,
    speedIndex: 'H', loadIndex: 91,
    partName: 'Шина', condition: 'USED', supply: null, donorCode: null, oem: null,
    description: null, note: null, section: 'A-1', published: true,
    barcode: null, legacyCode: null, photoCount: 0,
    createdAt: null, updatedAt: null, updatedByName: null,
    priceChangedAt: null, priceChangedByName: null, stock: { '1': 4 },
  };
}

/** Стрелка меню в заголовке колонки с этим названием. */
function menuOf(title: string): HTMLElement {
  const head = Array.from(document.querySelectorAll('thead th'))
    .find((cell) => cell.querySelector('.th__title')?.textContent?.trim() === title);
  const menu = head?.querySelector('.th__menu') as HTMLElement | null | undefined;
  if (!menu) throw new Error(`у колонки «${title}» нет меню`);
  return menu;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
