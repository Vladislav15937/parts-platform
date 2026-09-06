import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryReconcile } from './InventoryReconcile';

/**
 * Журнал пересчётов (задача 0020): список всех документов над сведением
 * расхождений, а не поиск открытой сессии по складу.
 *
 * <p>Проверяются слова и умолчания, которые задача называет дословно:
 * воронка «В работе / Выполненные / Отменённые / Все пересчёты» в этом
 * порядке и открытая по умолчанию «В работе», колонки «Номер/дата · Выборка ·
 * Статус · Посчитано · Комментарий», подвал «Пересчётов: N» и «Пересчётов
 * пока не было», и кнопки сведения расхождений, скрытые у закрытого документа
 * и у роли «Просмотр».
 */
describe('журнал пересчётов', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('воронка в заданном порядке, открыта «В работе» по умолчанию', async () => {
    const requested: string[] = [];
    stubApi({
      onList: (query) => {
        requested.push(query);
        return [];
      },
    });

    render(<InventoryReconcile reference={reference()} />);

    await waitFor(() => expect(screen.getByText('Пересчётов пока не было')).toBeTruthy());

    const buttons = screen.getAllByRole('button', {
      name: /В работе|Выполненные|Отменённые|Все пересчёты/,
    });
    expect(buttons.map((b) => b.textContent)).toEqual([
      'В работе', 'Выполненные', 'Отменённые', 'Все пересчёты',
    ]);
    expect(requested).toEqual(['status=OPEN']);
  });

  it('колонки в заданном порядке и подвал считает по воронке', async () => {
    stubApi({
      onList: () => [
        row({ id: 4, status: 'OPEN', selection: 'Ткацкая · весь склад',
              lines: 30, counted: 12 }),
      ],
    });

    render(<InventoryReconcile reference={reference()} />);

    const headers = await screen.findAllByRole('columnheader');
    expect(headers.map((h) => h.textContent)).toEqual([
      'Номер/дата', 'Выборка', 'Статус', 'Посчитано', 'Комментарий',
    ]);

    expect(screen.getByText('Ткацкая · весь склад')).toBeTruthy();
    expect(screen.getByText('Идёт подсчёт')).toBeTruthy();
    expect(screen.getByText('12 из 30')).toBeTruthy();
    expect(screen.getByText('Пересчётов: 1')).toBeTruthy();
  });

  it('«Все пересчёты» шлёт запрос без фильтра', async () => {
    const requested: string[] = [];
    stubApi({ onList: (query) => { requested.push(query); return []; } });

    render(<InventoryReconcile reference={reference()} />);
    await waitFor(() => expect(screen.getByText('Пересчётов пока не было')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Все пересчёты' }));
    await waitFor(() => expect(requested).toHaveLength(2));
    expect(requested[1]).toBe('');
  });

  it('нажатие на строку открывает сведения без кнопок у отменённого пересчёта', async () => {
    stubApi({
      onList: () => [row({ id: 9, status: 'CANCELLED' })],
      onSummary: (id) => row({ id, status: 'CANCELLED', lines: 5, counted: 5 }),
      onDiscrepancies: () => [],
    });

    render(<InventoryReconcile reference={reference()} />);

    fireEvent.click(await screen.findByText('Отменён'));

    await waitFor(() => expect(screen.getByText(/Сессия 9/)).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Завершить подсчёт' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Провести' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Отменить пересчёт' })).toBeNull();
  });

  /**
   * Пункт приёмки 6: комментарий, написанный тем, кто ходил по складу,
   * виден владельцу в колонке списка. Ради него в журнал и заходят.
   */
  it('комментарий виден в колонке списка и сохраняется у живого пересчёта', async () => {
    const posted: string[] = [];
    stubApi({
      onList: () => [row({ id: 7, status: 'OPEN', note: '83619 не найден' })],
      onSummary: (id) => row({ id, status: 'OPEN', note: '83619 не найден' }),
      onNote: (body) => {
        posted.push(body);
        return row({ id: 7, status: 'OPEN', note: 'Катушки не считали' });
      },
    });

    render(<InventoryReconcile reference={reference()} />);

    // В колонке списка — то, что написал человек.
    expect(await screen.findByText('83619 не найден')).toBeTruthy();

    fireEvent.click(screen.getByText('Идёт подсчёт'));
    await waitFor(() => expect(screen.getByText(/Сессия 7/)).toBeTruthy());

    // У живого пересчёта это поле ввода, а не текст.
    const field = screen.getByLabelText('Комментарий') as HTMLTextAreaElement;
    expect(field.value).toBe('83619 не найден');

    fireEvent.change(field, { target: { value: 'Катушки не считали' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить комментарий' }));

    await waitFor(() => expect(posted).toEqual(['{"note":"Катушки не считали"}']));
    await waitFor(() => expect(screen.getByText('Комментарий сохранён')).toBeTruthy());
  });

  /**
   * Пункт приёмки 7: у проведённого пересчёта комментарий показан текстом,
   * а не полем ввода. Приписка задним числом объясняла бы уже случившееся
   * не тем, что видел писавший, — и сервер её отобьёт: кнопка, которую
   * отобьют, хуже отсутствующей.
   */
  it('у проведённого пересчёта комментарий показан текстом, а не полем', async () => {
    stubApi({
      onList: () => [row({ id: 8, status: 'APPLIED', note: 'Все на месте' })],
      onSummary: (id) => row({ id, status: 'APPLIED', note: 'Все на месте' }),
      onDiscrepancies: () => [],
    });

    render(<InventoryReconcile reference={reference()} />);

    fireEvent.click(await screen.findByText('Проведён'));
    await waitFor(() => expect(screen.getByText(/Сессия 8/)).toBeTruthy());

    expect(screen.getByText('Комментарий: Все на месте')).toBeTruthy();
    expect(screen.queryByLabelText('Комментарий')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Сохранить комментарий' })).toBeNull();
  });

  it('«Просмотр» видит журнал, но не сводит расхождения даже у открытого', async () => {
    stubApi({
      onList: () => [row({ id: 3, status: 'OPEN' })],
      onSummary: (id) => row({ id, status: 'OPEN' }),
    });

    render(<InventoryReconcile reference={reference()} role="VIEWER" />);

    fireEvent.click(await screen.findByText('Идёт подсчёт'));

    await waitFor(() => expect(screen.getByText(/Сессия 3/)).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Завершить подсчёт' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Отменить пересчёт' })).toBeNull();
    // И поиска по складу «Просмотру» тоже нет: findOpenSession требует роль,
    // которой у него нет, и кнопка без дела читалась бы как рабочая.
    expect(screen.queryByRole('button', { name: 'Найти пересчёт' })).toBeNull();
  });
});

interface Row {
  id: number;
  warehouseId: number;
  warehouseName: string;
  selection: string;
  status: 'OPEN' | 'COUNTED' | 'APPLIED' | 'CANCELLED';
  startedAt: string;
  appliedAt: string | null;
  lines: number;
  counted: number;
  /** Комментарий человека или `null` — пустой строки сервер не отдаёт. */
  note: string | null;
}

function row(overrides: Partial<Row> = {}): Row {
  return {
    id: 1, warehouseId: 2, warehouseName: 'Ткацкая', selection: 'Ткацкая · весь склад',
    status: 'OPEN', startedAt: '2026-09-05T10:00:00Z', appliedAt: null,
    lines: 1, counted: 0, note: null,
    ...overrides,
  };
}

function reference(): never {
  return {
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [], donors: [], cells: [], partNames: [],
  } as never;
}

function stubApi(handlers: {
  onList?: (query: string) => Row[];
  onSummary?: (id: number) => Row;
  onDiscrepancies?: () => unknown[];
  /** Тело запроса приходит как есть — проверяем, что именно уехало на сервер. */
  onNote?: (body: string) => Row;
}): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/note') && handlers.onNote) {
      return json(handlers.onNote(String(init?.body ?? '')));
    }
    if (url.includes('/discrepancies')) {
      return json(handlers.onDiscrepancies?.() ?? []);
    }
    const oneMatch = url.match(/\/api\/inventory\/sessions\/(\d+)$/);
    if (oneMatch && handlers.onSummary) {
      return json(handlers.onSummary(Number(oneMatch[1])));
    }
    // Список — «/sessions» без хвоста, с фильтром или без («Все пересчёты»
    // шлёт запрос совсем без query, а не с пустым «?»).
    const listMatch = url.match(/\/api\/inventory\/sessions(?:\?(.*))?$/);
    if (listMatch) {
      return json(handlers.onList?.(listMatch[1] ?? '') ?? []);
    }
    return json([]);
  }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
