import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { UnmatchedScreen } from './UnmatchedScreen';

/**
 * Разбор нераспознанных написаний.
 *
 * <p>Название позиции само не меняется (задача 0304, решение владельца
 * 15.10.2026), поэтому «Станет: …» с будущим заголовком экран не показывает:
 * обещать подмену, которой нет, значит врать. Раньше здесь был предпросмотр
 * (`unmatchedPreview.test.tsx`), он снят вместе с подменой.
 */
describe('разбор нераспознанных написаний', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/suggestions')) {
        return json([
          { id: 1, name: 'Трос замка', categoryId: 1 },
          { id: 2, name: 'Замок зажигания', categoryId: 1 },
        ]);
      }
      if (url.includes('/unmatched')) {
        return json({
          total: 1,
          items: [{
            id: 7, name: 'трос замка зажигания', matchStatus: 'UNMATCHED',
            partKindId: null, categoryId: null, usageCount: 40,
            createdAt: '2026-08-05T00:00:00Z',
            sampleTitle: 'трос замка зажигания Mitsubishi Outlander 2006 (б/у)',
          }],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('не показывает будущий заголовок: сопоставление его не меняет', async () => {
    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);

    fireEvent.click(await screen.findByText('сопоставить'));
    await screen.findByRole('button', { name: 'Замок зажигания' });

    expect(screen.queryByText(/Станет:/)).toBeNull();
  });

  /**
   * Сопоставление применяется вторым нажатием.
   *
   * <p>Одно нажатие правит вид детали и категорию у всех позиций под
   * написанием — у живого клиента до трёхсот карточек, — и назад это
   * не откатывается ничем, кроме восстановления из бэкапа. Фишки эталонов
   * стоят в ряд и похожи друг на друга, а разбирают их сотнями подряд:
   * промах мышью стоит трёхсот карточек с чужим видом детали.
   */
  it('применяется вторым нажатием, а не первым', async () => {
    const sent: string[] = [];
    const before = globalThis.fetch as typeof fetch;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        sent.push(String(input));
        return json({ changed: 40 });
      }
      return before(input, init);
    }));

    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);
    fireEvent.click(await screen.findByText('сопоставить'));
    const chip = await screen.findByRole('button', { name: 'Трос замка' });

    fireEvent.click(chip);

    // Первое нажатие только спрашивает — и называет цену действия.
    await waitFor(() => expect(screen.getByRole('button', { name: /Точно\?/ })
      .textContent).toContain('40'));
    expect(sent, 'сопоставление ушло с первого нажатия').toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: /Точно\?/ }));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toContain('/match');
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
