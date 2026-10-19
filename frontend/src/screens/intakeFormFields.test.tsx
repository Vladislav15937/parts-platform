import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';
import { IntakeScreen } from './IntakeScreen';

/**
 * Заведение позиции как у Bazon (задача 0362, ответ владельца 19.10.2026
 * «всё как у Bazon»): состояние, комментарий, заметка, производитель,
 * кросс-номера, маркировка и цвет вводятся сразу, цена необязательна.
 *
 * <p>Раньше позицию заводили «голой» и открывали карточку ради каждого поля, а
 * цена «больше нуля» не давала завести позицию вовсе: разборщики сначала
 * размечают, что снимать, и цену ставят позже.
 */
describe('поля заведения на приёмке', () => {
  afterEach(cleanup);

  it('состояние, комментарий, заметка и остальное уходят в позицию партии', () => {
    const payload = sendOne({
      state: 'CONTRACT', comment: 'Без пробега по РФ', note: 'Снимаем только оптику',
      cross: '8414012531, 8414012532', marking: 'Koito 100-12345', color: 'Белый',
      manufacturer: 'Denso',
    });

    expect(payload.items[0]).toMatchObject({
      condition: 'CONTRACT', description: 'Без пробега по РФ', note: 'Снимаем только оптику',
      crossNumbers: '8414012531, 8414012532', marking: 'Koito 100-12345', color: 'Белый',
      manufacturer: 'Denso',
    });
  });

  it('состояние не трогали — «б/у»; в списке три значения, без «восстановленной»', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);
    const select = screen.getByLabelText('Состояние') as HTMLSelectElement;
    expect(select.value).toBe('USED');
    expect([...select.options].map((o) => o.textContent)).toEqual(['новая', 'б/у', 'контрактная']);
    cleanup();

    expect(sendOne({}).items[0]!.condition).toBe('USED');
  });

  it('цену можно не вписывать: позиция добавляется и уходит с нулём', () => {
    expect(sendOne({ price: '' }).items[0]!.price).toBe(0);
    expect(sendOne({ price: '0' }).items[0]!.price).toBe(0);
  });

  it('отрицательная цена позицию не добавляет', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);
    choseWarehouse();
    typeName('Фара');
    fireEvent.change(priceInput(), { target: { value: '-5' } });
    expect((screen.getByText('Добавить в партию') as HTMLButtonElement).disabled).toBe(true);
  });

  it('производитель подставлен маркой машины и правится', () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference()} onSend={onSend} />);
    choseWarehouse();
    fireEvent.change(screen.getByLabelText('Машина'), { target: { value: '7' } });
    expect((screen.getByLabelText('Производитель') as HTMLInputElement).value).toBe('Toyota');

    typeName('Фара');
    fireEvent.click(screen.getByText('Добавить в партию'));
    fireEvent.click(screen.getByText(/Отправить/));
    expect(onSend.mock.calls[0]![0].items[0].manufacturer).toBe('Toyota');
  });

  it('подсказка у номера, кросс-номеров и маркировки стоит рядом с полем, не в его подписи', () => {
    render(<IntakeScreen reference={reference()} onSend={vi.fn()} />);
    // Подпись поля чистая — иначе поле не найти по имени.
    expect(screen.getByLabelText('Номер производителя')).toBeTruthy();
    expect(screen.getByLabelText('Маркировка')).toBeTruthy();
    const hints = screen.getAllByRole('note').map((n) => n.getAttribute('title') ?? '');
    expect(hints.some((h) => h.includes('Допустимо указывать только один номер'))).toBe(true);
    expect(hints.some((h) => h.includes('разделив их запятой'))).toBe(true);
    expect(hints.some((h) => h.includes('нанесенная на поверхность'))).toBe(true);
  });
});

describe('цена ожидаемой позиции необязательна', () => {
  let posted: Record<string, unknown> | null;

  beforeEach(() => {
    posted = null;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/supplies/19/expected-parts') && init?.method === 'POST') {
        posted = JSON.parse(String(init.body));
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

  it('без цены кнопка «Завести» живая, уходит цена 0', async () => {
    render(<DonorScreen online canExpect reference={expectedReference()} onChanged={() => {}} />);
    fireEvent.click((await waitFor(() =>
      screen.getAllByRole('button', { name: 'Ожидаемый товар' })))[0]!);
    const create = await waitFor(() =>
      screen.getByRole('button', { name: 'Завести' }) as HTMLButtonElement);

    fireEvent.change(screen.getByLabelText('Вид детали'), { target: { value: 'двигатель' } });
    fireEvent.change(screen.getByLabelText('Количество'), { target: { value: '1' } });
    await waitFor(() => expect(create.disabled).toBe(false));

    fireEvent.click(create);
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted!.price).toBe(0);
  });
});

function sendOne(set: {
  state?: string; comment?: string; note?: string; cross?: string; marking?: string;
  color?: string; manufacturer?: string; price?: string;
}) {
  const onSend = vi.fn();
  render(<IntakeScreen reference={reference()} onSend={onSend} />);
  choseWarehouse();
  typeName('Фара левая');
  fireEvent.change(priceInput(), { target: { value: set.price ?? '1500' } });
  const field = (label: string, value: string | undefined) => {
    if (value !== undefined) {
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
    }
  };
  field('Состояние', set.state);
  field('Комментарий (для клиентов)', set.comment);
  field('Заметка (для вас)', set.note);
  field('Кросс-номера', set.cross);
  field('Маркировка', set.marking);
  field('Цвет', set.color);
  field('Производитель', set.manufacturer);
  fireEvent.click(screen.getByText('Добавить в партию'));
  fireEvent.click(screen.getByText(/Отправить/));
  expect(onSend).toHaveBeenCalledTimes(1);
  cleanup();
  return onSend.mock.calls[0]![0] as {
    items: Array<Record<string, unknown> & { condition: string; price: number }>;
  };
}

function choseWarehouse() {
  const warehouse = [...document.querySelectorAll('select')]
    .find((s) => [...s.options].some((o) => o.textContent === 'Ткацкая'))!;
  fireEvent.change(warehouse, { target: { value: '2' } });
}

function typeName(name: string) {
  const nameInput = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder?.includes('фара'))!;
  fireEvent.change(nameInput, { target: { value: name } });
}

function priceInput() {
  return [...document.querySelectorAll('input')].find((i) => i.type === 'number')!;
}

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [],
    donors: [{ id: 7, code: '350', brand: 'Toyota', model: 'Camry', year: 2007, vin: null,
      status: 'DISMANTLING', note: null, location: null }],
    partNames: [],
  } as never;
}

function expectedReference(): never {
  return {
    warehouses: [], donors: [], cells: [], partNames: [],
    supplies: [
      { id: 19, kind: 'CONTAINER', number: '18', supplierName: 'Поставщик',
        status: 'EXPECTED', arrivedOn: null, expectedOn: null },
    ],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}
