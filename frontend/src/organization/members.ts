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
  /**
   * Почему вошедшему нельзя менять пароль, выключать и включать этого
   * сотрудника (задача 0044, ответ владельца 24.10.2026, 1а: у него прав
   * больше, чем у вошедшего, или это владелец). Считает сервер тем же правилом,
   * что отказ; экран гасит кнопки по нему и своих правил не держит.
   * Нет поля (сервер прежней версии) или `null` — можно.
   */
  accountLocked?: string | null;
}

export type Role = 'OWNER' | 'MANAGER' | 'STOREKEEPER' | 'SELLER' | 'VIEWER' | 'AUDITOR';

/**
 * Прежние жёсткие роли словом — для показа, а не для прав (задача 0044).
 *
 * <p>Права с задачи 0044 решает роль клиента (`organization/access.ts`),
 * а прежняя колонка роли осталась снимком: ей подписан автор в журнале
 * действий и во «Входах» (запись сделана до 0044 либо прежним образом).
 * Назвать её словом нужно и там.
 */
const ROLES: Array<{ role: Role; title: string }> = [
  { role: 'OWNER', title: 'Владелец' },
  { role: 'MANAGER', title: 'Менеджер' },
  { role: 'SELLER', title: 'Продавец' },
  { role: 'STOREKEEPER', title: 'Кладовщик' },
  { role: 'VIEWER', title: 'Просмотр' },
  { role: 'AUDITOR', title: 'Ревизор' },
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

/**
 * Сотрудник заводится сразу с ролью клиента (задача 0044): заведение
 * и назначение — одна транзакция на сервере.
 */
export function createMember(
  login: string, password: string, displayName: string, roleId: number,
): Promise<Member> {
  return request<Member>('/api/members', {
    method: 'POST',
    body: { login, password, displayName, roleId },
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
