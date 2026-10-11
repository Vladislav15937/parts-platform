import { request } from '../api/client';

/**
 * Доступ администратора сервиса в кабинет — со стороны владельца разборки
 * (задача 0042).
 *
 * <p>Решения владельца продукта от 8 сентября 2026: вход «только с разрешения
 * владельца», срок выбирает клиент — бессрочно до отзыва либо на число часов,
 * отозвать можно, пока администратор внутри, и у клиента «отдельная кнопка
 * дать доступ администратору», не спрятанная за входящим запросом.
 */
export interface AdminAccessState {
  /** Действующее разрешение либо `null` — доступа нет. */
  grant: { grantedAt: string; expiresAt: string | null } | null;
  /** Открытый запрос администратора либо `null`. */
  request: { requestedAt: string; adminName: string } | null;
}

export function loadAdminAccess(): Promise<AdminAccessState> {
  return request<AdminAccessState>('/api/organization/admin-access');
}

/** @param hours `null` — до отзыва */
export function grantAdminAccess(hours: number | null): Promise<AdminAccessState> {
  return request<AdminAccessState>('/api/organization/admin-access/grant', {
    method: 'POST',
    body: { hours },
  });
}

export function revokeAdminAccess(): Promise<AdminAccessState> {
  return request<AdminAccessState>('/api/organization/admin-access/revoke', { method: 'POST' });
}

export function declineAdminAccess(): Promise<AdminAccessState> {
  return request<AdminAccessState>('/api/organization/admin-access/decline', { method: 'POST' });
}

/**
 * Момент со временем — словами, по-русски: «12 октября, 18:00».
 *
 * <p>Не ISO-строкой: «2026-10-12T15:00:00Z» владелец не читает, а час тут —
 * то, ради чего строку и показывают («до какого часа он открыл»).
 */
export function momentText(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', {
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  });
}
