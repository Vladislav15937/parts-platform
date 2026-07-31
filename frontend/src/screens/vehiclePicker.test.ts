import { describe, expect, it } from 'vitest';
import { NO_VEHICLE, vehicleLabel, type VehicleOption } from '../inventory/catalog';
import { itemsOf, levelOf } from './VehiclePicker';

const CAMRY_2AZ: VehicleOption = {
  brandId: 43, brand: 'Toyota', modelId: 518, model: 'Camry',
  body: 'ACV40', engine: '2AZ-FE', parts: 700,
};
const CAMRY_1AZ: VehicleOption = { ...CAMRY_2AZ, engine: '1AZ-FE', parts: 300 };
const COROLLA: VehicleOption = {
  brandId: 43, brand: 'Toyota', modelId: 519, model: 'Corolla',
  body: 'ZZE120', engine: '3ZZ-FE', parts: 200,
};

describe('подбор по машине', () => {
  it('начинается с марки', () => {
    expect(levelOf(NO_VEHICLE, [CAMRY_2AZ, COROLLA])).toBe('brand');
  });

  it('после марки спрашивает модель, если их несколько', () => {
    const chosen = { ...NO_VEHICLE, brandId: 43, brandName: 'Toyota' };
    expect(levelOf(chosen, [CAMRY_2AZ, COROLLA])).toBe('model');
  });

  // Список из одной строки — это кнопка без альтернативы: спрашивать нечего.
  it('пропускает уровень, на котором выбирать не из чего', () => {
    const chosen = { ...NO_VEHICLE, brandId: 43, brandName: 'Toyota' };
    // Одна модель, один кузов, два двигателя — сразу к двигателю.
    expect(levelOf(chosen, [CAMRY_2AZ, CAMRY_1AZ])).toBe('engine');
  });

  it('заканчивается, когда сужать больше нечем', () => {
    const chosen = { ...NO_VEHICLE, brandId: 43, brandName: 'Toyota' };
    expect(levelOf(chosen, [CAMRY_2AZ])).toBeNull();
  });

  // Марка держит детали от всех своих моделей — иначе владелец видит
  // «Toyota 700» там, где на складе тысяча двести.
  it('складывает детали по всем машинам уровня', () => {
    const items = itemsOf('brand', [CAMRY_2AZ, CAMRY_1AZ, COROLLA], '');
    expect(items).toEqual([{ id: 43, label: 'Toyota', parts: 1200 }]);
  });

  it('фильтрует список набранным', () => {
    const items = itemsOf('model', [CAMRY_2AZ, COROLLA], 'cor');
    expect(items.map((item) => item.label)).toEqual(['Corolla']);
  });

  // Незаполненный кузов у переехавшего клиента — не строка «пусто» в списке.
  it('не показывает незаполненные значения', () => {
    const items = itemsOf('body', [{ ...CAMRY_2AZ, body: null }, COROLLA], '');
    expect(items.map((item) => item.label)).toEqual(['ZZE120']);
  });

  it('называет выбранное словами', () => {
    expect(vehicleLabel({
      brandId: 43, brandName: 'Toyota', modelId: 518, modelName: 'Camry',
      body: '', engine: '2AZ-FE',
    })).toBe('Toyota Camry 2AZ-FE');
  });
});
