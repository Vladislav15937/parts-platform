import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';
import { CustomersScreen } from './CustomersScreen';
import { DealsScreen } from './DealsScreen';
import { DeliveryScreen } from './DeliveryScreen';
import { DonorScreen } from './DonorScreen';
import { FeedsScreen } from './FeedsScreen';
import { ImportScreen } from './ImportScreen';
import { IntakeScreen } from './IntakeScreen';
import { InventoryReconcile } from './InventoryReconcile';
import { InventoryScreen } from './InventoryScreen';
import { LabelsScreen } from './LabelsScreen';
import { MembersScreen } from './MembersScreen';
import { OrdersScreen } from './OrdersScreen';
import { OrganizationScreen } from './OrganizationScreen';
import { OutboxScreen } from './OutboxScreen';
import { ReportsScreen } from './ReportsScreen';
import { ReturnsScreen } from './ReturnsScreen';
import { SellerScreen } from './SellerScreen';
import { SettingsScreen } from './SettingsScreen';
import { StockMovesScreen } from './StockMovesScreen';
import { UnmatchedScreen } from './UnmatchedScreen';
import { WheelsScreen } from './WheelsScreen';
import { ReferencePanel } from '../reference/ReferencePanel';
import { TABS, type Tab } from './tabs';
import {
  PHONE_WIDTH, closeBrowser, measurePage, measureWidths, openBrowser,
} from '../test/narrowViewport';

/**
 * Ни один раздел приложения не уезжает вбок на телефоне (задача 0032).
 *
 * <p><b>Как выглядело для человека.</b> Владелец открывает раздел с телефона,
 * ведёт список вниз — и страница всё время сползает вбок: уезжает не таблица
 * внутри своей обёртки, а вся страница вместе с рельсом и шапкой. Чем длиннее
 * список, тем труднее по нему идти, а приёмщик и продавец работают с телефона.
 *
 * <p><b>Почему перебором, а не по одному экрану.</b> За два дня нашлись три
 * находки одного класса — общий `div.row` (0027), таблицы без `table-scroll`
 * (0030), список выбора и подпись в рамке отбора (0031), — и каждая нашлась
 * только потому,
 * что кто-то догадался померить именно этот экран. Разделов больше двадцати,
 * и число растёт каждым кругом: поймать четвёртый глазами — вопрос везения.
 * То же самое было с эндпоинтами без экрана, и остановил это перебор
 * (`tools/endpoint-coverage.py`), а не внимательность.
 *
 * <p><b>Список разделов — тот же, что у рельса</b> (`tabs.ts`). Переписанный
 * рядом, он разошёлся бы с рельсом на первом же новом экране — и разошёлся бы
 * молча, оставив новый раздел без сторожа. Отрисовка каждого раздела своя
 * (свойства у экранов разные), но `RENDERERS` объявлен `Record<Tab, …>`:
 * новый раздел, добавленный в рельс, валит сборку, пока его сюда не внесли.
 *
 * <p><b>Меряет настоящий браузер.</b> jsdom раскладку не считает вовсе —
 * `scrollWidth` и `clientWidth` у него всегда 0, — и такой тест зеленел бы
 * на любой вёрстке. Разметку экраны дают настоящую: они отрисовываются здесь
 * же, а измеряется их разметка в настоящей оболочке приложения.
 *
 * <p><b>Чего он не меряет — чтобы на него не полагались шире, чем он есть.</b>
 * Раздел меряется в том виде, в каком открывается: карточка позиции, открытая
 * выгрузка, раскрытые затраты машины и витрина с непустой выдачей — это уже
 * другие состояния, и сюда они не входят. Ширина, которую даёт содержимое,
 * держится на фикстурах ниже: где список наполняет клиент, там в ответе стоит
 * то, что бывает у живого клиента.
 */

/**
 * Разделы, которые уезжают вбок **сегодня**, с причиной и номером задачи.
 *
 * <p>Это очередь работы, а не разрешение, — как `ПРОБЕЛ`
 * в `tools/endpoint-coverage.py`. Пометка без причины не принимается,
 * и список обязан пустеть.
 *
 * <p>Поэтому раздел отсюда меряется наоборот: он **обязан** оказаться шире
 * экрана. Починили — тест падает и требует убрать строку: список известных
 * поломок, который зеленеет и после починки, через месяц перестают читать.
 *
 * <p><b>Сегодня список пуст.</b> «Машины» и «Сотрудники» ушли отсюда
 * задачей 0030 (572 → 390 и 608 → 390), «Отчёты» и «Выгрузки» — задачей 0031
 * (589 → 390 и 541 → 390). У последних двух причина оказалась разной,
 * хотя обе про элемент, чью ширину задаёт содержимое: у «Отчётов» это
 * `<select>` с пунктом «404 · Volkswagen Transporter T5 2009» — 552 пикселя
 * при 322 доступных, у «Выгрузок» — поле в рамке отбора с неразрывной
 * подписью, а `fieldset` в таблице стилей браузера растёт под свой минимум
 * (`min-inline-size: min-content`), а не обрезает его.
 */
const KNOWN_WIDE: Partial<Record<Tab, string>> = {};

/**
 * Раздел, который уезжает вбок **не везде**, — и потому не проверяется никак.
 *
 * <p>«Склады» нашёл этот самый перебор, и нашёл на первом же прогоне CI:
 * на рабочей машине раздел укладывается впритык (390 из 390), а на раннере
 * даёт 415. Разница не в коде — в шрифтах: Noto Sans приложение тянет
 * из сети, в замере её нет, и подстановка у macOS и ubuntu разная. То есть
 * раздел стоит на самой границе и переваливает за неё от смены шрифта,
 * масштаба или длины названия склада у клиента.
 *
 * <p>Строгая проверка тут невозможна ни в одну сторону: «обязан быть шире»
 * упадёт на рабочей машине, «обязан помещаться» — на CI. Мягкая — это
 * честное «не знаем», а не разрешение, и уйти строка отсюда должна вместе
 * с починкой.
 *
 * <p><b>Сегодня список пуст.</b> «Склады» починены задачей 0030 вместе
 * с «Машинами» и «Сотрудниками» — это и был тот третий табличный экран,
 * о котором задача предупреждала, что поиском по коду он не найдётся.
 * Мерятся они теперь строго, как все: обёрнутая таблица укладывается
 * и в 390, и в 320, и на шрифте пошире — то есть запаса хватает на разницу
 * между macOS и раннером, из-за которой раздел сюда и попал.
 */
const BORDERLINE: Partial<Record<Tab, string>> = {};

/**
 * Данные отвечают шириной, а не только формой.
 *
 * <p>`<select>` меряется по самому длинному пункту, таблица — по самой
 * длинной клетке: на пустом ответе оба помещаются куда угодно, и проверка
 * зеленела бы на сломанном экране. Поэтому списки, ширину которых задаёт
 * клиент, отвечают тем, что бывает у живого клиента: «Toyota Land Cruiser
 * Prado 2008 · №261», «Мару Групп Владивосток», «54 YARD».
 */
const DONORS = [
  {
    id: 1, code: '261', brand: 'Toyota', model: 'Land Cruiser Prado', year: 2008,
    vin: 'JTEBH9FJ40K012345', status: 'DISMANTLING', note: 'Синий маркер!!!',
    location: 'Ряд 3, место 12',
  },
  {
    id: 2, code: '350', brand: 'Mitsubishi', model: 'Pajero Sport', year: 2011,
    vin: null, status: 'DISMANTLED', note: 'ACV40 2AZFE', location: null,
  },
  // Купленная машина, у которой в строке стоят обе кнопки: «В разбор»
  // и «Затраты». Это обычное состояние — машины покупают быстрее,
  // чем разбирают.
  {
    id: 3, code: '404', brand: 'Volkswagen', model: 'Transporter T5', year: 2009,
    vin: 'WV2ZZZ7HZ9H123456', status: 'PURCHASED', note: 'Эвакуатор оплачен',
    location: 'Площадка 2, ряд 5',
  },
];

/**
 * Склады — с длинным названием, потому что называет их клиент.
 *
 * <p>«Ткацкая» и «Дальний» помещаются в телефон при любой вёрстке, и на них
 * проверка молчала: `<select>` в Chrome шириной с самый длинный пункт, а тут
 * пункт короткий. У живого клиента склад называется «Основной склад
 * на Ткацкой, бокс 3 (второй этаж)» — и на нём «Колёса» уезжали вбок на 529
 * при 390 (задача 0041), а «Выгрузки» до того на 465 при 390 (задача 0031).
 *
 * <p>До 0041 длинное имя стояло в отдельной проверке, только для «Выгрузок»:
 * в общей фикстуре оно валило «Колёса», а это была другая задача. Теперь
 * исключений нет, и имя стоит в общей — то есть длину меряют **все** разделы,
 * а не тот, про который вспомнили. Это ровно то, ради чего заведён перебор.
 */
const WAREHOUSES = [
  {
    id: 1, branchId: 1, name: 'Основной склад на Ткацкой, бокс 3 (второй этаж)',
    branchName: 'Основной склад', cells: 128,
  },
  { id: 2, branchId: 1, name: '54 YARD', branchName: 'Основной склад', cells: 0 },
];

const SUPPLIES = [
  {
    id: 1, kind: 'CONTAINER', number: '18', supplierName: 'Мару Групп Владивосток',
    status: 'ARRIVED', arrivedOn: '2026-08-30',
  },
];

const MEMBERS = [
  {
    id: 1, login: 'vladimir.petrov', displayName: 'Владимир Петров',
    role: 'OWNER', active: true, lastLoginAt: '2026-09-05T20:01:00Z',
  },
];

const FEEDS = [
  {
    id: 1, marketplace: 'DROM', title: 'Дром · низкая цена размещения',
    status: 'ACTIVE', hasCredentials: true, plaintextSecret: false, hasFeed: true,
    feedFileName: 'drom-parts.xml', productLine: 'PART',
    settings: { pricePercent: -20, priceRounding: 100, photoLimit: 10 },
    lastError: null, lastDownloadAt: '2026-09-05T20:01:00Z', deletedAt: null,
    priceFrom: null, priceTo: null, conditions: [], warehouseIds: [],
    kindIds: [], kindsExcluded: false, brandIds: [], brandsExcluded: false,
    filterColumns: {}, filterWords: {},
  },
];

/**
 * Сделки для раздела «Сделки»: шесть колонок, и ширину им задаёт содержимое —
 * имя клиента, имя ответственного и сумма. На пустой выдаче таблицы нет вовсе,
 * и замер зеленел бы на любой вёрстке.
 */
const DEALS = {
  total: 74,
  items: [
    {
      id: 8, number: 8, createdAt: '2026-09-05T20:01:00Z',
      customerId: 3, customerName: 'Автосервис на Русской',
      totalAmount: '34500.00', paidAmount: '12000.00',
      status: 'RESERVED', reservedUntil: '2026-09-12T20:59:59Z',
      managerId: 7, managerName: 'Владимир Петров',
    },
    // Заказ с площадки: клиента у него нет, ответственного — тоже, пока
    // его не приняли. Строка обязана меряться и в таком виде.
    {
      id: 9, number: 9, createdAt: '2026-09-06T08:12:00Z',
      customerId: null, customerName: null,
      totalAmount: '4500.00', paidAmount: '4500.00',
      status: 'ISSUED', reservedUntil: null,
      managerId: null, managerName: null,
    },
  ],
};

/** Справочники приёмки — те же, что экран забирает одним запросом. */
const REFERENCE = {
  loadedAt: '2026-09-07T09:00:00Z',
  warehouses: [
    {
      id: 1, name: 'Основной склад на Ткацкой, бокс 3 (второй этаж)',
      cells: [{ id: 7, code: 'A-01-1', zone: null }],
    },
    { id: 2, name: '54 YARD', cells: [] },
  ],
  supplies: SUPPLIES,
  donors: DONORS,
  partNames: [{ id: 1, name: 'Фара передняя левая', matched: true, usageCount: 128 }],
};

/** Справочник машин: марки нужны отбору выгрузки, иначе её карточка падает. */
const VEHICLES = {
  brands: [{ id: 1, name: 'Toyota', nameRu: 'Тойота' }],
  models: [],
  generations: [],
};

/** Отчёты приходят собранными: экран читает поля итогов напрямую. */
const REPORT = {
  month: '2026-08',
  rows: [],
  totals: {
    advances: 0, debts: 0, withAdvance: 0, withDebt: 0, problems: [],
    donors: 0, totalCost: 0, revenue: 0, stockValue: 0,
  },
  parts: { qty: 0, amount: 0 },
  wheels: { qty: 0, amount: 0 },
  deals: { count: 0, amount: 0, prepaid: 0 },
};

/**
 * Пустой ответ на всё остальное: сторож меряет раздел, а не его содержимое.
 * Списки приходят массивом, страницы и отчёты — объектом, и подсунуть одно
 * вместо другого значит проверить не тот путь.
 */
const EMPTY: Record<string, unknown> = { total: 0, rows: [], warehouses: [], items: [] };

/** Путь → ответ. Первое совпадение по вхождению, поэтому порядок значим. */
const RESPONSES: Array<[string, unknown]> = [
  ['/api/intake/reference', REFERENCE],
  ['/api/intake/donors', DONORS],
  ['/api/catalog/vehicles', VEHICLES],
  ['/api/import/bazon/photos', { total: 0, pending: 0, broken: 0 }],
  ['/api/organization/warehouses', WAREHOUSES],
  ['/api/organization/branches', []],
  ['/api/reports/supplies', { rows: SUPPLIES }],
  ['/api/reports/', REPORT],
  ['/api/members', MEMBERS],
  ['/api/marketplace-accounts', FEEDS],
  ['/api/deals/registry', DEALS],
  ['/api/deals/sources', []],
  ['/api/deals/services', []],
  ['/api/deals/orders', []],
  ['/api/payment-sources', []],
  ['/api/part-names/kinds', []],
  ['/api/stock/moves', []],
];

describe('на телефоне ни один раздел не уезжает вбок', () => {
  beforeAll(async () => {
    await openBrowser();
  }, 60_000);

  afterAll(async () => {
    await closeBrowser();
  });

  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body = RESPONSES.find(([path]) => url.includes(path))?.[1] ?? EMPTY;
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  /**
   * Раздел рельса → то, что оболочка на нём показывает владельцу.
   *
   * <p>`Record<Tab, …>` здесь не украшение: новый раздел в рельсе валит
   * `tsc`, пока его не внесли сюда, — то есть завести экран мимо этой
   * проверки нельзя молча.
   */
  const RENDERERS: Record<Tab, () => JSX.Element> = {
    intake: () => <IntakeScreen reference={reference()} onSend={() => {}} />,
    donor: () => <DonorScreen reference={reference()} online onChanged={() => {}} />,
    sales: () => (
      <SellerScreen canSell role="OWNER" company="t_1" memberId={7} openDealId={null} />
    ),
    deals: () => <DealsScreen onOpenDeal={() => {}} />,
    orders: () => <OrdersScreen canSell />,
    returns: () => <ReturnsScreen onOpenDeal={() => {}} />,
    customers: () => (
      <CustomersScreen role="OWNER" company="t_1" memberId={7} onOpenDeal={() => {}} />
    ),
    catalog: () => <CatalogScreen role="OWNER" />,
    wheels: () => <WheelsScreen canIntake role="OWNER" />,
    // Владелец видит на этой вкладке оба блока сразу — журнал пересчётов
    // со сведением расхождений и обход полок сканером.
    inventory: () => (
      <>
        <InventoryReconcile reference={reference()} role="OWNER" />
        <InventoryScreen reference={reference()} onCount={() => {}} />
      </>
    ),
    moves: () => <StockMovesScreen role="OWNER" />,
    outbox: () => (
      <OutboxScreen
        records={[{
          id: 1, kind: 'receipt', title: 'Партия из 3 позиций', state: 'failed',
          lastError: 'Сервер не отвечает', attempts: 3,
        } as never]}
        needsSignIn={false}
        onRetry={() => {}}
        onDrop={() => {}}
      />
    ),
    import: () => <ImportScreen reference={reference()} canImport />,
    names: () => <UnmatchedScreen canManage onTotalChanged={() => {}} />,
    reports: () => <ReportsScreen canRead />,
    feeds: () => <FeedsScreen role="OWNER" />,
    delivery: () => <DeliveryScreen canManage onTotalChanged={() => {}} />,
    labels: () => <LabelsScreen canPrint />,
    members: () => <MembersScreen />,
    organization: () => <OrganizationScreen />,
    settings: () => <SettingsScreen />,
    reference: () => <ReferencePanel />,
  };

  /**
   * Что проверка на самом деле померила. Без этого она молча зеленеет,
   * стоит обходу разделов исчезнуть, — ровно так зеленела проверка доски,
   * не нашедшая ни одного файла.
   */
  const measured = new Set<string>();

  it.each(TABS.map((spec) => [spec.section, spec.id] as const))(
    'раздел «%s» помещается в экран 390',
    async (section, id) => {
      measured.add(id);
      const { container } = render(RENDERERS[id]());
      await waitFor(() => expect(container.textContent).not.toBe(''));

      const { scrollWidth, clientWidth } = await measurePage(await settled(container));
      const known = KNOWN_WIDE[id];

      // Раздел на границе: числа разные на разных машинах, и любое строгое
      // утверждение о нём было бы неправдой на одной из них.
      if (BORDERLINE[id] !== undefined) return;

      if (known !== undefined) {
        expect(
          scrollWidth,
          `${section}: раздел числится уезжающим вбок (${known}), а он `
          + `помещается (scrollWidth ${scrollWidth} при clientWidth ${clientWidth}). `
          + 'Починили — уберите строку из KNOWN_WIDE.',
        ).toBeGreaterThan(clientWidth);
        return;
      }

      expect(
        scrollWidth,
        `${section}: страница уезжает вбок на ${scrollWidth - clientWidth} пикселей `
        + `(scrollWidth ${scrollWidth} при clientWidth ${clientWidth}, экран ${PHONE_WIDTH})`,
      ).toBe(clientWidth);
    },
    30_000,
  );

  /**
   * Обратная сторона той же правки: поле переносится, а не сжимается.
   *
   * <p><b>Почему одной проверки ширины страницы мало.</b> Уезд вбок лечится
   * двумя способами, и один из них хуже болезни: разрешить полю сжиматься
   * (`min-width: 0`) и не оставить признака переноса. Страница тогда
   * помещается в телефон идеально — а поля формы колеса садятся в один ряд
   * по 84 пикселя, и приёмщик не видит, что вводит. Сторож выше такую
   * «починку» пропустит: он меряет страницу, а не поле.
   *
   * <p>Поэтому здесь меряется само поле. Порог — 250 пикселей при экране 390:
   * это заметно больше сжатых 84 (основа 0) и 152 (основа 8rem), которые
   * давали пробные варианты правки, и заметно меньше 280, которые поле
   * занимает, когда ряд перенесён. Флажки исключены: подпись у них короткая,
   * и ширина им не нужна — по той же причине, по какой они исключены
   * из правила в `app.css`.
   *
   * <p>Число полей проверяется отдельно: пустая выборка прошла бы любое
   * утверждение о ширине, а форма, переставшая рисоваться, — ровно та
   * поломка, которую тест обязан замечать.
   */
  it('поля формы колеса переносятся, а не сжимаются', async () => {
    const { container } = render(<WheelsScreen canIntake role="OWNER" />);
    await waitFor(() => expect(container.textContent).not.toBe(''));

    const widths = await measureWidths(
      await settled(container),
      '.card .filter-row .field:not(.field--check)',
    );

    expect(
      widths.length,
      'Формы колеса на экране нет вовсе — мерить нечего, и проверка ниже '
      + 'прошла бы сама собой.',
    ).toBeGreaterThanOrEqual(12);

    const squeezed = widths.filter((width) => width < 250);
    expect(
      squeezed,
      `Поля формы колеса сжаты, а не перенесены: ${squeezed.length} из `
      + `${widths.length} уже 250 пикселей при экране ${PHONE_WIDTH}. `
      + 'В такое поле не видно, что вводишь.',
    ).toEqual([]);
  }, 30_000);

  it('меряет все разделы, а не те, до которых дошли руки', () => {
    const missed = Object.keys(RENDERERS).filter((id) => !measured.has(id));
    expect(
      missed,
      `Эти разделы не измерены ни разу: ${missed.join(', ')}. Проверка, `
      + 'не нашедшая экранов, зеленеет на чём угодно.',
    ).toEqual([]);
  });
});

/**
 * Справочники как свойство экрана — те же, что приезжают запросом: два
 * набора разошлись бы, и половина экранов мерилась бы на пустых списках.
 */
function reference(): never {
  return REFERENCE as never;
}

/**
 * Разметка экрана, когда он **догрузился**.
 *
 * <p>Экран рисуется в два-три шага: сперва «Загружаем…», потом кэш, потом
 * ответ сервера. Первый шаг помещается в телефон всегда — там одна строка
 * текста, — и померенный на нём раздел зелен при любой вёрстке. Хуже того,
 * какой именно шаг успеет отрисоваться, зависит от машины: сторож зеленел бы
 * то так, то этак. Поэтому ждём, пока разметка перестанет меняться, и меряем
 * её. Ожидание — по состоянию, а не по часам: это та же ловушка, на которой
 * краснела `main` из-за `DromFeedStreamTest`.
 */
async function settled(container: HTMLElement): Promise<string> {
  let previous = '';
  for (let step = 0; step < 40; step += 1) {
    await act(async () => {
      await new Promise((resolve) => { setTimeout(resolve, 10); });
    });
    const html = container.innerHTML;
    if (html !== '' && html === previous) return html;
    previous = html;
  }
  return container.innerHTML;
}
