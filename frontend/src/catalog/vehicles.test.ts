import { describe, expect, it } from 'vitest';
import {
  generationForYear,
  generationsOf,
  isValidVin,
  modelsOf,
  suggestBrands,
} from './vehicles';
import type { Brand, Generation, VehicleCatalog } from './vehicles';

/**
 * Справочник машин на телефоне.
 *
 * <p>Главное здесь — подбор поколения по году. Ошибка тихая: приёмщик увидит
 * заполненное поле и не станет проверять, а деталь уедет в объявление с чужой
 * применимостью — то есть к клиенту, которому она не подойдёт.
 */

const brands: Brand[] = [
  { id: 1, slug: 'toyota', name: 'Toyota', nameRu: 'Тойота' },
  { id: 2, slug: 'mazda', name: 'Mazda', nameRu: 'Мазда' },
  { id: 3, slug: 'uaz', name: 'УАЗ', nameRu: null },
  // Марка, у которой искомое слово стоит в середине названия.
  { id: 4, slug: 'great-wall', name: 'Great Wall Mazda-like', nameRu: null },
];

const camry: Generation[] = [
  { id: 10, modelId: 100, name: '2006—2008', yearFrom: 2006, yearTo: 2008 },
  { id: 11, modelId: 100, name: '2009—2010', yearFrom: 2009, yearTo: 2010 },
  { id: 12, modelId: 100, name: '2011—2013', yearFrom: 2011, yearTo: 2013 },
  { id: 13, modelId: 100, name: '2023—н.в.', yearFrom: 2023, yearTo: null },
];

const catalog: VehicleCatalog = {
  loadedAt: new Date().toISOString(),
  brands,
  models: [
    { id: 100, slug: 'camry', name: 'Camry', brandId: 1 },
    { id: 101, slug: 'mark_ii', name: 'Mark II', brandId: 1 },
    { id: 200, slug: 'demio', name: 'Demio', brandId: 2 },
  ],
  generations: camry,
};

describe('подсказки по марке', () => {
  it('находит по русскому написанию', () => {
    // Ради этого и заведена name_ru: переключать раскладку на телефоне —
    // лишнее действие на каждой машине.
    expect(suggestBrands(brands, 'тойо').map((b) => b.name)).toContain('Toyota');
  });

  it('находит по латинскому написанию', () => {
    expect(suggestBrands(brands, 'toyo').map((b) => b.name)).toContain('Toyota');
  });

  it('совпадение с начала идёт выше совпадения в середине', () => {
    expect(suggestBrands(brands, 'mazda')[0]?.name).toBe('Mazda');
  });

  it('марка без русского написания не ломает поиск', () => {
    expect(suggestBrands(brands, 'уаз').map((b) => b.slug)).toEqual(['uaz']);
  });

  it('пустой ввод ничего не предлагает', () => {
    expect(suggestBrands(brands, '  ')).toEqual([]);
  });
});

describe('модели и поколения марки', () => {
  it('модели чужой марки не показываются', () => {
    expect(modelsOf(catalog, 1).map((m) => m.name)).toEqual(['Camry', 'Mark II']);
  });

  it('без марки моделей нет', () => {
    expect(modelsOf(catalog, null)).toEqual([]);
  });

  it('поколения идут свежими сверху', () => {
    // На разбор приезжают машины последних поколений, и листать двадцать
    // диапазонов снизу вверх приёмщику пришлось бы на каждой второй.
    expect(generationsOf(catalog, 100).map((g) => g.yearFrom)).toEqual([
      2023, 2011, 2009, 2006,
    ]);
  });
});

describe('поколение по году машины', () => {
  it('год внутри диапазона даёт своё поколение', () => {
    expect(generationForYear(camry, 2012)?.name).toBe('2011—2013');
  });

  it('границы диапазона включены', () => {
    expect(generationForYear(camry, 2011)?.name).toBe('2011—2013');
    expect(generationForYear(camry, 2013)?.name).toBe('2011—2013');
  });

  it('год после последнего поколения относится к нему', () => {
    // У последнего года окончания нет — модель ещё выпускается.
    expect(generationForYear(camry, 2026)?.name).toBe('2023—н.в.');
  });

  it('год в дырке между поколениями не подставляет соседнее', () => {
    // 2014–2022 в этом наборе не покрыт. Подставить сюда «2011—2013»
    // значит соврать про применимость, а приёмщик не проверит.
    expect(generationForYear(camry, 2018)).toBeNull();
  });

  it('год раньше всех поколений не подставляет ничего', () => {
    expect(generationForYear(camry, 1990)).toBeNull();
  });

  it('без года поколение не выбирается', () => {
    expect(generationForYear(camry, null)).toBeNull();
    expect(generationForYear(camry, Number('не год'))).toBeNull();
  });
});

describe('проверка VIN', () => {
  it('нормальный VIN принимается', () => {
    expect(isValidVin('JTNBE46K073123456')).toBe(true);
  });

  it('короткий отвергается', () => {
    expect(isValidVin('JTNBE46K07312')).toBe(false);
  });

  it('буквы I, O и Q в VIN не встречаются', () => {
    // Их не используют, чтобы не путать с единицей и нулём. Приняв такой VIN,
    // мы запишем машину, которую потом не найти ни по документам, ни по
    // запросу клиента.
    expect(isValidVin('JTNBE46K07312345O')).toBe(false);
    expect(isValidVin('ITNBE46K073123456')).toBe(false);
  });

  it('регистр и пробелы по краям не мешают', () => {
    expect(isValidVin(' jtnbe46k073123456 ')).toBe(true);
  });
});
