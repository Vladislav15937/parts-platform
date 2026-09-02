import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorScreen } from './DonorScreen';

/**
 * Поставка: отметить приход и посмотреть, какие машины пришли партией.
 *
 * <p><b>Зачем.</b> Завести поставку экран умел, а дальше она была тупиком.
 * `POST /api/intake/supplies/{id}/arrived` не звала ни одна строка фронтенда:
 * дата прихода даже объявлена типом (`reference.ts`, `arrivedOn`) — и нигде
 * не показывалась. `GET /api/intake/supplies/{id}/donors` тоже не звал никто,
 * при том что по партии и разбирают, что из контейнера ещё не продано:
 * ради этого вопроса поставки и заведены. Оба пути написаны с самого начала
 * и покрыты тестами, то есть выглядели работающей возможностью.
 */
describe('поставка: приход и состав', () => {
  let arrived: string | null;
  let refreshed: number;
  let donorsFail: boolean;

  beforeEach(() => {
    arrived = null;
    refreshed = 0;
    donorsFail = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/arrived')) {
        arrived = url;
        return json({});
      }
      if (url.includes('/supplies/19/donors')) {
        if (donorsFail) {
          return new Response('{"message":"Запрос отклонён"}', { status: 401 });
        }
        return json([
          {
            id: 7, code: '350', brand: 'Toyota', model: 'Camry', year: 2007,
            vin: null, status: 'DISMANTLING', note: null, location: 'ряд 2',
          },
        ]);
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

  it('приход отмечается той датой, что стоит в поле', async () => {
    render(<DonorScreen online reference={reference()}
                        onChanged={() => { refreshed += 1; }} />);
    const button = await waitFor(() =>
      screen.getByRole('button', { name: 'Отметить приход' }));

    // Дата видна до нажатия и правится: контейнер отмечают и задним числом,
    // а подставленное молча сервером значение читается как факт.
    fireEvent.change(screen.getByLabelText('Дата прихода партии №18'),
      { target: { value: '2026-08-30' } });
    fireEvent.click(button);

    await waitFor(() => expect(arrived, 'приход не ушёл на сервер').not.toBeNull());
    expect(arrived).toContain('/api/intake/supplies/19/arrived');
    expect(arrived).toContain('on=2026-08-30');

    // Справочник перечитывается, иначе отметка не видна до перезагрузки
    // экрана и приёмщик жмёт кнопку второй раз.
    await waitFor(() => expect(refreshed, 'справочник не перечитан').toBe(1));
  });

  it('у приехавшей партии стоит дата, а кнопки прихода нет', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    // Дата как везде на экранах — «01.08.2026», а не «2026-08-01»: рядом
    // на этом же экране затраты по машине показаны русским порядком, и два
    // написания в одной таблице читаются как две разные величины.
    // Разбирается строкой, а не через new Date: у даты без времени тот берёт
    // полночь UTC, и западнее Гринвича показал бы вчерашний день.
    await waitFor(() => expect(screen.getByText('01.08.2026')).toBeTruthy());

    expect(screen.queryAllByRole('button', { name: 'Отметить приход' }).length,
      'приехавшую партию предлагают отметить ещё раз').toBe(1);
  });

  it('машины партии называются номером клиента, а не внутренним кодом', async () => {
    render(<DonorScreen online reference={reference()} onChanged={() => {}} />);
    const buttons = await waitFor(() => screen.getAllByRole('button', { name: 'Машины' }));

    fireEvent.click(buttons[0]!);

    // «350 · Toyota Camry 2007» — так машину зовёт владелец. Прежде этот путь
    // отдавал внутренний public_code: столбец шестнадцатеричных знаков,
    // которых клиент никогда не видел. Ровно на этом краснел отчёт
    // окупаемости.
    await waitFor(() => expect(screen.getByText(/350 · Toyota Camry 2007/)).toBeTruthy());
    expect(screen.getByText(/1 машина этой партией/)).toBeTruthy();
  });

  it('не смогли узнать состав — говорит причину, а не «машин нет»', async () => {
    donorsFail = true;
    const { container } = render(
      <DonorScreen online reference={reference()} onChanged={() => {}} />);
    const buttons = await waitFor(() => screen.getAllByRole('button', { name: 'Машины' }));

    fireEvent.click(buttons[0]!);

    // Сначала дождаться причины, и только потом проверять отсутствие «пусто».
    // Наоборот нельзя: пока на экране «Загружаем…», отрицание проходит само
    // собой, и тест зеленеет на сломанном коде — проверено удалением ветки
    // отказа, она этого не заметила.
    const reason = await waitFor(() => {
      const shown = container.querySelector('.note--error');
      expect(shown, 'отказ не показан вовсе').not.toBeNull();
      return shown;
    });
    expect(reason?.textContent ?? '').not.toBe('');

    // «Пусто» и «не смогли узнать» — разные вещи: пустая партия при истёкшей
    // сессии это утверждение, которого никто не делал.
    expect(screen.queryByText(/Машин этой партией не заводили/),
      'отказ выдан за пустую партию').toBeNull();
  });
});

function reference(): never {
  return {
    warehouses: [], donors: [], cells: [], partNames: [],
    supplies: [
      { id: 19, kind: 'CONTAINER', number: '18', supplierName: 'Иокогама',
        status: 'EXPECTED', arrivedOn: null },
      { id: 18, kind: 'CONTAINER', number: '17', supplierName: null,
        status: 'ARRIVED', arrivedOn: '2026-08-01' },
    ],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
