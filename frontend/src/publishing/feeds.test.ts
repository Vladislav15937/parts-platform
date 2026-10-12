import { describe, expect, it } from 'vitest';
import { deliveryLine, downloadMark, filterSummary, type DeliveryCode, type DeliveryStatus, type Feed } from './feeds';

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
    packetId: null,
    hasCredentials: false,
    plaintextSecret: false,
    hasFeed: true,
    // Имени файла не задавали: ссылка кончается токеном — рабочее состояние,
    // а не незаполненное поле.
    feedFileName: null,
    productLine: 'PART',
    settings: {
      pricePercent: null,
      priceRounding: null,
      photoLimit: null,
      installationNote: null,
      installationTemplate: null,
      expectedGoods: null,
      expectedGoodsNote: null,
      // Снимков машины-донора никому: так выглядит выгрузка, у которой
      // настройку не трогали, — и ровно так её отдаёт сервер.
      donorPhotoKinds: null,
    },
    lastError: null,
    deliveryStatus: null,
    lastDownloadAt: null,
    // Живая: удалённая в обычный список не приходит вовсе.
    deletedAt: null,
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

describe('слова итога отправки', () => {
  const CODES: DeliveryCode[] = ['PACKET_NOT_FOUND', 'AUTH_FAILED', 'EMPTY_REQUEST',
    'UNKNOWN_REASON', 'HTTP_ERROR', 'UNRECOGNIZED', 'TIMEOUT', 'NO_CONNECTION',
    'HOST_UNKNOWN', 'CONNECTION_BROKEN', 'TOO_BIG'];

  const refused = (code: DeliveryCode, extra: Partial<DeliveryStatus> = {}) =>
    deliveryLine(feed({
      packetId: '12345',
      deliveryStatus: { state: 'REFUSED', at: '2026-10-12T07:05:00Z', code, ...extra },
    }))!;

  it('у каждого кода отказа есть слова, и знакомого кода Дрома в них нет', () => {
    for (const code of CODES) {
      const line = refused(code);
      expect(line, code).not.toBeNull();
      expect(line.text, code).not.toMatch(/ERROR_REASON|PACKET_NOT_FOUND|undefined|null/);
    }
  });

  it('у каждого исхода свои слова — общих «не ответил или ошибка» больше нет', () => {
    // Решение владельца 12.10.2026: реальная причина, а не общие слова. Два
    // исхода с одинаковым текстом — это снова одна общая фраза на разное.
    const texts = CODES.map((code) => refused(code, { httpStatus: 502 }).text);
    expect(new Set(texts).size).toBe(CODES.length);
    expect(texts.join(' ')).not.toMatch(/не ответил или ответил ошибкой/);
  });

  it('сбой соединения называет, что случилось', () => {
    expect(refused('TIMEOUT').text).toMatch(/ответа не дождались/);
    expect(refused('NO_CONNECTION', { detail: 'Connection refused' }).text)
      .toMatch(/Не удалось соединиться с Дромом .*: Connection refused/);
    expect(refused('HOST_UNKNOWN').text).toMatch(/имя площадки не разрешилось/);
    expect(refused('CONNECTION_BROKEN', { detail: 'Connection reset' }).text)
      .toMatch(/Связь с Дромом оборвалась .*: Connection reset/);
  });

  it('ошибка HTTP называет статус, а страницу — её заголовком', () => {
    const page = refused('HTTP_ERROR',
      { httpStatus: 502, answerForm: 'PAGE', detail: '502 Bad Gateway' });
    expect(page.text).toMatch(/HTTP 502/);
    expect(page.text).toMatch(/веб-страница «502 Bad Gateway»/);
    expect(page.text).toMatch(/сбой на стороне площадки/);
    expect(refused('HTTP_ERROR', { httpStatus: 404, answerForm: 'EMPTY' }).text)
      .toMatch(/Дром отверг запрос — HTTP 404/);
  });

  it('незнакомый ответ показан как незнакомый — с самим ответом', () => {
    const odd = refused('UNRECOGNIZED',
      { httpStatus: 200, answerForm: 'JSON', detail: '{"status":"maintenance"}' });
    expect(odd.text).toMatch(/незнакомым образом/);
    expect(odd.text).toContain('«{"status":"maintenance"}»');
    expect(odd.text).toContain('HTTP 200');
    expect(refused('UNRECOGNIZED', { httpStatus: 200, answerForm: 'EMPTY', detail: '' }).text)
      .toContain('пустым ответом');
  });

  it('незнакомый код отличается от «кода нет» и показан сам', () => {
    const unknown = refused('UNKNOWN_REASON', { detail: 'ERROR_REASON_PACKET_DISABLED' });
    expect(unknown.text).toContain('ERROR_REASON_PACKET_DISABLED');
    expect(unknown.text).toMatch(/кодом, которого мы не знаем/);
    expect(unknown.text).not.toMatch(/незнакомым образом/);
  });

  it('красят знакомые отказы с действием и незнакомый код', () => {
    const red = CODES.filter((code) => deliveryLine(feed({
      deliveryStatus: { state: 'REFUSED', at: null, code },
    }))!.error);
    expect(red).toEqual(['PACKET_NOT_FOUND', 'AUTH_FAILED', 'EMPTY_REQUEST', 'UNKNOWN_REASON']);
  });

  it('у выключенной нет строки', () => {
    expect(deliveryLine(feed({ deliveryStatus: null }))).toBeNull();
  });
});
