import { request } from './client';

/** Вошедший сотрудник. Роль определяет, что показывать на экранах. */
export interface Me {
  memberId: number;
  login: string;
  displayName: string;
  role: 'OWNER' | 'MANAGER' | 'STOREKEEPER' | 'SELLER' | 'VIEWER';
  companySchema: string;
}

/**
 * Код компании нужен потому, что учётные записи живут в схеме арендатора:
 * найти сотрудника, не выбрав схему, невозможно. Позже код будет приходить
 * из поддомена, и с формы уйдёт.
 */
export interface Credentials {
  company: string;
  login: string;
  password: string;
}

export function login(credentials: Credentials): Promise<Me> {
  return request<Me>('/api/auth/login', { method: 'POST', body: credentials });
}

export function logout(): Promise<void> {
  return request<void>('/api/auth/logout', { method: 'POST' });
}

/** Кто я. Нужно при старте: сессия могла остаться живой с прошлого раза. */
export function me(): Promise<Me> {
  return request<Me>('/api/auth/me');
}
