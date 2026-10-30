import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { IncomesScreen } from './IncomesScreen';
import type { Reference } from '../reference/reference';

/**
 * Раздел «Поступления» (задача 0360): список по вкладкам, форма нового
 * и документ — слова и порядок кнопок ориентира.
 *
 * <p>Заглушка ведёт себя как сервер: помнит документ и его статус, чтобы
 * проверка «после „Провести документ“ стоит „Выполнен“» не зеленела
 * на экране, который просто перерисовал заглушку.
 */
describe('поступления', () => {
  let calls: { method: string; url: string; body: unknown }[];
  let doc: Record<string, unknown> | null;
  let unpostRefusal: string | null;

  beforeEach(() => {
    calls = [];
    doc = null;
    unpostRefusal = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      const body = init?.body === undefined ? undefined : JSON.parse(String(init.body));
      calls.push({ method, url, body });

      if (url.includes('/api/intake/supplies') && method === 'POST') {
        return json({ id: 77, number: (body as { number: string }).number }, 201);
      }
      if (url.match(/\/api\/intake\/incomes\?/)) {
        return json({
          rows: [row(41, 'DRAFT'), row(40, 'DONE')],
          counts: { drafts: 1, done: 1, cancelled: 0, all: 2 },
          more: false,
        });
      }
      if (url.endsWith('/api/intake/incomes') && method === 'POST') {
        const b = body as { warehouseId: number; supplyId: number | null; note: string | null };
        doc = {
          id: 9, number: 42, status: 'DRAFT', deleted: false,
          createdAt: '2026-10-16T10:27:00Z', completedAt: null,
          supplyId: b.supplyId, supply: b.supplyId === null ? null : 'Поставка №Иванов',
          warehouseId: b.warehouseId, warehouse: 'Ткацкая', note: b.note, amount: 0,
          lines: [], history: [entry('Создано поступление')],
        };
        return json(doc, 201);
      }
      if (url.endsWith('/api/intake/incomes/9/items') && method === 'POST') {
        const b = body as { rawName: string; quantity: number; costPrice: number | null };
        const lines = doc!.lines as unknown[];
        lines.push({ partId: 100 + lines.length, number: 500 + lines.length, publicCode: 'A1',
          title: b.rawName, condition: 'USED', qty: b.quantity, cost: b.costPrice });
        (doc!.history as unknown[]).push(entry(`Добавлена позиция ${500 + lines.length - 1}`));
        return json(doc, 201);
      }
      if (url.endsWith('/api/intake/incomes/9/post')) {
        doc = { ...doc!, status: 'DONE', completedAt: '2026-10-16T11:00:00Z',
          history: [...(doc!.history as unknown[]), entry('Поступление проведено')] };
        return json(doc);
      }
      if (url.endsWith('/api/intake/incomes/9/unpost')) {
        if (unpostRefusal !== null) {
          return json({ message: unpostRefusal }, 409);
        }
        doc = { ...doc!, status: 'DRAFT', completedAt: null,
          history: [...(doc!.history as unknown[]), entry('Проводка отменена')] };
        return json(doc);
      }
      if (url.endsWith('/api/intake/incomes/9/cancel')) {
        doc = { ...doc!, status: 'CANCELLED' };
        return json(doc);
      }
      if (url.endsWith('/api/intake/incomes/9')) {
        return json(doc);
      }
      return json({ message: 'нет такого' }, 404);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('список: вкладки ориентира, счётчик черновиков, колонки и подвал', async () => {
    render(<IncomesScreen reference={reference()} onSupplyAdded={() => {}} />);
    await waitFor(() => expect(screen.getByText('№ 41')).toBeTruthy());

    expect(screen.getByRole('button', { name: 'Черновики 1' })).toBeTruthy();
    for (const tab of ['Выполненные', 'Отменённые', 'Все поступления']) {
      expect(screen.getByRole('button', { name: tab })).toBeTruthy();
    }
    const heads = [...document.querySelectorAll('th')].map((th) => th.textContent);
    expect(heads).toEqual(['Номер/дата', 'Поставка', 'Склад оприходования', 'Сумма', 'Статус',
      'Комментарий']);
    expect(screen.getByText('Черновик')).toBeTruthy();
    expect(screen.getByText('Выполнен')).toBeTruthy();
    // Счётчик вкладки «Черновики» — 1, а не длина страницы (2 строки).
    expect(screen.getByText('1 документ')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Отменённые' }));
    await waitFor(() => expect(calls.some((c) => c.url.includes('tab=cancelled'))).toBe(true));
  });

  it('«Отменить» на новой форме не заводит документа', async () => {
    render(<IncomesScreen reference={reference()} onSupplyAdded={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Новое поступление' }));

    expect(screen.getByText('Создайте поступление, чтобы добавить товары')).toBeTruthy();
    expect(screen.getByText('Нет истории')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Отменить' }));

    await waitFor(() => expect(screen.getByText('№ 41')).toBeTruthy());
    expect(calls.filter((c) => c.method === 'POST'), 'отмена формы что-то отправила').toEqual([]);
  });

  it('создать, добавить позицию, провести и отменить проводку', async () => {
    render(<IncomesScreen reference={reference()} onSupplyAdded={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Новое поступление' }));

    const create = screen.getByRole('button', { name: 'Создать поступление' }) as HTMLButtonElement;
    expect(create.disabled, 'без склада кнопка жива').toBe(true);
    expect(screen.getByText('Выберите склад оприходования')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Склад оприходования'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Комментарий к поступлению'),
      { target: { value: 'контейнер' } });
    fireEvent.click(create);

    await waitFor(() => expect(screen.getByText('Поступление № 42')).toBeTruthy());
    const sent = calls.find((c) => c.method === 'POST' && c.url.endsWith('/api/intake/incomes'));
    expect(sent?.body).toMatchObject({ warehouseId: 3, supplyId: null, note: 'контейнер' });
    expect(typeof (sent?.body as { requestId: unknown }).requestId).toBe('string');
    expect(screen.getByText(/^Черновик от /)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Провести документ' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Отменить поступление' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Отменить проводку' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '+ Добавить' }));
    fireEvent.change(screen.getByLabelText('Наименование'), { target: { value: 'фара левая' } });
    fireEvent.change(screen.getByLabelText('Закупочная цена'), { target: { value: '1200' } });
    fireEvent.click(screen.getByRole('button', { name: 'Добавить' }));
    await waitFor(() => expect(screen.getByText('фара левая')).toBeTruthy());
    const heads = [...document.querySelectorAll('th')].map((th) => th.textContent);
    expect(heads).toEqual(['Номер', 'Наименование', 'Кол-во', 'Закупочная цена',
      'Накладные расходы']);

    fireEvent.click(screen.getByRole('button', { name: 'Провести документ' }));
    await waitFor(() => expect(screen.getByText(/^Выполнен /)).toBeTruthy());
    expect(screen.getByText('Поступление проведено')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Отменить проводку' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Провести документ' })).toBeNull();
    expect(screen.queryByRole('button', { name: '+ Добавить' }), 'в выполненный добавляют').toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Отменить проводку' }));
    await waitFor(() => expect(screen.getByText(/^Черновик от /)).toBeTruthy());
    expect(screen.getByText('Проводка отменена')).toBeTruthy();
  });

  it('отказ отмены проводки показан словами сервера, документ остаётся выполненным', async () => {
    unpostRefusal = '«фара левая»: позиция уже продана — отмена проводки невозможна';
    render(<IncomesScreen reference={reference()} onSupplyAdded={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Новое поступление' }));
    fireEvent.change(screen.getByLabelText('Склад оприходования'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Создать поступление' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Провести документ' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Отменить проводку' }));

    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(unpostRefusal));
    expect(screen.getByText(/^Выполнен /)).toBeTruthy();
  });

  it('«Отменить поступление» спрашивает и уводит документ в «Отменён»', async () => {
    render(<IncomesScreen reference={reference()} onSupplyAdded={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Новое поступление' }));
    fireEvent.change(screen.getByLabelText('Склад оприходования'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Создать поступление' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Отменить поступление' }));

    expect(calls.some((c) => c.url.endsWith('/cancel')), 'отменено без вопроса').toBe(false);
    const confirm = screen.getAllByRole('button', { name: 'Отменить поступление' });
    fireEvent.click(confirm[confirm.length - 1]!);
    await waitFor(() => expect(screen.getByText('Отменён')).toBeTruthy());
  });

  it('«+ Новая поставка»: «Добавить» неактивна без названия, поставка выбирается', async () => {
    const added = vi.fn();
    render(<IncomesScreen reference={reference()} onSupplyAdded={added} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Новое поступление' }));
    fireEvent.click(screen.getByRole('button', { name: '+ Новая поставка' }));

    const dialog = screen.getByRole('dialog', { name: 'Новая поставка' });
    const add = dialog.querySelector('button[type=submit]') as HTMLButtonElement;
    expect(add.textContent).toBe('Добавить');
    expect(add.disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'Закрыть' })).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Название'), { target: { value: 'Иванов' } });
    fireEvent.change(screen.getByLabelText('Описание'), { target: { value: 'кузовщина' } });
    fireEvent.click(add);

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const sent = calls.find((c) => c.url.includes('/api/intake/supplies'));
    expect(sent?.body).toEqual({ kind: 'OTHER', number: 'Иванов', note: 'кузовщина' });
    expect((screen.getByLabelText('Поставка') as HTMLSelectElement).value).toBe('77');
    expect(added).toHaveBeenCalled();
  });
});

function row(number: number, status: string) {
  return {
    id: number, number, status, deleted: false, createdAt: '2026-10-16T10:27:00Z',
    completedAt: status === 'DONE' ? '2026-10-16T11:00:00Z' : null,
    supply: null, warehouse: 'Ткацкая', amount: 0, note: null, lines: 0,
  };
}

function entry(text: string) {
  return { at: '2026-10-16T10:27:00Z', author: 'Владелец', text, partId: null, partTitle: null };
}

function reference(): Reference {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 3, name: 'Ткацкая', cells: [] }],
    supplies: [{ id: 5, kind: 'CONTAINER', number: '16', supplierName: null, status: 'ARRIVED',
      arrivedOn: null }],
    donors: [],
    partNames: [],
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}
