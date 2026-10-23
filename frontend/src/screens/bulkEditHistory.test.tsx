import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { BulkEditForm } from './BulkEditForm';
import { PartHistoryView } from './PartHistoryView';
import type { HistoryChange, PartHistory } from '../inventory/catalog';

/**
 * История позиции называет объём массовой правки (задача 0373).
 *
 * <p>Под полями правки, сделанной «Правкой списком», стоит «всего были
 * отредактированы 40 товаров по фильтру» (слова Bazon); у правки одной
 * карточки подписи нет. До правки массовая выглядела правкой руками.
 */
describe('массовая правка в истории позиции', () => {
  afterEach(cleanup);

  it('правка по фильтру называет число и «по фильтру»', () => {
    show(change({ bulk: { size: 40, scope: 'FILTER' } }));
    expect(screen.getByText('всего были отредактированы 40 товаров по фильтру')).toBeTruthy();
  });

  it('правка отмеченных — «по отмеченным», число склоняется', () => {
    show(change({ bulk: { size: 3, scope: 'SELECTED' } }));
    expect(screen.getByText('всего были отредактированы 3 товара по отмеченным')).toBeTruthy();
  });

  it('большое число с разделителем разрядов', () => {
    show(change({ bulk: { size: 35841, scope: 'FILTER' } }));
    expect(screen.getByText(/35\s841 товар по фильтру$/)).toBeTruthy();
  });

  it('правка одной карточки — без подписи', () => {
    show(change({ bulk: null }), change({}));
    expect(screen.getAllByText('Наличные')).toHaveLength(2);
    expect(screen.queryByText(/всего были отредактированы/)).toBeNull();
  });
});

/**
 * «Правка списком» называет себя источником `LIST` и при одной отмеченной
 * строке: «клиент должен явно понимать что правит не одну позицию а
 * выбранные» (решение владельца 23.10.2026, задача 0373). Без подписи
 * остаётся только правка из карточки (`CARD`, `partQuality.test.tsx`).
 */
describe('источник правки списком', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('одна отмеченная строка уходит правкой списком, а не карточкой', async () => {
    const sent: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(JSON.parse(String(init?.body)));
      }
      return new Response(JSON.stringify(
        { changed: 1, skipped: 0, rejected: 0, rejectedCodes: [], rejectedReason: null }),
      { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
    render(<BulkEditForm partIds={[5]} count={1} onSaved={() => {}} onCancel={() => {}} />);

    const field = [...document.querySelectorAll('.bulk-field')]
      .find((f) => f.textContent?.includes('Заметка'))!;
    fireEvent.click(field.querySelector('input[type=checkbox]')!);
    fireEvent.change(field.querySelector('input:not([type=checkbox]), textarea')!,
      { target: { value: 'Наличные' } });
    fireEvent.click(screen.getByText('Изменить 1 позицию'));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]).toMatchObject({ partIds: [5], source: 'LIST' });
  });
});

function show(...changes: HistoryChange[]) {
  const history: PartHistory = { changes, movements: [] };
  render(<PartHistoryView history={history} tab="changes" onTab={() => {}} />);
}

function change(extra: Partial<HistoryChange>): HistoryChange {
  return {
    at: '2026-10-19T07:26:00Z',
    author: 'Сотрудник Б',
    action: null,
    fields: [{ label: 'Текст.блок для объявлений', before: null, after: 'Наличные', delta: null }],
    ...extra,
  };
}
