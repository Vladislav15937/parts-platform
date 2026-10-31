import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';
import { DonorStatusesPanel } from './DonorStatusesPanel';

/**
 * Номер донора и «Общая информация» машины (задача 0364, вторая часть).
 *
 * <p>Машина называется номером, а не шестнадцатеричным кодом; в форме
 * подставлен следующий номер — подсмотр, поэтому уезжает он, только если
 * человек его изменил. Статус — из списка компании, рестайлинг, описание
 * для клиента, заметка и ссылка на видео — поля формы ориентира.
 */
describe('форма машины: номер донора и общая информация', () => {
  let posted: Record<string, unknown> | null;
  let put: Record<string, unknown> | null;
  let nextNumber: number;

  beforeEach(() => {
    posted = null;
    put = null;
    nextNumber = 512;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (url.includes('/api/catalog/vehicles')) {
        return json({
          loadedAt: new Date().toISOString(),
          brands: [{ id: 1, slug: 'toyota', name: 'Toyota', nameRu: 'Тойота' }],
          models: [{ id: 10, slug: 'camry', name: 'Camry', brandId: 1 }],
          generations: [
            { id: 100, modelId: 10, name: 'XV30', yearFrom: 2001, yearTo: 2005 },
            { id: 101, modelId: 10, name: 'XV40', yearFrom: 2006, yearTo: 2011 },
          ],
        });
      }
      if (url.endsWith('/api/intake/donor-vocabulary')) {
        return json({
          steering: [], transmission: [], drive: [],
          statuses: [{ code: '3', title: 'В Японии' }, { code: '4', title: 'Во Владивостоке' }],
        });
      }
      if (url.endsWith('/api/intake/donors/next-number')) {
        return json({ number: nextNumber });
      }
      if (url.endsWith('/api/intake/donors/7') && method === 'PUT') {
        put = JSON.parse(String(init?.body)) as Record<string, unknown>;
        return json({ ...details(), renamed: 0 });
      }
      if (url.endsWith('/api/intake/donors/7')) {
        return json(details());
      }
      if (url.endsWith('/api/intake/donors') && method === 'POST') {
        posted = JSON.parse(String(init?.body)) as Record<string, unknown>;
        const number = (posted.number as number | null) ?? nextNumber;
        nextNumber = number + 1;
        return json({ id: 9, number, status: 'PURCHASED', location: null, supplyId: null }, 201);
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

  it('номер подставлен; нетронутый не уезжает, сообщение называет номер, следующий подставлен снова', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const field = await screen.findByLabelText('Номер донора') as HTMLInputElement;
    await waitFor(() => expect(field.value, 'следующий номер не подставлен').toBe('512'));

    await pickToyota();
    fireEvent.click(screen.getByRole('button', { name: 'Завести машину' }));

    await waitFor(() => expect(posted, 'машина не ушла на сервер').not.toBeNull());
    // Предложенный номер — подсмотр: выдаёт его сервер, иначе две открытые
    // формы прислали бы одно число и вторая получила бы отказ.
    expect(posted?.number).toBeNull();
    await waitFor(() => expect(screen.getByText('Машина заведена: №512')).toBeTruthy());
    expect(screen.queryByText(/[0-9A-F]{12}/)).toBeNull();
    await waitFor(() => expect(field.value, 'следующий номер не обновился').toBe('513'));
  });

  it('вписанный номер уезжает', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const field = await screen.findByLabelText('Номер донора') as HTMLInputElement;
    await waitFor(() => expect(field.value).toBe('512'));
    fireEvent.change(field, { target: { value: '700' } });
    await pickToyota();
    fireEvent.click(screen.getByRole('button', { name: 'Завести машину' }));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted?.number).toBe(700);
    await waitFor(() => expect(screen.getByText('Машина заведена: №700')).toBeTruthy());
  });

  it('«Стоимость» уезжает числом у того, кто ведёт затраты; пустая — null; отрицательная не уезжает', async () => {
    // Ответ владельца 31.10.2026: «Стоимость — журналом затрат».
    render(<DonorScreen online reference={reference()} onChanged={() => {}} canRecordCost />);
    await pickToyota();
    const cost = screen.getByLabelText('Стоимость');
    fireEvent.change(cost, { target: { value: '-5' } });
    expect(screen.getByText('Стоимость — число не меньше нуля')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Завести машину' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(cost, { target: { value: '150000,5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Завести машину' }));
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted?.cost).toBe(150000.5);
    await waitFor(() => expect((cost as HTMLInputElement).value, 'стоимость осталась в форме').toBe(''));

    posted = null;
    fireEvent.click(screen.getByRole('button', { name: 'Завести машину' }));
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted?.cost).toBeNull();
  });

  it('без права вести затраты «Стоимости» нет, и она не уезжает', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await pickToyota();
    expect(screen.queryByLabelText('Стоимость')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Завести машину' }));
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted?.cost).toBeNull();
  });

  it('номер не числом — сказано, и завести нельзя', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const field = await screen.findByLabelText('Номер донора');
    await pickToyota();
    fireEvent.change(field, { target: { value: '12-А' } });

    expect(screen.getByText('Номер донора — целое число больше нуля')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Завести машину' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('статус, рестайлинг, описание, заметка и видео уезжают; смена поколения стирает рестайлинг', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await pickToyota();
    await waitFor(() => expect(screen.getByRole('option', { name: 'В Японии' })).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Статус'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Модель'), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText('Год'), { target: { value: '2003' } });
    fireEvent.change(screen.getByLabelText('Рестайлинг'), { target: { value: '1 рест.' } });
    // Год другого поколения — рестайлинг был про прежнее и стирается.
    fireEvent.change(screen.getByLabelText('Год'), { target: { value: '2008' } });
    expect((screen.getByLabelText('Рестайлинг') as HTMLInputElement).value).toBe('');
    fireEvent.change(screen.getByLabelText('Рестайлинг'), { target: { value: '2 рест.' } });
    fireEvent.change(screen.getByLabelText('Описание (для клиента)'), { target: { value: 'Целая левая сторона' } });
    fireEvent.change(screen.getByLabelText('Заметка (для вас)'), { target: { value: 'Синий маркер' } });
    fireEvent.change(screen.getByLabelText('Ссылка на видео'), { target: { value: 'https://youtu.be/abc' } });
    fireEvent.click(screen.getByRole('button', { name: 'Завести машину' }));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toMatchObject({
      statusId: 3, generationId: 101, restyling: '2 рест.',
      clientDescription: 'Целая левая сторона', note: 'Синий маркер',
      videoUrl: 'https://youtu.be/abc',
    });
  });

  it('ссылка на видео без адреса — сказано, и завести нельзя', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await pickToyota();
    fireEvent.change(screen.getByLabelText('Ссылка на видео'), { target: { value: 'видео у Пети' } });

    expect(screen.getByText(/Ссылка на видео не похожа на адрес/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Завести машину' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('карточка показывает новые поля, правка отправляет статус и номер', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Карточка' }));

    const view = (await screen.findByText('Номер донора', { selector: 'dt' })).closest('.donor-view') as HTMLElement;
    const rows: Array<[string, string]> = [
      ['Номер донора', '350'], ['Статус', 'В Японии'], ['Рестайлинг', '1 рест.'],
      ['Описание (для клиента)', 'Целая левая сторона'], ['Заметка (для вас)', 'Синий маркер'],
      ['Ссылка на видео', 'https://youtu.be/abc'],
    ];
    for (const [label, value] of rows) {
      const term = within(view).getByText(label, { selector: 'dt' });
      expect(term.nextElementSibling?.textContent, label).toBe(value);
    }

    fireEvent.click(screen.getByRole('button', { name: '✎ Изменить' }));
    const card = screen.getByRole('button', { name: 'Сохранить' }).closest('.donor-view') as HTMLElement;
    expect((within(card).getByLabelText('Номер донора') as HTMLInputElement).value).toBe('350');
    expect((within(card).getByLabelText('Статус') as HTMLSelectElement).value).toBe('3');
    fireEvent.change(within(card).getByLabelText('Статус'), { target: { value: '4' } });
    fireEvent.change(within(card).getByLabelText('Номер донора'), { target: { value: '351' } });
    fireEvent.click(within(card).getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(put).not.toBeNull());
    expect(put).toMatchObject({
      number: 351, statusId: 4, restyling: '1 рест.', clientDescription: 'Целая левая сторона',
      note: 'Синий маркер', videoUrl: 'https://youtu.be/abc',
    });
  });
});

describe('настройка «Статусы машин»', () => {
  let statuses: Array<{ id: number; name: string; donors: number }>;
  let failList: boolean;

  beforeEach(() => {
    failList = false;
    statuses = [{ id: 1, name: 'Куплена', donors: 2 }, { id: 3, name: 'В Японии', donors: 0 }];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (url.endsWith('/api/intake/donor-statuses') && method === 'POST') {
        const { name } = JSON.parse(String(init?.body)) as { name: string };
        statuses = [...statuses, { id: 9, name, donors: 0 }];
        return json(statuses);
      }
      if (url.endsWith('/api/intake/donor-statuses/1') && method === 'DELETE') {
        return json({ message: 'Статус «Куплена» выбран у машин: 2. Сначала смените им статус, потом удаляйте' }, 409);
      }
      if (url.endsWith('/api/intake/donor-statuses/3') && method === 'DELETE') {
        statuses = statuses.filter((s) => s.id !== 3);
        return json(statuses);
      }
      if (url.endsWith('/api/intake/donor-statuses/3') && method === 'PUT') {
        const { name } = JSON.parse(String(init?.body)) as { name: string };
        statuses = statuses.map((s) => (s.id === 3 ? { ...s, name } : s));
        return json(statuses);
      }
      if (url.endsWith('/api/intake/donor-statuses')) {
        if (failList) return json({ message: 'Сервер не отвечает' }, 503);
        return json(statuses);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('заводит, переименовывает и удаляет; отказ сервера назван его словами', async () => {
    render(<DonorStatusesPanel />);
    await waitFor(() => expect(screen.getByText('В Японии')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Название'), { target: { value: 'Во Владивостоке' } });
    fireEvent.click(screen.getByRole('button', { name: 'Добавить статус' }));
    await waitFor(() => expect(screen.getByText('Во Владивостоке')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Переименовать: В Японии' }));
    fireEvent.change(screen.getByLabelText('Новое название: В Японии'), { target: { value: 'В порту' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(screen.getByText('В порту')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Удалить: Куплена' }));
    await waitFor(() => expect(screen.getByText(/выбран у машин: 2/)).toBeTruthy());
    expect(screen.getByText('Куплена')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Удалить: В порту' }));
    await waitFor(() => expect(screen.queryByText('В порту')).toBeNull());
  });

  it('отказ загрузки не выдаётся за пустой список', async () => {
    failList = true;
    render(<DonorStatusesPanel />);
    await waitFor(() => expect(screen.getByText('Сервер не отвечает')).toBeTruthy());
    expect(screen.queryByText(/Статусы не заведены/)).toBeNull();
    expect(screen.queryByText('Загружаем…')).toBeNull();
  });
});

async function pickToyota(): Promise<void> {
  fireEvent.change(await screen.findByPlaceholderText('тойота, toyota'), { target: { value: 'toy' } });
  fireEvent.click(await screen.findByRole('button', { name: /Toyota/ }));
}

function details() {
  return {
    card: {
      id: 7, code: '350', status: 'В разборе', supply: null, brand: 'Toyota',
      model: 'Camry', generation: null, bodyCode: null, engineCode: null, year: 2003,
      steering: null, transmission: null, driveType: null, color: null, equipmentCode: null,
      mileageKm: null, vin: null, location: null, note: 'Синий маркер', partsCount: 3,
      statusName: 'В Японии', restyling: '1 рест.', clientDescription: 'Целая левая сторона',
      videoUrl: 'https://youtu.be/abc',
    },
    form: {
      brandId: 1, modelId: null, generationId: null, year: 2003, bodyCode: null,
      engineCode: null, steering: null, transmissionType: null, transmissionModel: null,
      driveType: null, color: null, equipmentCode: null, vin: null, mileageKm: null,
      supplyId: null, note: 'Синий маркер', number: 350, restyling: '1 рест.',
      clientDescription: 'Целая левая сторона', videoUrl: 'https://youtu.be/abc', statusId: 3,
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
