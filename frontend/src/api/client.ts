/**
 * Клиент API.
 *
 * <p>Здесь два решения, от которых зависит вся офлайн-приёмка.
 *
 * <p><b>Ошибки делятся на постоянные и временные.</b> Очередь отправки повторяет
 * только временные: сеть отвалилась, сервер ответил 500. Постоянные повторять
 * бессмысленно — «донор списан» и «пароль короткий» через час будут теми же,
 * и вечные повторы забьют очередь одной битой записью. Именно ради этого
 * различения на бэкенде появился ApiExceptionHandler: до него любое нарушение
 * правила приезжало как 500, то есть выглядело временным.
 *
 * <p><b>401 — не ошибка запроса, а потеря сессии.</b> Он не должен приводить
 * ни к повтору, ни к удалению записи из очереди: телефон был без связи, сессия
 * истекла, приёмщик войдёт заново, и очередь должна дождаться этого целой.
 */

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

/** Почему запрос не удался. Определяет поведение очереди. */
export type FailureKind =
  /** Повторять: сеть, таймаут, 5xx, 429. */
  | 'transient'
  /** Не повторять, показать человеку: 4xx с нарушением правила. */
  | 'permanent'
  /** Сессия потеряна: дождаться входа и повторить, запись не терять. */
  | 'unauthenticated';

export class ApiError extends Error {
  constructor(
    readonly kind: FailureKind,
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  get retryable(): boolean {
    return this.kind !== 'permanent';
  }
}

function csrfToken(): string | null {
  // Токен лежит в cookie, читаемой скриптом (withHttpOnlyFalse на бэкенде):
  // приложение обязано переложить его в заголовок само.
  const match = document.cookie
    .split('; ')
    .find((part) => part.startsWith(`${CSRF_COOKIE}=`));
  return match ? decodeURIComponent(match.slice(CSRF_COOKIE.length + 1)) : null;
}

function classify(status: number): FailureKind {
  if (status === 401) {
    return 'unauthenticated';
  }
  // 408 и 429 формально 4xx, но означают «попробуй позже», а не «так нельзя».
  if (status === 408 || status === 429 || status >= 500) {
    return 'transient';
  }
  return 'permanent';
}

/**
 * Текст отказа для человека.
 *
 * <p>Приложение объясняет свои отказы само — «Нечего снимать с резерва»,
 * «нет количества», — и такой текст уходит наружу как есть.
 *
 * <p><b>А вот когда тела нет, показывать код ответа нельзя.</b> Лежащее
 * приложение за живым терминатором отвечает 502, а прокси разработки — 500,
 * и продавец видел «HTTP 500»: по этому не понять ни что случилось, ни что
 * делать. Полный обрыв сети при этом говорил по-человечески — «Нет связи
 * с сервером», — то есть хуже всего сообщение было ровно в том случае,
 * который в ангаре и случается: wi-fi поднят, а сервера за ним нет.
 */
async function messageOf(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (body.message) {
      return body.message;
    }
  } catch {
    // Тело может быть пустым (204) или не JSON — это не повод падать.
  }
  return response.status >= 500
    ? 'Сервер не отвечает — проверьте связь и повторите'
    : `Запрос отклонён (${response.status})`;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  /** Ключ идемпотентности: тот же при повторах, иначе появится вторая партия. */
  requestId?: string;
}

/**
 * Один запрос к API.
 *
 * @throws ApiError всегда с определённым {@link FailureKind} — вызывающий
 *         не должен разбирать коды сам
 */
export async function request<T>(
  path: string,
  options: RequestOptions = {},
  retrying = false,
): Promise<T> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = {};

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (method !== 'GET') {
    const token = csrfToken();
    if (token) {
      headers[CSRF_HEADER] = token;
    }
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      // Тот же источник: фронтенд отдаётся через прокси, см. vite.config.ts.
      credentials: 'same-origin',
      body: options.body === undefined ? null : JSON.stringify(options.body),
    });
  } catch {
    // fetch отклоняется только на сетевых ошибках — это всегда временно.
    throw new ApiError('transient', 0, 'Нет связи с сервером');
  }

  /*
   * 403 — не обязательно «вам нельзя».
   *
   * Отказ фильтра CSRF выглядит точно так же, а токен живёт в cookie сессии
   * и за часы офлайна успевает устареть. Считать такой ответ постоянной ошибкой
   * значит отправить всю накопленную смену в «требует внимания» вместо того,
   * чтобы обновить токен и повторить.
   *
   * Поэтому один повтор с новым токеном. Если и он получил 403 — это настоящий
   * отказ по роли, и повторять больше нечего. Лишний запрос на отказ по роли
   * дешевле потерянной работы приёмщика.
   */
  if (response.status === 403 && method !== 'GET' && !retrying) {
    try {
      await refreshCsrfToken();
      return await request<T>(path, options, true);
    } catch (error) {
      // Если не удалось даже обновить токен, наружу должна уйти исходная
      // ошибка запроса, а не ошибка обновления: вызывающему важно, что
      // отклонили его операцию, а не что не пришёл токен.
      if (error instanceof ApiError && error.status === 403) {
        throw new ApiError('permanent', 403, await messageOf(response));
      }
      throw error;
    }
  }

  if (!response.ok) {
    throw new ApiError(classify(response.status), response.status, await messageOf(response));
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Забирает CSRF-токен, если его ещё нет.
 *
 * <p>Вызывается при старте: до этого cookie с токеном нет, и вход получит 403 —
 * ответ, по которому невозможно догадаться, что дело в токене, а не в пароле.
 */
export async function ensureCsrfToken(): Promise<void> {
  if (csrfToken()) {
    return;
  }
  await refreshCsrfToken();
}

/**
 * Забирает токен заново, даже если старый есть.
 *
 * <p>Нужен после 403: старый токен мог устареть вместе с сессией, пока телефон
 * был без связи.
 */
export async function refreshCsrfToken(): Promise<void> {
  await request<void>('/api/auth/csrf', {}, true);
}

/**
 * Отправка файла.
 *
 * <p>Отдельно от {@link request}: у multipart свой {@code Content-Type}
 * с границей, и задать его руками нельзя — браузер обязан сгенерировать
 * границу сам. Поэтому заголовок не ставится вовсе, а тело уходит как
 * {@code FormData}.
 *
 * <p>Повтор при 403 здесь тот же, что и у обычного запроса: просроченный
 * CSRF-токен выглядит отказом по роли, а перезагружать многомегабайтную
 * выгрузку из-за этого незачем.
 */
export async function upload<T>(path: string, form: FormData, retrying = false): Promise<T> {
  const headers: Record<string, string> = {};
  const token = csrfToken();
  if (token) {
    headers[CSRF_HEADER] = token;
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method: 'POST',
      headers,
      credentials: 'same-origin',
      body: form,
    });
  } catch {
    throw new ApiError('transient', 0, 'Нет связи с сервером');
  }

  if (response.status === 403 && !retrying) {
    await refreshCsrfToken();
    return await upload<T>(path, form, true);
  }
  if (!response.ok) {
    throw new ApiError(classify(response.status), response.status, await messageOf(response));
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}
