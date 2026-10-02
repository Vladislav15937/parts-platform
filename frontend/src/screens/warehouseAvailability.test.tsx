import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { OrganizationScreen } from './OrganizationScreen';

/**
 * Текст наличия склада и вилка дней заказа — то, что покупатель читает
 * в объявлении вместо одного «есть» (задача 0008).
 *
 * <p><b>Зачем.</b> Склады у клиента в разных местах, и деталь с дальнего едет
 * несколько дней. Задать это было негде вовсе: в прайс уходило булево
 * `available`, и покупатель, приехавший за деталью сегодня, узнавал про срок
 * уже по телефону.
 *
 * <p>Проверяется связка целиком — заданное уезжает на сервер тем же, что
 * владелец набрал; пустое уезжает `null`, а не пустой строкой («не задано»
 * и «пустой текст наличия» на площадке читаются по-разному); и склад,
 * которому ничего не задавали, в таблице назван словами, а не пустой клеткой.
 */
describe('наличие склада в объявлении', () => {
  let saved: { url: string; method: string; body: unknown } | null = null;

  beforeEach(() => {
    saved = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/availability')) {
        saved = {
          url,
          method: init?.method ?? 'GET',
          body: JSON.parse(String(init?.body ?? 'null')),
        };
        return json(warehouses()[0]);
      }
      if (url.includes('/warehouses')) {
        return json(warehouses());
      }
      if (url.includes('/branches')) {
        return json([{ id: 1, name: 'Полный цикл' }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('заданное у склада видно в таблице, а незаданное названо словами', async () => {
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Ткацкая')).toBeTruthy());

    // Ближний склад: текст есть, ждать не надо — «0 дн.» было бы шумом.
    expect(screen.getByText('в наличии')).toBeTruthy();
    // Дальний: свой текст и своя вилка.
    expect(screen.getByText('под заказ · 2–4 дн.')).toBeTruthy();
    // А складу, которому владелец ничего не задавал, пустая клетка читалась
    // бы как «не знаем»: мы знаем — не задавали.
    expect(screen.getByText('не задано')).toBeTruthy();
  });

  it('набранное уезжает на сервер тем же, что владелец видел', async () => {
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Ткацкая')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Наличие: Контейнерная площадка' }));

    fireEvent.change(screen.getByLabelText('Текст наличия'),
      { target: { value: 'под заказ' } });
    fireEvent.change(screen.getByLabelText('Дней заказа, от'), { target: { value: '2' } });
    fireEvent.change(screen.getByLabelText('Дней заказа, до'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить наличие' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.method).toBe('PUT');
    expect(saved?.url).toContain('/api/organization/warehouses/3/availability');
    expect(saved?.body).toEqual({
      availabilityNote: 'под заказ',
      orderDaysFrom: 2,
      orderDaysTo: 4,
    });
  });

  it('пустое уезжает null, а ноль дней — нулём', async () => {
    // На сервере это разные вещи: null значит «не задано» (склад ведёт себя
    // как до задачи), а ноль дней — «забрать можно сегодня». Превратив одно
    // в другое, экран отдал бы владельцу не то, что тот выбрал.
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Ткацкая')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Наличие: Ткацкая' }));
    fireEvent.change(screen.getByLabelText('Текст наличия'), { target: { value: '   ' } });
    fireEvent.change(screen.getByLabelText('Дней заказа, от'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить наличие' }));

    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved?.body).toEqual({
      availabilityNote: null,
      orderDaysFrom: 0,
      orderDaysTo: null,
    });
  });

  it('перевёрнутая вилка не уезжает, и кнопка говорит почему', async () => {
    // Сервер такое отбивает словами, но узнать об этом владелец должен
    // до нажатия: серая кнопка, которая молчит, читается как поломка.
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Ткацкая')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Наличие: Ткацкая' }));
    fireEvent.change(screen.getByLabelText('Дней заказа, от'), { target: { value: '4' } });
    fireEvent.change(screen.getByLabelText('Дней заказа, до'), { target: { value: '2' } });

    expect(screen.getByText('Верхняя граница вилки дней меньше нижней')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить наличие' }));
    expect(saved, 'перевёрнутая вилка уехала на сервер').toBeNull();
  });

  it('форма открывается тем, что у склада уже стоит', async () => {
    // Иначе владелец с тремя складами не знает, у какого срок уже задан,
    // и решает это заново каждый раз.
    render(<OrganizationScreen />);
    await waitFor(() => expect(screen.getByText('Ткацкая')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Наличие: 54 YARD' }));

    expect((screen.getByLabelText('Текст наличия') as HTMLInputElement).value)
      .toBe('под заказ');
    expect((screen.getByLabelText('Дней заказа, от') as HTMLInputElement).value).toBe('2');
    expect((screen.getByLabelText('Дней заказа, до') as HTMLInputElement).value).toBe('4');
  });

  function warehouses() {
    return [
      {
        id: 1, branchId: 1, name: 'Ткацкая', branchName: 'Полный цикл', cells: 12,
        availabilityNote: 'в наличии', orderDaysFrom: 0, orderDaysTo: null,
      },
      {
        id: 2, branchId: 1, name: '54 YARD', branchName: 'Полный цикл', cells: 0,
        availabilityNote: 'под заказ', orderDaysFrom: 2, orderDaysTo: 4,
      },
      {
        id: 3, branchId: 1, name: 'Контейнерная площадка', branchName: 'Полный цикл',
        cells: 0, availabilityNote: null, orderDaysFrom: null, orderDaysTo: null,
      },
    ];
  }
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
