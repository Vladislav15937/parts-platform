import { describe, expect, it } from 'vitest';
import { draftOf, toEdit } from './PartEditForm';
import type { PartEdit } from '../inventory/catalog';

function card(overrides: Partial<PartEdit> = {}): PartEdit {
  return {
    price: 4500, minPrice: 4000, costPrice: 1200, installationPrice: null,
    qualityGrade: 'GOOD', description: null, note: 'скол на креплении',
    textBlock: null, videoUrl: null, marking: null, manufacturer: 'Toyota',
    color: null, section: null, barcode: null,
    weightKg: 3.5, lengthMm: 120, widthMm: 80, heightMm: 45,
    packageLengthMm: null, packageWidthMm: null, packageHeightMm: null,
    packageWeightKg: null, storageCellId: 17, published: true,
    ...overrides,
  };
}

describe('форма правки карточки', () => {
  it('возвращает прочитанное без изменений', () => {
    // Форма уезжает целиком, поэтому «открыл и сохранил, ничего не трогая»
    // обязано быть тождеством. Иначе каждая правка цены тихо стирает соседнее
    // поле — а замечают это на отчёте через месяц.
    expect(toEdit(draftOf(card()))).toEqual(card());
  });

  it('не теряет себестоимость и минимальную цену', () => {
    // Их нет на витрине: собери форму из строки таблицы — и сохранение
    // стёрло бы закупочную цену, которая снимком уходит в сделку
    // и в отчёт окупаемости.
    const saved = toEdit(draftOf(card({ costPrice: 1200, minPrice: 4000 })));
    expect(saved.costPrice).toBe(1200);
    expect(saved.minPrice).toBe(4000);
  });

  it('не теряет ячейку, которой в форме нет', () => {
    // Адрес полки меняют перемещением, а не карточкой — но и терять его
    // сохранение не должно.
    expect(toEdit(draftOf(card({ storageCellId: 17 }))).storageCellId).toBe(17);
  });

  it('очищенное поле уезжает пустым, а не нулём', () => {
    const draft = { ...draftOf(card()), note: '  ', installationPrice: '' };
    const saved = toEdit(draft);
    expect(saved.note).toBeNull();
    // «Цена установки 0 ₽» в карточке — обещание, которого никто не давал.
    expect(saved.installationPrice).toBeNull();
  });

  it('принимает запятую как десятичный знак', () => {
    // Так набирают на русской раскладке, и «3,5» не должно превращаться
    // в NaN, то есть в пустой вес.
    expect(toEdit({ ...draftOf(card()), weightKg: '3.5' }).weightKg).toBe(3.5);
  });

  it('нечисловое значение не уезжает на сервер числом', () => {
    expect(toEdit({ ...draftOf(card()), price: 'дорого' }).price).toBeNull();
  });
});
