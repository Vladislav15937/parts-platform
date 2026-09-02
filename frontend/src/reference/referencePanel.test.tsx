import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { refreshReference } from './reference';
import { ReferencePanel } from './ReferencePanel';

/**
 * Справочник приёмки не называет свои машины «машинами в разборе».
 *
 * <p><b>Зачем.</b> В него идут и разобранные: вернуться за забытой мелочью
 * через неделю после закрытия разбора — обычное дело, и это закреплено
 * {@code IntakeReferenceServiceTest}. У переехавшего клиента разобраны
 * 440 машин из 441, то есть подпись «Машины в разборе: 441» была прямой
 * неправдой — и та же формулировка уже правилась на экране машин.
 *
 * <p>Цена ошибки не в слове: владелец, прочитав её, решает, что принимать
 * на разобранные нельзя, и заводит деталь без машины — теряя связь,
 * на которой держатся и подбор по машине, и отчёт окупаемости.
 */
describe('панель справочников', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/intake/reference')) {
        return json({
          loadedAt: '2026-08-09T02:00:00Z',
          warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
          cells: [],
          supplies: [],
          // Одна в разборе, две разобраны — как у переехавшего клиента,
          // только в меньшем масштабе.
          donors: [
            { id: 1, code: '1', brand: 'Toyota', model: 'Camry', year: 2007,
              vin: null, status: 'DISMANTLING', note: null },
            { id: 2, code: '2', brand: 'Honda', model: 'Fit', year: 2004,
              vin: null, status: 'DISMANTLED', note: null },
            { id: 3, code: '3', brand: 'Nissan', model: 'Note', year: 2010,
              vin: null, status: 'DISMANTLED', note: null },
          ],
          partNames: [],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('называет машины по тому, зачем они там, а не по состоянию', async () => {
    // Панель читает локальный справочник, а не сеть: кладём его тем же
    // способом, каким это делает приложение.
    await refreshReference();
    render(<ReferencePanel />);

    await waitFor(() => expect(screen.getByText(/Машины для приёмки/)).toBeTruthy());
    expect(screen.queryByText(/Машины в разборе/),
      'разобранные машины названы «в разборе»').toBeNull();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
