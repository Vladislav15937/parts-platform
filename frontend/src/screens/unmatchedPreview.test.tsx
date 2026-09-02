import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { UnmatchedScreen } from './UnmatchedScreen';

/**
 * «Станет» показывает тот эталон, на который навели.
 *
 * <p>Заголовок после сопоставления — единственное, чем верное решение
 * отличается от ложного: обе кнопки выглядят одинаково, а нажатие правит
 * сотни карточек и назад не откатывается. Ровно так «Знак аварийной
 * остановки» однажды стал «Набором инструментов».
 *
 * <p>Пока строка показывала только первый эталон, у остальных сравнить было
 * нечего — а среди них стоят соседи вроде «Ключ зажигания» и «Замок
 * зажигания». Нативной подсказки в title для этого мало: она всплывает
 * через секунду, и разбирающий шестьсот написаний подряд её не дожидается.
 */
describe('предпросмотр заголовка при разборе', () => {
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

  it('следует за наведением, а не показывает вечно первый', async () => {
    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);

    fireEvent.click(await screen.findByText('сопоставить'));
    const second = await screen.findByRole('button', { name: 'Замок зажигания' });

    // До наведения — первый эталон: показать что-то надо и без мыши.
    expect(screen.getByText(/Станет:/).textContent).toContain('Трос замка');

    fireEvent.mouseEnter(second);
    await waitFor(() => expect(screen.getByText(/Станет:/).textContent)
      .toContain('Замок зажигания'));

    fireEvent.mouseLeave(second);
    await waitFor(() => expect(screen.getByText(/Станет:/).textContent)
      .toContain('Трос замка'));
  });

  /**
   * Сопоставление применяется вторым нажатием.
   *
   * <p>Одно нажатие правит все позиции под написанием — у живого клиента
   * до трёхсот карточек, — и назад это не откатывается ничем, кроме
   * восстановления из бэкапа. Фишки эталонов стоят в ряд и похожи друг
   * на друга, а разбирают их сотнями подряд: промах мышью стоит трёхсот
   * карточек, утверждающих, что они другая деталь. Ровно так «Знак
   * аварийной остановки» однажды стал «Набором инструментов».
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
