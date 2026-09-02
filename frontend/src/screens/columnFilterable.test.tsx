import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';

/**
 * Отбор предлагается только по тем колонкам, где он есть.
 *
 * <p><b>Зачем.</b> Меню открывалось у любой колонки — в том числе у превью,
 * состояния, комплектации и себестоимости, которых в серверном списке нет.
 * Сервер на такой запрос отвечает «по этой колонке отбор не делается», но
 * оба отказа проглатывались: список значений превращался в пустой (`catch`),
 * а выбранное значение ничего не меняло. Владелец нажимал «Состояние →
 * новое», видел те же тридцать пять тысяч строк и делал единственный
 * возможный вывод — что весь склад новый либо что отбор сломан.
 *
 * <p>Список отбираемых колонок приходит с сервера вместе со страницей:
 * повторённый на клиенте, он разошёлся бы с серверным на первой же новой
 * колонке — и разошёлся бы молча.
 */
describe('меню колонки', () => {
  let asked: string[];

  beforeEach(() => {
    asked = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/catalog/values')) {
        asked.push(url);
        return json(['Ford']);
      }
      if (url.includes('/api/parts/catalog')) {
        return json({
          total: 1,
          warehouses: [],
          // Сервер отбирает по марке и не отбирает по превью и состоянию.
          filterable: ['brand'],
          rows: [{ id: 1, publicCode: 'A-1', title: 'Фара', brand: 'Ford',
                   price: '100', condition: 'USED', photoUrl: null, qtyByWarehouse: {} }],
        });
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('не открывается там, где сервер отбор не делает', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelectorAll('tbody tr').length).toBe(1));

    const head = (title: string): HTMLElement =>
      [...document.querySelectorAll('thead th')]
        .find((th) => th.textContent?.includes(title)) as HTMLElement;

    // По марке отбор есть — стрелка на месте и значения спрашиваются.
    const brand = head('Марка');
    expect(brand.querySelector('.th__menu'), 'у отбираемой колонки нет меню').toBeTruthy();
    fireEvent.click(brand.querySelector('.th__menu')!);
    await waitFor(() => expect(asked.length).toBe(1));
    expect(document.querySelector('.value-picker')).toBeTruthy();

    // По превью отбора нет: ни стрелки, ни запроса значений.
    const photo = head('Превью');
    expect(photo.querySelector('.th__menu'),
      'меню предлагает отбор по колонке, которую сервер отобьёт').toBeNull();
    expect(asked.length, 'значения спрошены у колонки без отбора').toBe(1);
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
