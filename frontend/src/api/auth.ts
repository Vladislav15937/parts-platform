import { request } from './client';

/** Вошедший сотрудник. Права определяют, что на экранах доступно. */
export interface Me {
  memberId: number;
  login: string;
  displayName: string;
  /** `ADMIN` — администратор сервиса в кабинете разборки (задача 0042). */
  role: 'OWNER' | 'MANAGER' | 'STOREKEEPER' | 'SELLER' | 'VIEWER' | 'AUDITOR' | 'ADMIN';
  companySchema: string;
  /**
   * Роль «Владелец» или администратор сервиса: все права всегда (задача 0044).
   * Нет у личности, запомненной телефоном до выкладки 0044.
   */
  owner?: boolean;
  /** Роль клиента названием; `null` — роли нет. Нет у личности до 0044. */
  roleName?: string | null;
  /**
   * Права вошедшего ключами (`access/rights.ts`). Решают, что на экранах
   * доступно, а что погашено; прежняя роль `role` остаётся для показа.
   * Нет у личности, запомненной до выкладки 0044, — см. `rightsOf`.
   */
  permissions?: string[];
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
