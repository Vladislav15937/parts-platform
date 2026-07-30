import { request } from '../api/client';

/**
 * То, что не доехало до площадок.
 *
 * <p>Событие, которое обработчик не принял, ложится в разбор. На языке
 * владельца это «сделка выдана, а объявление на Дроме висит доступным» —
 * и до появления этого экрана он узнавал об этом от клиента, приехавшего
 * за проданной деталью.
 *
 * <p>Большинство отказов временные, и повторяет их робот. Сюда владелец
 * заходит за тем, что робот не осилил: неверный ключ кабинета сам
 * не починится.
 */

export interface DeadLetter {
  id: number;
  handler: string;
  eventId: number;
  eventType: string;
  aggregateType: string | null;
  aggregateId: number;
  error: string;
  attempts: number;
  /** Робот отступился — дальше решает человек. */
  needsAttention: boolean;
  createdAt: string;
  nextAttemptAt: string;
}

export interface DeadLetterPage {
  items: DeadLetter[];
  total: number;
}

export function deadLetters(): Promise<DeadLetterPage> {
  return request<DeadLetterPage>('/api/events/dead-letters');
}

export interface RetryResult {
  delivered: boolean;
  /** Причина отказа. То же имя, что у общего ответа об ошибке. */
  message: string | null;
}

export function retryDeadLetter(id: number): Promise<RetryResult> {
  return request<RetryResult>(`/api/events/dead-letters/${id}/retry`, { method: 'POST' });
}

export function discardDeadLetter(id: number): Promise<RetryResult> {
  return request<RetryResult>(`/api/events/dead-letters/${id}/discard`, { method: 'POST' });
}

/**
 * Что именно не уехало — словами владельца.
 *
 * <p>«deal.issued.v1» ему ничего не говорит, а «выдача сделки» говорит.
 * Незнакомый тип показывается как есть: соврать про него нечего, а спрятать
 * значит потерять запись из разбора.
 */
export function eventName(type: string): string {
  const names: Record<string, string> = {
    'deal.issued.v1': 'Выдача сделки',
    'deal.returned.v1': 'Возврат по сделке',
    'deal.cancelled.v1': 'Отмена сделки',
    'part.price_changed.v1': 'Изменение цены',
  };
  return names[type] ?? type;
}

/** Куда не уехало. Тот же приём: имя обработчика владельцу ни о чём. */
export function targetName(handler: string): string {
  const names: Record<string, string> = {
    'drom-deal-delta': 'Дром',
  };
  return names[handler] ?? handler;
}
