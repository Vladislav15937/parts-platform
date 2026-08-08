import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { UnmatchedScreen } from './UnmatchedScreen';

/**
 * Пересопоставить написания по нынешнему справочнику можно с экрана.
 *
 * <p><b>Зачем.</b> Справочник видов деталей растёт с релизом, а написания
 * клиента заведены раньше: пополнение само по себе не меняет ничего, и
 * владелец продолжает видеть ту же стену нераспознанных. `POST
 * /api/part-names/rematch` для этого и заведён, но фронтенд не звал его
 * ни строкой — дотянуться можно было только повторным импортом, то есть
 * перезалив выгрузку целиком ради пересчёта.
 *
 * <p>Отвечает числом: «сопоставил» и «ничего не изменилось» — разные
 * новости, и по экрану их иначе не различить.
 */
describe('пересопоставление наименований', () => {
  let matched: number;
  let calls: number;

  beforeEach(() => {
    matched = 12;
    calls = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/rematch')) {
        calls += 1;
        return json({ matched, updated: matched === 0 ? 0 : 379 });
      }
      if (url.includes('/api/part-names/unmatched')) {
        return json({
          total: 675,
          items: [{ id: 49, name: 'тросик ручного тормоза', usageCount: 379,
                    sampleTitle: null }],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('отвечает числом сопоставленных и исправленных карточек', async () => {
    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Всего 675/)).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Пересопоставить по справочнику' }));

    await waitFor(() => expect(calls, 'запрос не ушёл').toBe(1));
    await waitFor(() =>
      expect(screen.getByText(/Сопоставлено написаний: 12, исправлено карточек: 379/)).toBeTruthy());
  });

  it('ноль объясняется словами, а не выглядит как «не сработало»', async () => {
    matched = 0;
    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Всего 675/)).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Пересопоставить по справочнику' }));

    await waitFor(() =>
      expect(screen.getByText(/Ни одно написание не совпало с эталоном/)).toBeTruthy());
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
