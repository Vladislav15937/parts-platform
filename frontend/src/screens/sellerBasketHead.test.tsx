import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Корзина видна всегда, а не в конце списка.
 *
 * <p><b>Зачем.</b> Блок «В сделку» лежал под списком находок. «Фара» на живом
 * складе — это полсотни показанных строк, то есть два экрана прокрутки вниз
 * и столько же обратно за каждой следующей деталью. Клиент называет
 * три-четыре детали подряд — четыре прокрутки туда-обратно за разговор,
 * и всё это время продавец не знает, сколько уже набрал и на сколько.
 * Единственным подтверждением была смена слова на кнопке строки.
 *
 * <p>Проверяем то, что видит продавец: число, сумму и то, что счётчик стоит
 * <b>выше</b> списка, а не под ним. «Не уезжает при прокрутке» — это
 * `position: sticky`, и его jsdom не считает: он проверен живым прогоном
 * в узком окне.
 */
describe('счётчик корзины в шапке продажи', () => {
  beforeEach(() => {
    // jsdom не умеет прокрутку; нам важен сам факт вызова.
    Element.prototype.scrollIntoView = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/parts/stock')) {
        return json({
          total: 181,
          rows: [
            row(1, 'Фара левая Camry V50', '7500'),
            row(2, 'Фара правая Camry V50', '8200'),
            row(3, 'Фара левая Corolla E160', '4300'),
          ],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('пустая корзина говорит, что она пуста, и что делать', () => {
    render(<SellerScreen canSell role="SELLER" />);

    const head = text('.seller-head');
    expect(head, 'продавец не видит состояния корзины до первой находки')
      .toContain('Список пуст');
    expect(head, 'пустой счётчик молчит о том, что делать')
      .toContain('Выберите товары для продажи');
  });

  it('счётчик показывает число и сумму сразу, не дожидаясь прокрутки', async () => {
    render(<SellerScreen canSell role="SELLER" />);
    await search();

    add();
    await waitFor(() => expect(text('.seller-head')).toContain('1 позиция'));
    expect(text('.seller-head'), 'сумма первой позиции не показана').toContain('7 500 ₽');

    add();
    add();
    await waitFor(() => expect(text('.seller-head')).toContain('3 позиции'));
    expect(text('.seller-head'), 'сумма трёх не сошлась').toContain('20 000 ₽');

    // Убрали одну из корзины — счётчик обязан уменьшиться.
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'убрать')!);
    await waitFor(() => expect(text('.seller-head')).toContain('2 позиции'));
    expect(text('.seller-head')).toContain('12 500 ₽');
  });

  it('счётчик стоит выше списка находок, а не под ним', async () => {
    render(<SellerScreen canSell role="SELLER" />);
    await search();
    add();

    const head = document.querySelector('.seller-head')!;
    const list = document.querySelector('.stock-list')!;
    // DOCUMENT_POSITION_FOLLOWING: список идёт после счётчика. Ради этого
    // всё и затевалось — иначе за числом надо прокручивать.
    expect(
      head.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING,
      'счётчик оказался ниже списка — прокрутка осталась',
    ).toBeTruthy();
  });

  it('нажатие на счётчик уводит к оформлению', async () => {
    render(<SellerScreen canSell role="SELLER" />);
    await search();
    add();

    const counter = await waitFor(() => {
      const found = document.querySelector('.seller-head button');
      expect(found, 'счётчик не нажимается — до оформления по-прежнему прокрутка').toBeTruthy();
      return found as HTMLElement;
    });
    fireEvent.click(counter);

    expect(Element.prototype.scrollIntoView, 'нажатие никуда не привело')
      .toHaveBeenCalled();
  });

  /** Кладёт в корзину верхнюю ещё не взятую находку. */
  function add(): void {
    fireEvent.click([...document.querySelectorAll('.stock-row button')]
      .find((b) => b.textContent === 'в сделку') as HTMLElement);
  }

  async function search(): Promise<void> {
    const input = document.querySelector('input') as HTMLInputElement;
    setNative(input, 'фара');
    fireEvent.click([...document.querySelectorAll('button')]
      .find((b) => b.textContent === 'Найти')!);
    await waitFor(() => expect(document.querySelector('.stock-row')).toBeTruthy());
  }
});

/** Текст блока с неразрывными пробелами, приведёнными к обычным. */
function text(selector: string): string {
  return (document.querySelector(selector)?.textContent ?? '')
    .replace(/ /g, ' ');
}

function row(partId: number, title: string, price: string) {
  return {
    partId, publicCode: `A-${partId}`, title, price, status: 'IN_STOCK',
    warehouseId: 2, warehouseName: 'Ткацкая', cellCode: null,
    qty: '1', qtyReserved: '0', qtyAvailable: '1',
  };
}

/**
 * React слушает нативный сеттер: присвоение value напрямую он не заметит,
 * и поле останется пустым при внешне набранном тексте.
 */
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
