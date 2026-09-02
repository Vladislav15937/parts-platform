import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';

/**
 * Где стоит машина — видно и правится с экрана.
 *
 * <p><b>Зачем.</b> `POST /api/intake/donors/{id}/location` написан с самого
 * начала, значение приезжало в карточку машины — а показать его было негде
 * и позвать этот путь некому. На площадке с полусотней машин «где она стоит»
 * единственный способ её найти, и держалось оно в голове того, кто её ставил.
 * Найдено перебором эндпоинтов против того, что зовёт фронтенд.
 *
 * <p>Правится прямо в строке: значение и поле ввода в одной клетке. Иначе
 * владелец жмёт кнопку и ищет, куда делась строка, — та же болезнь, что
 * с затратами, открывавшимися за одиннадцать экранов ниже.
 */
describe('где стоит машина', () => {
  let sent: string | null;
  let listed: number;

  beforeEach(() => {
    sent = null;
    listed = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/location')) {
        sent = url;
        return json({});
      }
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [] });
      }
      if (url.includes('/api/intake/donors')) {
        listed += 1;
        return json([
          {
            id: 7, code: '350', brand: 'Toyota', model: 'Camry', year: 2007,
            vin: null, status: 'PURCHASED', note: null,
            location: listed > 1 ? 'ряд 2, место 14' : null,
          },
          {
            id: 8, code: '351', brand: 'Honda', model: 'Fit', year: 2011,
            vin: null, status: 'DISMANTLING', note: null, location: 'бокс 3',
          },
        ]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показывается в строке, а незаполненное названо словом', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);

    // Заполненное видно как есть: ради этого колонка и заведена.
    await waitFor(() => expect(screen.getByRole('button', { name: 'бокс 3' })).toBeTruthy());
    // А пустое — «не указано», а не пустая клетка: по пустоте не понять,
    // машину не ставили или колонка сломалась.
    expect(screen.getByRole('button', { name: 'не указано' })).toBeTruthy();
  });

  it('правится в строке и уходит на сервер', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const cell = await waitFor(() => screen.getByRole('button', { name: 'не указано' }));

    fireEvent.click(cell);
    fireEvent.change(screen.getByLabelText(/Где стоит 350/), {
      target: { value: ' ряд 2, место 14 ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(sent, 'место не ушло на сервер').not.toBeNull());
    // Пробелы срезаны, значение уехало закодированным: в адресе живут
    // запятая и пробелы.
    expect(sent).toContain('/api/intake/donors/7/location');
    expect(sent).toContain(encodeURIComponent('ряд 2, место 14'));

    // Список перечитывается: оставить на экране прежнее значит показать
    // владельцу площадку, которой уже нет.
    await waitFor(() => expect(listed, 'список машин не перечитан').toBe(2));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'ряд 2, место 14' })).toBeTruthy());
  });

  it('ищется вместе с остальными полями строки', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'бокс 3' })).toBeTruthy());

    // «Покажи всё, что стоит в боксе» — вопрос, который задают, стоя
    // на площадке. Поиск обязан отвечать по тому же, что видно в строке.
    fireEvent.change(
      screen.getByPlaceholderText('Найти машину — номер, марка, модель, заметка, место, VIN'),
      { target: { value: 'бокс' } });

    await waitFor(() => expect(screen.queryByText(/Toyota Camry/)).toBeNull());
    expect(screen.getByText(/Honda Fit/)).toBeTruthy();
  });
});

function reference(): never {
  return { warehouses: [], supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
