import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

/**
 * Не-картинка отбивается, а не раскодированная картинка — нет.
 *
 * <p>Это две проверки, и вторая обязательна: наивное «не удалось раскодировать
 * → не картинка» отвергло бы **HEIC**, которым снимает айфон, то есть ровно
 * тот снимок от поставщика, ради которого загрузка с компьютера и делается.
 * `createImageBitmap` падает на HEIC в части браузеров.
 *
 * <p>До правки 30 сентября 2026 первый случай проходил целиком: уменьшение
 * на PDF падало и отправляло файл как есть, сервер тип не проверял вовсе,
 * хранилище звало объект `.jpg` — и в объявление уезжала битая картинка,
 * за которую площадка снимает объявление.
 */

const requestMock = vi.fn();

vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client');
  return { ...actual, request: (...args: unknown[]) => requestMock(...args) };
});

const { uploadPhoto } = await import('./photos');
const { ApiError } = await import('../api/client');

/**
 * PDF, названный `.jpg`.
 *
 * <p>Тип `image/jpeg` здесь не выдумка теста: браузер берёт `file.type`
 * из расширения, а не из содержимого, — поэтому по заявленному типу такой
 * файл от снимка не отличить, и смотреть надо байты.
 */
function pdfNamedJpg(): File {
  return new File([new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34])], 'счёт.jpg', {
    type: 'image/jpeg',
  });
}

/** Снимок с айфона: контейнер ISO-BMFF, подпись «ftyp» на четвёртом байте. */
function heicFile(): File {
  const head = [
    0x00, 0x00, 0x00, 0x18, // размер блока
    0x66, 0x74, 0x79, 0x70, // ftyp
    0x68, 0x65, 0x69, 0x63, // heic
  ];
  return new File([new Uint8Array(head)], 'IMG_0007.heic', { type: 'image/heic' });
}

beforeEach(() => {
  requestMock.mockReset();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }));
  vi.stubGlobal('crypto', { randomUUID: () => 'ключ-запроса' });
  // Браузер, который HEIC не раскодировал: именно так ведёт себя часть
  // настоящих браузеров, и уменьшение уходит в catch.
  vi.stubGlobal(
    'createImageBitmap',
    vi.fn().mockRejectedValue(new Error('формат не поддерживается')),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('файл, который не картинка', () => {
  it('отбивается словами и не оставляет ни одной записи о снимке', async () => {
    await expect(uploadPhoto(42, pdfNamedJpg())).rejects.toBeInstanceOf(ApiError);

    // Ни ссылки, ни подтверждения: записи о снимке не появилось вовсе.
    expect(requestMock).not.toHaveBeenCalled();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  it('называет, что именно не так, и не повторяется очередью', async () => {
    const failure = await uploadPhoto(42, pdfNamedJpg()).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    const error = failure as InstanceType<typeof ApiError>;
    expect(error.message).toContain('не картинка');
    // Файл не станет картинкой от повтора — очередь такое не повторяет.
    expect(error.kind).toBe('permanent');
    expect(error.message).toContain('счёт.jpg');
  });
});

describe('картинка, которую браузер не раскодировал', () => {
  it('загружается как есть — HEIC не отвергается', async () => {
    requestMock
      .mockResolvedValueOnce({ photoId: 9, key: 'k', uploadUrl: 'https://s3/put' })
      .mockResolvedValueOnce(undefined);

    await uploadPhoto(7, heicFile());

    const [urlCall, confirmCall] = requestMock.mock.calls as [unknown[], unknown[]];
    expect(urlCall[0]).toBe('/api/parts/7/photos/upload-url');
    // Тип уезжает свой, а не подменённый на image/jpeg: по нему хранилище
    // назовёт объект .heic, и подпись ссылки сойдётся.
    expect((urlCall[1] as { body: { contentType: string } }).body.contentType).toBe('image/heic');

    const put = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [
      string,
      { headers: Record<string, string> },
    ];
    expect(put[1].headers['Content-Type']).toBe('image/heic');

    // Запись о снимке есть — подтверждение дошло.
    expect(confirmCall[0]).toBe('/api/parts/7/photos/9/confirm');
  });
});
