import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';
import { IntakeScreen } from './IntakeScreen';

/**
 * Товар по ожидаемой поставке: завести (владелец, из карточки поставки) и
 * принять той же позицией (приёмщик, с телефона) — задача 0170.
 *
 * <p><b>Как это выглядело.</b> Выгрузка умела отправлять на площадку товар,
 * по которому ожидается поступление, но данных для неё человеку взять было
 * неоткуда: приёмка всегда создавала новую позицию и всегда клала её на склад.
 * Контейнеры из Японии клиент заводит заранее и продаёт по ним до прихода;
 * предзаказы жили в телефонных разговорах.
 *
 * <p>Кнопка показывается только владельцу — и тот же адрес закрыт на сервере
 * (`PreorderTest`): спрятанная кнопка при открытом адресе не защита.
 */
describe('ожидаемый товар в карточке поставки', () => {
  let posted: Record<string, unknown> | null;
  let dated: Record<string, unknown> | null;

  beforeEach(() => {
    posted = null;
    dated = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/supplies/19/expected-parts') && init?.method === 'POST') {
        posted = JSON.parse(String(init.body));
        return json([{ id: 5, number: 410, title: 'Фара левая Toyota Camry',
          quantity: 3, price: 9000, received: 0, preordered: 0 }]);
      }
      if (url.endsWith('/supplies/19/expected-parts')) {
        return json([]);
      }
      if (url.endsWith('/supplies/19/expected-on') && init?.method === 'PUT') {
        dated = JSON.parse(String(init.body));
        return json({});
      }
      if (url.includes('/supplies/19/donors')) {
        return json([{ id: 7, code: '350', brand: 'Toyota', model: 'Camry', year: 2007,
          vin: null, status: 'DISMANTLING', note: null, location: null }]);
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('не владельцу кнопки нет вовсе', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await waitFor(() => screen.getAllByRole('button', { name: 'Машины' }));

    expect(screen.queryByRole('button', { name: 'Ожидаемый товар' })).toBeNull();
  });

  it('владелец заводит позицию: вид детали, машина, количество, цена', async () => {
    render(<DonorScreen online canExpect reference={reference()} onChanged={() => {}} />);
    // Только у ожидаемой поставки: приехавшую заводить поздно.
    const buttons = await waitFor(() =>
      screen.getAllByRole('button', { name: 'Ожидаемый товар' }));
    expect(buttons).toHaveLength(1);
    fireEvent.click(buttons[0]!);

    await waitFor(() => expect(screen.getByText('Завести позицию')).toBeTruthy());
    const create = await waitFor(() => {
      const button = screen.getByRole('button', { name: 'Завести' }) as HTMLButtonElement;
      expect(screen.getByRole('option', { name: /350 · Toyota Camry/ })).toBeTruthy();
      return button;
    });
    // Пока не заполнено — кнопка серая и называет причину.
    expect(create.disabled).toBe(true);
    expect(document.body.textContent).toContain('Впишите вид детали');

    fireEvent.change(screen.getByLabelText('Вид детали'), { target: { value: 'фара левая' } });
    fireEvent.change(screen.getByLabelText('Машина (необязательно)'), { target: { value: '7' } });
    fireEvent.change(screen.getByLabelText('Количество'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Цена, ₽'), { target: { value: '9000' } });
    await waitFor(() => expect(create.disabled).toBe(false));

    fireEvent.click(create);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toEqual({ rawName: 'фара левая', donorId: 7, quantity: 3, price: 9000,
      requestId: expect.any(String) });
    // Список показывается после заведения, из ответа.
    await waitFor(() => expect(document.body.textContent).toContain('№ 410'));
  });

  it('машину можно не указывать: контрактные агрегаты возят партиями без машин', async () => {
    render(<DonorScreen online canExpect reference={reference()} onChanged={() => {}} />);
    fireEvent.click((await waitFor(() =>
      screen.getAllByRole('button', { name: 'Ожидаемый товар' })))[0]!);
    const create = await waitFor(() =>
      screen.getByRole('button', { name: 'Завести' }) as HTMLButtonElement);

    // Машина не выбрана — и причины отказа про машину нет.
    fireEvent.change(screen.getByLabelText('Вид детали'), { target: { value: 'двигатель' } });
    fireEvent.change(screen.getByLabelText('Количество'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Цена, ₽'), { target: { value: '45000' } });
    await waitFor(() => expect(create.disabled).toBe(false));
    expect(document.body.textContent).not.toContain('Выберите машину');

    fireEvent.click(create);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toEqual({ rawName: 'двигатель', donorId: null, quantity: 1, price: 45000,
      requestId: expect.any(String) });
  });

  it('ключ запроса: тот же при повторе после отказа, новый на следующую позицию', async () => {
    const keys: string[] = [];
    let attempt = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/supplies/19/expected-parts') && init?.method === 'POST') {
        keys.push(JSON.parse(String(init.body)).requestId);
        attempt += 1;
        if (attempt === 1) {
          return new Response(JSON.stringify({ message: 'Сервер занят' }),
            { status: 503, headers: { 'Content-Type': 'application/json' } });
        }
        return json([]);
      }
      if (url.includes('/supplies/19/donors')) return json([]);
      return json([]);
    }));
    render(<DonorScreen online canExpect reference={reference()} onChanged={() => {}} />);
    fireEvent.click((await waitFor(() =>
      screen.getAllByRole('button', { name: 'Ожидаемый товар' })))[0]!);
    const create = await waitFor(() =>
      screen.getByRole('button', { name: 'Завести' }) as HTMLButtonElement);

    fireEvent.change(screen.getByLabelText('Вид детали'), { target: { value: 'двигатель' } });
    fireEvent.change(screen.getByLabelText('Цена, ₽'), { target: { value: '45000' } });
    await waitFor(() => expect(create.disabled).toBe(false));

    fireEvent.click(create);
    await waitFor(() => expect(keys).toHaveLength(1));
    await waitFor(() => expect(create.disabled).toBe(false));
    fireEvent.click(create);
    await waitFor(() => expect(keys).toHaveLength(2));
    // Повтор той же позиции несёт тот же ключ: сервер ответит первым результатом.
    expect(keys[1]).toBe(keys[0]);

    // Следующая позиция — новый ключ.
    await waitFor(() => expect((screen.getByLabelText('Вид детали') as HTMLInputElement).value).toBe(''));
    fireEvent.change(screen.getByLabelText('Вид детали'), { target: { value: 'фара' } });
    fireEvent.change(screen.getByLabelText('Цена, ₽'), { target: { value: '9000' } });
    await waitFor(() => expect(create.disabled).toBe(false));
    fireEvent.click(create);
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(keys[2]).not.toBe(keys[0]);
  });

  it('ожидаемая дата сохраняется и называет, что сдвинется', async () => {
    render(<DonorScreen online canExpect reference={reference()} onChanged={() => {}} />);
    fireEvent.click((await waitFor(() =>
      screen.getAllByRole('button', { name: 'Ожидаемый товар' })))[0]!);

    const field = await waitFor(() => screen.getByLabelText('Ожидаемая дата прихода'));
    expect(document.body.textContent).toContain('переносит срок резерва открытых предзаказов');
    const save = screen.getByRole('button', { name: 'Сохранить дату' }) as HTMLButtonElement;
    // Пока дату не поменяли, сохранять нечего.
    expect(save.disabled).toBe(true);

    fireEvent.change(field, { target: { value: '2030-02-03' } });
    await waitFor(() => expect(save.disabled).toBe(false));
    fireEvent.click(save);
    await waitFor(() => expect(dated).toEqual({ expectedOn: '2030-02-03' }));
  });
});

describe('приёмка принимает заведённую заранее позицию, а не новую', () => {
  afterEach(cleanup);

  it('позиция по выбранной поставке предложена, уходит с partId и количеством', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={phoneReference()} onSend={onSend} />);

    // Пока поставка не выбрана — предложить нечего.
    expect(screen.queryByText(/Ожидалось по этой поставке/)).toBeNull();

    selectByOption('— выберите склад —', '2');
    selectByOption('не указана', '19');
    expect(screen.getByText(/Ожидалось по этой поставке/)).toBeTruthy();
    expect(screen.getByText('Фара левая Toyota Camry')).toBeTruthy();
    // Чужая поставка в список не попала.
    expect(screen.queryByText('Дверь чужая')).toBeNull();

    fireEvent.change(screen.getByLabelText('Пришло, шт'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Принять' }));
    expect(screen.getByText('в партии')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Отправить партию' }));
    const [payload] = onSend.mock.calls[0]!;
    expect(payload.items).toEqual([expect.objectContaining({
      partId: 5, quantity: 2, rawName: 'Фара левая Toyota Camry', price: 9000,
    })]);
  });

  it('без склада принять нельзя — и сказано почему', () => {
    render(<IntakeScreen reference={phoneReference()} onSend={vi.fn()} />);
    selectByOption('не указана', '19');

    const accept = screen.getByRole('button', { name: 'Принять' }) as HTMLButtonElement;
    expect(accept.disabled).toBe(true);
    expect(document.body.textContent).toContain('Выберите склад');
  });

  it('кэш телефона прежней версии, без ожидаемых позиций, не ломает экран', () => {
    const old = phoneReference() as unknown as Record<string, unknown>;
    delete old.expectedParts;
    render(<IntakeScreen reference={old as never} onSend={vi.fn()} />);
    selectByOption('не указана', '19');

    expect(screen.queryByText(/Ожидалось по этой поставке/)).toBeNull();
  });
});

function selectByOption(optionText: string, value: string): void {
  const select = [...document.querySelectorAll('select')]
    .find((s) => [...s.options].some((o) => o.textContent === optionText))!;
  fireEvent.change(select, { target: { value } });
}

function reference(): never {
  return {
    warehouses: [], donors: [], cells: [], partNames: [],
    supplies: [
      { id: 19, kind: 'CONTAINER', number: '18', supplierName: 'Иокогама',
        status: 'EXPECTED', arrivedOn: null, expectedOn: null },
      { id: 18, kind: 'CONTAINER', number: '17', supplierName: null,
        status: 'ARRIVED', arrivedOn: '2026-08-01', expectedOn: null },
    ],
  } as never;
}

function phoneReference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [
      { id: 19, number: '18', supplierName: 'Иокогама', kind: 'CONTAINER',
        status: 'EXPECTED', arrivedOn: null, expectedOn: null },
      { id: 17, number: '16', supplierName: 'DDI-22', kind: 'CONTAINER',
        status: 'EXPECTED', arrivedOn: null, expectedOn: null },
    ],
    donors: [],
    partNames: [],
    expectedParts: [
      { id: 5, supplyId: 19, title: 'Фара левая Toyota Camry', price: 9000, remaining: 3 },
      { id: 6, supplyId: 17, title: 'Дверь чужая', price: 4000, remaining: 1 },
    ],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
