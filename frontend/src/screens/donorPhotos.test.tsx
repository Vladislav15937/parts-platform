import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { DonorPhotos } from './DonorPhotos';

/**
 * Снимки машины-донора под её строкой.
 *
 * <p><b>Зачем экран.</b> Снимков у машины в системе не было вовсе: фотографии
 * хранились только у позиции. А продаётся с разборки не только деталь —
 * для двигателя и коробки покупатель смотрит на машину, с которой её снимают.
 *
 * <p>Проверяется то, что видит человек: три состояния различимы («грузим»,
 * «снимков нет» и «не смогли узнать» — последнее отдельной строкой, иначе
 * отказ сервера читается как машина без фотографий), главный снимок назван
 * словом, а не кнопкой, и действия доходят до сервера.
 */
describe('снимки машины-донора', () => {
  let calls: Array<{ url: string; method: string }> = [];
  let photos: unknown[] = [];
  let failing = false;

  beforeEach(() => {
    calls = [];
    failing = false;
    photos = [
      { photoId: 11, main: true, url: 'https://s3.example.org/mashina-1.jpg' },
      { photoId: 12, main: false, url: 'https://s3.example.org/mashina-2.jpg' },
    ];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, method: init?.method ?? 'GET' });
      if (failing) {
        return new Response('{"message":"Машина не найдена"}', { status: 404 });
      }
      if (url.endsWith('/photos') && (init?.method ?? 'GET') === 'GET') {
        return json(photos);
      }
      return new Response(null, { status: 204 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показывает снимки машины и называет главный словом', async () => {
    render(<DonorPhotos donorId={3} title="Toyota Camry 2007" />);

    await waitFor(() => expect(screen.getByText('главный')).toBeTruthy());
    expect(screen.getAllByAltText('Снимок машины')).toHaveLength(2);
    // Заголовок называет машину: блоков на экране столько же, сколько
    // раскрытых строк, и «Фотографии машины» без имени ничего не говорит.
    expect(screen.getByText('Фотографии машины: Toyota Camry 2007')).toBeTruthy();
  });

  it('говорит, что снимков нет, а не показывает пустоту', async () => {
    photos = [];
    render(<DonorPhotos donorId={3} title="Toyota Camry 2007" />);

    await waitFor(() => expect(
      screen.getByText('Снимков машины нет. Добавьте — они пойдут в объявления.')).toBeTruthy());
  });

  it('отказ сервера назван отказом, а не «снимков нет»', async () => {
    // Пустая полоса на отказе читается как машина без фотографий, и владелец
    // грузит их заново вместо того, чтобы обновить страницу.
    failing = true;
    render(<DonorPhotos donorId={3} title="Toyota Camry 2007" />);

    await waitFor(() => expect(screen.getByText('Машина не найдена')).toBeTruthy());
    expect(screen.queryByText('Снимков машины нет. Добавьте — они пойдут в объявления.'))
      .toBeNull();
  });

  it('«Сделать главным» доходит до сервера и перечитывает полосу', async () => {
    render(<DonorPhotos donorId={3} title="Toyota Camry 2007" />);
    await waitFor(() => expect(screen.getByText('главный')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Сделать главным' }));

    await waitFor(() => expect(
      calls.some((c) => c.method === 'POST'
        && c.url.includes('/api/intake/donors/3/photos/12/main'))).toBe(true));
    // Перечитывание обязательно: иначе отметка «главный» осталась бы
    // на прежнем снимке, и владелец решил бы, что нажатие не сработало.
    await waitFor(() => expect(
      calls.filter((c) => c.method === 'GET' && c.url.endsWith('/photos')).length)
      .toBeGreaterThan(1));
  });

  it('удаление доходит до сервера', async () => {
    render(<DonorPhotos donorId={3} title="Toyota Camry 2007" />);
    await waitFor(() => expect(screen.getByText('главный')).toBeTruthy());

    fireEvent.click(screen.getAllByRole('button', { name: 'Удалить' })[0] as HTMLElement);

    await waitFor(() => expect(
      calls.some((c) => c.method === 'DELETE'
        && c.url.includes('/api/intake/donors/3/photos/11'))).toBe(true));
  });

  it('говорит, кому эти снимки видны: пока наименование не отмечено — никому', async () => {
    // Загруженный снимок выглядит как снимок, который видит покупатель,
    // а до отметки наименования у выгрузки его не видит никто.
    render(<DonorPhotos donorId={3} title="Toyota Camry 2007" />);

    await waitFor(() => expect(screen.getByText(/Снимки машины-донора/)).toBeTruthy());
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
