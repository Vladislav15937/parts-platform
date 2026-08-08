import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Ключ синхронизации вводится с экрана.
 *
 * <p><b>Зачем.</b> `PUT /api/marketplace-accounts/{id}/credentials` был написан
 * и закрыт ролью владельца, а звать его было некому: фронтенд не обращался
 * к нему ни строкой. Экран при этом сам писал «ключ к ним Дром выдаёт
 * по заявке» — то есть называл действие и не давал его сделать.
 *
 * <p>Цена ошибки не в лишнем шаге: без ключа дельты по API не уходят вовсе.
 * Принятая, подорожавшая или проданная деталь ждёт полного забора прайса —
 * до трёх суток на бесплатном размещении. Заметить это нельзя ничем:
 * очередь отметок разгребается, `publication_log` пуст, и всё выглядит
 * работающим.
 *
 * <p>Поле всегда пустое: прочитать ключ нельзя ни одним эндпоинтом, и это
 * часть защиты, а не недоделка.
 */
describe('ключ синхронизации кабинета', () => {
  let sent: { url: string; method: string; body: unknown } | null;

  beforeEach(() => {
    sent = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/credentials')) {
        sent = {
          url,
          method: init?.method ?? 'GET',
          body: JSON.parse(String(init?.body ?? 'null')),
        };
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([{ id: 7, title: 'Дром: основной', marketplace: 'DROM',
                       productLine: 'PART', hasFeed: true, hasCredentials: false,
                       packetId: null, priceFrom: null, priceTo: null, conditions: [],
                       warehouseIds: [], kindIds: [], kindsExcluded: false,
                       brandIds: [], brandsExcluded: false }]);
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

  it('сохраняется и говорит, чем грозит его отсутствие', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText(/Ключ синхронизации/)).toBeTruthy());

    // Пока ключа нет, экран объясняет последствие, а не молчит.
    expect(screen.getByText(/дельты по API не уходят/)).toBeTruthy();

    const field = document.querySelector('input[type="password"]') as HTMLInputElement;
    expect(field, 'ключ ввести негде').toBeTruthy();

    fireEvent.change(field, { target: { value: ' 0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0 ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить ключ' }));

    await waitFor(() => expect(sent, 'ключ не ушёл на сервер').not.toBeNull());
    expect(sent!.method).toBe('PUT');
    expect(sent!.url).toContain('/api/marketplace-accounts/7/credentials');
    // Пробелы по краям срезаны: ключ копируют из письма поддержки.
    expect(sent!.body).toEqual({ secret: '0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0' });
    // И поле очищено: оставленный в нём ключ выглядел бы как «мы вам его показываем».
    await waitFor(() => expect(field.value).toBe(''));
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
