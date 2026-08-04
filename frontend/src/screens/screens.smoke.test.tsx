import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';
import { WheelsScreen } from './WheelsScreen';
import { ReportsScreen } from './ReportsScreen';
import { FeedsScreen } from './FeedsScreen';
import { MembersScreen } from './MembersScreen';
import { OrganizationScreen } from './OrganizationScreen';
import { UnmatchedScreen } from './UnmatchedScreen';
import { OrdersScreen } from './OrdersScreen';
import { DeliveryScreen } from './DeliveryScreen';
import { LabelsScreen } from './LabelsScreen';

/**
 * Дымовой проход по экранам: отрисовались ли и не упали ли на первом запросе.
 *
 * Тестов на рендер в проекте не было вовсе, и за одну сессию глазами нашлись
 * три дефекта, которых не видит ни один из шестисот тестов бэкенда: накладка
 * снимков, уезжающая таблица под карточкой и битые превью. Полноценный e2e
 * тут не нужен — нужен сторож на то, что экран вообще открывается: белая
 * страница вместо таблицы это худшее, чем может кончиться выкат.
 *
 * Ответы сервера подставляются пустыми: проверяется не содержимое, а то,
 * что экран переживает пустой ответ. Ровно на этом ломались экраны,
 * написанные под непустые данные.
 */
const EMPTY: Record<string, unknown> = {
  total: 0,
  rows: [],
  warehouses: [],
  items: [],
  content: [],
};

describe('экраны открываются', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      // Списки отдаём массивом, страницы — объектом: экран разбирает
      // ответ по-разному, и подсунуть одно вместо другого значит проверить
      // не тот путь.
      // Форма ответа взята из контроллеров: списки — массивом, страницы
      // и отчёты — объектом. Подсунуть одно вместо другого значит проверить
      // не тот путь и получить падение, которого в жизни не будет.
      const listLike = ['/api/marketplace-accounts', '/api/members',
        '/api/organization', '/api/deals/orders', '/api/catalog/vehicles',
        '/api/intake/donors', '/api/deals/sources', '/api/deals/services',
        '/api/part-names/kinds'];
      const body: unknown = listLike.some((path) => url.includes(path))
        ? []
        : url.includes('/api/reports/')
          ? { rows: [], totals: {}, month: '2026-08' }
          : EMPTY;
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  const screens: Array<[string, () => JSX.Element]> = [
    ['Склад', () => <CatalogScreen role="OWNER" />],
    ['Шины и диски', () => <WheelsScreen role="OWNER" />],
    ['Отчёты', () => <ReportsScreen />],
    ['Выгрузки', () => <FeedsScreen role="OWNER" />],
    ['Сотрудники', () => <MembersScreen />],
    ['Склады', () => <OrganizationScreen />],
    ['Наименования', () => <UnmatchedScreen onCount={() => {}} />],
    ['Заказы', () => <OrdersScreen />],
    ['Доставка', () => <DeliveryScreen />],
    ['Этикетки', () => <LabelsScreen />],
  ];

  it.each(screens)('%s отрисовывается и не падает на пустом ответе', async (name, make) => {
    const errors: unknown[] = [];
    const consoleError = vi.spyOn(console, 'error').mockImplementation((...args) => {
      errors.push(args);
    });

    render(make());
    await waitFor(() => expect(document.body.textContent).not.toBe(''));

    // Пустая страница — это и есть та поломка, ради которой тест написан.
    expect(document.body.textContent?.trim().length ?? 0).toBeGreaterThan(0);
    expect(errors, `${name}: ошибки при отрисовке`).toHaveLength(0);
    consoleError.mockRestore();
  });
});
