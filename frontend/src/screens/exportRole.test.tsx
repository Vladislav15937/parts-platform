import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

import { CatalogScreen } from './CatalogScreen';
import { WheelsScreen } from './WheelsScreen';

/**
 * «Скачать таблицу» видит не всякий, кому открыт экран (задача 0049).
 *
 * <p><b>Как выглядело для человека.</b> Экраны «Склад» и «Шины и диски»
 * открыты всем вошедшим намеренно — цену и наличие смотрит и кладовщик,
 * и «Просмотр». Рядом с «Настроить таблицу» у них стояла ссылка, уносящая
 * весь склад одним файлом: номенклатура целиком, с ценами, поставками,
 * заметками, кросс-номерами и остатками по каждому складу. Для разборки
 * это опись имущества, а первые десять клиентов — компании из одного
 * города; ушедший кладовщик с таким файлом уносит готовый прайс конкурента.
 *
 * <p><b>Почему одной кнопки мало и почему одного сервера мало.</b> Спрятанная
 * кнопка при открытом адресе — не защита: адрес видно в исходниках экрана,
 * и он отдаёт файл. Закрытый адрес при видимой кнопке — обещание, которое
 * не выполняется: «Просмотр» нажимает и получает отказ, не понимая, сломано
 * ли. Поэтому проверок две, здесь и в `ExportRoleTest` на сервере, и списки
 * ролей у них общий (`EXPORT_ROLES` против `@PreAuthorize`).
 *
 * <p>Вторая сторона обязательна и тут: правка, спрятавшая ссылку от всех,
 * тоже «чинит» дыру — и делает это молча, потому что жалуется на пропавшую
 * выгрузку только владелец и не сразу.
 */
describe('«Скачать таблицу» и роли', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')
          || url.includes('/api/organization')) {
        return json([]);
      }
      return json({ total: 0, warehouses: [], filterable: [], rows: [] });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('на складе её нет у кладовщика и «Просмотра», но есть у владельца и менеджера',
    async () => {
      for (const role of ['STOREKEEPER', 'VIEWER']) {
        render(<CatalogScreen role={role} />);
        await waitFor(() => expect(link('Настроить таблицу')).toBeTruthy());
        expect(link('Скачать таблицу'),
          `«${role}» уносит весь склад одним файлом`).toBeUndefined();
        cleanup();
      }

      for (const role of ['OWNER', 'MANAGER']) {
        render(<CatalogScreen role={role} />);
        await waitFor(() => expect(link('Настроить таблицу')).toBeTruthy());
        const download = link('Скачать таблицу');
        expect(download, `«${role}» остался без выгрузки склада`).toBeTruthy();
        expect(download!.getAttribute('href'),
          'ссылка ведёт не на выгрузку витрины').toContain('/api/parts/catalog/export');
        cleanup();
      }
    });

  it('на колёсах — то же самое', async () => {
    for (const role of ['STOREKEEPER', 'VIEWER']) {
      render(<WheelsScreen canIntake role={role} />);
      await waitFor(() => expect(link('Настроить таблицу')).toBeTruthy());
      expect(link('Скачать таблицу'),
        `«${role}» уносит колёсный склад одним файлом`).toBeUndefined();
      cleanup();
    }

    for (const role of ['OWNER', 'MANAGER']) {
      render(<WheelsScreen canIntake role={role} />);
      await waitFor(() => expect(link('Настроить таблицу')).toBeTruthy());
      const download = link('Скачать таблицу');
      expect(download, `«${role}» остался без выгрузки колёс`).toBeTruthy();
      expect(download!.getAttribute('href'),
        'ссылка ведёт не на выгрузку колёс').toContain('/api/wheels/export');
      cleanup();
    }
  });
});

/**
 * Ищется по тексту среди кнопок и ссылок сразу: «Настроить таблицу» —
 * кнопка, «Скачать таблицу» — ссылка (файл качает браузер), и разница
 * между ними для этой проверки несущественна.
 */
function link(text: string): HTMLElement | undefined {
  return [...document.querySelectorAll('a, button')]
    .find((el) => el.textContent?.trim() === text) as HTMLElement | undefined;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
