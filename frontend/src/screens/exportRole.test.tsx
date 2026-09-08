import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';
import { WheelsScreen } from './WheelsScreen';

/**
 * «Скачать таблицу» видит тот, кому она разрешена.
 *
 * <p><b>Зачем.</b> Экран «Склад» и вкладка «Шины и диски» открыты всем
 * вошедшим намеренно — продавцу нужна цена и наличие, кладовщику полка, —
 * а ссылка на выгрузку стояла там же и была открыта тем же всем: опись
 * всей номенклатуры с ценами, поставками, заметками и остатками по каждому
 * складу уносилась одним нажатием, в том числе ролью «Просмотр». Список
 * ролей — решение владельца продукта от 9 сентября 2026,
 * `tasks/0050-vygruzka-sklada-dostupna-vsem.md`.
 *
 * <p><b>Это половина защиты, и вторая важнее.</b> Спрятанная кнопка при
 * открытом адресе не защищает ничего: адрес выгрузки постоянный, и его
 * достаточно один раз увидеть. Отказ сервера стережёт
 * `CatalogExportRoleTest`; здесь проверяется только то, что экран не зовёт
 * человека нажать на то, что ему запрещено, — и что у владельца ссылка
 * при этом осталась.
 *
 * <p>Обе стороны обязательны по той же причине, что и на сервере: одна
 * проверка «кнопки нет» зеленеет и на правке, убравшей её у всех.
 */
describe('«Скачать таблицу»', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body: unknown = url.includes('/api/catalog/vehicles')
        ? { brands: [], models: [], generations: [] }
        : url.includes('/api/organization')
          ? []
          : { total: 0, rows: [], warehouses: [], filterable: [] };
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  /** Ряд кнопок над таблицей — тот самый, в котором стоит ссылка. */
  const toolbar = (): Element | undefined =>
    [...document.querySelectorAll('button')].find(
      (b) => b.textContent?.includes('Настроить таблицу'),
    );

  /** Ссылка, а не кнопка: файл качает браузер. Ищем именно её. */
  const links = (): HTMLElement[] =>
    [...document.querySelectorAll('a')].filter(
      (a) => a.textContent?.includes('Скачать таблицу'),
    );

  it.each(['VIEWER', 'STOREKEEPER', 'SELLER'])(
    'роли %s не показана — ни на складе, ни на колёсах',
    async (role) => {
      render(<CatalogScreen role={role} />);
      // Ждём соседнюю кнопку того же ряда, а не таблицу: на пустом складе
      // таблицы нет вовсе, и проверка «ссылки нет» прошла бы на экране,
      // который ещё не нарисован.
      await waitFor(() => expect(toolbar()).not.toBeNull());
      expect(links()).toHaveLength(0);

      cleanup();

      render(<WheelsScreen canIntake={false} role={role} />);
      // Ждём соседнюю кнопку того же ряда, а не таблицу: на пустом складе
      // таблицы нет вовсе, и проверка «ссылки нет» прошла бы на экране,
      // который ещё не нарисован.
      await waitFor(() => expect(toolbar()).not.toBeNull());
      expect(links()).toHaveLength(0);
    },
  );

  it.each(['OWNER', 'MANAGER'])('роли %s показана на обоих экранах', async (role) => {
    render(<CatalogScreen role={role} />);
    await waitFor(() => expect(links()).toHaveLength(1));

    cleanup();

    render(<WheelsScreen canIntake role={role} />);
    await waitFor(() => expect(links()).toHaveLength(1));
  });
});
