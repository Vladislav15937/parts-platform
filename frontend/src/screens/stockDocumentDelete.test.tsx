import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartCard } from './PartCard';
import { PartHistoryView } from './PartHistoryView';
import { StockMovesScreen } from './StockMovesScreen';
import type { CatalogRow, PartHistory } from '../inventory/catalog';
import { deletionNotice } from '../inventory/stockDocuments';

/**
 * «Движение товара» колонками как у Bazon и удаление проведённого документа
 * (задача 0367, «Ответ владельца — 23 октября 2026», решения 1–5 и 7).
 *
 * <p>До правки лента была «Когда · Что · Кол-во · Документ · Склад», статус
 * стоял мелко под номером документа, а удалить документ было нечем.
 */
describe('«Движение товара» и удаление документа', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('колонки как у Bazon: Дата/Время · Документ/Склад · Статус · Кол-во · Цена · Поступление · Поставка', () => {
    render(<PartHistoryView history={history()} tab="movements" onTab={() => {}} />);
    const headers = [...document.querySelectorAll('thead th')].map((th) => th.textContent);
    expect(headers).toEqual(
      ['Дата/Время', 'Документ/Склад', 'Статус', 'Кол-во', 'Цена', 'Поступление', 'Поставка']);
    const first = [...document.querySelectorAll('tbody tr')][0]!;
    const cells = [...first.children].map((td) => td.textContent);
    expect(cells[1]).toContain('Сделка №50001 / Основной');
    expect(cells[2], 'статус не своей колонкой').toBe('Резерв');
    expect(cells[4]?.replace(/\s/g, ' ')).toBe('4 500 ₽');
    expect(cells[5]).toBe('22');
    expect(cells[6]).toBe('Контейнер №18');
    expect(screen.queryByText('Отложено под клиента'), 'прежнее слово колонки «Что»').toBeNull();
  });

  it('удалённый документ — «Выполнен (Удалён)» красным', () => {
    render(<PartHistoryView history={history()} tab="movements" onTab={() => {}} />);
    const status = screen.getByText('Выполнен (Удалён)');
    expect(status.className).toContain('history__status--deleted');
    expect(screen.getByText('Выполнен').className).not.toContain('history__status--deleted');
  });

  it('кнопка «Удалить» — только у строки, отмеченной сервером, и спрашивает подтверждение', async () => {
    const onDelete = vi.fn(async () => {});
    render(<PartHistoryView history={history()} tab="movements" onTab={() => {}} onDelete={onDelete} />);

    const buttons = screen.getAllByRole('button', { name: /^Удалить:/ });
    expect(buttons).toHaveLength(1);
    fireEvent.click(buttons[0]!);
    expect(onDelete, 'удалило без подтверждения').not.toHaveBeenCalled();
    expect(screen.getByText(/целиком\? Со склада снимется только свободное/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Удалить документ' }));
    await waitFor(() => expect(onDelete).toHaveBeenCalledWith(7));
  });

  it('итог называет несписанное позицией и числом', () => {
    const text = deletionNotice({
      documentId: 7, number: 22, docType: 'INTAKE', deletedAt: '2026-10-23T10:00:00Z',
      deletedBy: 'Владелец', alreadyDeleted: false,
      notTaken: [{ partId: 1, number: 347, publicCode: 'A1', title: 'Фара левая', wanted: 2, taken: 1 }],
    });
    expect(text).toBe('Поступление №22 удалено. Не всё снято со склада — остальное продано'
      + ' или отложено: Фара левая (№ 347) — снято 1 из 2.');
  });

  it('карточка: «Удалить» в «Движении товара» зовёт сервер, лента перечитывается, итог назван', async () => {
    const calls: string[] = [];
    let deleted = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push(`${init?.method ?? 'GET'} ${url}`);
      if (url.includes('/api/stock/documents/7')) {
        deleted = true;
        return json({
          documentId: 7, number: 22, docType: 'INTAKE', deletedAt: '2026-10-23T10:00:00Z',
          deletedBy: 'Владелец', alreadyDeleted: false,
          notTaken: [{ partId: 5, number: 347, publicCode: 'A1', title: 'Фара левая', wanted: 1, taken: 0 }],
        });
      }
      if (url.includes('/history')) {
        const h = history();
        const intake = h.movements[1]!;
        intake.deletableDocumentId = deleted ? null : 7;
        intake.status = deleted ? 'Выполнен (Удалён)' : 'Выполнен';
        intake.deleted = deleted;
        return json(h);
      }
      return json([]);
    }));

    render(<PartCard row={cardRow()} warehouses={[{ id: 2, name: 'Основной' }]} role="OWNER"
      onClose={() => {}} onChanged={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'История' }));
    fireEvent.click(await screen.findByRole('button', { name: /^Движения/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Удалить: Поступление №22' }));
    fireEvent.click(screen.getByRole('button', { name: 'Удалить документ' }));

    await waitFor(() => expect(screen.getByText(/Поступление №22 удалено\. Не всё снято/)).toBeTruthy());
    expect(calls).toContain('DELETE /api/stock/documents/7');
    await waitFor(() => expect(screen.getAllByText('Выполнен (Удалён)')).toHaveLength(2));
    expect(screen.queryByRole('button', { name: /^Удалить:/ })).toBeNull();
  });

  it('«Перевозки»: удалённая отмечена, у живой кнопка, удаление зовёт сервер и называет итог', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push(`${init?.method ?? 'GET'} ${url}`);
      if (url.includes('/api/stock/documents/4')) {
        return json({
          documentId: 4, number: 4, docType: 'MOVE', deletedAt: '2026-10-23T10:00:00Z',
          deletedBy: 'Владелец', alreadyDeleted: false, notTaken: [],
        });
      }
      return json([
        { id: 4, number: 4, createdAt: '2026-09-05T20:01:00Z', fromWarehouse: 'Ткацкая',
          toWarehouse: 'Ангар', lines: 1, note: null, author: 'Иванов',
          deletedAt: null, deletedBy: null, deletable: true },
        { id: 3, number: 3, createdAt: '2026-09-04T20:01:00Z', fromWarehouse: 'Ткацкая',
          toWarehouse: 'Дальний', lines: 2, note: null, author: 'Иванов',
          deletedAt: '2026-09-06T10:00:00Z', deletedBy: 'Владелец', deletable: false },
      ]);
    }));

    render(<StockMovesScreen role="OWNER" />);
    await waitFor(() => expect(screen.getByText('Ангар')).toBeTruthy());
    expect(screen.getByText('Удалён · Владелец')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /^Удалить:/ })).toHaveLength(1);
    expect(screen.getByText(/Перевозок: 1 на 1 позицию · удалённых: 1/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Удалить: перемещение №4' }));
    fireEvent.click(screen.getByRole('button', { name: 'Удалить документ' }));
    await waitFor(() => expect(screen.getByText('Перемещение №4 удалено.')).toBeTruthy());
    expect(calls).toContain('DELETE /api/stock/documents/4');
  });
});

function history(): PartHistory {
  const base = {
    warehouse: 'Основной', reason: null, author: null, held: false, price: null,
    deleted: false, deletableDocumentId: null,
  };
  return {
    changes: [],
    intake: '22',
    supply: 'Контейнер №18',
    movements: [
      { ...base, at: '2026-10-08T10:00:00Z', type: 'Сделка', qty: 1, document: 'Сделка №50001',
        status: 'Резерв', held: true, price: 4500 },
      { ...base, at: '2026-09-08T10:00:00Z', type: 'Поступление', qty: 1,
        document: 'Поступление №22', status: 'Выполнен', deletableDocumentId: 7 },
      { ...base, at: '2026-08-12T10:00:00Z', type: 'Перемещение', qty: 1,
        document: 'Перемещение №9', status: 'Выполнен (Удалён)', deleted: true,
        warehouse: 'Склад А → Склад Б' },
    ],
  };
}

function cardRow(): CatalogRow {
  return {
    id: 5, number: 347, code: 'A1', title: 'Фара левая', qualityGrade: null, condition: null,
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: 10000, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, cellCode: null,
    sideLr: null, sideFr: null, qty: 1, oem: null, crosses: null, photoUrl: null,
    supply: null, equipment: null, partName: null, published: null, barcode: null,
    legacyCode: null, dromAdNumber: null, videoUrl: null, textBlock: null,
    weightKg: null, dimensions: null, packageDimensions: null, packageWeightKg: null,
    createdAt: null, updatedAt: null, updatedByName: null,
    priceChangedAt: null, priceChangedByName: null, photoCount: 0, stock: { '2': 1 },
  } as unknown as CatalogRow;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}
