import { describe, expect, it } from 'vitest';
import {
  DEAL_ITEM_STATUS_NAMES,
  DEAL_STATUS_NAMES,
  SHARED_DEAL_STATUS_NAMES,
  dealItemStatusName,
  dealStatusName,
  dealStatusNameLower,
  sharedDealStatusName,
} from './dealStatus';

/**
 * Полноту словаря против самого перечисления проверяет
 * `DealStatusVocabularyTest` на стороне сервера: там `DealStatus` доступен
 * как `values()`, а не как разбор текста, и новое состояние попадает
 * в перебор в тот же момент, когда его дописали в enum. Файл фронтенда
 * этого сделать не может — `@types/node` в сборке нет, а `?raw` за пределы
 * `frontend/` не пускает `server.fs`.
 *
 * <p>Здесь остаётся то, чего не видно из Java: доезжает ли слово из словаря
 * до вызывающего и не завелась ли копия словаря на экране.
 */

describe('слово доезжает до экрана', () => {
  it.each(Object.keys(DEAL_STATUS_NAMES))('%s не показывается сырым кодом', (status) => {
    expect(dealStatusName(status), `состояние ${status} показано кодом`)
      .not.toBe(status);
    expect(sharedDealStatusName(status), `состояние ${status} показано кодом покупателю`)
      .not.toBe(status);
  });

  it.each(Object.keys(DEAL_ITEM_STATUS_NAMES))('позиция %s не показывается сырым кодом',
    (status) => {
      expect(dealItemStatusName(status), `состояние позиции ${status} показано кодом`)
        .not.toBe(status.toLowerCase());
    });

  /**
   * Обе поверхности кабинета знают одни и те же состояния.
   *
   * <p>Пока словарей было три, они и разъехались: «Клиенты» не знали
   * `RETURNED`. Общий словарь сам по себе этого не отменяет — дописать
   * состояние в один и забыть про второй можно и в одном файле.
   */
  it('кабинет и страница покупателя знают одно и то же', () => {
    expect(Object.keys(SHARED_DEAL_STATUS_NAMES).sort())
      .toEqual(Object.keys(DEAL_STATUS_NAMES).sort());
  });

  /**
   * Строчный вариант берётся из того же словаря, а не из второй таблицы.
   *
   * <p>Экран продавца пишет состояние внутри предложения («Сделка отложена —
   * платить по ней не за что»), и до сведения ради этого держалась своя
   * копия из пяти строк.
   */
  it('строчный вариант — то же слово', () => {
    expect(dealStatusNameLower('RESERVED')).toBe('отложена');
    expect(dealStatusNameLower('DRAFT')).toBe('черновик');
    for (const [status, word] of Object.entries(DEAL_STATUS_NAMES)) {
      expect(dealStatusNameLower(status)).toBe(word.toLowerCase());
    }
  });
});

/**
 * Перебор по экранам: не завелась ли копия словаря снова.
 *
 * <p>Проверку полноты копия обходит целиком: свои четыре строки экран правит
 * сам, а тест смотрит на общий словарь и остаётся зелёным. Ровно так четыре
 * копии и дожили до разбора PR #78.
 *
 * <p>Ищутся обе записи, которыми копию пишут: объектный литерал
 * (`RESERVED: 'отложена'`) и ветка `switch` (`case 'RESERVED': return '…'`).
 * Одного шаблона мало — и это не догадка: перебор с одним лишь литералом
 * нашёл три копии из четырёх и пропустил как раз ту, ради которой задача
 * заведена: в «Клиентах» словарь написан через `switch`.
 *
 * <p>Файл считается копией с двух таких строк. Одного совпадения мало,
 * потому что `CANCELLED` законно встречается в словаре состояний пересчёта
 * (`inventory.ts`), где к сделке отношения нет; настоящая же копия
 * перечисляет состояния подряд, и меньше двух их не бывает.
 */
describe('копий словаря нет', () => {
  const NAMES = new Set([
    ...Object.keys(DEAL_STATUS_NAMES),
    ...Object.keys(DEAL_ITEM_STATUS_NAMES),
  ]);

  const SOURCES = import.meta.glob('../**/*.{ts,tsx}', {
    query: '?raw', eager: true, import: 'default',
  }) as Record<string, string>;

  it('состояние превращается в слово только в dealStatus.ts', () => {
    // Перебор без файлов — это зелёный тест ни о чём: glob, промахнувшийся
    // мимо каталога, обязан сказать об этом сам.
    expect(Object.keys(SOURCES).length, 'перебор не нашёл ни одного исходника')
      .toBeGreaterThan(20);

    const guilty = Object.entries(SOURCES)
      // Свой каталог glob отдаёт как `./dealStatus.ts`, чужой — как
      // `../screens/…`: сравнивать надо имя файла, а не начало пути.
      .filter(([path]) => !/(^|\/)dealStatus\.(ts|test\.ts)$/.test(path))
      .filter(([, text]) => text
        .split('\n')
        .filter((line) => {
          const key = /^\s*([A-Z][A-Z0-9_]*)\s*:\s*['"]/.exec(line)
            ?? /\bcase\s+['"]([A-Z][A-Z0-9_]*)['"]\s*:\s*return\s+['"]/.exec(line);
          return key !== null && key[1] !== undefined && NAMES.has(key[1]);
        })
        .length > 1)
      .map(([path]) => path.replace('../', 'frontend/src/'))
      .sort();

    expect(
      guilty,
      'здесь завелась своя таблица состояний — она разойдётся с общей,'
      + ' как уже расходились «Клиенты» и экран продавца.'
      + ' Зовите dealStatusName / dealItemStatusName / sharedDealStatusName:',
    ).toEqual([]);
  });
});
