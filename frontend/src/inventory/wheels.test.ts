import { describe, expect, it } from 'vitest';
import {
  sizeOf,
  WHEEL_COLUMNS,
  WHEEL_DEFAULT_VISIBLE,
  rowOfWheel,
  wheelFields,
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
