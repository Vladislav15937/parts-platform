import { ApiError, request } from '../api/client';
import { getAll, put, remove, STORE_OUTBOX } from '../storage/db';

/**
 * Очередь отправки.
 *
 * <p>Между приёмщиком и сервером всегда стоит она: экран кладёт запись сюда
 * и считает работу сделанной. Связь в ангаре появляется и пропадает, и если
 * экран будет ждать ответа сервера, приёмщик будет ждать вместе с ним.
 *
 * <p>Три правила, на которых всё держится.
 *
 * <p><b>Запись не удаляется до подтверждения.</b> Удалить до ответа значит
 * потерять работу при обрыве; удалить после — гарантировать, что она уйдёт
 * хотя бы раз. Дубликаты отбивает {@code requestId}, который сервер помнит.
 *
 * <p><b>Постоянные и временные ошибки разделены.</b> Повторяются только
 * временные. «Донор списан» через час будет тем же, и вечные повторы забьют
 * очередь одной битой записью, а настоящая работа за ней не уйдёт.
 *
 * <p><b>Потеря сессии останавливает обработку целиком.</b> Не помечает записи
 * ошибочными, не тратит попытки: приёмщик войдёт заново, и очередь продолжится
 * с того же места.
 */

/**
 * Что делает запись.
 *
 * <p>Фотография — отдельная операция, а не часть приёмки, потому что зависит
 * от её результата: подписанную ссылку выдают на существующую деталь,
 * а идентификатор детали появляется только после отправки партии.
 */
export type OutboxKind = 'receipt' | 'photo' | 'count';

export type OutboxState =
  /** Ждёт отправки. */
  | 'pending'
  /** Отклонено сервером по существу. Нужен человек. */
  | 'failed';

/** Снимок, ждущий отправки вместе со своей позицией партии. */
export interface PendingPhoto {
  /** Номер позиции в партии: деталей ещё нет, привязываться больше не к чему. */
  itemIndex: number;
  blob: Blob;
  contentType: string;
  width: number;
  height: number;
}

export interface OutboxRecord {
  id: string;
  /** Ключ идемпотентности. Не меняется при повторах — в этом весь смысл. */
  requestId: string;
  kind: OutboxKind;
  payload: unknown;
  /** Человекочитаемое описание для экрана очереди. */
  title: string;
  state: OutboxState;
  attempts: number;
  /** Не раньше этого момента: экспоненциальная задержка после отказов. */
  nextAttemptAt: number;
  lastError?: string;
  createdAt: number;
  /** Снимки партии. Уезжают отдельными записями после её отправки. */
  photos?: PendingPhoto[];
  /** Тело снимка для записи вида photo. */
  blob?: Blob;
}

/** Итог одного прохода очереди. */
export interface ProcessResult {
  sent: number;
  failed: number;
  /** Обработка остановлена: сессия кончилась, нужен вход. */
  needsSignIn: boolean;
}

/**
 * Отправка одной записи.
 *
 * <p>Внедряется, чтобы очередь проверялась без сети. Может вернуть записи-
 * продолжения: отправленная партия порождает по записи на каждый снимок,
 * потому что раньше идентификаторов деталей не существовало.
 */
export type Sender = (record: OutboxRecord) => Promise<OutboxRecord[] | void>;

/**
 * Задержка перед следующей попыткой.
 *
 * <p>Растёт вдвое и упирается в пять минут. Без верхней границы телефон,
 * пролежавший ночь без связи, проснулся бы с задержкой в сутки — и приёмщик
 * утром не понимал бы, почему ничего не уходит.
 */
const BASE_DELAY_MS = 5_000;
const MAX_DELAY_MS = 5 * 60_000;

export function backoffMs(attempts: number): number {
  return Math.min(BASE_DELAY_MS * 2 ** Math.max(0, attempts - 1), MAX_DELAY_MS);
}

/**
 * Момент постановки в очередь, строго возрастающий.
 *
 * <p>Обычного {@code Date.now()} мало: несколько записей попадают в одну
 * миллисекунду, время у них совпадает, и порядок начинает определять случайный
 * идентификатор. Очередь при этом отправляет записи вперемешку — а фотографии
 * зависят от того, что их деталь уже создана.
 *
 * <p>Между запусками приложения счётчик не нужен: часы за это время уходят
 * вперёд заведомо дальше.
 */
let lastCreatedAt = 0;

function nextCreatedAt(): number {
  lastCreatedAt = Math.max(Date.now(), lastCreatedAt + 1);
  return lastCreatedAt;
}

/** Кладёт операцию в очередь и возвращает её запись. */
export async function enqueue(
  kind: OutboxKind,
  payload: unknown,
  title: string,
  photos?: PendingPhoto[],
): Promise<OutboxRecord> {
  const record: OutboxRecord = {
    id: crypto.randomUUID(),
    requestId: crypto.randomUUID(),
    kind,
    payload,
    title,
    state: 'pending',
    attempts: 0,
    nextAttemptAt: 0,
    createdAt: nextCreatedAt(),
    ...(photos !== undefined && photos.length > 0 ? { photos } : {}),
  };
  await put(STORE_OUTBOX, record);
  return record;
}

/** Все записи, свежие снизу — как их набирал приёмщик. */
export async function listOutbox(): Promise<OutboxRecord[]> {
  const records = await getAll<OutboxRecord>(STORE_OUTBOX);
  return records.sort((a, b) => a.createdAt - b.createdAt);
}

/** Убирает запись: приёмщик решил, что она не нужна. */
export function dropRecord(id: string): Promise<unknown> {
  return remove(STORE_OUTBOX, id);
}

/**
 * Возвращает отклонённую запись в очередь.
 *
 * <p>Нужна после исправления причины на сервере: донора вернули из списанных,
 * ячейку завели заново. Счётчик попыток обнуляется — иначе запись уйдёт
 * с пятиминутной задержкой, и приёмщик решит, что кнопка не работает.
 */
export async function retryRecord(id: string): Promise<void> {
  const records = await getAll<OutboxRecord>(STORE_OUTBOX);
  const record = records.find((r) => r.id === id);
  if (record === undefined) {
    return;
  }
  await put(STORE_OUTBOX, {
    ...record,
    state: 'pending' as const,
    attempts: 0,
    nextAttemptAt: 0,
    lastError: undefined,
  });
}

/** Чтобы два прохода не отправили одну запись дважды. */
let running = false;

/**
 * Проходит очередь: отправляет всё, чему пришло время.
 *
 * @param now время для проверки задержек — параметром, чтобы тесты
 *            не ждали пять минут по-настоящему
 */
export async function processOutbox(
  send: Sender = sendRecord,
  now: number = Date.now(),
): Promise<ProcessResult> {
  if (running) {
    return { sent: 0, failed: 0, needsSignIn: false };
  }
  running = true;

  const result: ProcessResult = { sent: 0, failed: 0, needsSignIn: false };
  try {
    const records = await listOutbox();

    for (const record of records) {
      if (record.state !== 'pending' || record.nextAttemptAt > now) {
        continue;
      }
      try {
        const followUps = await send(record);
        // Удаляем только после ответа: обрыв до него означает повтор,
        // а не потерю.
        await remove(STORE_OUTBOX, record.id);
        result.sent += 1;

        if (followUps !== undefined && followUps.length > 0) {
          // Снимки отправляются в этом же проходе: связь только что была,
          // и ждать следующего тика значит терять её окно.
          for (const followUp of followUps) {
            await put(STORE_OUTBOX, followUp);
            records.push(followUp);
          }
        }
      } catch (error) {
        const kind = error instanceof ApiError ? error.kind : 'transient';
        const message = error instanceof Error ? error.message : 'Неизвестная ошибка';

        if (kind === 'unauthenticated') {
          // Сессия кончилась. Записи не трогаем вовсе: попытки не тратим,
          // ошибок не приписываем — виноват не приёмщик.
          result.needsSignIn = true;
          break;
        }
        if (kind === 'permanent') {
          await put(STORE_OUTBOX, {
            ...record,
            state: 'failed' as const,
            lastError: message,
            attempts: record.attempts + 1,
          });
          result.failed += 1;
          continue;
        }

        const attempts = record.attempts + 1;
        await put(STORE_OUTBOX, {
          ...record,
          attempts,
          nextAttemptAt: now + backoffMs(attempts),
          lastError: message,
        });
        // Временная ошибка почти наверняка повторится на следующей записи:
        // если сети нет, она не появится за миллисекунду. Прекращаем проход,
        // чтобы не потратить попытки всей очереди на один обрыв.
        break;
      }
    }
  } finally {
    running = false;
  }
  return result;
}

/** Ответ приёмки. Порядок карточек повторяет порядок позиций запроса. */
interface ReceiptResponse {
  parts: { id: number }[];
}

interface UploadResponse {
  photoId: number;
  key: string;
  uploadUrl: string;
}

/** Настоящая отправка. Идемпотентность обеспечивает requestId в теле. */
async function sendRecord(record: OutboxRecord): Promise<OutboxRecord[] | void> {
  if (record.kind === 'receipt') {
    return await sendReceipt(record);
  }
  if (record.kind === 'photo') {
    return await sendPhoto(record);
  }
  if (record.kind === 'count') {
    return await sendCount(record);
  }
  throw new Error(`Неизвестный вид операции: ${String(record.kind)}`);
}

/**
 * Отправляет подсчёт полки.
 *
 * <p><b>Давность считается здесь, а не при постановке в очередь.</b> Запись
 * может пролежать в ангаре полдня и уйти с третьей попытки; зафиксированная
 * заранее давность к этому моменту врёт ровно на время лежания — то есть
 * на всё, ради чего она нужна. Обе отметки берутся с одних часов, поэтому
 * их смещение сокращается, а на сервер уходит только разность.
 */
async function sendCount(record: OutboxRecord): Promise<void> {
  const payload = record.payload as { sessionId: number; partId: number; qty: string; countedAt: number };

  await request(`/api/inventory/sessions/${payload.sessionId}/counts`, {
    method: 'POST',
    body: {
      partId: payload.partId,
      qty: payload.qty,
      countedAgoMs: Math.max(0, Date.now() - payload.countedAt),
    },
  });
}

/**
 * Отправляет партию и порождает записи на снимки.
 *
 * <p>Снимки привязываются к деталям по номеру позиции: до ответа сервера
 * идентификаторов деталей не существует, а порядок карточек в ответе повторяет
 * порядок позиций запроса — это контракт, закреплённый тестом
 * {@code partsFollowRequestOrder}.
 */
async function sendReceipt(record: OutboxRecord): Promise<OutboxRecord[]> {
  const response = await request<ReceiptResponse>('/api/intake/receipts', {
    method: 'POST',
    body: { ...(record.payload as object), requestId: record.requestId },
  });

  const photos = record.photos ?? [];
  const followUps: OutboxRecord[] = [];

  for (const photo of photos) {
    const part = response.parts[photo.itemIndex];
    if (part === undefined) {
      // Позиции нет в ответе — привязать снимок не к чему. Тихо потерять его
      // хуже, чем не создать запись: приёмщик хотя бы не увидит ложной
      // отправки.
      continue;
    }
    followUps.push({
      id: crypto.randomUUID(),
      requestId: crypto.randomUUID(),
      kind: 'photo',
      payload: {
        partId: part.id,
        contentType: photo.contentType,
        width: photo.width,
        height: photo.height,
      },
      title: `Фото к позиции ${photo.itemIndex + 1}`,
      state: 'pending',
      attempts: 0,
      nextAttemptAt: 0,
      createdAt: nextCreatedAt(),
      blob: photo.blob,
    });
  }
  return followUps;
}

/**
 * Отправляет снимок в три шага: ссылка, загрузка, подтверждение.
 *
 * <p><b>Ссылка запрашивается здесь, а не при съёмке.</b> Она живёт пятнадцать
 * минут, а телефон бывает без связи до вечера: полученная заранее, к моменту
 * отправки она просрочена.
 *
 * <p>Загрузка идёт прямо в хранилище, минуя приложение: снимок весит сотни
 * килобайт, и гонять его через бэкенд значит занимать его потоки на минуты.
 */
async function sendPhoto(record: OutboxRecord): Promise<void> {
  const payload = record.payload as {
    partId: number;
    contentType: string;
    width: number;
    height: number;
  };
  if (record.blob === undefined) {
    throw new ApiError('permanent', 0, 'Снимок потерян: тело записи пустое');
  }

  const upload = await request<UploadResponse>(`/api/parts/${payload.partId}/photos/upload-url`, {
    method: 'POST',
    body: { contentType: payload.contentType, requestId: record.requestId },
  });

  const put = await fetch(upload.uploadUrl, {
    method: 'PUT',
    // Content-Type входит в подпись: другой здесь — отказ хранилища.
    headers: { 'Content-Type': payload.contentType },
    body: record.blob,
  }).catch(() => null);

  if (put === null || !put.ok) {
    // Хранилище недоступно или ссылка протухла — временная беда, повторим
    // с новой ссылкой.
    throw new ApiError('transient', put?.status ?? 0, 'Снимок не загрузился в хранилище');
  }

  try {
    await request(`/api/parts/${payload.partId}/photos/${upload.photoId}/confirm`, {
      method: 'POST',
      body: { width: payload.width, height: payload.height },
    });
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      // Сервер не нашёл объект в хранилище: загрузка оборвалась незаметно.
      // Общая классификация считает 409 отказом по существу, но здесь это
      // ровно наоборот — повод повторить. Тот же requestId вернёт ту же
      // запись и новую ссылку, дубликата не будет.
      throw new ApiError('transient', 409, 'Хранилище не приняло снимок, повторим');
    }
    throw error;
  }
}
