import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Номер позиции в выдаче продавца (задача 0061, перебор поверхностей).
 *
 * <p><b>Как это выглядело для человека.</b> Задача 0060 завела позиции
 * порядковый номер — тот, которым деталь называют вслух, — и владелец
 * назван в решении дословно: номер нужен, «чтобы человек мог применять
 * человекочитаемые цифры для точной идентификации конкретной запчасти
 * при общении с другими работниками». Продавец и есть тот работник:
 * он держит трубку. А в его выдаче стоял только публичный код
 * («7584A8FEAE3D»), который по телефону не произносят вовсе.
 *
 * <p>Выдача продавца — список строк, а не таблица, поэтому проверяется
 * не колонка, а сама строка позиции: убранный номер обязан валить тест
 * словами про строку, в которой его нет, а не пустым экраном.
 */
describe('номер позиции в выдаче продавца', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/parts/stock')) {
        return json({
          total: 1,
          facets: { vehicles: [{ brand: 'Toyota', model: 'Camry' }], grades: ['б/у'] },
          rows: [{
            partId: 1, number: 347, publicCode: '7584A8FEAE3D',
            title: 'Фара Toyota Camry 2007 лев.', price: '1500', status: 'IN_STOCK',
            warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
            qty: '1', qtyReserved: '0', qtyAvailable: '1',
          }],
        });
      }
      if (url.includes('/api/organization/warehouses')) {
        return json([{
          id: 2, branchId: 1, name: 'Ткацкая', branchName: 'Филиал', cells: 3,
        }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('строка выдачи называет номер позиции, а не только публичный код',
    async () => {
      render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
      await search();

      const info = screen.getByText('Фара Toyota Camry 2007 лев.').closest('.stock-info')!;
      const line = plain(info.textContent);

      expect(
        line,
        `номера позиции в строке выдачи нет: ${line}`,
      ).toContain('№ 347');

      // Публичный код рядом остался: он про этикетку и сканер, и продавец
      // читает его с самой детали. Одно другого не заменяет.
      expect(
        line,
        'публичный код пропал из строки: его читают с этикетки на детали',
      ).toContain('7584A8FEAE3D');
    });

  /**
   * Та же строка в корзине — и это не украшение.
   *
   * <p>Набранную корзину продавец называет вслух: кладовщику, который пойдёт
   * снимать с полки, или клиенту при сверке. В блоке «В сделку» не было
   * ни номера, ни публичного кода — только наименование, а на живом складе
   * «Фара Toyota Camry 2007» это сотня одинаковых строк.
   */
  it('в корзине строка тоже названа номером позиции', async () => {
    render(<SellerScreen canSell role="SELLER" company="test" memberId={1} />);
    await search();

    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'в сделку')!);

    const basket = await screen.findByText('В сделку');
    const line = plain(basket.nextElementSibling!.textContent);

    expect(
      line,
      `в корзине позицию назвать нечем: ${line}`,
    ).toContain('№ 347');
  });

  async function search(): Promise<void> {
    const input = document.querySelector('input') as HTMLInputElement;
    setNative(input, 'фара');
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Найти')!);
    await waitFor(() => expect(document.querySelectorAll('.stock-row').length)
      .toBeGreaterThan(0));
  }
});

/**
 * Текст строки с обычными пробелами.
 *
 * <p>Номер отделён неразрывным пробелом — он там ради переноса, а не ради
 * текста, — и сравнение с обычным развалилось бы молча. Escape, а не сам
 * символ: невидимый пробел в исходнике теста однажды уже сделал такую
 * замену пустой, и тест падал на строке, которая глазами выглядит верной.
 */
function plain(text: string | null): string {
  return (text ?? '').replace(/\u00a0/g, ' ');
}

/** React слушает нативный сеттер, а не присваивание value. */
function setNative(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value')!.set!;
  setter.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
