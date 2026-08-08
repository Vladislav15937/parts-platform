import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';

/**
 * Поставку можно завести с экрана.
 *
 * <p><b>Зачем.</b> `POST /api/intake/supplies` написан с самого начала,
 * и звать его было некому: список поставок приезжал справочником, а новую
 * завести было нельзя ниоткуда. У переехавшего клиента их восемнадцать —
 * все из переноса, — и следующий пришедший контейнер записать было бы
 * не на что: приёмщик выбрал бы «не указана», и связь детали с партией
 * потерялась бы навсегда.
 *
 * <p>Рядом с выбором поставки, а не отдельным разделом: контейнер и машины
 * приходят вместе, и заводит их один человек за один заход.
 */
describe('заведение поставки', () => {
  let sent: unknown;
  let refreshed: number;

  beforeEach(() => {
    sent = null;
    refreshed = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/intake/supplies')) {
        sent = JSON.parse(String(init?.body ?? 'null'));
        return json({ id: 19, number: '18', kind: 'CONTAINER', status: 'EXPECTED' });
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [] });
      }
      if (url.includes('/api/intake/donors')) {
        return json([]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('уходит на сервер и обновляет справочник', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => { refreshed += 1; }} />);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Завести поставку' })).toBeTruthy());

    const number = screen.getByPlaceholderText('18');
    fireEvent.change(number, { target: { value: ' 18 ' } });
    fireEvent.change(screen.getByPlaceholderText('необязательно'),
      { target: { value: 'Иокогама' } });
    fireEvent.click(screen.getByRole('button', { name: 'Завести поставку' }));

    await waitFor(() => expect(sent, 'поставка не ушла на сервер').not.toBeNull());
    // Пробелы срезаны, вид по умолчанию — контейнер: основной поток
    // у клиентов с японским товаром.
    expect(sent).toEqual({ number: '18', kind: 'CONTAINER', supplierName: 'Иокогама' });

    // Справочник обязан перечитаться: иначе заведённая поставка не появится
    // в списке ни здесь, ни у приёмщика на телефоне.
    await waitFor(() => expect(refreshed, 'справочник не перечитан').toBe(1));
    await waitFor(() => expect(screen.getByText(/Поставка «18» заведена/)).toBeTruthy());
  });

  it('без номера не отправляется', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const button = await waitFor(() =>
      screen.getByRole('button', { name: 'Завести поставку' }));

    expect((button as HTMLButtonElement).disabled,
      'пустой номер уехал бы на сервер').toBe(true);
  });
});

function reference(): never {
  return { warehouses: [], supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
