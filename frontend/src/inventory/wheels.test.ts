import { describe, expect, it } from 'vitest';
import { sizeOf, type Wheel } from './wheels';

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
