import { describe, expect, it } from 'vitest';
import { cardFields } from './partCard';
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
  FILTER_EMPTY,
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
    brand: null, model: null, discBrand: null, discModel: null,
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

  it('производитель шины и диска — разные колонки и разные поля', () => {
    // Пока видов товара было два, поле годилось одно: строка — либо шина,
    // либо диск. У колеса в сборе они разные — шина Dunlop на диске
    // Mitsubishi, — и одним полем такое не записать.
    const tyre = wheel({ kind: 'TYRE', brand: 'Goodyear' });
    const disc = wheel({ kind: 'DISC', brand: null, discBrand: 'Enkei' });
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
    // А производителя карточка берёт из свойств колеса: там он назван
    // по-своему для шины и для диска, и второй раз в общих полях он был бы
    // строкой, показанной дважды.
    expect(row.manufacturer).toBeNull();
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
    const params = wheelParams(EMPTY_WHEEL_QUERY);
    expect([...params.keys()].sort()).toEqual(['desc', 'sort']);
  });

  it('у страницы и выгрузки отбор общий', () => {
    // Скачанный файл обязан совпасть с тем, что владелец видел на экране, —
    // ради этой сверки он его и качает.
    const query = {
      ...EMPTY_WHEEL_QUERY,
      q: '195/65', kind: 'TYRE' as const, missing: true,
      columns: { diameter: '15', season: 'зимняя' },
      sort: 'price', desc: false,
    };
    const inUrl = new URLSearchParams(wheelExportUrl(query).split('?')[1] ?? '');
    expect([...inUrl.entries()].sort()).toEqual([...wheelParams(query).entries()].sort());
    expect(wheelExportUrl(query).startsWith('/api/wheels/export?')).toBe(true);
  });

  it('пробелы в запросе не считаются поиском', () => {
    // Иначе «найти» по пустой строке отсекает весь склад условием ILIKE '% %'.
    expect(wheelParams({ ...EMPTY_WHEEL_QUERY, q: '   ' }).get('q')).toBeNull();
    expect(wheelParams({ ...EMPTY_WHEEL_QUERY, q: ' 195 ' }).get('q')).toBe('195');
  });

  it('каждый фильтр уезжает своей парой «колонка:значение»', () => {
    // Одним параметром их не свести: значение может содержать что угодно,
    // включая запятую — «Контейнер №7, Владивосток».
    const params = wheelParams({
      ...EMPTY_WHEEL_QUERY,
      columns: { diameter: '15', tyreBrand: 'Nokian' },
    });
    expect(params.getAll('filter').sort()).toEqual(['diameter:15', 'tyreBrand:Nokian']);
  });

  it('видит заданный отбор — иначе сбросить его нечем', () => {
    expect(hasWheelFilters(EMPTY_WHEEL_QUERY)).toBe(false);
    // Сортировка отбором не считается: она задана всегда, и «Сбросить»
    // висело бы на экране постоянно.
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, sort: 'price', desc: false })).toBe(false);
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, columns: { diameter: '15' } })).toBe(true);
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, kind: 'DISC' })).toBe(true);
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, missing: true })).toBe(true);
  });

  it('«пусто» и «не пусто» уезжают как значения', () => {
    // Отдельными пунктами списка, а не пустой строкой: пустая строка среди
    // марок выглядела бы промахом мыши.
    const params = wheelParams({
      ...EMPTY_WHEEL_QUERY, columns: { season: FILTER_EMPTY },
    });
    expect(params.getAll('filter')).toEqual([`season:${FILTER_EMPTY}`]);
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

  it('колонки без отбора помечены', () => {
    // По превью отбирать нечего, а даты сервер в отбор не пускает: список
    // из тридцати пяти тысяч дат — это не список.
    const noFilter = WHEEL_COLUMNS.filter((c) => c.filter === false).map((c) => c.key);
    expect(noFilter).toContain('photo');
    expect(noFilter).toContain('created');
  });
});

describe('колесо в сборе', () => {
  const assembly = () => wheel({
    kind: 'ASSEMBLY', diameter: 18, tyreWidth: 225, tyreHeight: 55, construction: 'R',
    season: 'WINTER_FRICTION', wearMm: 4, brand: 'Dunlop', model: 'Winter Maxx SJ8',
    discType: 'Литой', discWidth: 7, offsetMm: 38, boltPattern: '5x114.3', hubBore: 66,
    discBrand: 'Mitsubishi', discModel: 'Rays',
  });

  it('в карточке названы оба производителя', () => {
    // Шина Dunlop на диске Mitsubishi: одной строкой «производитель»
    // их не назвать, а перепутав — отдашь покупателю не то, что он подбирал.
    const fields = Object.fromEntries(wheelFields(assembly()));
    expect(fields['Производитель шины']).toBe('Dunlop');
    expect(fields['Производитель диска']).toBe('Mitsubishi');
    expect(fields['Производитель']).toBeUndefined();
  });

  it('в карточке показаны свойства и шины, и диска', () => {
    const titles = wheelFields(assembly()).map(([t]) => t);
    expect(titles).toContain('Сезон');
    expect(titles).toContain('Сверловка');
    expect(titles).toContain('Вылет');
  });

  it('у шины и диска производитель остаётся одной строкой', () => {
    // Там он один, и «производитель шины» на карточке диска — лишний вопрос.
    const tyre = Object.fromEntries(wheelFields(wheel({ kind: 'TYRE', brand: 'Goodyear' })));
    expect(tyre['Производитель']).toBe('Goodyear');
    const disc = Object.fromEntries(
      wheelFields(wheel({ kind: 'DISC', brand: null, discBrand: 'Enkei' })),
    );
    expect(disc['Производитель']).toBe('Enkei');
  });

  it('колонки производителей берут каждая своё поле', () => {
    const w = assembly();
    expect(value('tyreBrand', w)).toBe('Dunlop');
    expect(value('discBrand', w)).toBe('Mitsubishi');
    expect(value('tyreModel', w)).toBe('Winter Maxx SJ8');
    expect(value('discModel', w)).toBe('Rays');
    expect(value('kind', w)).toBe('Колесо');
  });

  it('зимняя различает шипы и липучку', () => {
    expect(value('season', wheel({ season: 'WINTER_STUDDED' }))).toBe('зимняя (шипы)');
    expect(value('season', wheel({ season: 'WINTER_FRICTION' }))).toBe('зимняя (липучка)');
    // Прежнее «зимняя» осталось: у заведённых раньше шин неизвестно, какие они.
    expect(value('season', wheel({ season: 'WINTER' }))).toBe('зимняя');
  });
});

describe('размер колеса в сборе', () => {
  it('называет шину, а не диск', () => {
    // Покупатель называет «225/55 R18», а сверловка и вылет стоят
    // в карточке своими полями.
    expect(sizeOf(wheel({
      kind: 'ASSEMBLY', tyreWidth: 225, tyreHeight: 55, diameter: 18, construction: 'R',
      discWidth: 7, boltPattern: '5x114.3', offsetMm: 38,
    }))).toBe('225/55 R18');
  });
});

describe('поля карточки колеса', () => {
  it('не повторяются', () => {
    // Свойства колеса и общие поля карточки складываются в один список,
    // и повторённое название — это строка, показанная дважды. Поймано
    // предупреждением React о повторяющемся ключе, а не глазами.
    const row = rowOfWheel({
      wheel: wheel({ kind: 'TYRE', brand: 'Goodyear', model: 'EfficientGrip' }),
      photoUrl: null,
    });
    const titles = [
      ...wheelFields(wheel({ kind: 'TYRE', brand: 'Goodyear', model: 'EfficientGrip' }))
        .map(([t]) => t),
      ...cardFields(row).map(([t]) => t),
    ];
    expect(titles.length).toBe(new Set(titles).size);
  });
});

describe('слово, вбитое в колонку', () => {
  it('уезжает отдельным параметром от выбранного из списка', () => {
    // Выбранное из списка ищется точно, набранное руками — вхождением:
    // это разные вопросы, и на сервере они разведены.
    const params = wheelParams({
      ...EMPTY_WHEEL_QUERY,
      columns: { kind: 'Шина' },
      words: { tyreBrand: 'Nok' },
    });
    expect(params.getAll('filter')).toEqual(['kind:Шина']);
    expect(params.getAll('find')).toEqual(['tyreBrand:Nok']);
  });

  it('пустое слово не уезжает', () => {
    // Стёртое поле — это снятый отбор, а не поиск по пустоте.
    expect(wheelParams({ ...EMPTY_WHEEL_QUERY, words: { tyreBrand: '  ' } })
      .getAll('find')).toEqual([]);
  });

  it('считается заданным отбором', () => {
    // Иначе «Сбросить отбор» не появится, и снять его будет нечем.
    expect(hasWheelFilters({ ...EMPTY_WHEEL_QUERY, words: { tyreBrand: 'Nok' } })).toBe(true);
  });

  it('доезжает до выгрузки', () => {
    const url = wheelExportUrl({ ...EMPTY_WHEEL_QUERY, words: { tyreBrand: 'Nok' } });
    expect(new URLSearchParams(url.split('?')[1] ?? '').getAll('find'))
      .toEqual(['tyreBrand:Nok']);
  });
});
