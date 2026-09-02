import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Ссылку на прайс можно посмотреть, не сломав её.
 *
 * <p>Показывалась она только сразу после выдачи или смены — то есть узнать
 * её потом было нельзя вовсе. Владелец завёл выгрузку, отдал адрес
 * техспециалисту площадки, через неделю адрес спросили снова: посмотреть
 * негде, а единственная кнопка — «Сменить ссылку», которая, как честно
 * написано рядом, выгрузку останавливает. Чтобы узнать ссылку, приходилось
 * её сломать.
 */
describe('ссылка на прайс', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/feed-url')) {
        // POST — смена ссылки; GET — та, что уже выдана.
        // Полный адрес рядом с путём: его копируют и отдают человеку
        // на той стороне, по одному пути тот не сходит никуда.
        const path = init?.method === 'POST'
          ? '/feeds/drom/co/novaya.xml' : '/feeds/drom/co/tekushchaya.xml';
        return json({ path, url: 'https://sklad.example.ru' + path });
      }
      if (url.includes('/api/marketplace-accounts')) {
        return json([{ id: 1, title: 'Дром: основной', marketplace: 'DROM',
                       productLine: 'PART', hasFeed: true, packetId: null,
                       priceFrom: null, priceTo: null, conditions: [], warehouseIds: [],
                       kindIds: [], kindsExcluded: false, brandIds: [], brandsExcluded: false }]);
      }
      // Справочник машин: экран берёт из него марки для отбора и падал
      // на пустом массиве вместо объекта — заглушка молчала об этом,
      // потому что первый тест успевал проверить своё до падения.
      if (url.includes('/api/catalog/vehicles')) {
        return json({ brands: [], models: [], generations: [], modifications: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показывается при раскрытии, а не только после смены', async () => {
    render(<FeedsScreen role="OWNER" />);
    const details = await waitFor(() => {
      const found = document.querySelector('details');
      expect(found).toBeTruthy();
      return found!;
    });

    // До раскрытия ссылки нет — и это верно: её незачем держать на виду.
    expect(document.body.textContent).not.toContain('tekushchaya.xml');

    (details as HTMLDetailsElement).open = true;
    fireEvent(details, new Event('toggle'));

    await waitFor(() => expect(document.body.textContent)
      .toContain('tekushchaya.xml'));
  });

  it('показывает полный адрес и даёт скачать файл', async () => {
    render(<FeedsScreen role="OWNER" />);
    const details = await waitFor(() => {
      const found = document.querySelector('details');
      expect(found).toBeTruthy();
      return found!;
    });
    fireEvent.click(details.querySelector('summary')!);

    await waitFor(() => expect(
      screen.getByText('https://sklad.example.ru/feeds/drom/co/tekushchaya.xml'),
      'показан путь без домена — техспециалист площадки по нему не сходит',
    ).toBeTruthy());

    const download = screen.getByText('Скачать файл прайса') as HTMLAnchorElement;
    expect(download.getAttribute('href'), 'скачивание ведёт не на прайс')
      .toBe('/feeds/drom/co/tekushchaya.xml');
    expect(download.hasAttribute('download')).toBe(true);
  });
});

/**
 * Скачать файл — не запасной путь, а единственный быстрый.
 *
 * <p>Забор прайса по ссылке идёт раз в трое суток, и до него новая деталь
 * на площадке не появится: API заводить товары не умеет, только обновлять
 * уже выгруженные. Значит заливка файлом руками — то, чем пользуются
 * каждый день, а ссылка была выведена простым текстом: ни нажать,
 * ни сохранить.
 */
function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
