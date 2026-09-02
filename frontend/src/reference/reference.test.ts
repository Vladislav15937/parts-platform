import { afterEach, describe, expect, it, vi } from 'vitest';
import { cachedReference, refreshReference, suggestNames } from './reference';
import type { PartNameRef, Reference } from './reference';

/**
 * Подсказки по наименованию.
 *
 * <p>Их смысл не в удобстве: приёмщик, которому предложили существующее
 * написание, не заведёт двадцать первый вариант той же детали. Список
 * нераспознанных растёт ровно из отсутствия подсказок.
 */
describe('подсказки', () => {
  const names: PartNameRef[] = [
    name(1, 'фара левая', 240),
    name(2, 'фара правая', 231),
    name(3, 'Фара противотуманная', 45),
    name(4, 'крепление фары', 30),
    name(5, 'бампер передний', 190),
  ];

  it('совпадения с начала идут раньше совпадений внутри', () => {
    const found = suggestNames(names, 'фар');

    // «крепление фары» тоже подходит, но приёмщик, пишущий «фар», почти всегда
    // имеет в виду саму фару.
    expect(found.map((n) => n.name)).toEqual([
      'фара левая',
      'фара правая',
      'Фара противотуманная',
      'крепление фары',
    ]);
  });

  it('ищет и внутри строки, а не только с начала', () => {
    // Пишут «противотуманная», имея в виду «Фара противотуманная».
    expect(suggestNames(names, 'противотум').map((n) => n.name)).toEqual([
      'Фара противотуманная',
    ]);
  });

  it('регистр не важен', () => {
    expect(suggestNames(names, 'ФАРА ПРАВАЯ').map((n) => n.name)).toEqual(['фара правая']);
  });

  it('на одной букве подсказок нет: подошла бы половина справочника', () => {
    expect(suggestNames(names, 'ф')).toEqual([]);
    expect(suggestNames(names, ' ')).toEqual([]);
  });

  it('порядок внутри группы — по частоте, как отдал сервер', () => {
    const found = suggestNames(names, 'фара');

    expect(found.map((n) => n.usageCount)).toEqual([240, 231, 45]);
  });

  it('список ограничен: длинный на телефоне не читают', () => {
    const many = Array.from({ length: 50 }, (_, i) => name(i + 100, `фара номер ${i}`, 1));

    expect(suggestNames(many, 'фара', 5)).toHaveLength(5);
  });
});

describe('хранение справочников', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('забранное с сервера читается из локального хранилища', async () => {
    const reference: Reference = {
      loadedAt: '2026-07-30T10:00:00Z',
      warehouses: [{ id: 1, name: 'Ткацкая', cells: [{ id: 10, code: 'А-01-1', zone: 'А' }] }],
      supplies: [],
      donors: [],
      partNames: [name(1, 'фара левая', 5)],
    };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(reference) }),
    );

    await refreshReference();

    // Ради этого всё и затевалось: после выхода из сети данные на месте.
    const cached = await cachedReference();
    expect(cached?.warehouses[0]?.cells[0]?.code).toBe('А-01-1');
    expect(cached?.loadedAt).toBe('2026-07-30T10:00:00Z');
  });

  it('повторная загрузка заменяет справочники целиком, а не сливает', async () => {
    const first: Reference = empty('2026-07-30T10:00:00Z');
    first.warehouses = [{ id: 1, name: 'Закрытый склад', cells: [] }];
    await store(first);

    const second: Reference = empty('2026-07-30T12:00:00Z');
    second.warehouses = [{ id: 2, name: 'Рабочий склад', cells: [] }];
    await store(second);

    // Слияние оставило бы закрытый склад в списке навсегда: удаления
    // на сервере клиент не видит.
    const cached = await cachedReference();
    expect(cached?.warehouses.map((w) => w.name)).toEqual(['Рабочий склад']);
  });
});

// ---------- вспомогательное ----------

function name(id: number, value: string, usageCount: number): PartNameRef {
  return { id, name: value, matched: false, usageCount };
}

function empty(loadedAt: string): Reference {
  return { loadedAt, warehouses: [], supplies: [], donors: [], partNames: [] };
}

async function store(reference: Reference): Promise<void> {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(reference) }),
  );
  await refreshReference();
}

