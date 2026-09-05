import { describe, expect, it } from 'vitest';
import { downloadMark, filterSummary, type Feed } from './feeds';

/**
 * Как выгрузка описывает свой отбор в списке.
 *
 * <p>У владельца их пять на одну площадку, и различаются они только отбором.
 * «Есть фильтр» не говорит ничего: нужно с первого взгляда понимать, какая
 * выгрузка какой кусок склада забирает, иначе прайс-листы в кабинете площадки
 * перепутаются между собой.
 */

function feed(overrides: Partial<Feed> = {}): Feed {
  return {
    id: 1,
    marketplace: 'DROM',
    title: 'Дром',
    status: 'ACTIVE',
    hasCredentials: false,
    plaintextSecret: false,
    hasFeed: true,
    productLine: 'PART',
    settings: { pricePercent: null, priceRounding: null },
    lastError: null,
    lastDownloadAt: null,
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
    ...overrides,
  };
}

// Разделитель разрядов у toLocaleString неразрывный, а не обычный пробел:
// сравнивать надо тем же форматом, иначе тест ловит не то, что проверяет.
const rub = (value: number) => value.toLocaleString('ru-RU');

describe('сводка отбора выгрузки', () => {
  it('пустой отбор — это весь склад, а не «фильтров нет»', () => {
    // Разница не косметическая: «фильтров нет» читается как «ничего
    // не выгружается», и владелец идёт заполнять поля, которых не нужно.
    expect(filterSummary(feed())).toBe('весь склад');
  });

  it('диапазон показывается диапазоном', () => {
    // Числа, а не строки: сервер отдаёт numeric числом JSON, и объявленный
    // строкой тип компилятор не поправит — падает уже в браузере.
    expect(filterSummary(feed({ priceFrom: 0, priceTo: 1000 })))
      .toBe(`${rub(0)}—${rub(1000)} ₽`);
  });

  it('одна граница не превращается в диапазон', () => {
    // «от 50 000» и «50 000—∞» — первое читается, второе нет.
    expect(filterSummary(feed({ priceFrom: 50000 }))).toBe(`от ${rub(50000)} ₽`);
    expect(filterSummary(feed({ priceTo: 1000 }))).toBe(`до ${rub(1000)} ₽`);
  });

  it('состояние названо по-русски, а не кодом', () => {
    expect(filterSummary(feed({ conditions: ['NEW'] }))).toBe('новые');
  });

  it('направление списка названо словом, а не числом', () => {
    // «наименований: 3» не отвечает на вопрос, выгружаются они или наоборот
    // исключены, а решения по этому противоположные.
    expect(filterSummary(feed({ kindIds: [1, 2, 3] }))).toContain('только наименований: 3');
    expect(filterSummary(feed({ kindIds: [1], kindsExcluded: true })))
      .toContain('кроме наименований: 1');
  });

  it('склады и цена показываются вместе', () => {
    const summary = filterSummary(feed({ priceTo: 1000, warehouseIds: [1, 2] }));
    expect(summary).toContain(`до ${rub(1000)} ₽`);
    expect(summary).toContain('складов: 2');
  });
});

/**
 * Отметка о заборе прайса.
 *
 * <p>Месяц пишется словом и своей таблицей: короткое имя из `Intl` зависит
 * от сборки ICU («сент.» против «сен»), и экран показывал бы в браузере
 * не то, что в тестах. Число вместо слова тоже не годится — «04.09» рядом
 * со временем читается как ещё одно время.
 */
describe('когда площадка последний раз забирала прайс', () => {
  it('называет день и время', () => {
    // Местное время: забор идёт ночью, и час здесь смысловой.
    const at = new Date(2026, 8, 4, 22, 30).toISOString();
    expect(downloadMark(feed({ lastDownloadAt: at }))).toBe('Скачан 04 сен, 22:30');
  });

  it('день и час дополняются нулём до двух знаков', () => {
    // «4 сен, 9:5» — не время, а опечатка на глаз владельца.
    const at = new Date(2026, 0, 4, 9, 5).toISOString();
    expect(downloadMark(feed({ lastDownloadAt: at }))).toBe('Скачан 04 янв, 09:05');
  });

  it('у незабранной выгрузки отвечает словами, а не пустотой', () => {
    // Ни пусто, ни «01.01.1970»: первое читается как «экран не знает»,
    // второе — как поломка, и обе догадки уводят от настоящего ответа.
    expect(downloadMark(feed({ lastDownloadAt: null }))).toBe('Прайс не забирали');
  });
});
