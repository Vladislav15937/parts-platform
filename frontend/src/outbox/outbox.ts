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

/** Что делает запись. Пока одна операция; фотографии добавятся следующим шагом. */
export type OutboxKind = 'receipt';

export type OutboxState =
  /** Ждёт отправки. */
  | 'pending'
  /** Отклонено сервером по существу. Нужен человек. */
  | 'failed';

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
}

/** Итог одного прохода очереди. */
export interface ProcessResult {
  sent: number;
  failed: number;
  /** Обработка остановлена: сессия кончилась, нужен вход. */
  needsSignIn: boolean;
}

/** Отправка одной записи. Внедряется, чтобы очередь проверялась без сети. */
export type Sender = (record: OutboxRecord) => Promise<void>;

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
        await send(record);
        // Удаляем только после ответа: обрыв до него означает повтор,
        // а не потерю.
        await remove(STORE_OUTBOX, record.id);
        result.sent += 1;
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

/** Настоящая отправка. Идемпотентность обеспечивает requestId в теле. */
async function sendRecord(record: OutboxRecord): Promise<void> {
  if (record.kind === 'receipt') {
    await request('/api/intake/receipts', {
      method: 'POST',
      body: { ...(record.payload as object), requestId: record.requestId },
    });
    return;
  }
  throw new Error(`Неизвестный вид операции: ${String(record.kind)}`);
}
