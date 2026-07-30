import { describe, expect, it } from 'vitest';
import { duplicateColumns, FIELDS, missingRequired } from './warehouseImport';
import type { FieldKey } from './warehouseImport';

/**
 * Проверки перед запуском импорта.
 *
 * <p>Это последний рубеж перед тем, как в склад уедут тысячи позиций.
 * Отменить импорт можно только восстановлением из бэкапа, поэтому неверное
 * сопоставление обязано не дать нажать кнопку — а не всплыть на первой
 * продаже, когда всё стоит по три рубля.
 */

const columns = (given: Partial<Record<FieldKey, number>>) => given;

describe('обязательные колонки', () => {
  it('без наименования и количества запускать нечего', () => {
    expect(missingRequired(columns({}))).toEqual(['Наименование', 'Количество']);
  });

  it('цена необязательна', () => {
    // Склад без цен всё равно лучше склада в тетради: цены проставят потом.
    expect(missingRequired(columns({ NAME: 0, QUANTITY: 1 }))).toEqual([]);
  });

  it('нулевая колонка считается указанной', () => {
    // Наименование в самом первом столбце — обычное дело, а 0 легко принять
    // за «не задано».
    expect(missingRequired(columns({ NAME: 0, QUANTITY: 3 }))).toEqual([]);
  });

  it('не хватает только количества — говорим именно про него', () => {
    expect(missingRequired(columns({ NAME: 2 }))).toEqual(['Количество']);
  });
});

describe('колонка, назначенная дважды', () => {
  it('одна колонка на два поля — это ошибка', () => {
    // Цена и количество из одного столбца дадут склад, где всё стоит
    // столько, сколько его лежит. Сервер такое примет молча.
    expect(duplicateColumns(columns({ NAME: 0, QUANTITY: 2, PRICE: 2 }))).toEqual([2]);
  });

  it('разные колонки не считаются дублем', () => {
    expect(duplicateColumns(columns({ NAME: 0, QUANTITY: 1, PRICE: 2 }))).toEqual([]);
  });

  it('несопоставленные поля дублем не считаются', () => {
    // У всех незаданных значение undefined, и наивная проверка увидела бы
    // в них четыре одинаковых «колонки».
    expect(duplicateColumns(columns({ NAME: 0, QUANTITY: 1 }))).toEqual([]);
  });
});

describe('список полей', () => {
  it('обязательны ровно наименование и количество', () => {
    expect(FIELDS.filter((f) => f.required).map((f) => f.label))
      .toEqual(['Наименование', 'Количество']);
  });
});
