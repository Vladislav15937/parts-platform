import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAll, remove, STORE_OUTBOX } from '../storage/db';
import { enqueue, listOutbox, processOutbox } from './outbox';
import type { OutboxRecord, PendingPhoto } from './outbox';

/**
 * Отправка снимков.
 *
 * <p>Проверяется связка, которую нельзя увидеть ни в одном из концов по
 * отдельности: снимок сделан до того, как деталь существует, и найти её он
 * может только по номеру позиции в партии. Ошибись здесь — и фотография от
 * бампера уедет к фаре, причём молча.
 *
 * <p>Сеть подменена целиком: настоящего хранилища в тестах нет, а важен
 * порядок обращений и то, как очередь ведёт себя при отказе каждого из трёх
 * шагов.
 */

const { calls, requestMock } = vi.hoisted(() => ({
  calls: [] as { path: string; body: unknown }[],
  requestMock: vi.fn(),
}));

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>();
  return { ...actual, request: requestMock };
});

const { ApiError } = await import('../api/client');

/** Отвечает как исправный сервер: приёмка, ссылка, подтверждение. */
function happyServer(): void {
  requestMock.mockImplementation(async (path: string, init?: { body?: unknown }) => {
    calls.push({ path, body: init?.body });
    if (path === '/api/intake/receipts') {
      return { parts: [{ id: 501 }, { id: 502 }] };
    }
    if (path.endsWith('/upload-url')) {
      return { photoId: 9001, key: 'k', uploadUrl: 'https://s3.local/put/k' };
    }
    return undefined;
  });
}

function photo(itemIndex: number): PendingPhoto {
  return {
    itemIndex,
    blob: new Blob(['jpeg'], { type: 'image/jpeg' }),
    contentType: 'image/jpeg',
    width: 1600,
    height: 1200,
  };
}

describe('снимки в очереди', () => {
  beforeEach(async () => {
    for (const record of await getAll<OutboxRecord>(STORE_OUTBOX)) {
      await remove(STORE_OUTBOX, record.id);
    }
    calls.length = 0;
    requestMock.mockReset();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 200 })),
    );
  });

  it('снимки уходят после партии и попадают на свои детали', async () => {
    happyServer();
    // Первый снимок к первой позиции, второй — ко второй.
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(0), photo(1)]);

    const result = await processOutbox();

    // Партия и два снимка — всё в одном проходе: связь была именно сейчас.
    expect(result.sent).toBe(3);
    expect(await listOutbox()).toEqual([]);

    const uploads = calls.filter((c) => c.path.endsWith('/upload-url'));
    expect(uploads.map((c) => c.path)).toEqual([
      '/api/parts/501/photos/upload-url',
      '/api/parts/502/photos/upload-url',
    ]);
  });

  it('несколько снимков одной позиции идут на одну деталь', async () => {
    happyServer();
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(1), photo(1)]);

    await processOutbox();

    const uploads = calls.filter((c) => c.path.endsWith('/upload-url'));
    expect(uploads).toHaveLength(2);
    expect(uploads.every((c) => c.path === '/api/parts/502/photos/upload-url')).toBe(true);
  });

  it('порядок шагов соблюдается: ссылка, загрузка, подтверждение', async () => {
    happyServer();
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(0)]);

    await processOutbox();

    expect(calls.map((c) => c.path)).toEqual([
      '/api/intake/receipts',
      '/api/parts/501/photos/upload-url',
      '/api/parts/501/photos/9001/confirm',
    ]);
    // Подписанная ссылка берётся перед самой загрузкой, а не при съёмке:
    // она живёт минуты, а телефон бывает без связи часами.
    expect(vi.mocked(fetch).mock.calls[0]?.[0]).toBe('https://s3.local/put/k');
  });

  it('ключ идемпотентности у снимка свой и переживает повтор', async () => {
    happyServer();
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 503 }));
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(0)]);

    // Первый проход: партия ушла, хранилище отказало.
    await processOutbox();
    const queued = await listOutbox();
    expect(queued).toHaveLength(1);
    const firstRequestId = queued[0]?.requestId;

    // Второй проход по тем же данным, задержка уже прошла.
    await processOutbox(undefined, Date.now() + 60_000);

    const uploads = calls.filter((c) => c.path.endsWith('/upload-url'));
    expect(uploads).toHaveLength(2);
    // Тот же ключ на повторе: иначе сервер заведёт вторую запись и оставит
    // в хранилище сирот.
    expect((uploads[1]?.body as { requestId: string }).requestId).toBe(firstRequestId);
  });

  it('отказ хранилища не теряет снимок и не подтверждает загрузку', async () => {
    happyServer();
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(0)]);

    const result = await processOutbox();

    expect(result.sent).toBe(1); // только партия
    expect(result.failed).toBe(0);
    const queue = await listOutbox();
    expect(queue).toHaveLength(1);
    expect(queue[0]?.kind).toBe('photo');
    expect(queue[0]?.state).toBe('pending');
    // Подтверждать нечего: объект в хранилище не появился.
    expect(calls.some((c) => c.path.endsWith('/confirm'))).toBe(false);
  });

  it('409 при подтверждении означает повтор, а не отказ', async () => {
    requestMock.mockImplementation(async (path: string, init?: { body?: unknown }) => {
      calls.push({ path, body: init?.body });
      if (path === '/api/intake/receipts') {
        return { parts: [{ id: 501 }] };
      }
      if (path.endsWith('/upload-url')) {
        return { photoId: 9001, key: 'k', uploadUrl: 'https://s3.local/put/k' };
      }
      // Сервер не нашёл объект: загрузка оборвалась незаметно для браузера.
      throw new ApiError('permanent', 409, 'Объект не найден');
    });
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(0)]);

    const result = await processOutbox();

    expect(result.failed).toBe(0);
    const queue = await listOutbox();
    // Общая классификация считает 409 отказом по существу. Здесь наоборот:
    // пометить снимок отклонённым значит потерять его насовсем.
    expect(queue[0]?.state).toBe('pending');
    expect(queue[0]?.attempts).toBe(1);
  });

  it('непрошедшая партия не порождает снимков', async () => {
    requestMock.mockImplementation(async () => {
      throw new ApiError('transient', 0, 'Нет связи с сервером');
    });
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(0), photo(1)]);

    await processOutbox();

    const queue = await listOutbox();
    // Деталей нет — привязывать снимки не к чему. Они ждут внутри партии
    // и уедут вместе с её повтором.
    expect(queue).toHaveLength(1);
    expect(queue[0]?.kind).toBe('receipt');
    expect(queue[0]?.photos).toHaveLength(2);
  });

  it('снимок несуществующей позиции не отправляется', async () => {
    requestMock.mockImplementation(async (path: string, init?: { body?: unknown }) => {
      calls.push({ path, body: init?.body });
      // Сервер вернул одну карточку, а снимок сделан ко второй позиции.
      return path === '/api/intake/receipts'
        ? { parts: [{ id: 501 }] }
        : { photoId: 1, key: 'k', uploadUrl: 'https://s3.local/put/k' };
    });
    await enqueue('receipt', { warehouseId: 1 }, 'Партия', [photo(5)]);

    const result = await processOutbox();

    expect(result.sent).toBe(1);
    // Отправить его наугад к первой попавшейся детали хуже, чем не отправить:
    // чужая фотография в карточке уедет на площадку и продаст не то.
    expect(calls.some((c) => c.path.endsWith('/upload-url'))).toBe(false);
    expect(await listOutbox()).toEqual([]);
  });
});
