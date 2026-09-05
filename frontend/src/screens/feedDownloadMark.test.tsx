import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Владелец видит, когда площадку последний раз кормили прайсом.
 *
 * <p>Это первый вопрос при подключении клиента и первый, когда объявления
 * пропали: «прайс вообще уехал?». Отвечал на него разработчик по логам
 * приложения — то есть клиент ждал человека, чтобы узнать факт, который
 * система знает.
 *
 * <p>Проверяются обе стороны сразу. Выгрузка, которую забирали, называет
 * день и время; выгрузка, которую не забирали ни разу, говорит об этом
 * словами: пустая клетка читается как «экран не знает», а «01.01.1970» —
 * как поломка, и обе догадки уводят от настоящего ответа.
 */
describe('отметка забора прайса', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [], modifications: [] });
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([
          { ...feed, id: 1, title: 'Дром: основной',
            // Время местное: забор идёт ночью, и час здесь смысловой.
            lastDownloadAt: new Date(2026, 8, 4, 22, 30).toISOString() },
          { ...feed, id: 2, title: 'Дром: колёса', productLine: 'WHEEL',
            lastDownloadAt: null },
        ]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('называет день и время последнего забора', async () => {
    render(<FeedsScreen role="OWNER" />);

    await waitFor(() => expect(
      screen.getByText('Скачан 04 сен, 22:30'),
      'по строке выгрузки не понять, забирали ли прайс',
    ).toBeTruthy());
  });

  it('у незабранной выгрузки говорит об этом словами', async () => {
    render(<FeedsScreen role="OWNER" />);

    // Сначала дожидаемся загрузки: отрицание на пустом экране проходит
    // само собой, и тест зеленел бы на вырезанной отметке.
    await waitFor(() => expect(screen.getByText('Дром: колёса')).toBeTruthy());

    expect(screen.getByText('Прайс не забирали')).toBeTruthy();
    expect(document.body.textContent, 'показана эпоха Unix вместо ответа')
      .not.toContain('1970');
  });
});

const feed = {
  marketplace: 'DROM',
  status: 'ACTIVE',
  hasCredentials: false,
  plaintextSecret: false,
  hasFeed: true,
  productLine: 'PART',
  lastError: null,
  priceFrom: null,
  priceTo: null,
  conditions: [],
  warehouseIds: [],
  kindIds: [],
  kindsExcluded: false,
  brandIds: [],
  brandsExcluded: false,
  filterColumns: {},
  filterWords: {},
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
