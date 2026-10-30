import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';
import { donorView } from './CatalogScreen';
import { defaultQuery } from '../inventory/catalog';

/**
 * Машина целиком одной формой и правка после заведения (задача 0364).
 *
 * <p>До неё на форме не было руля, КПП, привода, цвета, комплектации
 * и пробега, хотя сервер их принимал, — и «Комплектация» каждой снятой
 * детали оставалась пустой. Исправить машину после заведения было нечем
 * вовсе: ошибся годом — и все детали с неё наследуют неверный год.
 */
describe('карточка машины', () => {
  let posted: Record<string, unknown> | null;
  let put: Record<string, unknown> | null;
  let puts: number;
  let vocabularyFails: boolean;

  beforeEach(() => {
    posted = null;
    put = null;
    puts = 0;
    vocabularyFails = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (url.includes('/api/catalog/vehicles')) {
        return json({
          loadedAt: new Date().toISOString(),
          brands: [{ id: 1, slug: 'toyota', name: 'Toyota', nameRu: 'Тойота' }],
          models: [],
          generations: [],
        });
      }
      if (url.endsWith('/api/intake/donor-vocabulary')) {
        if (vocabularyFails) return json({ message: 'сломалось' }, 500);
        return json({
          steering: [{ code: 'LEFT', title: 'Левый руль' }, { code: 'RIGHT', title: 'Правый руль' }],
          transmission: [{ code: 'MT', title: 'МКПП' }, { code: 'AT', title: 'АКПП' }],
          drive: [{ code: 'FWD', title: 'Передний' }, { code: 'AWD', title: 'Полный' }],
        });
      }
      if (url.endsWith('/api/intake/donors/7') && method === 'PUT') {
        puts += 1;
        put = JSON.parse(String(init?.body)) as Record<string, unknown>;
        return json(details({ year: put.year as number }));
      }
      if (url.endsWith('/api/intake/donors/7')) {
        return json(details({ year: 2003 }));
      }
      if (url.endsWith('/api/intake/donors') && method === 'POST') {
        posted = JSON.parse(String(init?.body)) as Record<string, unknown>;
        return json({ id: 9, publicCode: 'A1B2C3' }, 201);
      }
      if (url.endsWith('/api/intake/donors')) {
        return json([{
          id: 7, code: '350', brand: 'Toyota', model: 'Camry', year: 2003,
          vin: null, status: 'DISMANTLING', note: null, location: null,
        }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('заводит машину со всеми полями: руль, КПП и привод — кодами из словаря сервера', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    fireEvent.change(await screen.findByPlaceholderText('тойота, toyota'), { target: { value: 'toy' } });
    fireEvent.click(await screen.findByRole('button', { name: /Toyota/ }));

    await waitFor(() => expect(screen.getByRole('option', { name: 'Правый руль' })).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Расположение руля'), { target: { value: 'RIGHT' } });
    fireEvent.change(screen.getByLabelText('Тип КПП'), { target: { value: 'AT' } });
    fireEvent.change(screen.getByLabelText('Привод'), { target: { value: 'FWD' } });
    fireEvent.change(screen.getByLabelText('Модель КПП'), { target: { value: 'u340e' } });
    fireEvent.change(screen.getByLabelText('Цвет'), { target: { value: 'Черный' } });
    fireEvent.change(screen.getByLabelText('Комплектация'), { target: { value: '500000' } });
    fireEvent.change(screen.getByLabelText('Пробег, км'), { target: { value: '158000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Завести машину' }));

    await waitFor(() => expect(posted, 'машина не ушла на сервер').not.toBeNull());
    expect(posted).toMatchObject({
      brandId: 1, steering: 'RIGHT', transmissionType: 'AT', driveType: 'FWD',
      transmissionModel: 'U340E', color: 'Черный', equipmentCode: '500000', mileageKm: 158000,
    });
    // После заведения поля пустеют: следующая машина партии — другая.
    await waitFor(() => expect((screen.getByLabelText('Комплектация') as HTMLInputElement).value).toBe(''));
  });

  it('пробег с буквами не отправляется — сказано почему', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    fireEvent.change(await screen.findByPlaceholderText('тойота, toyota'), { target: { value: 'toy' } });
    fireEvent.click(await screen.findByRole('button', { name: /Toyota/ }));
    fireEvent.change(screen.getByLabelText('Пробег, км'), { target: { value: '158 тыс' } });

    expect(screen.getByText(/Пробег — целое число километров/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Завести машину' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('словарь не загрузился — сказано, а не пустой выбор', async () => {
    vocabularyFails = true;
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await waitFor(() => expect(screen.getByText(/Руль, КПП и привод не загрузились/)).toBeTruthy());
  });

  it('карточка показывает машину словами и «N запчастей — Посмотреть» ведёт на склад', async () => {
    const opened: string[] = [];
    render(<DonorScreen online reference={reference()} onChanged={() => {}}
                        onDonorParts={(code) => opened.push(code)} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Карточка' }));

    await waitFor(() => expect(screen.getByText('Правый руль')).toBeTruthy());
    expect(screen.getByText('АКПП, U340E')).toBeTruthy();
    expect(screen.getByText(/12/).closest('.donor-view__parts')?.textContent)
      .toContain('12 запчастей');

    fireEvent.click(screen.getByRole('button', { name: 'Посмотреть' }));
    expect(opened).toEqual(['350']);
  });

  it('«карандаш» исправляет год, а нетронутое уезжает тем, что лежало', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Карточка' }));
    fireEvent.click(await screen.findByRole('button', { name: '✎ Изменить' }));

    const card = screen.getByRole('button', { name: 'Сохранить' }).closest('.donor-view') as HTMLElement;
    expect((within(card).getByLabelText('Год') as HTMLInputElement).value).toBe('2003');
    expect((within(card).getByLabelText('Расположение руля') as HTMLSelectElement).value).toBe('RIGHT');
    fireEvent.change(within(card).getByLabelText('Год'), { target: { value: '2004' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(put, 'правка не ушла на сервер').not.toBeNull());
    expect(put).toMatchObject({
      year: 2004, brandId: 1, supplyId: 5, steering: 'RIGHT', transmissionModel: 'U340E',
      mileageKm: 158000, equipmentCode: '500000',
    });
    await waitFor(() => expect(screen.getByText('Машина сохранена.')).toBeTruthy());
    expect(screen.getByText('2004')).toBeTruthy();
  });

  it('«Отменить» закрывает форму и ничего не отправляет', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Карточка' }));
    fireEvent.click(await screen.findByRole('button', { name: '✎ Изменить' }));
    const card = screen.getByRole('button', { name: 'Отменить' }).closest('.donor-view') as HTMLElement;
    fireEvent.change(within(card).getByLabelText('Год'), { target: { value: '1999' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Отменить' }));

    expect(screen.getByText('2003')).toBeTruthy();
    expect(screen.queryByText('1999')).toBeNull();
    expect(puts).toBe(0);
  });
});

describe('склад с карточки машины', () => {
  it('снимает прочие отборы, показывает отсутствующие и включает колонку номера донора', () => {
    const base = defaultQuery(50);
    const view = donorView(
      { query: { ...base, q: 'фара', missing: false, columns: { brand: 'Ford' } }, visible: ['title'] },
      '350');

    expect(view.query.columns).toEqual({ donor: '350' });
    expect(view.query.q).toBe(base.q);
    // Плитка считает и проданное — без отсутствующих число разошлось бы со строками.
    expect(view.query.missing).toBe(true);
    expect(view.visible).toContain('donor');
  });
});

function details({ year }: { year: number }) {
  return {
    card: {
      id: 7, code: '350', status: 'В разборе', supply: 'Контейнер №16', brand: 'Toyota',
      model: 'Camry', generation: null, bodyCode: 'ACV30', engineCode: null, year,
      steering: 'Правый руль', transmission: 'АКПП, U340E', driveType: 'Передний',
      color: 'Черный', equipmentCode: '500000', mileageKm: 158000, vin: null,
      location: 'ряд 2', note: null, partsCount: 12,
    },
    form: {
      brandId: 1, modelId: null, generationId: null, year, bodyCode: 'ACV30',
      engineCode: null, steering: 'RIGHT', transmissionType: 'AT', transmissionModel: 'U340E',
      driveType: 'FWD', color: 'Черный', equipmentCode: '500000', vin: null,
      mileageKm: 158000, supplyId: 5, note: null,
    },
  };
}

function reference(): never {
  return { warehouses: [], supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
