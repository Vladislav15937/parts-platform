import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { UnmatchedScreen } from './UnmatchedScreen';

/**
 * Снять ошибочное сопоставление можно с того же экрана, где его ставят
 * (задача 0167).
 *
 * <p><b>Зачем.</b> `POST /api/part-names/{id}/unmatch` существовал с самого
 * начала и не вызывался ни одним экраном — он был последним незакрытым
 * `ПРОБЕЛ` в `tools/endpoint-coverage.py`. Экран разбора показывал только
 * нераспознанные, то есть сведённое с эталоном человеку не показывалось
 * вовсе: сопоставить он мог, отменить нет. А после переезда клиента таких
 * сопоставлений сотни, и ошибочное означает деталь, уехавшую в объявление
 * под чужим наименованием.
 *
 * <p>Проверяется то, что видит человек: написание, эталон словом, число
 * позиций — и что сказано про сами позиции. Снятие, после которого
 * непонятно, что изменилось, вернёт его к разработчику.
 */
describe('снятие сопоставления написания', () => {
  let matchedLeft: number;
  let unmatchedTotal: number;
  let dropped: number[];

  const ROW = {
    partName: {
      id: 49, name: 'знак аварийной остановки', matchStatus: 'MANUAL',
      partKindId: 120, categoryId: 4, usageCount: 9,
      sampleTitle: 'Набор инструментов Honda Fit (б/у)',
      createdAt: '2026-09-01T10:00:00Z',
    },
    kindName: 'Набор инструментов',
  };

  beforeEach(() => {
    matchedLeft = 1;
    unmatchedTotal = 675;
    dropped = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      // Порядок важен: «/unmatched» содержит «/unmatch» как подстроку,
      // поэтому список разбирается раньше самого снятия.
      if (url.includes('/api/part-names/unmatched')) {
        return json({ total: unmatchedTotal, items: [] });
      }
      if (url.endsWith('/unmatch')) {
        dropped.push(Number(url.split('/').at(-2)));
        matchedLeft -= 1;
        unmatchedTotal += 1;
        return json({ ...ROW.partName, matchStatus: 'UNMATCHED', partKindId: null });
      }
      if (url.includes('/api/part-names/matched')) {
        return json(matchedLeft > 0 ? { total: 1, items: [ROW] } : { total: 0, items: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('строка называет написание, эталон и число затронутых позиций', async () => {
    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);

    await waitFor(() => expect(screen.getByText('Сопоставленные написания')).toBeTruthy());
    expect(screen.getByText('знак аварийной остановки')).toBeTruthy();
    // Эталон словом, а не номером, и цена действия числом.
    expect(screen.getByText(/эталон: Набор инструментов/)).toBeTruthy();
    expect(screen.getByText(/позиций под этим написанием: 9/)).toBeTruthy();
    expect(screen.getByText(/Набор инструментов Honda Fit/)).toBeTruthy();
  });

  it('снимается вторым нажатием, и сказано, что стало с позициями', async () => {
    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);
    await waitFor(() => expect(screen.getByText('Сопоставленные написания')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Снять сопоставление' }));

    // Первое нажатие спрашивает и ничего не отправляет: промах мышью уводит
    // написание в стену нераспознанных, где его потом искать среди шестисот.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Точно снять\? 9 позиций не тронем/ })).toBeTruthy());
    expect(dropped, 'первое нажатие уже сняло сопоставление').toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: /Точно снять\?/ }));

    await waitFor(() => expect(dropped, 'запрос на снятие не ушёл').toEqual([49]));
    // Пункт 6 критерия приёмки: что стало с позициями — сказано словами.
    await waitFor(() =>
      expect(screen.getByText(/написание вернулось в нераспознанные/)).toBeTruthy());
    expect(screen.getByText(/Карточки не тронуты: 9 позиций/)).toBeTruthy();
  });

  it('снятое из списка уходит', async () => {
    render(<UnmatchedScreen canManage onTotalChanged={() => {}} />);
    await waitFor(() => expect(screen.getByText('знак аварийной остановки')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Снять сопоставление' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Точно снять\?/ })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /Точно снять\?/ }));

    // Иначе снять его предлагали бы второй раз — и второе нажатие ушло бы
    // на сервер, который уже ответил «не сопоставлено».
    await waitFor(() =>
      expect(screen.getByText(/Сведённых с эталоном написаний пока нет/)).toBeTruthy());
  });

  it('роли, которая не сводит написания, блока не показывают', async () => {
    render(<UnmatchedScreen canManage={false} onTotalChanged={() => {}} />);

    await waitFor(() => expect(screen.getByText('Наименования')).toBeTruthy());
    // Кнопка, дающая 403, хуже отсутствующей: роль та же, что
    // у сопоставления, и сервер отвечает ей отказом.
    expect(screen.queryByText('Сопоставленные написания')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Снять сопоставление' })).toBeNull();
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
