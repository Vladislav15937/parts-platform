import { describe, expect, it } from 'vitest';
import { COLUMNS, DEFAULT_VISIBLE, loadVisible, type CatalogRow } from './catalog';

/**
 * Витрина склада: состав колонок и их значения.
 *
 * <p>Ошибки тут тихие: колонка, показывающая не то, читается как правда.
 * «Лев.» вместо «прав.» на витрине из тридцати пяти тысяч позиций заметят
 * тогда, когда деталь приедет покупателю не той стороной.
 */
function row(overrides: Partial<CatalogRow> = {}): CatalogRow {
  return {
    id: 1, code: 'A1', title: 'Фара', qualityGrade: null, condition: 'USED',
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null,
    price: null, installationPrice: null, color: null, description: null, note: null,
    manufacturer: null, marking: null, section: null, sideLr: null, sideFr: null,
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

  it('состояние берётся из оценки, а при её отсутствии — из вида', () => {
    expect(value('quality', row({ qualityGrade: 'C' }))).toBe('C');
    expect(value('quality', row({ condition: 'NEW' }))).toBe('новая');
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
    expect(loadVisible().length).toBeGreaterThan(0);
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

