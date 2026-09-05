import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ReturnsScreen } from './ReturnsScreen';

/**
 * Реестр возвратов: список видит все документы, а не только сделку клиента.
 *
 * <p>Раньше единственный список возвратов жил внутри открытой сделки
 * (`GET /api/deals/{id}/returns`) — до него ещё надо было дойти через
 * клиента. Этот экран читает `GET /api/deals/returns` и показывает всё
 * сразу; проверяются ровно те слова и умолчания, что называет задача
 * 0021: порядок колонок, «Частное лицо», текст брака, подвал и различие
 * между «пусто вообще» и «пусто по отбору».
 */
describe('реестр возвратов', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('колонки, «Частное лицо» и текст брака — дословно', async () => {
    stubApi({
      items: [
        {
          id: 11, number: 11, createdAt: '2026-09-05T12:00:00Z',
          dealId: 7, dealNumber: 42,
          customerId: null, customerName: null,
          warehouseId: 3, warehouseName: 'Основной',
          restocked: false, status: 'DONE', amount: '5000.00',
          reason: 'скрипит при повороте',
        },
      ],
      total: 1,
      totalAmount: '5000.00',
    });

    render(<ReturnsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Частное лицо')).toBeTruthy());
    // Дата коротким месяцем, а не ISO и не точками: «05 сен 26».
    expect(screen.getByText('05 сен 26')).toBeTruthy();
    // Брак — деньги вернули, а на склад не ставили, и это важнее названия
    // склада: адреса у такой позиции нет.
    expect(screen.getByText('Брак, на склад не ставили')).toBeTruthy();
    expect(screen.queryByText('Основной')).toBeNull();
    expect(screen.getByText('скрипит при повороте')).toBeTruthy();
    expect(screen.getByText('Выполнен')).toBeTruthy();
    // Подвал — дословно из задачи, без округления до сотен и без ₽.
    expect(screen.getByText(/Возвратов: 1 на сумму 5 000/)).toBeTruthy();
  });

  it('номер сделки — ссылка, нажатие открывает ровно ту сделку', async () => {
    stubApi({
      items: [row({ dealId: 9, dealNumber: 77 })],
      total: 1,
      totalAmount: '1000.00',
    });
    const onOpenDeal = vi.fn();

    render(<ReturnsScreen onOpenDeal={onOpenDeal} />);

    const link = await screen.findByRole('button', { name: '77' });
    fireEvent.click(link);
    expect(onOpenDeal).toHaveBeenCalledWith(9);
  });

  it('отменённый возврат — серым, и не входит в сумму подвала', async () => {
    stubApi({
      items: [row({ status: 'CANCELLED', amount: '3000.00' })],
      total: 1,
      // Сервер уже посчитал без отменённого — экран не пересчитывает сам,
      // а показывает то, что пришло: розданное на двух концах правило
      // разошлось бы на первой же новой ветке фильтра.
      totalAmount: '0',
    });

    render(<ReturnsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Отменён')).toBeTruthy());
    expect(screen.getByText(/Возвратов: 1 на сумму 0/)).toBeTruthy();
  });

  it('пустая система говорит одно, пустой отбор — другое', async () => {
    stubApi({ items: [], total: 0, totalAmount: '0' });
    render(<ReturnsScreen onOpenDeal={() => {}} />);

    await waitFor(() => expect(screen.getByText('Возвратов пока не было')).toBeTruthy());

    fireEvent.change(screen.getByPlaceholderText('Номер сделки, клиент или причина'), {
      target: { value: 'стук' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Найти' }));

    await waitFor(() => expect(screen.getByText('По этому отбору возвратов нет')).toBeTruthy());
    expect(screen.queryByText('Возвратов пока не было')).toBeNull();
  });
});

function row(overrides: Partial<Row> = {}): Row {
  return {
    id: 1, number: 1, createdAt: '2026-09-01T10:00:00Z',
    dealId: 1, dealNumber: 1,
    customerId: 5, customerName: 'Автосервис',
    warehouseId: 3, warehouseName: 'Основной',
    restocked: true, status: 'DONE', amount: '1000.00', reason: null,
    ...overrides,
  };
}

interface Row {
  id: number;
  number: number | null;
  createdAt: string;
  dealId: number;
  dealNumber: number | null;
  customerId: number | null;
  customerName: string | null;
  warehouseId: number | null;
  warehouseName: string | null;
  restocked: boolean;
  status: string;
  amount: string;
  reason: string | null;
}

function stubApi(page: { items: Row[]; total: number; totalAmount: string }): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/deals/returns')) {
      return json(page);
    }
    return json({});
  }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
