import { describe, expect, it } from 'vitest';

import type { Applicability } from './catalog';
import { fitLabel } from './partCard';

/**
 * Строка применимости в карточке (задача 0289, разбор PR 416).
 *
 * <p>Поколения справочника названы диапазоном лет, а годы строки — годы клиента
 * из блока «Подходит»; печатать оба значило «2013—2023 · 2013—2024».
 */
function fit(over: Partial<Applicability>): Applicability {
  return { id: 1, verified: false, brand: 'Toyota', model: '4Runner', generation: null,
    yearFrom: null, yearTo: null, ...over };
}

describe('строка применимости', () => {
  it('поколение-диапазон при годах клиента не печатается дважды', () => {
    expect(fitLabel(fit({ generation: '2013—2023', yearFrom: 2013, yearTo: 2024 })))
      .toBe('Toyota 4Runner · 2013—2024');
  });

  it('«н.в.» словами, а не пустотой после тире', () => {
    expect(fitLabel(fit({ generation: '2005—н.в.', yearFrom: 2005, yearTo: null })))
      .toBe('Toyota 4Runner · 2005—н.в.');
  });

  it('поколение с настоящим именем остаётся рядом с годами', () => {
    expect(fitLabel(fit({ model: 'Camry', generation: 'XV40', yearFrom: 2006, yearTo: 2011 })))
      .toBe('Toyota Camry XV40 · 2006—2011');
  });

  it('без годов поколение-диапазон — единственное, что есть о годах, и остаётся', () => {
    expect(fitLabel(fit({ generation: '2009—2012' }))).toBe('Toyota 4Runner 2009—2012');
    expect(fitLabel(fit({ model: null }))).toBe('Toyota');
  });
});
