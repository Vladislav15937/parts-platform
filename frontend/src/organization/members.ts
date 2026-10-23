import { request } from '../api/client';

/**
 * Сотрудники арендатора.
 *
 * <p>Учётные записи живут в схеме клиента, а не в общем контуре: восстановление
 * одного клиента из бэкапа обязано возвращать и его людей, а логин не обязан
 * быть уникальным между компаниями.
 */
export interface Member {
  /** Внутренний ключ строки: им зовут API, человеку он не показывается. */
  id: number;
  /**
   * Видимый номер сотрудника (задача 0373): порядковый внутри компании
   * и неизменный. Его показывают человеку рядом с логином и именем —
   * по нему сотрудника опознают «на 100%», как «ID NNNN» у Bazon.
   */
  number: number;
  /** Нет у администратора сервиса: его вход живёт вне кабинета. */
  login: string | null;
  displayName: string | null;
  role: Role | typeof SERVICE_ADMIN;
  active: boolean;
  lastLoginAt: string | null;
}

export type Role = 'OWNER' | 'MANAGER' | 'STOREKEEPER' | 'SELLER' | 'VIEWER' | 'AUDITOR';

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
  { role: 'AUDITOR', title: 'Ревизор', can: 'журнал действий — кто что менял' },
];

/**
 * Администратор сервиса в кабинете разборки (задача 0042).
 *
 * <p>В `ROLES` его нет намеренно: это перечень того, что владелец выдаёт
 * сотруднику, а эту роль владелец «не заводит и не может отобрать» (решение
 * владельца продукта от 8 сентября 2026). Попав туда, она стала бы пунктом
 * в выборе роли нового сотрудника. Название же нужно везде, где роль
 * показывают: в списке сотрудников, в журнале действий и во «Входах».
 */
export const SERVICE_ADMIN = 'ADMIN';

export function roleTitle(role: string): string {
  if (role === SERVICE_ADMIN) {
    return 'Администратор сервиса';
  }
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
