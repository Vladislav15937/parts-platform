import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FeedsScreen } from './FeedsScreen';

/**
 * Ошибка из размонтированного колбэка никуда не возвращается: эффект зовёт
 * {@code void listWarehouses()…}, и отклонённое обещание всплывает только
 * необработанным — поймать его можно лишь на хосте, где выполняется vitest.
 *
 * <p>Объявлено здесь, а не через {@code @types/node}: типов Node в проекте
 * нет ни одной строкой, и заводить зависимость ради двух вызовов дороже,
 * чем назвать то, чем пользуемся. Тот же приём, что в
 * {@code inventoryMountedGuard.test.tsx}.
 */
declare const process: {
  on(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
  off(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
};

/**
 * Запас по времени у проверок StrictMode задан явно, и это не «на всякий
 * случай».
 *
 * <p>Умолчание `waitFor` — секунда, и её не хватает под нагрузкой: разбор
 * поймал 1141 мс и три падения из девяти полных прогонов, всегда на этом
 * же месте. Цепочка тут длиннее обычной — двойное монтирование StrictMode,
 * кэш в IndexedDB и четыре запроса, — и в секунду укладывается не всегда.
 *
 * <p>Ирония, ради которой это и записано: тест, написанный против
 * случайных падений, сам падал случайно. Тонкий запас по времени — тот же
 * класс, что и незакрытый сторож: зелено, пока машина свободна.
 */
const ЖДАТЬ = { timeout: 5000 };

/**
 * Сторож «экран ещё на месте» на экране выгрузок.
 *
 * <p><b>Почему именно этот экран.</b> Он ронял прогон живьём: 512 тестов
 * проходили, а vitest выходил с кодом 1 из-за трёх необработанных отказов
 * {@code ReferenceError: window is not defined} — и сообщались они
 * из <b>чужого</b> файла ({@code feedInstallationNote.test.tsx}), потому что
 * колбэк догонял среду, которую vitest уже снёс под следующий тест. Тот же
 * код от этого зелен и красен через раз: {@code main} падала на дереве,
 * побайтово равном зелёному.
 *
 * <p>У сторожа два обязательства, и каждое проверяется своей стороной —
 * поодиночке любое из них выполняется тривиально и неправильно:
 *
 * <ul>
 *   <li><b>Пока экран на месте, колбэки работают.</b> Ловится
 *       {@code StrictMode}: в разработке он прогоняет эффекты дважды
 *       (setup → cleanup → setup), и сторож, который только снимается,
 *       остаётся {@code false} навсегда. Экран при этом не падает — он
 *       молчит: «Загружаем выгрузки…» не сменяется списком.</li>
 *   <li><b>Уйдя с вкладки, колбэки молчат.</b> Ловится сносом среды:
 *       {@code cleanup()} сразу после отрисовки и снятый {@code window} —
 *       так vitest убирает среду между файлами.</li>
 * </ul>
 *
 * <p><b>Почему полный прогон это не ловит.</b> React 18 на правку состояния
 * размонтированного компонента не говорит ничего — предупреждение убрали
 * в 18.0. Обычный тест размонтирует экран в {@code afterEach}, когда среда
 * ещё цела и колбэки уже отработали, поэтому набор остаётся зелёным при обеих
 * поломках: разбор хотфикса #56 прогнал тот же набор восемь раз на сломанном
 * коде, и все восемь были зелёными.
 */
describe('сторож размонтирования на выгрузках', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);

      if (url.startsWith('/api/marketplace-accounts')) {
        // Удалённые приходят отдельным ответом и в обычном списке их нет.
        return json(url.includes('deleted=true') ? [] : [feed()]);
      }
      // Склады: их два, иначе отбор по складу не показывается вовсе.
      if (url.startsWith('/api/organization/warehouses')) {
        return json([{ id: 1, name: 'Ткацкая' }, { id: 2, name: 'Щёлковская' }]);
      }
      // Справочник машин: марки берутся отсюда, и это самая длинная цепочка
      // на экране — пустой кэш IndexedDB, потом запрос. Она и проигрывала
      // гонку в живом прогоне.
      if (url.startsWith('/api/catalog/vehicles')) {
        return json({
          brands: [{ id: 3, slug: 'toyota', name: 'Toyota', nameRu: 'Тойота' }],
          models: [],
          generations: [],
        });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('в StrictMode список выгрузок доезжает, а не висит на «Загружаем выгрузки…»', async () => {
    render(<StrictMode><FeedsScreen role="OWNER" /></StrictMode>);

    await waitFor(() => expect(screen.getByDisplayValue('Дром: основной')).toBeTruthy(), ЖДАТЬ);
    expect(screen.queryByText('Загружаем выгрузки…')).toBeNull();
  });

  it('в StrictMode отбор по складу заполняется, а не остаётся пустым', async () => {
    // Склады грузятся вторым эффектом и попадают в форму отбора. Замолчавший
    // сторож оставил бы её пустой при исправном сервере — владелец решил бы,
    // что складов у него нет, и выгрузил бы весь склад вместо одного.
    render(<StrictMode><FeedsScreen role="OWNER" /></StrictMode>);

    await waitFor(() => expect(screen.getByLabelText('Ткацкая')).toBeTruthy(), ЖДАТЬ);
    expect(screen.getByLabelText('Щёлковская')).toBeTruthy();
  });

  it('в StrictMode марка находится в отборе, а не пропадает вместе с кэшем', async () => {
    // Та самая цепочка из FeedsScreen.tsx, что роняла прогон: пустой кэш
    // IndexedDB → запрос справочника → setBrands. Отбор марок ищет по уже
    // загруженному списку, поэтому найденная «Тойота» и есть доказательство,
    // что запись состояния дошла.
    render(<StrictMode><FeedsScreen role="OWNER" /></StrictMode>);

    const brandSearch = await waitFor(() => brandQueryField(), ЖДАТЬ);
    fireEvent.change(brandSearch, { target: { value: 'той' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'Тойота' })).toBeTruthy(), ЖДАТЬ);
  });

  it('уход с вкладки посреди загрузки не будит снесённую среду', async () => {
    const escaped: unknown[] = [];
    const collect = (reason: unknown): void => { escaped.push(reason); };
    process.on('unhandledRejection', collect);

    try {
      // Отрисовали и в тот же тик ушли с вкладки: ни один из четырёх
      // запросов эффекта ещё не вернулся.
      render(<FeedsScreen role="OWNER" />);
      cleanup();

      // Так vitest убирает среду между файлами — а колбэки ещё в пути.
      const globals = globalThis as unknown as { window?: unknown };
      const savedWindow = globals.window;
      delete globals.window;
      await sleep(500);
      globals.window = savedWindow;

      expect(escaped).toEqual([]);
    } finally {
      process.off('unhandledRejection', collect);
    }
  });
});

/** Пауза без Node API: в `src/` их нет ни одной, и заводить не за чем. */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

/**
 * Поле поиска в отборе марок.
 *
 * <p>По подписи его не найти: «Найти» подписаны оба выбора — и наименования,
 * и марки. Идём от легенды: она называет, о каком справочнике речь.
 */
function brandQueryField(): HTMLInputElement {
  const picker = Array.from(document.querySelectorAll('fieldset.picker'))
    .find((f) => f.querySelector('legend')?.textContent === 'Марки');
  expect(picker).toBeTruthy();
  const field = picker?.querySelector('input');
  expect(field).toBeTruthy();
  return field as HTMLInputElement;
}

function feed() {
  return {
    id: 7,
    marketplace: 'DROM',
    title: 'Дром: основной',
    productLine: 'PART',
    status: 'ACTIVE',
    hasFeed: true,
    hasCredentials: false,
    plaintextSecret: false,
    feedFileName: null,
    lastError: null,
    settings: {
      pricePercent: null,
      priceRounding: null,
      photoLimit: null,
      installationNote: false,
      installationTemplate: null,
      expectedGoods: false,
      expectedGoodsNote: null,
    },
    priceFrom: null,
    priceTo: null,
    conditions: [],
    warehouseIds: [],
    kindIds: [],
    kindsExcluded: false,
    brandIds: [],
    brandsExcluded: false,
    filterColumns: {},
    filterWords: {},
    lastDownloadAt: null,
    deletedAt: null,
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
