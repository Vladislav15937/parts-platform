import { refreshCsrfToken, request } from '../api/client';

/**
 * Система администрирования сервиса — отдельная от всех кабинетов (задача 0042).
 *
 * <p>Решение владельца продукта от 8 сентября 2026: «разборка выбирается
 * только в административном окне, живущем отдельно от всех разборок».
 * Отсюда свой вход (без кода компании), свой список клиентов и свой путь
 * в кабинет — только с разрешения владельца разборки.
 */
export interface AdminMe {
  login: string;
  displayName: string;
  /** Кабинет, в котором администратор сейчас, либо `null`. */
  visit: { code: string | null; companyName: string | null; expiresAt: string | null } | null;
}

export interface TenantAccess {
  code: string;
  companyName: string;
  /** Действующее разрешение владельца; `expiresAt === null` — до отзыва. */
  grant: { grantedAt: string; expiresAt: string | null } | null;
  /** Когда администратор попросил доступ, если запрос ещё открыт. */
  requestedAt: string | null;
}

export function adminMe(): Promise<AdminMe> {
  return request<AdminMe>('/api/admin/me');
}

/**
 * Токен берётся заново, как у входа в кабинет: вчерашний из cookie сервер
 * отвергнет ответом 401 — тем же, что на неверный пароль.
 */
export async function adminLogin(login: string, password: string): Promise<AdminMe> {
  await refreshCsrfToken();
  return request<AdminMe>('/api/admin/login', { method: 'POST', body: { login, password } });
}

export function adminLogout(): Promise<void> {
  return request<void>('/api/auth/logout', { method: 'POST' });
}

export function serviceTenants(): Promise<TenantAccess[]> {
  return request<TenantAccess[]>('/api/admin/tenants');
}

export function requestAccess(code: string): Promise<void> {
  return request<void>(`/api/admin/tenants/${encodeURIComponent(code)}/request`, {
    method: 'POST',
  });
}

export function enterCabinet(code: string): Promise<{ code: string }> {
  return request<{ code: string }>(`/api/admin/tenants/${encodeURIComponent(code)}/enter`, {
    method: 'POST',
  });
}

/** Выйти из кабинета обратно в систему администрирования. */
export function leaveCabinet(): Promise<AdminMe> {
  return request<AdminMe>('/api/admin/visit/leave', { method: 'POST' });
}

/**
 * Сколько осталось до конца разрешения — словами: «3 ч 12 мин», «40 мин».
 *
 * <p>Критерий приёмки задачи 0042: срок окончания виден обеим сторонам,
 * и администратору — «сколько осталось».
 */
export function remainingText(expiresAt: string, now: number = Date.now()): string {
  const minutes = Math.max(0, Math.floor((new Date(expiresAt).getTime() - now) / 60000));
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) {
    return `${rest} мин`;
  }
  return rest === 0 ? `${hours} ч` : `${hours} ч ${rest} мин`;
}
