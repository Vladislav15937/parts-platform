import { describe, expect, it } from 'vitest';
import {
  sizeOf,
  WHEEL_COLUMNS,
  WHEEL_DEFAULT_VISIBLE,
  rowOfWheel,
  wheelFields,
  wheelParams,
  wheelExportUrl,
  EMPTY_WHEEL_QUERY,
  hasWheelFilters,
  type Wheel,
} from './wheels';

/**
 * Размер колеса строкой.
 *
 * <p>В списке это первое, на что смотрят: покупатель называет «195 65 15»,
 * а не модель шины. Ошибка тут тихая — размер, собранный неверно, найдётся
 * поиском не тот, и клиенту отдадут колесо, которое не встанет.
 */
function wheel(overrides: Partial<Wheel> = {}): Wheel {
  return {
    id: 1, publicCode: null, title: '', price: null, status: 'IN_STOCK', qty: 1,
    kind: 'TYRE', setNo: null, diameter: null, tyreWidth: null, tyreHeight: null,
    construction: null, tyreType: null, season: null, wearMm: null, madeYear: null,
    discType: null, discWidth: null, offsetMm: null, boltPattern: null, hubBore: null,
    brand: null, model: null,
    markingType: null, treadType: null, runFlat: null, lightTruck: null,
    speedIndex: null, loadIndex: null,
    partName: null, condition: 'USED', supply: null, donorCode: null, oem: null,
    description: null, note: null, section: null, published: true,
    barcode: null, legacyCode: null, photoCount: 0,
    createdAt: null, updatedAt: null, updatedByName: null,
    priceChangedAt: null, priceChangedByName: null, stock: {},
    ...overrides,
  };
}

describe('размер колеса', () => {
  it('шина собирается как 195/65 R15', () => {
    expect(sizeOf(wheel({ tyreWidth: 195, tyreHeight: 65, diameter: 15, construction: 'R' })))
      .toBe('195/65 R15');
  });

  it('диск собирается как 6x15 5x100 ET45', () => {
    expect(sizeOf(wheel({
      kind: 'DISC', discWidth: 6, diameter: 15, boltPattern: '5x100', offsetMm: 45,
    }))).toBe('6x15 5x100 ET45');
  });

  it('незаполненное не превращается в мусор', () => {
    // Полупустая карточка — обычное дело при быстрой приёмке, и «undefined/null R»
    // в списке хуже пустой строки: по нему нельзя даже понять, чего не хватает.
    expect(sizeOf(wheel({ diameter: 15 }))).toBe('R15');
    expect(sizeOf(wheel())).toBe('');
  });
});

describe('паритет колонок с прежней системой', () => {
  /**
   * Список снят с настроек таблицы «Шины и диски» живого клиента
   * (yardt.baz-on.ru, 221 товар): двадцать восемь активных колонок
   * и семнадцать отключённых. Отключённые входят сюда наравне с активными —
   * клиент выключил их у себя, но у следующего они будут включены,
   * а данные для них должны быть.
   *
   * <p>Проверяется тестом, а не глазами: колонок сорок пять, и пропажу одной
   * при правке не замечают. Владелец переехавшего клиента ищет глазами те же
   * поля, к которым привык, и отсутствие «Индекса нагрузки» читается
   * как потеря данных.
   */
  const BAZON = [
    'Номер товара', 'Превью', 'Номер комплекта', 'Товар', 'Диаметр', 'Тип шины',
    'Ширина шины', 'Тип маркировки', 'Тип протектора', 'Тип конструкции',
    'Высота шины', 'Износ', 'Производитель шины', 'Модель шины', 'Сезон',
    'Год производства', 'Тип диска', 'Ширина диска', 'Вылет', 'Сверловка',
    'Диаметр ЦО', 'Производитель диска', 'Модель диска', 'Номер производителя',
    'Цена', 'Комментарий', 'Заметка', 'Секция',
    'Создан', 'Изменён', 'Кто изменил', 'Поставка', 'Наименование', 'Состояние',
    'RunFlat', 'Легкогрузовая (LT)', 'Индекс скорости', 'Индекс нагрузки',
    'Номер донора', 'Выгружать', 'Количество фото', 'Старые данные', 'Ст. баркод',
    'Цена изменена в', 'Кто изменил цену',
  ];

  it('показывает каждую колонку кабинета клиента', () => {
    const mine = WHEEL_COLUMNS.map((c) => c.title);
    expect(BAZON.filter((t) => !mine.includes(t))).toEqual([]);
  });

  it('по умолчанию включено то, по чему колесо ищут', () => {
    // Сорок пять колонок сразу — таблица шириной в четыре экрана.
    expect(WHEEL_DEFAULT_VISIBLE.length).toBeLessThan(WHEEL_COLUMNS.length / 2);
    expect(WHEEL_DEFAULT_VISIBLE).toContain('season');
    expect(WHEEL_DEFAULT_VISIBLE).toContain('wear');
  });

  it('производитель шины и диска — разные колонки', () => {
    // В базе поле одно на карточку, но у шины и диска они означают разное:
    // Goodyear в колонке «Производитель диска» — это неверно прочитанная
    // строка, а покупатель подбирает по обоим.
    const tyre = wheel({ kind: 'TYRE', brand: 'Goodyear' });
    const disc = wheel({ kind: 'DISC', brand: 'Enkei' });
    expect(value('tyreBrand', tyre)).toBe('Goodyear');
    expect(value('discBrand', tyre)).toBe('');
    expect(value('discBrand', disc)).toBe('Enkei');
    expect(value('tyreBrand', disc)).toBe('');
  });

  it('поднятый флажок виден, снятый не занимает место', () => {
    // RunFlat дороже обычной вдвое: «да» обязано быть видно. А колонка,
    // в которой у всех «нет», — это столбец шума на всю таблицу.
    expect(value('runFlat', wheel({ runFlat: true }))).toBe('да');
    expect(value('runFlat', wheel({ runFlat: false }))).toBe('');
    expect(value('runFlat', wheel({ runFlat: null }))).toBe('');
  });

  it('коды переводятся на язык склада', () => {
    expect(value('markingType', wheel({ markingType: 'METRIC' }))).toBe('Метрическая');
    expect(value('treadType', wheel({ treadType: 'DIRECTIONAL' }))).toBe('Направленный');
    expect(value('season', wheel({ season: 'WINTER' }))).toBe('зимняя');
  });
});

function value(key: string, w: Wheel): string {
  const column = WHEEL_COLUMNS.find((c) => c.key === key);
  if (column === undefined) throw new Error(`нет колонки ${key}`);
  return column.value(w);
}

describe('карточка колеса', () => {
  it('открывается той же карточкой, что и запчасть', () => {
    // Цена, списание и перемещение написаны на складе, а не на виде товара.
    // Пока карточки не было, колесо нельзя было ни поправить, ни списать,
    // ни перевезти: витрина склада показывает только запчасти.
    const row = rowOfWheel({
      wheel: wheel({ id: 7, publicCode: 'W-1', title: 'Шина 195/65 R15',
                     price: 3500, brand: 'Goodyear', stock: { '1': 4 } }),
      photoUrl: 'https://s3/фото.jpg',
    });

    expect(row.id).toBe(7);
    expect(row.code).toBe('W-1');
    expect(row.price).toBe(3500);
    // Остаток по складам обязан доехать: иначе в карточке нечего списывать
    // и неоткуда перевозить — блоки смотрят именно на него.
    expect(row.stock).toEqual({ '1': 4 });
    expect(row.photoUrl).toBe('https://s3/фото.jpg');
    // Производитель шины и есть производитель товара.
    expect(row.manufacturer).toBe('Goodyear');
  });

  it('показывает свойства шины и молчит про дисковые', () => {
    const fields = wheelFields(wheel({
      kind: 'TYRE', tyreWidth: 195, tyreHeight: 65, diameter: 15, construction: 'R',
      season: 'WINTER', wearMm: 7, runFlat: true, speedIndex: 'V', loadIndex: 94,
    }));
    const titles = fields.map(([t]) => t);

    expect(titles).toContain('Сезон');
    expect(titles).toContain('Индекс нагрузки');
    expect(titles).not.toContain('Сверловка');
    expect(fields.find(([t]) => t === 'Износ')?.[1]).toBe('7 мм');
    expect(fields.find(([t]) => t === 'Размер')?.[1]).toBe('195/65 R15');
  });

  it('показывает свойства диска и молчит про шинные', () => {
    const titles = wheelFields(wheel({
      kind: 'DISC', discType: 'Литой', boltPattern: '5x100', offsetMm: 45, hubBore: 54.1,
    })).map(([t]) => t);

    expect(titles).toContain('Сверловка');
    expect(titles).toContain('Вылет');
    expect(titles).not.toContain('Сезон');
    expect(titles).not.toContain('RunFlat');
  });

  it('не показывает пустые свойства', () => {
    // Двадцать строк, из которых заполнены три, — это три строки,
    // потерянные среди прочерков.
    expect(wheelFields(wheel()).map(([t]) => t)).toEqual(['Товар']);
  });
});

describe('отбор вкладки колёс', () => {
  it('пустой отбор не шлёт лишних параметров', () => {
    // Пустой фильтр — «без ограничения», а не «ничего».
    const params = wheelParams(EMPTY_WHEEL_QUERY);
    expect(params.get('q')).toBeNull();
    expect(params.get('kind')).toBeNull();
    expect(params.get('missing')).toBeNull();
    expect(params.get('sort')).toBe('set');
  });

  it('у страницы и выгрузки отбор общий', () => {
    // Скачанный файл обязан совпасть с тем, что владелец видел на экране, —
    // ради этой сверки он его и качает.
    const query = {
      ...EMPTY_WHEEL_QUERY,
      q: '195/65', kind: 'TYRE' as const, missing: true,
      diameter: '15', season: 'WINTER', wearFrom: '5',
      sort: 'price', desc: false,
    };
    const url = wheelExportUrl(query);
    const inUrl = new URLSearchParams(url.split('?')[1] ?? '');
    expect([...inUrl.entries()].sort())
      .toEqual([...wheelParams(query).entries()].sort());
    expect(url.startsWith('/api/wheels/export?')).toBe(true);
  });

  it('пробелы в запросе не считаются поиском', () => {
    // Иначе «найти» по пустой строке отсекает весь склад условием ILIKE '% %'.
    expect(wheelParams({ ...EMPTY_WHEEL_QUERY, q: '   ' }).get('q')).toBeNull();
    expect(wheelParams({ ...EMPTY_WHEEL_QUERY, q: ' 195 ' }).get('q')).toBe('195');
  });

  it('сортируемые колонки названы теми же именами, что знает сервер', () => {
    // Белый список сортировок лежит на сервере, и незнакомое имя молча
    // становится умолчанием: разъехавшись, экран показывал бы стрелку
    // на колонке, по которой не сортирует.
    const SERVER = ['code', 'set', 'kind', 'diameter', 'tyreWidth', 'tyreHeight',
      'wear', 'season', 'madeYear', 'tyreBrand', 'discBrand', 'price', 'section', 'created'];
    const mine = WHEEL_COLUMNS.map((c) => c.sort).filter((s): s is string => s !== undefined);
    expect(mine.filter((s) => !SERVER.includes(s))).toEqual([]);
  });
});

describe('отбор по свойствам колеса', () => {
  it('пустые свойства не уезжают на сервер', () => {
    // Пустое поле — «без ограничения»: незаполненный отбор обязан отдавать
    // весь склад, а не пустоту.
    const params = wheelParams({ ...EMPTY_WHEEL_QUERY, diameter: '  ' });
    expect(params.get('diameter')).toBeNull();
    expect([...params.keys()].sort()).toEqual(['desc', 'sort']);
  });

  it('заполненные свойства уезжают все', () => {
    const params = wheelParams({
      ...EMPTY_WHEEL_QUERY,
      diameter: '15', tyreWidth: '195', tyreHeight: '65', season: 'WINTER',
      wearFrom: '5', boltPattern: '5x100', brand: 'Nokian',
      priceFrom: '3000', priceTo: '6000',
    });
    expect(params.get('diameter')).toBe('15');
    expect(params.get('tyreWidth')).toBe('195');
    expect(params.get('season')).toBe('WINTER');
    expect(params.get('wearFrom')).toBe('5');
    expect(params.get('boltPattern')).toBe('5x100');
    expect(params.get('brand')).toBe('Nokian');
    expect(params.get('priceFrom')).toBe('3000');
    expect(params.get('priceTo')).toBe('6000');
  });

  it('видит заданный отбор — иначе сбросить его нечем', () => {
    expect(hasWheelFilters(EMPTY_WHEEL_QUERY)).toBe(false);
    // Сортировка отбором не считается: она задана всегда, и «Сбросить»
    // висело бы на экране постоянно.
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, sort: 'price', desc: false })).toBe(false);
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, diameter: '15' })).toBe(true);
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, kind: 'DISC' })).toBe(true);
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, missing: true })).toBe(true);
  });

  it('отбор по свойствам доезжает и до выгрузки', () => {
    const query = { ...EMPTY_WHEEL_QUERY, diameter: '15', season: 'WINTER' };
    const inUrl = new URLSearchParams(wheelExportUrl(query).split('?')[1] ?? '');
    expect(inUrl.get('diameter')).toBe('15');
    expect(inUrl.get('season')).toBe('WINTER');
  });
});
