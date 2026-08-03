import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

/**
 * Досъёмка снимков в карточке.
 *
 * <p>Три шага — ссылка, загрузка в хранилище, подтверждение, — и ошибка
 * на среднем шаге тихая: запись о снимке в базе есть, файла нет, а в фид
 * площадки уезжает ссылка в никуда. За такое площадка снимает объявление.
 */

const requestMock = vi.fn();
const resizeMock = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, request: (...args: unknown[]) => requestMock(...args) };
});

vi.mock('../photos/resize', () => ({
  resizePhoto: (...args: unknown[]) => resizeMock(...args),
  RESIZED_CONTENT_TYPE: 'image/jpeg',
}));

const { uploadPhoto } = await import('./photos');
const { ApiError } = await import('../api/client');

beforeEach(() => {
  requestMock.mockReset();
  resizeMock.mockReset();
  resizeMock.mockResolvedValue({
    blob: new Blob(['x']), contentType: 'image/jpeg', width: 800, height: 600,
  });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }));
  vi.stubGlobal('crypto', { randomUUID: () => 'ключ-запроса' });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('добавление снимка к карточке', () => {
  it('идёт тремя шагами: ссылка, хранилище, подтверждение', async () => {
    requestMock
      .mockResolvedValueOnce({ photoId: 7, key: 'k', uploadUrl: 'https://s3/put' })
      .mockResolvedValueOnce(undefined);

    await uploadPhoto(42, new File(['x'], 'снимок.jpg'));

    const [urlCall, confirmCall] = requestMock.mock.calls as [unknown[], unknown[]];
    expect(urlCall[0]).toBe('/api/parts/42/photos/upload-url');
    // Ключ клиента: повтор после обрыва вернёт ту же запись, а не заведёт
    // второй снимок.
    expect((urlCall[1] as { body: { requestId: string } }).body.requestId)
      .toBe('ключ-запроса');

    const put = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [
      string, { method: string; headers: Record<string, string> },
    ];
    expect(put[0]).toBe('https://s3/put');
    expect(put[1].method).toBe('PUT');
    // Content-Type входит в подпись: другой здесь — отказ хранилища.
    expect(put[1].headers['Content-Type']).toBe('image/jpeg');

    expect(confirmCall[0]).toBe('/api/parts/42/photos/7/confirm');
    expect((confirmCall[1] as { body: unknown }).body)
      .toEqual({ width: 800, height: 600 });
  });

  it('уменьшает снимок до отправки', async () => {
    // Фотография с телефона весит пять мегабайт, а в карточке и в прайсе
    // площадки от них не остаётся ничего, кроме времени загрузки.
    requestMock
      .mockResolvedValueOnce({ photoId: 1, key: 'k', uploadUrl: 'https://s3/put' })
      .mockResolvedValueOnce(undefined);
    const file = new File(['x'], 'снимок.jpg');

    await uploadPhoto(1, file);

    expect(resizeMock).toHaveBeenCalledWith(file);
  });

  it('не подтверждает снимок, если хранилище его не приняло', async () => {
    // Иначе в карточке появится битая картинка, а в фид уедет ссылка
    // в никуда — за такое площадка снимает объявление.
    requestMock.mockResolvedValueOnce({ photoId: 3, key: 'k', uploadUrl: 'https://s3/put' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }));

    await expect(uploadPhoto(5, new File(['x'], 'снимок.jpg'))).rejects.toBeInstanceOf(ApiError);
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it('обрыв связи с хранилищем — временная беда, а не потеря снимка', async () => {
    requestMock.mockResolvedValueOnce({ photoId: 3, key: 'k', uploadUrl: 'https://s3/put' });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('сети нет')));

    await expect(uploadPhoto(5, new File(['x'], 'снимок.jpg')))
      .rejects.toMatchObject({ kind: 'transient' });
  });
});
