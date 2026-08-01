import { request } from '../api/client';

/**
 * Сотрудники арендатора.
 *
 * <p>Учётные записи живут в схеме клиента, а не в общем контуре: восстановление
 * одного клиента из бэкапа обязано возвращать и его людей, а логин не обязан
 * быть уникальным между компаниями.
 */
export interface Member {
  id: number;
  login: string;
  displayName: string | null;
  role: Role;
  active: boolean;
  lastLoginAt: string | null;
}

export type Role = 'OWNER' | 'MANAGER' | 'STOREKEEPER' | 'SELLER' | 'VIEWER';

/**
 * Что делает роль. Показывается рядом с выбором: «менеджер» и «продавец»
 * звучат похоже, а видят разное — у менеджера отчёты с зарплатной базой
 * смены и себестоимостью.
 */
export const ROLES: Array<{ role: Role; title: string; can: string }> = [
  { role: 'OWNER', title: 'Владелец', can: 'всё, включая сотрудников и выгрузки' },
  { role: 'MANAGER', title: 'Менеджер', can: 'склад, продажи, отчёты, выгрузки' },
  { role: 'SELLER', title: 'Продавец', can: 'поиск, продажа, возврат — без отчётов' },
  { role: 'STOREKEEPER', title: 'Кладовщик', can: 'приёмка, пересчёт, этикетки' },
  { role: 'VIEWER', title: 'Просмотр', can: 'только смотреть' },
];

export function roleTitle(role: string): string {
  return ROLES.find((r) => r.role === role)?.title ?? role;
}

export function loadMembers(): Promise<Member[]> {
  return request<Member[]>('/api/members');
}

export function createMember(
  login: string, password: string, displayName: string, role: Role,
): Promise<Member> {
  return request<Member>('/api/members', {
    method: 'POST',
    body: { login, password, displayName, role },
  });
}

/**
 * Пароль задаётся заново, а не показывается: хранится он хешем, и «напомнить»
 * его нельзя никому — ни сотруднику, ни владельцу.
 */
export function changePassword(id: number, password: string): Promise<void> {
  return request<void>(`/api/members/${id}/password`, { method: 'POST', body: { password } });
}

/**
 * Выключение вместо удаления: за сотрудником остались приёмки, продажи
 * и записи в истории документов, и удалить его значит стереть автора
 * у совершённых операций.
 */
export function disableMember(id: number): Promise<void> {
  return request<void>(`/api/members/${id}/disable`, { method: 'POST' });
}

export function enableMember(id: number): Promise<void> {
  return request<void>(`/api/members/${id}/enable`, { method: 'POST' });
}
