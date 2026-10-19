import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

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
