import { describe, expect, it } from 'vitest';
import { cardFields } from './partCard';
import type { CatalogRow } from './catalog';

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

function titles(r: CatalogRow): string[] {
  return cardFields(r).map(([title]) => title);
}

function valueOf(r: CatalogRow, title: string): string | undefined {
  return cardFields(r).find(([t]) => t === title)?.[1];
}

describe('карточка товара', () => {
  it('не показывает пустые поля', () => {
    // Двадцать строк, из которых заполнены шесть, — это шесть строк,
    // потерянных среди прочерков.
    expect(titles(row())).toEqual(['Номер товара', 'Состояние']);
  });

  it('переводит состояние и стороны на язык кладовщика', () => {
    const r = row({ condition: 'USED', sideLr: 'LEFT', sideFr: 'REAR' });
    expect(valueOf(r, 'Состояние')).toBe('б/у');
    expect(valueOf(r, 'Левый / Правый')).toBe('лев.');
    expect(valueOf(r, 'Передний / Задний')).toBe('задн.');
  });

  it('печатает поколение вместе с годами', () => {
    expect(valueOf(row({ generation: '1 пок.', yearFrom: 2000, yearTo: 2002 }), 'Поколение'))
      .toBe('1 пок. 2000—2002');
    // Годы без поколения — тоже ответ: у половины склада поколение не задано.
    expect(valueOf(row({ yearFrom: 2000, yearTo: 2002 }), 'Поколение')).toBe('2000—2002');
    expect(valueOf(row({ generation: '2 пок.' }), 'Поколение')).toBe('2 пок.');

    // А в поставляемом справочнике поколение называется самим диапазоном:
    // все 12 430 записей — «1986—1990», «1966—н.в.». Дописывать к такому имени
    // те же годы значит печатать одно и то же дважды, будто это две разные
    // величины: карточка показывала «2006—2008 2006—2008».
    expect(valueOf(row({ generation: '2006—2008', yearFrom: 2006, yearTo: 2008 }), 'Поколение'))
      .toBe('2006—2008');
  });

  it('называет единицы там, где число само по себе врёт', () => {
    const r = row({ weightKg: 3.5, packageWeightKg: 4, installationPrice: 1500 });
    expect(valueOf(r, 'Вес товара')).toBe('3.5 кг');
    expect(valueOf(r, 'Вес в упаковке')).toBe('4 кг');
    // Разряды разделены неразрывным пробелом: это toLocaleString('ru-RU'),
    // и обычный пробел здесь означал бы перенос числа на две строки.
    expect(valueOf(r, 'Цена установки')).toBe('1 500 ₽');
  });

  it('различает «не выгружать» и «поле не заполнено»', () => {
    // false — это решение владельца, и оно обязано быть видно; null означает,
    // что позиция пришла без флага вовсе.
    expect(valueOf(row({ published: false }), 'Выгружать')).toBe('Нет');
    expect(valueOf(row({ published: true }), 'Выгружать')).toBe('Везде');
    expect(titles(row({ published: null }))).not.toContain('Выгружать');
  });

  it('не пишет «фотографий 0»', () => {
    // Снимков нет — об этом говорит пустая рамка справа, а не строка в списке.
    expect(titles(row({ photoCount: 0 }))).not.toContain('Количество фото');
    expect(valueOf(row({ photoCount: 3 }), 'Количество фото')).toBe('3');
  });
});

describe('паритет карточки с прежней системой', () => {
  // Снято с карточки товара живого клиента (yardt.baz-on.ru, товар 112344).
  // Пустые поля там тоже не показываются, поэтому список собран по позиции
  // с заполненной применимостью и дополнен полями, которые кабинет
  // показывает на других карточках.
  const BAZON = [
    'Номер товара',
    'Поставка',
    'Марка',
    'Модель',
    'Модель кузова',
    'Модель двигателя',
    'Год выпуска',
    'Комплектация',
    'Поколение',
    'Производитель',
    'Номер производителя',
  ];

  it('показывает каждое поле карточки клиента', () => {
    const full = row({
      supply: 'Контейнер №16', brand: 'Toyota', model: 'Corolla Fielder',
      body: 'NZE121', engine: '1NZFE', year: 2000,
      equipment: 'Правый руль, АКПП', generation: '1 пок.',
      yearFrom: 2000, yearTo: 2002,
      manufacturer: 'Toyota', oem: '4520912150',
    });
    const mine = titles(full);
    expect(BAZON.filter((t) => !mine.includes(t))).toEqual([]);
  });
});
