import { Fragment, useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  loadCatalog, lockedReason, lockedReasonAny, rightsFrom, type PermissionKey, type Rights, type Section,
} from '../access/rights';
import {
  assignAccess,
  beyondOwn,
  foreignReason,
  rightTitles,
  loadMemberAccess,
  loadRoles,
  overridesFor,
  type AccessRole,
  type MemberAccess,
} from '../organization/access';
import {
  changePassword,
  createMember,
  disableMember,
  enableMember,
  loadMembers,
  roleTitle,
  SERVICE_ADMIN,
  type Member,
} from '../organization/members';
import { LockedAction } from '../ui/LockedAction';
import { useMounted } from '../ui/useMounted';
import { AccessPermissions } from './AccessPermissions';
import { AdminAccessPanel } from './AdminAccessPanel';
import { RolesPanel } from './RolesPanel';

/**
 * Сотрудники компании: кто входит и что может.
 *
 * <p>Экран владельца. До него завести продавца можно было только запросом
 * к API — то есть нельзя: новый клиент не настраивается без разработчика.
 *
 * <p><b>Сотрудник выключается, а не удаляется.</b> За ним остались приёмки,
 * продажи и записи в истории документов; удалить его значит стереть автора
 * у совершённых операций, а история для того и ведётся, чтобы через месяц
 * можно было спросить, кто это сделал.
 *
 * <p><b>Права — ролями клиента и поверх них (задача 0044).</b> Роли заводятся
 * в разделе «Роли»; у сотрудника кнопка «Права» раскрывает под строкой выбор
 * роли и галочки: отмеченное сверх роли — добавлено ему одному, снятое из роли —
 * снято ему одному. Сохранение закрывает открытые входы сотрудника: права лежат
 * в сессии, и без этого он доработал бы смену со старыми.
 *
 * @param managesAccess меняет ли вошедший сотрудников и права: право
 *        «Сотрудники и роли» и не администратор сервиса. Администратор видит
 *        сотрудников, роли и раздел доступа, но ничего не меняет: всё это
 *        пережило бы отзыв его доступа (разбор PR 393). Сервер отбивает то же 403.
 * @param ownerActs вошёл владелец: назначить и снять роль «Владелец» может только он
 * @param mine права вошедшего: выдавать можно только свои (ответ владельца
 *        24.10.2026, задача 0044, 2б) — роль шире них и чужое право поверх роли
 *        погашены с причиной. Решает сервер, он отбивает то же 403 словами.
 */
export function MembersScreen({
  managesAccess = true, ownerActs = true, mine = OWNER,
}: { managesAccess?: boolean; ownerActs?: boolean; mine?: Rights }) {
  const [members, setMembers] = useState<Member[] | null>(null);
  const [access, setAccess] = useState<Map<number, MemberAccess>>(new Map());
  // Роли: null — ещё грузим либо не смогли узнать (причина в rolesError).
  const [rolesLoaded, setRolesLoaded] = useState<AccessRole[] | null>(null);
  const roles = rolesLoaded ?? [];
  const [sections, setSections] = useState<Section[]>([]);
  // Три причины отказа — три строки, а не одна общая: успех одного запроса
  // стирал бы отказ соседнего, и «Роли не загрузились» пропадало бы, как только
  // приехал список сотрудников.
  const [error, setError] = useState('');
  const [accessError, setAccessError] = useState('');
  const [rolesError, setRolesError] = useState('');
  const [busy, setBusy] = useState(false);

  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  // Роль нового сотрудника не подставляется: какую ему дать, знает только
  // владелец, а подставленная «первая» уехала бы молча (то же правило,
  // что у склада в приёмке).
  const [roleId, setRoleId] = useState('');

  // Чьи права раскрыты под строкой и что в них выбрано.
  const [editing, setEditing] = useState<number | null>(null);
  const [editRole, setEditRole] = useState('');
  const [editChosen, setEditChosen] = useState<Set<PermissionKey>>(new Set());
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  const reload = useCallback(() => {
    loadMembers()
      .then((found) => {
        if (mounted.current) {
          setMembers(found);
          setError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Сотрудники не загрузились'));
      });
    // Роли и права — отдельными запросами: без них список сотрудников всё равно
    // нужен (сменить пароль, выключить), и падать вместе ему незачем.
    loadMemberAccess()
      .then((found) => {
        if (mounted.current) {
          setAccess(new Map(normalized(found).map((a) => [a.memberId, a])));
          setAccessError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setAccessError(describe(cause, 'Права сотрудников не загрузились'));
      });
    loadRoles()
      .then((found) => {
        if (mounted.current) {
          setRolesLoaded(Array.isArray(found) ? found : []);
          setRolesError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setRolesError(describe(cause, 'Роли не загрузились'));
      });
    loadCatalog()
      .then((found) => { if (mounted.current) setSections(Array.isArray(found) ? found : []); })
      .catch(() => {
        // Без справочника галочек нет, а роли и сотрудники видны: причина
        // показана у раскрытых прав.
      });
  }, [mounted]);

  useEffect(reload, [reload]);

  const ownerRole = roles.find((r) => r.owner);
  const allKeys = sections.flatMap((s) => s.permissions.map((a) => a.key));

  const openRights = (member: Member) => {
    const current = access.get(member.id);
    setEditing(member.id);
    setEditRole(current?.roleId === null || current === undefined ? '' : String(current.roleId));
    setEditChosen(new Set(current?.effective ?? []));
  };

  const roleOf = (id: string) => roles.find((r) => String(r.id) === id);
  // Роль шире прав вошедшего: назначить её он не может (сервер отбивает 403).
  const wider = (r: AccessRole) => !r.owner && beyondOwn(r.permissions, mine).length > 0;
  const roleOption = (r: AccessRole) => (
    <option key={r.id} value={String(r.id)} disabled={(r.owner && !ownerActs) || wider(r)}>
      {r.owner && !ownerActs ? `${r.name} — назначает только владелец`
        : wider(r) ? `${r.name} — ${WIDER}` : r.name}
    </option>
  );
  // Четыре права на раздачу (ответ владельца 24.10.2026, «развёрнутые вопросы», 2а):
  // каждое гасит своё. Слово права — справочником сервера.
  const titles = new Map(sections.flatMap((s) => s.permissions.map((a) => [a.key, a.title] as const)));
  const mayAccounts = mine.has('members.accounts');
  const accountsReason = lockedReason('members.accounts', titles);
  // «Права» раскрывают роль и права поверх роли: без обоих прав там менять нечего.
  const mayRights = mine.has('members.assign_roles') || mine.has('members.grant_overrides');
  const rightsReason = lockedReasonAny(['members.assign_roles', 'members.grant_overrides'], titles);
  // Пароль, выключение, включение: у кого прав больше, чем у вошедшего (и у владельца),
  // гасит сервер признаком в списке (ответ владельца 24.10.2026, 1а) — экран своих
  // правил не держит. Свой пароль меняет каждый; выключать себя нельзя всё равно,
  // и это отбивает сервер словами.
  const accountLock = (member: Member): string | null =>
    !mayAccounts ? accountsReason : member.accountLocked ?? null;

  return (
    <section className="screen screen--members">
      <h2>Сотрудники</h2>

      {error !== '' && <p className="note note--error">{error}</p>}
      {accessError !== '' && <p className="note note--error">{accessError}</p>}

      {/* Без права на учётные записи пароль и выключение погашены у всех: причина
          одна на экран, а не полсотни одинаковых у каждой строки. */}
      {managesAccess && !mayAccounts && members !== null && (
        <p className="note locked-action__reason">{accountsReason}</p>
      )}
      {managesAccess && !mayRights && members !== null && (
        <p className="note locked-action__reason">«Права»: {rightsReason}</p>
      )}

      {members === null ? (
        // «Загружаем…» — пока грузим, а не пока пусто: при ошибке состояние
        // так и остаётся пустым, и надпись висела рядом с «Запрос отклонён
        // (401)» вечно. Причина уже показана выше — второй раз её
        // не повторяем, но и не выдаём неудачу за ожидание.
        error !== '' ? null : <p className="note">Загружаем…</p>
      ) : (
        // Прокручивается таблица внутри своей обёртки, а не страница:
        // шесть колонок с датой последнего входа и кнопками в телефон
        // не помещаются, а без обёртки вбок уезжал весь экран.
        <div className="table-scroll">
          {/* На телефоне строка таблицы — карточка (`.stack-table`): шесть колонок
              с тремя кнопками в 390 не помещаются, и «Права» уезжали за край
              прокрутки, а раскрытые права — на 605 px внутри неё (живой
              прогон 24.10.2026). Подпись клетки — `data-label`. */}
          <table className="stack-table">
            <thead>
              <tr>
                {/* Видимый номер сотрудника, а не номер строки базы: его
                    называют вслух и им подписан автор в истории карточки
                    (задача 0373). */}
                <th>Номер</th>
                <th>Логин</th>
                <th>Имя</th>
                <th>Роль</th>
                <th>Последний вход</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {members.map((member) => (
                <Fragment key={member.id}>
                <tr className={member.active ? undefined : 'muted'}>
                  {/* У администратора сервиса логина в кабинете нет: вход
                      у него свой, вне всех разборок (задача 0042). */}
                  <td data-label="Номер">{member.number}</td>
                  <td data-label="Логин">{member.login ?? '—'}</td>
                  <td data-label="Имя">{member.displayName ?? '—'}</td>
                  <td data-label="Роль">{roleCell(member, access.get(member.id))}</td>
                  <td data-label="Последний вход">
                    {member.lastLoginAt === null
                      ? 'не входил'
                      : new Date(member.lastLoginAt).toLocaleString('ru-RU')}
                  </td>
                  {member.role === SERVICE_ADMIN ? (
                    // Владелец этого участника «не заводит и не может
                    // отобрать» (решение владельца продукта от 8 сентября
                    // 2026): кнопки, которые сервер отобьёт, не рисуем, а
                    // говорим, где его рычаг.
                    <td className="note">доступ — в разделе ниже</td>
                  ) : !managesAccess ? (
                    // Кнопки, которые сервер отобьёт, администратору не рисуем.
                    <td />
                  ) : (
                  <td className="filter-row stack-table__actions">
                    <button
                      type="button"
                      className="button--ghost"
                      aria-label={`Права: ${member.login ?? member.displayName ?? ''}`}
                      // Роль и права того, у кого прав больше, не меняются (ответ владельца
                      // 24.10, вопрос 2: сужать нельзя) — признак тот же, что у пароля.
                      disabled={busy || (member.accountLocked ?? null) !== null || !mayRights}
                      title={member.accountLocked ?? (mayRights ? undefined : rightsReason)}
                      onClick={() => (editing === member.id ? setEditing(null) : openRights(member))}
                    >
                      Права
                    </button>
                    {/* Шире вошедшего (и владелец) — кнопки погашены и названы, а не
                        спрятаны; признак и слова — сервера (accountLocked), тем же
                        правилом он отбивает и сам запрос. */}
                    <button
                      type="button"
                      className="button--ghost"
                      disabled={busy || accountLock(member) !== null}
                      title={accountLock(member) ?? undefined}
                      onClick={() => void resetPassword(member)}
                    >
                      Сменить пароль
                    </button>
                    {/* Кнопка есть и у владельца: последнего отобьёт сервер
                        с объяснением, а второго выключить — обычное дело. */}
                    <button
                      type="button"
                      className="button--ghost"
                      disabled={busy || accountLock(member) !== null}
                      title={accountLock(member) ?? undefined}
                      onClick={() => void toggle(member)}
                    >
                      {member.active ? 'Выключить' : 'Включить'}
                    </button>
                    {mayAccounts && accountLock(member) !== null && (
                      <span className="note locked-action__reason">{accountLock(member)}</span>
                    )}
                  </td>
                  )}
                </tr>
                {editing === member.id && (
                  <tr>
                    <td colSpan={6} className="stack-table__wide">{rightsEditor(member)}</td>
                  </tr>
                )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <RolesPanel roles={rolesLoaded} error={rolesError} sections={sections}
                  canChange={managesAccess && mine.has('members.roles')}
                  lockedBy={managesAccess && !mine.has('members.roles') ? 'members.roles' : undefined}
                  mine={mine}
                  onChanged={reload} />

      <AdminAccessPanel canChange={managesAccess} mayView={mine.has('admin_access.manage')} />

      {!managesAccess ? (
        <p className="note">
          Сотрудников заводит и меняет владелец: вход, заданный администратором
          сервиса, остался бы и после того, как владелец закроет ему доступ.
        </p>
      ) : !mayAccounts ? (
        <div className="card">
          <h3>Новый сотрудник</h3>
          <LockedAction permission="members.accounts" label="Завести" className="" />
        </div>
      ) : (
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
                снаружи, если он потеряет доступ. Назначить его может только
                владелец — пункт погашен и назван, а не спрятан. */}
            <select value={roleId} onChange={(e) => setRoleId(e.target.value)}>
              <option value="">— выберите роль —</option>
              {roles.map(roleOption)}
            </select>
          </label>
        </div>
        {roles.some(wider) && <p className="note">{WIDER_NOTE}</p>}

        {/* Что умеет роль — рядом с выбором, а не в справке. */}
        {roleOf(roleId) !== undefined && (
          <p className="note">{roleSummary(roleOf(roleId) as AccessRole, sections)}</p>
        )}

        <button
          type="button"
          disabled={busy || login.trim() === '' || password.length < 8 || roleId === ''}
          onClick={() => void add()}
        >
          {busy ? 'Заводим…' : 'Завести'}
        </button>
        {password !== '' && password.length < 8 && (
          <p className="note">Пароль от восьми символов.</p>
        )}
        {roleId === '' && <p className="note">Выберите роль сотрудника.</p>}
      </div>
      )}
    </section>
  );

  function rightsEditor(member: Member) {
    const current = access.get(member.id);
    const role = roleOf(editRole);
    const ownerChosen = role?.owner === true;
    const roleKeys = new Set(role?.permissions ?? []);
    const wasOwner = current?.owner === true;
    const ownerLocked = (ownerChosen || wasOwner) && !ownerActs;
    const grant = grantObstacle(role, editChosen);
    // Роль и права поверх роли — разные права (2а): у кого одного нет, то погашено.
    const mayAssign = mine.has('members.assign_roles');
    const mayOverride = mine.has('members.grant_overrides');
    const lost = resetLoss(current, editRole);
    return (
      <div className="card">
        <label className="field">
          Роль
          <select
            value={editRole}
            disabled={(wasOwner && !ownerActs) || !mayAssign}
            onChange={(e) => {
              setEditRole(e.target.value);
              // Права поверх прежней роли к новой не переносятся: сменив роль,
              // владелец видит ровно её набор и правит от него.
              setEditChosen(new Set(roleOf(e.target.value)?.permissions ?? []));
            }}
          >
            <option value="">— без роли —</option>
            {roles.map(roleOption)}
          </select>
        </label>
        {!mayAssign && (
          <p className="note locked-action__reason">{lockedReason('members.assign_roles', titles)}</p>
        )}
        {sections.length === 0 ? (
          <p className="note">Список прав не загрузился — обновите страницу.</p>
        ) : (
          <AccessPermissions
            sections={sections}
            chosen={editChosen}
            locked={ownerChosen
              ? 'У владельца все права всегда: отдельные права ему не добавляют и не снимают'
              : !mayOverride ? lockedReason('members.grant_overrides', titles) : undefined}
            foreign={(key) => !mine.has(key)}
            onToggle={(key, on) => setEditChosen((was) => {
              const next = new Set(was);
              if (on) next.add(key); else next.delete(key);
              return next;
            })}
            marked={(key) => (roleKeys.has(key) === editChosen.has(key)
              ? null
              : editChosen.has(key) ? 'добавлено поверх роли' : 'снято поверх роли')}
          />
        )}
        {lost !== null && <p className="note note--error">{lost}</p>}
        {legacyAuditor(member, current) && editRole === '' && (
          <p className="note">
            Роль не назначена: журнал действий у сотрудника — от прежней роли «Ревизор».
            {' '}Назначьте роль, чтобы управлять его правами.
          </p>
        )}
        <p className="note">
          Сохранение закроет открытые входы сотрудника — новые права он получит,
          войдя заново.
        </p>
        <div className="row">
          <button
            type="button"
            disabled={busy || role === undefined || ownerLocked || grant !== null}
            onClick={() => void saveRights(member, role as AccessRole)}
          >
            {busy ? 'Сохраняем…' : 'Сохранить права'}
          </button>
          <button type="button" className="button--ghost" onClick={() => setEditing(null)}>
            Отмена
          </button>
        </div>
        {role === undefined && <p className="note">Выберите роль — без неё права не сохранить.</p>}
        {ownerLocked && (
          <p className="note">Назначить и снять роль «Владелец» может только владелец.</p>
        )}
        {grant !== null && <p className="note">{grant}</p>}
        {ownerRole === undefined && <p className="note">Роли не загрузились.</p>}
      </div>
    );
  }

  /**
   * Что сотрудник потеряет, если сменить ему роль: права поверх прежней роли
   * не переносятся (решение исполнителя PR 508, не оспорено), — и сказать это
   * надо до «Сохранить», а не молча (живой прогон 24.10.2026).
   */
  function resetLoss(current: MemberAccess | undefined, chosenRole: string): string | null {
    if (current === undefined || String(current.roleId ?? '') === chosenRole) return null;
    const parts: string[] = [];
    for (const [word, keys] of [['добавленные', current.added], ['снятые', current.removed]] as const) {
      if (keys.length === 0) continue;
      const titles = rightTitles(keys, sections);
      parts.push(`${word} ${titles === '' ? `(${keys.length})` : titles}`);
    }
    return parts.length === 0 ? null
      : `Смена роли сбросит отдельные права сотрудника: ${parts.join('; ')}. `
        + 'Отмечен набор новой роли — нужное отметьте заново.';
  }

  /**
   * Почему права не сохранить: роль шире своих либо отмечено поверх роли чужое
   * право. Одно выражение на кнопку и на подпись — разойдись они, кнопка снова
   * начала бы молчать (тот же приём, что `paymentObstacle` у оплаты).
   */
  function grantObstacle(role: AccessRole | undefined, chosen: ReadonlySet<PermissionKey>):
    string | null {
    if (role === undefined || role.owner) return null;
    const inRole = beyondOwn(role.permissions, mine);
    if (inRole.length > 0) {
      return `${foreignReason(`В роли «${role.name}» есть права, которых у вас нет`, inRole, sections)}`
        + ' — её назначает владелец.';
    }
    const roleKeys = new Set(role.permissions);
    const added = beyondOwn([...chosen].filter((key) => !roleKeys.has(key)), mine);
    return added.length === 0 ? null
      : `${foreignReason('Отмечены права, которых у вас нет', added, sections)} — снимите их.`;
  }

  async function saveRights(member: Member, role: AccessRole): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await assignAccess(member.id, role.id,
        role.owner ? {} : overridesFor(role.permissions, editChosen, allKeys));
      if (mounted.current) {
        setEditing(null);
        reload();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Права не сохранились'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function add(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await createMember(login.trim(), password, displayName.trim(), Number(roleId));
      if (mounted.current) {
        setLogin('');
        setPassword('');
        setDisplayName('');
        setRoleId('');
        reload();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Сотрудник не заведён'));
    } finally {
      if (mounted.current) setBusy(false);
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
      if (mounted.current) setError('');
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Пароль не сменился'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function toggle(member: Member): Promise<void> {
    setBusy(true);
    try {
      await (member.active ? disableMember(member.id) : enableMember(member.id));
      if (mounted.current) reload();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Не вышло'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

/**
 * Роль в строке сотрудника: роль клиента названием, отдельные права
 * числом рядом. Пока права не приехали — прежняя роль словом.
 */
function roleCell(member: Member, access: MemberAccess | undefined): string {
  if (member.role === SERVICE_ADMIN || access === undefined) {
    return roleTitle(member.role);
  }
  if (access.roleName === null) {
    // Ревизор, которому роль «Ревизор» не заводили (задача 0044): журнал у него
    // есть — от прежней роли, — и «без роли» при рабочем праве было бы неправдой.
    // Удалил клиент «Ревизора» — журнала нет, и это честное «без роли».
    return legacyAuditor(member, access) ? LEGACY_AUDITOR : 'без роли';
  }
  const extra = access.added.length + access.removed.length;
  return extra === 0 ? access.roleName : `${access.roleName} · отдельно: ${extra}`;
}

/** Ревизор без роли клиента, чей журнал держится прежней ролью (`MemberAccess`). */
function legacyAuditor(member: Member, access: MemberAccess | undefined): boolean {
  return access !== undefined && access.roleId === null && member.role === 'AUDITOR'
    && access.effective.includes('journal.view');
}

const LEGACY_AUDITOR = 'Ревизор: журнал действий';

function roleSummary(role: AccessRole, sections: readonly Section[]): string {
  if (role.owner) {
    return 'Все права всегда, включая сотрудников и роли.';
  }
  const titles = sections.flatMap((s) => s.permissions)
    .filter((a) => role.permissions.includes(a.key))
    .map((a) => a.title);
  return titles.length === 0 ? 'Прав нет — только смотреть то, что открыто всем.' : titles.join(', ');
}

const OWNER = rightsFrom(true, []);

/** Подпись у роли шире прав вошедшего — пункт погашен и назван, а не спрятан. */
const WIDER = 'шире ваших прав';
const WIDER_NOTE = 'Роли шире ваших прав погашены: выдавать можно только свои права.';

/** Ответ разбирается как обещание, а не как гарантия: пустой — не падение. */
function normalized(found: unknown): MemberAccess[] {
  return Array.isArray(found) ? (found as MemberAccess[]) : [];
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
