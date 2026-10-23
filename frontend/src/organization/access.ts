import { request } from '../api/client';
import type { PermissionKey } from '../access/rights';

/**
 * Роли клиента и права сотрудников (задача 0044).
 *
 * <p>Роль — название и набор прав; назначается одному или нескольким
 * сотрудникам и связана с ними живо: правка набора меняет права у всех,
 * кому роль назначена. Поверх роли сотруднику можно добавить или снять
 * отдельное право. Встроенная роль одна — «Владелец»: у неё все права
 * всегда, и набора у неё нет.
 */
export interface AccessRole {
  id: number;
  name: string;
  owner: boolean;
  /** Набор ключами; у владельца пусто — у него всё. */
  permissions: PermissionKey[];
  /** Скольким сотрудникам с входом назначена. */
  members: number;
}

export interface MemberAccess {
  memberId: number;
  /** `null` — роли нет (прежняя роль удалена либо не назначалась). */
  roleId: number | null;
  roleName: string | null;
  owner: boolean;
  added: PermissionKey[];
  removed: PermissionKey[];
  /** Итог: что сотрудник может. */
  effective: PermissionKey[];
}

export function loadRoles(): Promise<AccessRole[]> {
  return request<AccessRole[]>('/api/access/roles');
}

export function createRole(name: string, permissions: PermissionKey[]): Promise<AccessRole> {
  return request<AccessRole>('/api/access/roles', { method: 'POST', body: { name, permissions } });
}

export function updateRole(
  id: number, name: string, permissions: PermissionKey[],
): Promise<AccessRole> {
  return request<AccessRole>(`/api/access/roles/${id}`, {
    method: 'PUT',
    body: { name, permissions },
  });
}

export function deleteRole(id: number): Promise<void> {
  return request<void>(`/api/access/roles/${id}`, { method: 'DELETE' });
}

export function loadMemberAccess(): Promise<MemberAccess[]> {
  return request<MemberAccess[]>('/api/access/members');
}

/**
 * Роль и права поверх неё — одним сохранением.
 *
 * @param overrides право → `true` добавлено поверх роли, `false` снято
 */
export function assignAccess(
  memberId: number, roleId: number, overrides: Partial<Record<PermissionKey, boolean>>,
): Promise<MemberAccess> {
  return request<MemberAccess>(`/api/access/members/${memberId}`, {
    method: 'PUT',
    body: { roleId, overrides },
  });
}

/**
 * Права поверх роли, которые дают ровно выбранный итог.
 *
 * <p>Отмеченное, которого нет в роли, — добавлено; снятое, которое в роли
 * есть, — снято. Совпадающее с ролью не пишется: оно ничего не меняет,
 * а на экране выглядело бы решением владельца.
 */
export function overridesFor(
  role: readonly PermissionKey[], chosen: ReadonlySet<PermissionKey>,
  all: readonly PermissionKey[],
): Partial<Record<PermissionKey, boolean>> {
  const inRole = new Set(role);
  const overrides: Partial<Record<PermissionKey, boolean>> = {};
  for (const key of all) {
    if (chosen.has(key) && !inRole.has(key)) overrides[key] = true;
    if (!chosen.has(key) && inRole.has(key)) overrides[key] = false;
  }
  return overrides;
}
