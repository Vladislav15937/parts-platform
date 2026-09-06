import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  changePassword,
  createMember,
  disableMember,
  enableMember,
  loadMembers,
  roleTitle,
  ROLES,
  type Member,
  type Role,
} from '../organization/members';

/**
 * Сотрудники компании: кто входит и что видит.
 *
 * <p>Экран владельца. До него завести продавца можно было только запросом
 * к API — то есть нельзя: новый клиент не настраивается без разработчика.
 *
 * <p><b>Сотрудник выключается, а не удаляется.</b> За ним остались приёмки,
 * продажи и записи в истории документов; удалить его значит стереть автора
 * у совершённых операций, а история для того и ведётся, чтобы через месяц
 * можно было спросить, кто это сделал.
 */
export function MembersScreen() {
  const [members, setMembers] = useState<Member[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<Role>('SELLER');

  const reload = useCallback(() => {
    loadMembers()
      .then((found) => {
        setMembers(found);
        setError('');
      })
      .catch((cause) => setError(describe(cause, 'Сотрудники не загрузились')));
  }, []);

  useEffect(reload, [reload]);

  return (
    <section className="screen">
      <h2>Сотрудники</h2>

      {error !== '' && <p className="note note--error">{error}</p>}

      {members === null ? (
        // «Загружаем…» — пока грузим, а не пока пусто: при ошибке состояние
        // так и остаётся пустым, и надпись висела рядом с «Запрос отклонён
        // (401)» вечно. Причина уже показана выше — второй раз её
        // не повторяем, но и не выдаём неудачу за ожидание.
        error !== '' ? null : <p className="note">Загружаем…</p>
      ) : (
        // Прокручивается таблица внутри своей обёртки, а не страница:
        // пять колонок с датой последнего входа и двумя кнопками в телефон
        // не помещаются, а без обёртки вбок уезжал весь экран.
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Логин</th>
                <th>Имя</th>
                <th>Роль</th>
                <th>Последний вход</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {members.map((member) => (
                <tr key={member.id} className={member.active ? undefined : 'muted'}>
                  <td>{member.login}</td>
                  <td>{member.displayName ?? '—'}</td>
                  <td>{roleTitle(member.role)}</td>
                  <td>
                    {member.lastLoginAt === null
                      ? 'не входил'
                      : new Date(member.lastLoginAt).toLocaleString('ru-RU')}
                  </td>
                  <td className="filter-row">
                    <button
                      type="button"
                      className="button--ghost"
                      disabled={busy}
                      onClick={() => void resetPassword(member)}
                    >
                      Сменить пароль
                    </button>
                    {/* Кнопка есть и у владельца: последнего отобьёт сервер
                        с объяснением, а второго выключить — обычное дело. */}
                    <button
                      type="button"
                      className="button--ghost"
                      disabled={busy}
                      onClick={() => void toggle(member)}
                    >
                      {member.active ? 'Выключить' : 'Включить'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <h3>Новый сотрудник</h3>
        <div className="row">
          <label className="field">
            Логин
            <input
              value={login}
              onChange={(e) => setLogin(e.target.value)}
              autoCapitalize="none"
            />
          </label>
          <label className="field">
            Имя
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </label>
        </div>
        <div className="row">
          <label className="field">
            Пароль
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          <label className="field">
            Роль
            {/* Владелец в списке есть: компания с одним владельцем запирается
                снаружи, если он потеряет доступ, а завести второго можно было
                только провижинингом — то есть через разработчика. Выключить
                последнего владельца сервер не даёт. */}
            <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
              {ROLES.map((r) => (
                <option key={r.role} value={r.role}>{r.title}</option>
              ))}
            </select>
          </label>
        </div>

        {/* Что умеет роль — рядом с выбором, а не в справке: «менеджер»
            и «продавец» звучат похоже, а видят разное. */}
        <p className="note">{ROLES.find((r) => r.role === role)?.can}</p>

        <button
          type="button"
          disabled={busy || login.trim() === '' || password.length < 8}
          onClick={() => void add()}
        >
          {busy ? 'Заводим…' : 'Завести'}
        </button>
        {password !== '' && password.length < 8 && (
          <p className="note">Пароль от восьми символов.</p>
        )}
      </div>
    </section>
  );

  async function add(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await createMember(login.trim(), password, displayName.trim(), role);
      setLogin('');
      setPassword('');
      setDisplayName('');
      reload();
    } catch (cause) {
      setError(describe(cause, 'Сотрудник не заведён'));
    } finally {
      setBusy(false);
    }
  }

  async function resetPassword(member: Member): Promise<void> {
    const next = window.prompt(`Новый пароль для «${member.login}», от восьми символов`);
    if (next === null || next.length < 8) {
      return;
    }
    setBusy(true);
    try {
      await changePassword(member.id, next);
      setError('');
    } catch (cause) {
      setError(describe(cause, 'Пароль не сменился'));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(member: Member): Promise<void> {
    setBusy(true);
    try {
      await (member.active ? disableMember(member.id) : enableMember(member.id));
      reload();
    } catch (cause) {
      setError(describe(cause, 'Не вышло'));
    } finally {
      setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
