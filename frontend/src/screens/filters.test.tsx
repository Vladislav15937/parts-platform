import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';

/**
 * Отбор колонки виден и снимается, даже когда выдача пуста.
 *
 * <p>Снимался он только в меню колонки — то есть в шапке таблицы. А при
 * пустой выдаче таблицы нет вовсе: отбор действует, объяснения ему нет
 * нигде, и снять его нечем. Владелец видит «Ничего не найдено» на складе
 * в тридцать пять тысяч позиций и не понимает, почему; выход один —
 * перезагрузить страницу. Та же болезнь, что со скрытой колонкой «Номер
 * донора»: почему осталось две позиции из трёхсот семидесяти пяти,
 * на экране не написано.
 *
 * <p>Поймано живым прогоном: отбор по марке из меню колонки плюс подбор
 * по машине дали ноль, и снять первый было нечем.
 */
describe('отбор колонки при пустой выдаче', () => {
  // Первая страница непустая — иначе таблицы не будет и отбор поставить
  // будет нечем; с отбором сервер отвечает пустой выдачей, без него —
  // снова непустой: снятие обязано вернуть товары.
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/values')) {
        return json(['Daihatsu', 'Ford']);
      }
      if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')) {
        return json([]);
      }
      return json(url.includes('filter=') ? { total: 0, rows: [], warehouses: [] } : {
        total: 1,
        warehouses: [],
        rows: [{ id: 1, publicCode: 'A-1', title: 'Фара', brand: 'Ford', price: '100',
                 stock: {}, photoCount: 0 }],
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('чип отбора остаётся, когда таблица исчезла, и снимается', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelector('tbody tr')).toBeTruthy());

    // Ставим отбор через меню колонки — так его ставит владелец.
    const mark = [...document.querySelectorAll('thead th')]
      .find((th) => th.textContent?.includes('Марка'));
    fireEvent.click(mark!.querySelector('button')!);
    // «Ford» на экране двое: значение в строке таблицы и пункт меню.
    // Нужен пункт меню — он кнопка.
    await waitFor(() => expect(
      [...document.querySelectorAll('button')].some((b) => b.textContent === 'Ford'),
    ).toBe(true));
    fireEvent.click(
      [...document.querySelectorAll('button')].find((b) => b.textContent === 'Ford')!,
    );

    // Выдача опустела: таблицы больше нет, а отбор действует.
    await waitFor(() => expect(screen.getByText('Ничего не найдено.')).toBeTruthy());
    expect(document.querySelector('thead')).toBeNull();

    const chip = [...document.querySelectorAll('.crumb')]
      .find((c) => c.textContent?.includes('Ford'));
    expect(chip, 'отбор действует, а снять его нечем').toBeTruthy();

    fireEvent.click(chip!);
    await waitFor(() => expect(document.querySelector('tbody tr')).toBeTruthy());
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
