import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';

/**
 * Витрина помнит, как её разложили, — и умеет вернуться к исходному виду.
 *
 * <p><b>Что было для человека.</b> Выбранная сортировка не запоминалась нигде,
 * а состав колонок лежал в `localStorage` — то есть настройка таблицы, работа
 * на несколько минут, пропадала при переходе на другой компьютер или
 * в другой браузер. Решение владельца продукта от 11 сентября 2026 названо
 * дословно: помнить «для каждого отдельного пользователя всегда. Даже если он
 * закрыл браузер, выключил компьютер, открыл в другом браузере или из другого
 * места». Это прямо означает сервер: `localStorage` не даёт ни одного
 * из трёх последних случаев.
 *
 * <p><b>И ровно поэтому — кнопки сброса.</b> Запомненный отбор опаснее всего:
 * владелец открывает склад через неделю, видит «Ничего не найдено» и решает,
 * что склад пуст или сломан. Отборы колонок снимались только в шапке таблицы,
 * а при пустой выдаче таблицы нет вовсе.
 */
describe('память витрины и сброс', () => {
  /** Что «лежит на сервере» у этого сотрудника. */
  let stored: Record<string, unknown> | null = null;
  /** Тела всех записей настройки — по ним видно, что именно запомнилось. */
  let written: Array<Record<string, unknown>> = [];
  /** Адреса запросов к самой витрине — по ним виден применённый отбор. */
  let asked: string[] = [];

  beforeEach(() => {
    stored = null;
    written = [];
    asked = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/me/settings/catalog')) {
        if (init?.method === 'PUT') {
          const body = JSON.parse(String(init.body)) as Record<string, unknown>;
          written.push(body);
          stored = body;
          return json({ screen: 'catalog', value: body });
        }
        return json({ screen: 'catalog', value: stored });
      }
      if (url.includes('/api/parts/catalog?')) {
        asked.push(url);
        // Отбор «Toyota» на этом складе не находит ничего: так и выглядит
        // вчерашний отбор, из-за которого владелец решает, что склад пуст.
        const empty = url.includes('filter=brand');
        return json({
          total: empty ? 0 : 1,
          warehouses: [],
          filterable: ['brand', 'code'],
          rows: empty ? [] : [catalogRow()],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('открывается порядком заведения, а не случайным кодом', async () => {
    render(<CatalogScreen role="OWNER" />);

    await waitFor(() => expect(asked.length).toBeGreaterThan(0));
    // До задачи 0060 здесь стояло sort=code&desc=true, то есть порядок
    // шести случайных байт: первые пятьдесят строк из тридцати пяти тысяч
    // выбирал жребий, и новый при каждом открытии.
    expect(asked[0], `витрина запросила «${asked[0]}»`).toContain('sort=number');
    expect(asked[0]).toContain('desc=false');
  });

  it('восстанавливает сохранённое за сотрудником, а не умолчание', async () => {
    stored = {
      visible: ['number', 'title'],
      sort: 'price', desc: true, q: '', reserved: true, missing: false,
      warehouses: [], columns: { brand: 'Toyota' }, words: {},
      vehicle: {
        brandId: null, brandName: '', modelId: null, modelName: '', body: '', engine: '',
      },
    };

    render(<CatalogScreen role="OWNER" />);

    await waitFor(() => expect(asked.length).toBeGreaterThan(0));
    // Никакого запроса умолчанием до восстановленного: иначе владелец
    // успевает прочитать первую выдачу и решить, что отбор слетел.
    expect(asked, 'склад успели запросить умолчанием до восстановления настройки')
      .toHaveLength(1);
    expect(asked[0]).toContain('sort=price');
    expect(asked[0]).toContain('filter=brand%3AToyota');
  });

  it('запоминает выбранное на сервере, а не в браузере', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(asked.length).toBeGreaterThan(0));

    click('Показывать: отсутствующие');
    await waitFor(() => expect(written.length).toBeGreaterThan(0));

    expect(written[written.length - 1]?.missing,
      'выбранное не уехало на сервер — в другом браузере его не будет').toBe(true);
  });

  it('при пустой выдаче кнопка сброса на экране есть, и она возвращает склад',
    async () => {
      stored = {
        visible: ['number', 'title'],
        sort: 'number', desc: false, q: 'фара', reserved: true, missing: false,
        warehouses: [], columns: { brand: 'Toyota' }, words: {},
        vehicle: {
          brandId: null, brandName: '', modelId: null, modelName: '', body: '', engine: '',
        },
      };

      render(<CatalogScreen role="OWNER" />);
      await waitFor(() => expect(asked.length).toBeGreaterThan(0));
      // Таблицы на экране нет — отбор действует, а снять его было нечем:
      // до задачи 0060 он снимался только в шапке таблицы.
      expect(document.body.textContent).toContain('Ничего не найдено');

      const reset = button('Сбросить отбор');
      expect(reset, 'при пустой выдаче снять отбор нечем').toBeTruthy();
      reset!.click();

      await waitFor(() => expect(asked.length).toBeGreaterThan(1));
      const last = asked[asked.length - 1];
      expect(last, 'отбор по колонке пережил сброс').not.toContain('filter=brand');
      // И набранный поиск тоже: «нажимающий „сбросить“ хочет увидеть весь
      // склад» (решение владельца продукта от 11 сентября 2026).
      expect(last, 'набранный поиск пережил сброс').not.toContain('q=');
      expect(document.body.textContent).not.toContain('Ничего не найдено');
    });

  it('сброс сортировки появляется только когда порядок не по умолчанию',
    async () => {
      render(<CatalogScreen role="OWNER" />);
      await waitFor(() => expect(asked.length).toBeGreaterThan(0));

      // Кнопка, которая ничего не меняет, хуже отсутствующей.
      expect(button('Сбросить сортировку'),
        'кнопка предлагает сбросить порядок, который и так по умолчанию')
        .toBeUndefined();

      stored = null;
      cleanup();
      asked = [];
      stored = {
        visible: ['number', 'title'],
        sort: 'price', desc: true, q: '', reserved: true, missing: false,
        warehouses: [], columns: {}, words: {},
        vehicle: {
          brandId: null, brandName: '', modelId: null, modelName: '', body: '', engine: '',
        },
      };
      render(<CatalogScreen role="OWNER" />);
      await waitFor(() => expect(asked.length).toBeGreaterThan(0));

      const reset = button('Сбросить сортировку');
      expect(reset, 'порядок задан, а вернуть умолчание нечем').toBeTruthy();
      reset!.click();

      await waitFor(() => expect(asked[asked.length - 1]).toContain('sort=number'));
    });
});

/** Нажатие по тексту кнопки или по подписи флажка — как это делает человек. */
function click(text: string): void {
  if (text.startsWith('Показывать: ')) {
    const label = [...document.querySelectorAll('label')]
      .find((el) => el.textContent?.trim() === text.slice('Показывать: '.length));
    (label?.querySelector('input') as HTMLInputElement).click();
    return;
  }
  button(text)!.click();
}

function button(text: string): HTMLElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((el) => el.textContent?.trim() === text) as HTMLElement | undefined;
}

function catalogRow() {
  return {
    id: 1, number: 1, code: 'A1', title: 'Фара', qualityGrade: null, condition: 'USED',
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: null, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, cellCode: null,
    sideLr: null, sideFr: null,
    qty: 1, oem: null, crosses: null, photoUrl: null, supply: null, equipment: null,
    partName: null, published: null, barcode: null, legacyCode: null,
    videoUrl: null, textBlock: null, weightKg: null, dimensions: null,
    packageDimensions: null, packageWeightKg: null,
    createdAt: null, updatedAt: null, updatedByName: null,
    priceChangedAt: null, priceChangedByName: null, photoCount: 0, stock: {},
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
