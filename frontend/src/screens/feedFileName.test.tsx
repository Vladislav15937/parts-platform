import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Имя файла прайса задаёт владелец, и ссылка кончается им.
 *
 * <p>Адрес прописывает в кабинете площадки её техспециалист руками, и хвост
 * из сорока случайных символов токена он переносит с ошибками — а ошибку
 * видно только по тому, что объявления не появились. Сервер умеет задавать
 * имя (`PUT /api/marketplace-accounts/{id}/feed-file`), и без поля на экране
 * это была бы возможность, доступная только через разработчика.
 */
describe('имя файла прайса', () => {
  let saved: unknown = null;

  beforeEach(() => {
    saved = null;
    let fileName: string | null = null;

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/feed-file')) {
        saved = JSON.parse(String(init?.body));
        fileName = (saved as { fileName: string }).fileName;
        return json({ id: 1, feedFileName: fileName });
      }
      if (url.includes('/feed-url')) {
        // Ссылка кончается именем файла, как только оно задано: ради неё
        // имя и вводят, и показывать после сохранения прежний адрес значит
        // заставить владельца гадать, попало ли имя в ссылку.
        const path = fileName === null
          ? '/feeds/drom/co/sekret.xml'
          : `/feeds/drom/co/sekret/${fileName}`;
        return json({ path, url: 'https://sklad.example.ru' + path });
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([{ id: 1, title: 'Дром: основной', marketplace: 'DROM',
                       productLine: 'PART', hasFeed: true, feedFileName: null,
                       priceFrom: null, priceTo: null, conditions: [], warehouseIds: [],
                       kindIds: [], kindsExcluded: false, brandIds: [], brandsExcluded: false }]);
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [], modifications: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('сохраняется и попадает в ссылку', async () => {
    render(<FeedsScreen role="OWNER" />);
    const details = await waitFor(() => {
      const found = document.querySelector('details');
      expect(found).toBeTruthy();
      return found!;
    });
    fireEvent.click(details.querySelector('summary')!);

    const field = await waitFor(() => screen.getByPlaceholderText('drom-parts.xml'));
    fireEvent.change(field, { target: { value: 'drom-parts.xml' } });
    fireEvent.click(screen.getByText('Задать имя файла'));

    await waitFor(() => expect(saved, 'имя файла не ушло на сервер')
      .toEqual({ fileName: 'drom-parts.xml' }));

    // Ссылка перечитывается: показанный после сохранения прежний адрес
    // означал бы, что владелец не знает, что отдавать площадке.
    await waitFor(() => expect(
      screen.getByText('https://sklad.example.ru/feeds/drom/co/sekret/drom-parts.xml'),
    ).toBeTruthy());
  });

  it('пустое поле снимает имя, а не сохраняет пустую строку в ссылке', async () => {
    render(<FeedsScreen role="OWNER" />);
    const details = await waitFor(() => {
      const found = document.querySelector('details');
      expect(found).toBeTruthy();
      return found!;
    });
    fireEvent.click(details.querySelector('summary')!);

    const field = await waitFor(() => screen.getByPlaceholderText('drom-parts.xml'));
    fireEvent.change(field, { target: { value: '   ' } });
    fireEvent.click(screen.getByText('Задать имя файла'));

    // Пробелы — это «имени нет», а не имя из пробелов: сервер пишет NULL,
    // и две выгрузки без имени не сталкиваются в уникальном индексе.
    await waitFor(() => expect(saved).toEqual({ fileName: '' }));
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
