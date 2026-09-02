import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, ensureCsrfToken, request } from './client';

/**
 * Клиент API — основание будущей очереди отправки.
 *
 * <p>Проверяется классификация ошибок: от неё зависит, повторит очередь запись
 * или отправит её в «требует внимания». Ошибка здесь стоит либо забитой
 * вечными повторами очереди, либо потерянной смены приёмщика.
 */
describe('классификация ошибок', () => {
  beforeEach(() => {
    setCookie('');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('401 — потеря сессии, а не ошибка запроса', async () => {
    stubFetch({ status: 401 });

    // Очередь на 401 не повторяет и не удаляет запись: приёмщик войдёт заново.
    await expect(request('/api/parts')).rejects.toMatchObject({ kind: 'unauthenticated' });
  });

  it('нарушение правила постоянно: повторять бессмысленно', async () => {
    stubFetch({ status: 400, body: { message: 'Пароль короче 8 символов' } });

    const error = await request('/api/members', { method: 'POST', body: {} }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).kind).toBe('permanent');
    expect((error as ApiError).retryable).toBe(false);
    expect((error as ApiError).message).toBe('Пароль короче 8 символов');
  });

  it('конфликт состояния тоже постоянен', async () => {
    stubFetch({ status: 409, body: { message: 'Донор списан' } });

    await expect(request('/api/x', { method: 'POST' })).rejects.toMatchObject({
      kind: 'permanent',
    });
  });

  it('поломка сервера временна', async () => {
    stubFetch({ status: 500 });

    await expect(request('/api/x')).rejects.toMatchObject({ kind: 'transient' });
  });

  it('429 и 408 временны, хотя формально это 4xx', async () => {
    // «Попробуй позже», а не «так нельзя».
    stubFetch({ status: 429 });
    await expect(request('/api/x')).rejects.toMatchObject({ kind: 'transient' });

    stubFetch({ status: 408 });
    await expect(request('/api/x')).rejects.toMatchObject({ kind: 'transient' });
  });

  it('обрыв сети временен', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    await expect(request('/api/x')).rejects.toMatchObject({
      kind: 'transient',
      status: 0,
    });
  });

  it('нечитаемое тело ошибки не роняет клиент', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        json: () => Promise.reject(new Error('не JSON')),
      }),
    );

    await expect(request('/api/x')).rejects.toMatchObject({ kind: 'transient', status: 502 });
  });

  /**
   * Лежащее приложение за живым терминатором отвечает 502, а прокси
   * разработки — 500, и продавец видел «HTTP 500»: по этому не понять
   * ни что случилось, ни что делать. Полный обрыв сети при этом говорил
   * по-человечески — то есть хуже всего сообщение было ровно в том случае,
   * который в ангаре и случается: wi-fi поднят, а сервера за ним нет.
   */
  it('недоступный сервер объясняется словами, а не кодом', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        json: () => Promise.reject(new Error('не JSON')),
      }),
    );

    await expect(request('/api/x')).rejects.toThrow(/связ/i);
  });

  /** Отказ приложения объясняет само приложение — его текст сильнее нашего. */
  it('текст сервера доходит до человека как есть', async () => {
    stubFetch({ status: 409, body: { message: 'Нечего снимать с резерва' } });

    await expect(request('/api/x')).rejects.toThrow('Нечего снимать с резерва');
  });
});

describe('CSRF', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setCookie('');
  });

  it('токен из cookie уходит заголовком на изменяющем запросе', async () => {
    setCookie('XSRF-TOKEN=abc-123');
    const fetchMock = stubFetch({ status: 204 });

    await request('/api/x', { method: 'POST', body: { a: 1 } });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc-123');
  });

  it('на чтении токен не нужен', async () => {
    setCookie('XSRF-TOKEN=abc-123');
    const fetchMock = stubFetch({ status: 200, body: {} });

    await request('/api/x');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('403 на изменяющем запросе — сначала обновить токен и повторить', async () => {
    /*
     * Это поведение выведено из живого запуска. Отказ фильтра CSRF выглядит
     * ровно как отказ по роли — 403, — а токен за часы офлайна успевает
     * устареть. Без повтора вся накопленная смена уехала бы в «требует
     * внимания» вместо отправки.
     */
    const fetchMock = vi.fn();
    // Первый — 403 (токен устарел), второй — выдача токена, третий — успех.
    fetchMock.mockResolvedValueOnce({ ok: false, status: 403, json: () => Promise.resolve({}) });
    fetchMock.mockImplementationOnce(() => {
      setCookie('XSRF-TOKEN=свежий');
      return Promise.resolve({ ok: true, status: 204 });
    });
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: () => Promise.resolve({ ok: 1 }) });
    vi.stubGlobal('fetch', fetchMock);

    const result = await request<{ ok: number }>('/api/x', { method: 'POST' });

    expect(result).toEqual({ ok: 1 });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const [, retryInit] = fetchMock.mock.calls[2] as [string, RequestInit];
    expect((retryInit.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('свежий');
  });

  it('второй 403 — настоящий отказ по роли, повторов больше нет', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: () => Promise.resolve({ message: 'Недостаточно прав' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const error = await request('/api/members', { method: 'POST' }).catch((e: unknown) => e);

    expect((error as ApiError).kind).toBe('permanent');
    expect((error as ApiError).status).toBe(403);
    // Наружу уходит отказ по исходному запросу, а не по обновлению токена.
    expect((error as ApiError).message).toBe('Недостаточно прав');
    // Запрос и попытка обновить токен. Обновление тоже отказано — повторять
    // исходный запрос незачем.
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('токен не запрашивается повторно, если он уже есть', async () => {
    setCookie('XSRF-TOKEN=уже-есть');
    const fetchMock = stubFetch({ status: 204 });

    await ensureCsrfToken();

    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('тело ответа', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('204 отдаётся как отсутствие значения, а не как ошибка разбора', async () => {
    stubFetch({ status: 204 });

    await expect(request('/api/x', { method: 'POST' })).resolves.toBeUndefined();
  });
});

// ---------- вспомогательное ----------

function stubFetch(response: { status: number; body?: unknown }) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: response.status >= 200 && response.status < 300,
    status: response.status,
    json: () => Promise.resolve(response.body ?? {}),
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function setCookie(value: string): void {
  Object.defineProperty(document, 'cookie', {
    value,
    writable: true,
    configurable: true,
  });
}
