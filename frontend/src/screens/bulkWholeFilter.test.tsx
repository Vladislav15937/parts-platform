import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';

/**
 * Правку можно распространить на весь отбор, а не на видимую страницу.
 *
 * <p><b>Зачем.</b> Выгрузка прежней системы приходит без колонки «Выгружать»,
 * если её не включили перед экспортом, — и тогда весь склад импортируется
 * без разрешения публиковать. У живого клиента это 35 841 позиция: прайс
 * уезжает на Дром пустым, 55 байт вместо двадцати мегабайт, и площадка молча
 * не заводит ни одного объявления.
 *
 * <p>Отметить руками можно только видимое, а видно пятьдесят строк: семьсот
 * семнадцать страниц, с потерей выделения на каждой. Сама возможность была
 * написана — правка списком принимает хоть весь склад, — и не было только
 * способа назвать «всё, что я вижу».
 *
 * <p>Проверяется, что кнопка есть и что она отправляет отбор, а не список
 * из пятидесяти номеров: это разные адреса, и подмена одного другим
 * незаметна — обе отвечают «изменено N».
 */
describe('правка всего отбора', () => {
  let sent: Array<{ url: string; body: string }>;

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'POST') {
        sent.push({ url, body: String(init.body ?? '') });
        return json({ changed: 35841 });
      }
      if (url.includes('/values')) return json([]);
      if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')) {
        return json([]);
      }
      // Тридцать пять тысяч позиций против пятидесяти на странице —
      // в этом разрыве и живёт ошибка.
      return json({
        total: 35841,
        warehouses: [],
        rows: [{ id: 1, publicCode: 'A-1', title: 'Фара', price: '100',
                 stock: {}, photoCount: 0 }],
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('включает «Выгружать» всему отбору, а не отмеченной странице', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelector('tbody tr')).toBeTruthy());

    fireEvent.click(button('Правка списком')!);

    const whole = button('Выбрать весь отбор (35\u00a0841)');
    expect(whole, 'отметить можно только страницу — склад целиком не включить').toBeTruthy();
    fireEvent.click(whole!);

    await waitFor(() => expect(// Точка вместо разделителя: getByText нормализует неразрывный пробел
      // в обычный, а textContent у кнопок — нет.
      screen.getByText(/Выбран весь отбор: 35.841/)).toBeTruthy());
    fireEvent.click(button('Изменить')!);

    // Отмечаем «Выгружать» — то самое поле, из-за которого прайс пуст.
    const flag = [...document.querySelectorAll('.bulk-field')]
      .find((f) => f.textContent?.includes('Выгружать'));
    fireEvent.click(flag!.querySelector('input[type=checkbox]')!);

    // Вторым нажатием: отменить правку склада нечем, кроме бэкапа.
    fireEvent.click(button('Изменить 35\u00a0841 позицию')!);
    await waitFor(() => expect(button('Точно изменить 35\u00a0841 позицию?')).toBeTruthy());
    fireEvent.click(button('Точно изменить 35\u00a0841 позицию?')!);

    await waitFor(() => expect(sent.length).toBe(1));
    const request = sent[0]!;
    expect(request.url, 'уехал список номеров вместо отбора — тронется одна страница')
      .toContain('/api/parts/catalog/bulk');
    expect(request.body).toContain('"published":true');
    expect(request.body, 'в теле оказались номера страницы, а не отбор')
      .not.toContain('partIds');
  });

  /**
   * Отдельная ошибка, жившая и в правке по отмеченным строкам: список
   * показывался на «Везде», а нетронутым читался как «Нет». То есть
   * владелец отмечал «Выгружать», видел «Везде», сохранял — и снимал
   * позиции с выгрузки. Обратное тому, что написано на экране, и заметно
   * это только по опустевшему прайсу через несколько дней.
   *
   * <p>Проверка идёт по отмеченной строке, а не по отбору: путь старше
   * и пострадал бы первым.
   */
  it('нетронутый список «Выгружать» уезжает тем, чем показан', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelector('tbody tr')).toBeTruthy());

    fireEvent.click(button('Правка списком')!);
    fireEvent.click(document.querySelector('tbody tr')!);
    await waitFor(() => expect(screen.getByText('Выбрано 1')).toBeTruthy());
    fireEvent.click(button('Изменить')!);

    const flag = [...document.querySelectorAll('.bulk-field')]
      .find((f) => f.textContent?.includes('Выгружать'));
    fireEvent.click(flag!.querySelector('input[type=checkbox]')!);

    const select = flag!.querySelector('select') as HTMLSelectElement;
    expect(select.value, 'список показан не тем, чем читается').toBe('yes');

    fireEvent.click(button('Изменить 1 позицию')!);
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!.body, 'показано «Везде», а уехало «Нет» — позиции сняты с выгрузки')
      .toContain('"published":true');
  });
});

function button(text: string): HTMLElement | undefined {
  return [...document.querySelectorAll('button')].find((b) => b.textContent === text);
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
