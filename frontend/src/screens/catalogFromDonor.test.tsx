import { rightsAs } from '../test/rights';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';

/**
 * Склад, открытый плиткой «N запчастей — Посмотреть» с карточки машины
 * (задача 0364): первый же запрос — по номеру машины, с отсутствующими,
 * поверх запомненного отбора, а не после него.
 */
describe('склад с карточки машины', () => {
  let stored: Record<string, unknown> | null;
  let written: Array<Record<string, unknown>>;
  let asked: string[];

  beforeEach(() => {
    // Вчерашний отбор по марке: он и спрятал бы машину целиком.
    stored = { columns: { brand: 'Ford' }, missing: false, visible: ['title'] };
    written = [];
    asked = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/me/settings/catalog')) {
        if (init?.method === 'PUT') {
          written.push(JSON.parse(String(init.body)) as Record<string, unknown>);
          return json({});
        }
        return json({ screen: 'catalog', value: stored });
      }
      if (url.includes('/api/parts/catalog?')) {
        asked.push(decodeURIComponent(url));
        return json({ total: 0, warehouses: [], filterable: ['donor', 'brand'], rows: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('первый запрос — по номеру машины, с отсутствующими, без прежних отборов', async () => {
    let used = 0;
    render(<CatalogScreen rights={rightsAs('OWNER')} donorFilter="350"
                          onDonorFilterUsed={() => { used += 1; }} />);

    await waitFor(() => expect(asked.length).toBeGreaterThan(0));
    expect(asked).toHaveLength(1);
    expect(asked[0]).toContain('filter=donor:350');
    expect(asked[0]).toContain('missing=true');
    expect(asked[0], 'прежний отбор по марке остался').not.toContain('brand');
    // Номер отдан и забыт оболочкой: следующее открытие склада — обычное.
    expect(used).toBe(1);
    await waitFor(() => expect(written.length).toBe(1));
  });

  it('без номера машины открывается запомненным', async () => {
    render(<CatalogScreen rights={rightsAs('OWNER')} />);
    await waitFor(() => expect(asked.length).toBeGreaterThan(0));
    expect(asked[0]).not.toContain('donor:');
    // Фикстура и правда ставит отбор — иначе проверка выше шла бы вхолостую.
    expect(asked[0]).toContain('filter=brand:Ford');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
