import { useState } from 'react';
import { ApiError } from '../api/client';
import {
  lockedReason, rightsFrom, type PermissionKey, type Rights, type Section,
} from '../access/rights';
import { LockedAction } from '../ui/LockedAction';
import {
  beyondOwn, createRole, deleteRole, foreignReason, updateRole, type AccessRole,
} from '../organization/access';
import { count, plural } from '../ui/plural';
import { useMounted } from '../ui/useMounted';
import { AccessPermissions } from './AccessPermissions';

/**
 * Роли компании: завести, поправить набор, удалить (задача 0044).
 *
 * <p>Решение владельца 23.10.2026: «клиент должен иметь функцию задать
 * роль, написать её название и сохранить эту роль с готовым набором прав.
 * Эту роль владелец может назначать на одного или нескольких сотрудников».
 * Связь живая — правка набора меняет права у всех, кому роль назначена,
 * и об этом сказано у кнопки сохранения: иначе владелец, поправивший
 * «Кладовщика» для одного, удивится переменам у пятерых.
 *
 * <p>«Владелец» стоит строкой, но не правится и не удаляется: у него все
 * права всегда. Кнопок у этой строки нет, а вместо них сказано почему —
 * то же правило, что у погашенного: не прятать, а называть причину.
 */
export function RolesPanel({
  roles, error: loadError = '', sections, canChange, lockedBy, onChanged, mine = OWNER,
}: {
  /** `null` — ещё не приехали (грузим либо не смогли узнать — см. `error`). */
  roles: readonly AccessRole[] | null;
  /** Почему роли не загрузились; пусто — отказа не было. */
  error?: string;
  sections: readonly Section[];
  canChange: boolean;
  /**
   * Право, которого нет у вошедшего, чтобы править роли (`members.roles`,
   * задача 0044, 2а), — тогда «Изменить» и «Новая роль» погашены с причиной,
   * а не спрятаны. Не задано и `canChange` ложно — администратор сервиса:
   * у него кнопок нет вовсе (раздавать права ему нельзя).
   */
  lockedBy?: PermissionKey | undefined;
  onChanged: () => void;
  /**
   * Права того, кто правит: набор роли не шире их (ответ владельца 24.10.2026,
   * задача 0044, 2б). Сервер отбивает то же 403 словами.
   */
  mine?: Rights;
}) {
  const titles = new Map(sections.flatMap((s) => s.permissions.map((a) => [a.key, a.title] as const)));
  // Какая роль раскрыта на правку; 'new' — заводится новая.
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [name, setName] = useState('');
  const [chosen, setChosen] = useState<Set<PermissionKey>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();
  const noCatalog = sections.length === 0;

  const open = (role: AccessRole | null) => {
    setError('');
    setEditing(role === null ? 'new' : role.id);
    setName(role === null ? '' : role.name);
    setChosen(new Set(role === null ? [] : role.permissions));
  };

  const editor = (role: AccessRole | null) => {
    const extra = beyondOwn(chosen, mine);
    const foreign = extra.length === 0 ? null
      : foreignReason('В наборе есть права, которых у вас нет', extra, sections)
        + ' — снимите их или попросите владельца.';
    return (
    <div className="card">
      <label className="field">
        Название роли
        <input value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      {/* Без справочника редактор пуст, и «Сохранить» записало бы роли набор,
          которого человек не видел, — у прежней роли это снятые молча права.
          Поэтому сохранение гаснет, а причина сказана (то же, что у прав
          сотрудника в MembersScreen). */}
      {noCatalog ? (
        <p className="note">Список прав не загрузился — обновите страницу.</p>
      ) : (
        <AccessPermissions
          sections={sections}
          chosen={chosen}
          foreign={(key) => !mine.has(key)}
          onToggle={(key, on) => setChosen((was) => {
            const next = new Set(was);
            if (on) next.add(key); else next.delete(key);
            return next;
          })}
        />
      )}
      {role !== null && role.members > 0 && (
        <p className="note">
          Роль назначена: {employees(role.members)}.
          {' '}Новый набор прав получат все они, а их открытые входы закроются — войти
          придётся заново.
        </p>
      )}
      <div className="row">
        <button
          type="button"
          disabled={busy || noCatalog || name.trim() === '' || foreign !== null}
          onClick={() => void save(role)}
        >
          {busy ? 'Сохраняем…' : 'Сохранить роль'}
        </button>
        <button type="button" className="button--ghost" onClick={() => setEditing(null)}>
          Отмена
        </button>
      </div>
      {name.trim() === '' && <p className="note">Напишите название роли.</p>}
      {foreign !== null && <p className="note">{foreign}</p>}
    </div>
    );
  };

  return (
    <section className="card">
      <h3>Роли</h3>
      <p className="note">
        Роль — название и набор прав. Назначьте её сотрудникам ниже; поверх роли
        каждому можно добавить или снять отдельное право.
      </p>
      {error !== '' && <p className="note note--error">{error}</p>}
      {/* Три состояния различимы: грузим, не смогли узнать, пусто. */}
      {roles === null ? (
        loadError !== ''
          ? <p className="note note--error">{loadError}</p>
          : <p className="note">Загружаем…</p>
      ) : roles.length === 0 ? (
        <p className="note">Ролей нет — заведите первую.</p>
      ) : (
      <div className="table-scroll">
        <table className="stack-table">
          <thead>
            <tr>
              <th>Роль</th>
              <th>Прав</th>
              <th>Назначена</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {roles.map((role) => (
              <tr key={role.id}>
                <td data-label="Роль">{role.name}</td>
                <td data-label="Прав">{role.owner ? 'все, всегда' : role.permissions.length}</td>
                <td data-label="Назначена">{employees(role.members)}</td>
                <td className="filter-row stack-table__actions">
                  {role.owner ? (
                    <span className="note">встроенная: не правится и не удаляется</span>
                  ) : canChange ? (
                    <>
                      <button
                        type="button"
                        className="button--ghost"
                        aria-label={`Изменить роль: ${role.name}`}
                        disabled={busy}
                        onClick={() => open(role)}
                      >
                        Изменить
                      </button>
                      <button
                        type="button"
                        className="button--ghost"
                        aria-label={`Удалить роль: ${role.name}`}
                        disabled={busy || role.members > 0}
                        title={role.members > 0 ? ASSIGNED : undefined}
                        onClick={() => void remove(role)}
                      >
                        Удалить
                      </button>
                      {/* Причина — видимым текстом: на телефоне наводить нечем
                          (решение владельца 23.10.2026, вопрос 3). */}
                      {role.members > 0 && <span className="note">{ASSIGNED}</span>}
                    </>
                  ) : lockedBy !== undefined ? (
                    // Причина одна — у «Новой роли» ниже, видимым текстом.
                    <button
                      type="button"
                      className="button--ghost"
                      aria-label={`Изменить роль: ${role.name}`}
                      disabled
                      title={lockedReason(lockedBy, titles)}
                    >
                      Изменить
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      )}
      {typeof editing === 'number' && editor(roles?.find((r) => r.id === editing) ?? null)}
      {editing === 'new' && editor(null)}
      {canChange && editing === null && roles !== null && (
        <button type="button" disabled={busy} onClick={() => open(null)}>
          Новая роль
        </button>
      )}
      {!canChange && lockedBy !== undefined && roles !== null && (
        <LockedAction permission={lockedBy} label="Новая роль" className="" />
      )}
    </section>
  );

  async function save(role: AccessRole | null): Promise<void> {
    setBusy(true);
    setError('');
    try {
      const keys = [...chosen].sort();
      await (role === null ? createRole(name.trim(), keys) : updateRole(role.id, name.trim(), keys));
      if (mounted.current) {
        setEditing(null);
        onChanged();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Роль не сохранилась'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function remove(role: AccessRole): Promise<void> {
    if (!window.confirm(`Удалить роль «${role.name}»?`)) {
      return;
    }
    setBusy(true);
    setError('');
    try {
      await deleteRole(role.id);
      if (mounted.current) onChanged();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Роль не удалилась'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

const OWNER = rightsFrom(true, []);

const ASSIGNED = 'Роль назначена — удалить можно, назначив сотрудникам другую';

function employees(value: number): string {
  return `${count(value)} ${plural(value, 'сотрудник', 'сотрудника', 'сотрудников')}`;
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
