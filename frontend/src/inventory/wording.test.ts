import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { CONDITION } from './catalog';
import { SEASONS } from './wheels';

/**
 * Слова на экране и слова в отборе — одни и те же.
 *
 * <p><b>Зачем.</b> Колонку «Состояние» рисует словарь клиента, а список
 * значений для отбора собирает сервер выражением `CASE p.condition WHEN
 * 'NEW' THEN 'новая' …`. Совпадают они сегодня слово в слово, и на этом
 * держится отбор: в меню стоит «б/у», и оно же уходит на сервер сравнением
 * с тем самым выражением. Разойдись одна буква — и выбранное из списка
 * значение перестанет находить что-либо, а понять почему можно будет
 * только чтением SQL.
 *
 * <p>Та же связка у сезонов: на вкладке колёс отбор сравнивает с русским
 * написанием, которое собирает сервер.
 *
 * <p>Сверяем исходником, а не запросом: правило про совпадение слов важнее
 * в тот момент, когда слово правят, — а правят его глазами в одном месте
 * из двух.
 */
describe('слова сервера и клиента', () => {
  const catalogService = readFileSync(
    '../src/main/java/ru/partsflow/inventory/CatalogService.java', 'utf8');
  const wheelService = readFileSync(
    '../src/main/java/ru/partsflow/inventory/WheelService.java', 'utf8');

  it('состояние показано теми же словами, какими отбирается', () => {
    for (const word of Object.values(CONDITION)) {
      expect(catalogService,
        `состояние «${word}» показывается на экране, но отбор его не знает`)
        .toContain(`'${word}'`);
    }
  });

  it('сезон показан теми же словами, какими отбирается', () => {
    for (const season of SEASONS) {
      expect(wheelService,
        `сезон «${season.name}» показывается на экране, но отбор его не знает`)
        .toContain(`"${season.name}"`);
    }
  });
});
