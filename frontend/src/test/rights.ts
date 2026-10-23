import { LEGACY_ROLE_PERMISSIONS, rightsFrom, type PermissionKey, type Rights } from '../access/rights';

/**
 * Права прежней роли — для тестов, написанных до задачи 0044 словами ролей.
 *
 * <p>Набор — тот же, что раздача при переносе (`tenant/101`), то есть тест
 * «менеджер видит правку» по-прежнему спрашивает про менеджера, каким он
 * переехал. Новые проверки называют права прямо — {@link rightsWith}.
 */
export function rightsAs(role: string): Rights {
  return role === 'OWNER' || role === 'ADMIN'
    ? rightsFrom(true, [])
    : rightsFrom(false, LEGACY_ROLE_PERMISSIONS[role] ?? []);
}

/** Ровно эти права и ничего сверх. */
export function rightsWith(...keys: PermissionKey[]): Rights {
  return rightsFrom(false, keys);
}
