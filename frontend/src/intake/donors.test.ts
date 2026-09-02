import { describe, expect, it } from 'vitest';
import { donorTitle } from './donors';
import type { DonorLabel } from './donors';

/**
 * Подпись машины в списке приёмки.
 *
 * <p>Марки, модели и года мало: у переехавшего клиента 200 машин из 442
 * совпадают по этой тройке, и четыре «Toyota Camry 2007» подряд приёмщик
 * различить не может. Ошибка тут не косметическая — деталь уедет с чужой
 * применимостью, то есть покупателю, которому не подойдёт.
 */
describe('подпись машины', () => {
  it('начинается с номера клиента, а не с нашего внутреннего', () => {
    expect(donorTitle(donor({ code: '350', brand: 'Toyota', model: 'Camry', year: 2007 })))
      .toBe('350 · Toyota Camry 2007');
  });

  it('различает машины заметкой владельца', () => {
    const первая = donor({ code: '350', brand: 'Toyota', model: 'Camry', year: 2007,
      note: 'Toyota Camry ACV40 2AZFE' });
    const вторая = donor({ code: '229', brand: 'Toyota', model: 'Camry', year: 2007,
      note: 'Toyota Camry ACV40 2AZFE Синий маркер!!!' });

    expect(donorTitle(первая)).not.toBe(donorTitle(вторая));
    expect(donorTitle(вторая)).toContain('Синий маркер!!!');
  });

  it('не заикается маркой и моделью, когда заметка начинается с них', () => {
    const подпись = donorTitle(donor({ code: '350', brand: 'Toyota', model: 'Camry',
      year: 2007, note: 'Toyota Camry ACV40 2AZFE' }));

    expect(подпись).toBe('350 · Toyota Camry 2007 · ACV40 2AZFE');
  });

  it('обходится без заметки и без марки', () => {
    expect(donorTitle(donor({ code: 'B6E5E78B450E' }))).toBe('B6E5E78B450E');
  });

  function donor(fields: Partial<DonorLabel>): DonorLabel {
    return { code: '1', note: null, brand: null, model: null, year: null, ...fields };
  }
});
