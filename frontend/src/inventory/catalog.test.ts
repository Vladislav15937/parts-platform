import { describe, expect, it } from 'vitest';
import {
  COLUMNS, DEFAULT_VISIBLE, DEFAULT_SORT, DEFAULT_DESC, defaultQuery,
  hasFilters, isSorted, withDefaultSort, withoutFilters, viewKey,
  type CatalogRow,
} from './catalog';

/**
 * Витрина склада: состав колонок и их значения.
 *
 * <p>Ошибки тут тихие: колонка, показывающая не то, читается как правда.
 * «Лев.» вместо «прав.» на витрине из тридцати пяти тысяч позиций заметят
 * тогда, когда деталь приедет покупателю не той стороной.
 */
function row(overrides: Partial<CatalogRow> = {}): CatalogRow {
  return {
    id: 1, number: 12, code: 'A1', title: 'Фара', qualityGrade: null, condition: 'USED',
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
    ...overrides,
  };
}

function value(key: string, source: CatalogRow): string {
  const column = COLUMNS.find((c) => c.key === key);
  if (column === undefined) {
    throw new Error(`нет колонки ${key}`);
  }
  return column.value(source);
}

describe('колонки витрины', () => {
  it('стороны показаны словами, а не кодом', () => {
    // «LEFT» в таблице — это утечка внутреннего представления: кладовщик
    // читает «лев.» и «перед.», а не английские константы.
    expect(value('sideLr', row({ sideLr: 'LEFT' }))).toBe('лев.');
    expect(value('sideFr', row({ sideFr: 'REAR' }))).toBe('задн.');
  });

  it('пустая сторона даёт пустоту, а не «undefined»', () => {
    expect(value('sideLr', row())).toBe('');
  });

  it('рестайлинг не повторяет поколение', () => {
    // Обе колонки показывали один диапазон в каждой строке: имя поколения
    // в поставляемом справочнике и есть диапазон лет («1986—1990» у всех
    // 12 430 записей), а «Рестайлинг» складывал те же годы из соседних полей.
    // Владелец читал их как два разных факта. Данных о рестайлинге у нас нет
    // (`catalog.generation.is_restyling` не заполнен ни у одной записи),
    // и колонка говорит об этом прочерком.
    const r = row({ generation: '2006—2008', yearFrom: 2006, yearTo: 2008 });
    expect(value('generation', r)).toBe('2006—2008');
    expect(value('restyling', r)).toBe('—');
    expect(value('restyling', r)).not.toBe(value('generation', r));
  });

  it('оценка состояния показана словом, а её отсутствие — пустотой', () => {
    // Внутреннее имя в колонке — то же, что «REAR» вместо «Задн.»:
    // владелец видел `NO_DEFECTS` рядом с переведённым «б/у».
    expect(value('quality', row({ qualityGrade: 'NO_DEFECTS' }))).toBe('Без дефектов');
    // Пусто — это «не оценена», а не состояние. Подставленное состояние
    // отвечало на другой вопрос, и отбор по колонке предлагал «б/у».
    expect(value('quality', row({ condition: 'NEW' }))).toBe('');
    expect(value('quality', row({ qualityGrade: null, condition: 'USED' }))).toBe('');
  });

  it('цена без значения — пусто, а не ноль', () => {
    // Ноль означает «отдаём даром», и на витрине это утверждение.
    expect(value('price', row())).toBe('');
    expect(value('price', row({ price: 0 }))).toBe('0');
  });

  it('по умолчанию показаны не все колонки', () => {
    // Двадцать три сразу — простыня, в которой не найти цену.
    expect(DEFAULT_VISIBLE.length).toBeLessThan(COLUMNS.length);
    expect(DEFAULT_VISIBLE).toContain('price');
    expect(DEFAULT_VISIBLE).toContain('number');
  });
});

/**
 * Паритет с таблицей товаров прежней системы.
 *
 * <p>Сверено с живым каталогом клиента: сорок две его колонки против наших
 * двадцати четырёх. Владелец переехавшего клиента ищет глазами те же поля,
 * к которым привык, и отсутствие «Выгружать» или «Поставки» читается
 * как потеря данных, хотя данные на месте.
 */
describe('паритет колонок с прежней системой', () => {
  const BAZON = [
    'Номер товара', 'Превью', 'Запчасть', 'Наименование', 'Оценка состояния',
    'Состояние', 'Марка', 'Модель', 'Поколение донора', 'Рестайлинг донора',
    'Кузов', 'Двигатель', 'Год выпуска', 'Передний / Задний', 'Левый / Правый',
    'Номер донора', 'Цена', 'Установка', 'Цвет', 'Комментарий', 'Производитель',
    'Номер производителя', 'Кросс-номера', 'Заметка', 'Маркировка', 'Секция',
    'Поставка', 'Комплектация', 'Выгружать', 'Количество фото', 'Текстовый блок',
    'Видео', 'Вес товара', 'Габариты товара', 'Габариты товара в упаковке',
    'Ст. баркод', 'Старые данные', 'Создан', 'Изменён', 'Кто изменил',
    'Цена изменена в', 'Кто изменил цену',
  ];

  it('показывает всё, что показывает кабинет клиента', () => {
    const mine = COLUMNS.map((c) => c.title);
    expect(BAZON.filter((title) => !mine.includes(title))).toEqual([]);
  });

  // Поле, доехавшее до строки, но не показанное ни одной колонкой, — это
  // данные, о которых никто не узнает.
  it('поля паритета доезжают до значений колонок', () => {
    const full = row({
      partName: 'фара лев.', published: true, barcode: '4600',
      legacyCode: 'P0001', videoUrl: 'https://v/1',
      textBlock: 'без сколов', weightKg: 3.5, dimensions: '120×80×45',
      packageDimensions: '130×90×50', packageWeightKg: 4,
      photoCount: 3, updatedByName: 'Сергей', priceChangedByName: 'Марина',
    });

    expect(value('partName', full)).toBe('фара лев.');
    expect(value('published', full)).toBe('Везде');
    expect(value('barcode', full)).toBe('4600');
    expect(value('legacy', full)).toBe('P0001');
    expect(value('video', full)).toBe('https://v/1');
    expect(value('textBlock', full)).toBe('без сколов');
    expect(value('weight', full)).toBe('3.5 кг');
    expect(value('dimensions', full)).toBe('120×80×45');
    expect(value('packageDimensions', full)).toBe('130×90×50');
    expect(value('photoCount', full)).toBe('3');
    expect(value('updatedBy', full)).toBe('Сергей');
    expect(value('priceChangedBy', full)).toBe('Марина');
  });

  // Незаполненное поле — пусто, а не «null» и не «0 кг»: прочерк в таблице
  // читается как «не заполнено», а ноль — как измеренный ноль.
  it('незаполненное не превращается в ноль и в null', () => {
    const empty = row();

    expect(value('weight', empty)).toBe('');
    expect(value('dimensions', empty)).toBe('');
    expect(value('photoCount', empty)).toBe('');
    expect(value('published', empty)).toBe('');
  });
});

/**
 * Порядок витрины по умолчанию (задача 0060).
 *
 * <p><b>Как выглядело для человека.</b> Экран открывался с `sort: 'code'`,
 * а `code` — это `public_code`, шесть случайных байт. Владелец с тридцатью
 * пятью тысячами позиций видел **случайную полусотню** — и завтра другую:
 * позиция, стоявшая вчера второй сверху, сегодня не видна вовсе, и это
 * читается как «пропала».
 */
describe('порядок витрины по умолчанию', () => {
  it('открывается порядковым номером по возрастанию, а не случайным кодом', () => {
    const open = defaultQuery(50);

    // «Сортировать витрину по порядковому номеру 1. 2. 3. и тд» — решение
    // владельца продукта от 11 сентября 2026.
    expect(open.sort,
      `витрина открывается сортировкой «${open.sort}» вместо «number»: `
      + '«code» — это шесть случайных байт, то есть случайная полусотня '
      + 'из тридцати пяти тысяч, новая при каждом заходе').toBe('number');
    expect(open.desc,
      'витрина открывается от последнего заведённого, а не от первого: '
      + '«1. 2. 3. и тд» читается сверху вниз').toBe(false);
    expect(DEFAULT_SORT).toBe('number');
    expect(DEFAULT_DESC).toBe(false);
  });

  it('колонка номера есть и сортируется по нему же', () => {
    const column = COLUMNS.find((c) => c.key === 'number');
    expect(column, 'номера позиции нет среди колонок витрины').toBeTruthy();
    // Разойдись имя сортировки колонки с тем, что принимает сервер, — нажатие
    // на стрелку меняло бы порядок на неизвестный серверу, то есть молча
    // на умолчание.
    expect(column?.sort).toBe(DEFAULT_SORT);
    expect(value('number', row({ number: 347 }))).toBe('347');
  });
});

/**
 * Две кнопки сброса — решение владельца продукта от 11 сентября 2026:
 * «запоминать всё, и должна быть кнопка сбросить фильтры, сбросить
 * сортировку».
 *
 * <p>Они здесь не удобство, а условие безопасности самой памяти: запомненный
 * отбор переживает браузер и устройство, и владелец, открывший склад через
 * неделю, видит «Ничего не найдено» и решает, что склад пуст или сломан.
 */
describe('сброс отбора и сортировки', () => {
  const busy = {
    ...defaultQuery(50),
    q: 'фара',
    reserved: false,
    missing: true,
    warehouses: [7],
    columns: { brand: 'Toyota' },
    words: { note: 'скол' },
    vehicle: {
      brandId: 3, brandName: 'Toyota', modelId: null, modelName: '', body: '', engine: '',
    },
    sort: 'price',
    desc: true,
    page: 4,
  };

  it('сброс отбора снимает и набранный поиск, а порядок оставляет', () => {
    const clean = withoutFilters(busy);

    // «Сбрасывать: нажимающий „сбросить“ хочет увидеть весь склад» — решение
    // владельца продукта от 11 сентября 2026. Полумера хуже отсутствия кнопки:
    // человек нажал и по-прежнему видит полсотни строк вместо склада.
    expect(clean.q, 'набранный поиск пережил сброс отбора').toBe('');
    expect(clean.columns).toEqual({});
    expect(clean.words).toEqual({});
    expect(clean.vehicle.brandId).toBeNull();
    expect(clean.warehouses).toEqual([]);
    expect(clean.reserved).toBe(true);
    expect(clean.missing).toBe(false);
    // Сортировка — другое желание и другая кнопка.
    expect(clean.sort, 'сброс отбора сбросил заодно и порядок').toBe('price');
    expect(clean.desc).toBe(true);
    expect(hasFilters(clean)).toBe(false);
  });

  it('сброс сортировки возвращает порядок заведения и не трогает отбор', () => {
    const ordered = withDefaultSort(busy);

    expect(ordered.sort).toBe(DEFAULT_SORT);
    expect(ordered.desc).toBe(DEFAULT_DESC);
    expect(ordered.q, 'сброс сортировки снял заодно и отбор').toBe('фара');
    expect(ordered.columns).toEqual({ brand: 'Toyota' });
    expect(isSorted(ordered)).toBe(false);
  });

  it('кнопки показываются только когда есть что сбрасывать', () => {
    // Кнопка, которая ничего не меняет, хуже отсутствующей.
    expect(hasFilters(defaultQuery(50))).toBe(false);
    expect(isSorted(defaultQuery(50))).toBe(false);
    expect(hasFilters(busy)).toBe(true);
    expect(isSorted(busy)).toBe(true);
    // Каждое условие включает кнопку само по себе: «показывать отсутствующие»
    // тоже сужает выдачу, а снять его при пустой таблице так же нечем.
    expect(hasFilters({ ...defaultQuery(50), missing: true })).toBe(true);
    expect(hasFilters({ ...defaultQuery(50), reserved: false })).toBe(true);
    expect(hasFilters({ ...defaultQuery(50), warehouses: [1] })).toBe(true);
  });

  it('перелистывание не меняет запоминаемого', () => {
    const visible = ['number', 'title'];
    // Страница не запоминается: «всё» сказано про то, как разложен экран,
    // а не про то, где человек остановился. Заодно это значит, что стрелка
    // «вперёд» не пишет в базу ничего.
    expect(viewKey({ query: { ...busy, page: 9, after: '12' }, visible }))
      .toBe(viewKey({ query: busy, visible }));
    expect(viewKey({ query: { ...busy, sort: 'title' }, visible }))
      .not.toBe(viewKey({ query: busy, visible }));
  });
});

