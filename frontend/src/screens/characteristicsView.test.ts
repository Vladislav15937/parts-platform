import { describe, expect, it } from 'vitest';

import { characteristicLines } from './CharacteristicsView';

/**
 * Показ «Характеристик» (задача 0292) на ячейках «Прочих полей» из файла клиента
 * Strikexz: подпись без `[ключа]`, служебные дубли Дрома спрятаны, значение
 * клиента не теряется. Хранимый текст при этом не меняется — это только чтение.
 */
describe('строки «Характеристик»', () => {
  it('магнитола: пара model/«Модель магнитолы» с одним значением — одна строка', () => {
    expect(characteristicLines(
      'model [model]: Toyota NHZT-W58\nМодель магнитолы [model]: Toyota NHZT-W58\nРазмер [size]: 2 DIN — 178x100 мм',
    )).toEqual(['Модель магнитолы: Toyota NHZT-W58', 'Размер: 2 DIN — 178x100 мм']);
  });

  it('усилитель: «Бренд», каналы и мощность, без служебного model', () => {
    expect(characteristicLines(
      'model [model]: Zapco\nБренд [model]: Zapco\nКоличество каналов [channel]: 4-канальный\n'
      + 'Номинальная мощность [nominalPower]: 65 Вт',
    )).toEqual(['Бренд: Zapco', 'Количество каналов: 4-канальный', 'Номинальная мощность: 65 Вт']);
  });

  it('колёса (строка 466): model~… и marking [marking] спрятаны — то же сказано человеческими строками', () => {
    const lines = characteristicLines([
      'model~tireFirmAndModel [model~tireFirmAndModel]: Bridgestone Blizzak DM-Z3',
      'Количество колёс в комплекте [inSetQuantity]: 4 шт',
      'marking [marking]: 265/65 R17',
      'Диаметр [marking]: 17"',
      'Модель шины [tireFirmAndModel]: Bridgestone Blizzak DM-Z3',
      'Тип шины [predestination]: Обычная',
      'Модель диска [discFirmAndModel]: noname',
    ].join('\n'));
    expect(lines).toEqual([
      'Количество колёс в комплекте: 4 шт',
      'Диаметр: 17"',
      'Модель шины: Bridgestone Blizzak DM-Z3',
      'Тип шины: Обычная',
      'Модель диска: noname',
    ]);
  });

  it('служебная строка без человеческого двойника остаётся значением, без имени ключа', () => {
    // Строка 349: модель шины есть только служебной строкой.
    expect(characteristicLines(
      'model~tireFirmAndModel [model~tireFirmAndModel]: Dunlop Grandtrek PT3\nТип шины [predestination]: Обычная',
    )).toEqual(['Dunlop Grandtrek PT3', 'Тип шины: Обычная']);
  });

  it('строка без [ключа] и пустые строки', () => {
    // Строка 946: последняя строка у клиента без ключа.
    expect(characteristicLines('Тип [diskType]: Литой\n\n  \nШирина диска: 7.5"'))
      .toEqual(['Тип: Литой', 'Ширина диска: 7.5"']);
  });
});
