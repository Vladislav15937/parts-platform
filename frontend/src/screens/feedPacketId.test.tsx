import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Номер прайс-листа вписывается и правится у заведённой выгрузки (задача 0267).
 *
 * <p>Номер появляется у владельца после размещения прайса площадкой, то есть
 * когда выгрузка давно заведена, а принимал его только `POST` при заведении.
 * Без него дельты молча не уходят.
 */
describe('номер прайс-листа у заведённой выгрузки', () => {
  let sent: Array<{ url: string; method: string; body: unknown }>;
  let packetId: string | null;

  beforeEach(() => {
    sent = [];
    packetId = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/packet-id')) {
        sent.push({ url, method: init?.method ?? 'GET', body: JSON.parse(String(init?.body ?? 'null')) });
        return json(feed());
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([feed()]);
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

  function feed() {
    return { id: 7, title: 'Дром: основной', marketplace: 'DROM', productLine: 'PART',
             hasFeed: true, hasCredentials: true, packetId, priceFrom: null, priceTo: null,
             conditions: [], warehouseIds: [], kindIds: [], kindsExcluded: false,
             brandIds: [], brandsExcluded: false };
  }

  it('сохраняется, из адреса берётся номер, а кнопка гаснет с причиной', async () => {
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText(/Номер прайс-листа · не задан/)).toBeTruthy());

    const button = screen.getByRole('button', { name: 'Сохранить номер' }) as HTMLButtonElement;
    const field = screen.getByPlaceholderText('12345') as HTMLInputElement;
    // Пустое поле при пустом номере: менять нечего, и причина названа.
    expect(button.disabled).toBe(true);
    expect(screen.getByText('Номер не изменился')).toBeTruthy();
    // Строка про источник номера стоит и под полем карточки, и в форме заведения.
    expect(screen.getAllByText(/появляется после того, как специалист Дрома разместит прайс/).length)
      .toBeGreaterThanOrEqual(2);

    fireEvent.change(field, { target: { value: '12 345' } });
    expect(button.disabled).toBe(true);
    expect(screen.getByText(/Номер не распознан/)).toBeTruthy();

    fireEvent.change(field, {
      target: { value: ' https://baza.drom.ru/personal/goods/packet/12345/recurrent-update ' } });
    expect(button.disabled).toBe(false);
    fireEvent.click(button);

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.method).toBe('PUT');
    expect(sent[0]!.url).toContain('/api/marketplace-accounts/7/packet-id');
    expect(sent[0]!.body).toEqual({ packetId: '12345' });
  });

  it('показывает текущий номер, а пустое поле снимает его', async () => {
    packetId = '12345';
    render(<FeedsScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText(/Номер прайс-листа · задан/)).toBeTruthy());

    const field = screen.getByPlaceholderText('12345') as HTMLInputElement;
    expect(field.value).toBe('12345');

    fireEvent.change(field, { target: { value: '' } });
    expect(screen.getByText(/Пустое поле снимет номер/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить номер' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.body).toEqual({ packetId: '' });
  });

  it('управляющему блок номера не показывается', async () => {
    render(<FeedsScreen role="MANAGER" />);
    await waitFor(() => expect(screen.getByText(/Ключ синхронизации/)).toBeTruthy());
    expect(screen.queryByText(/Номер прайс-листа ·/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Сохранить номер' })).toBeNull();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
