import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  declineAdminAccess,
  grantAdminAccess,
  loadAdminAccess,
  momentText,
  revokeAdminAccess,
  type AdminAccessState,
} from '../organization/adminAccess';
import { LockedAction } from '../ui/LockedAction';
import { useMounted } from '../ui/useMounted';

/** Верхняя граница числа часов — та же, что у сервера (`AdminAccess.MAX_HOURS`). */
const MAX_HOURS = 24 * 365;

/**
 * Доступ администратора сервиса: открыт ли, до какого часа, не просят ли его.
 *
 * <p><b>Своя кнопка владельца, а не ответ на запрос.</b> Решение владельца
 * продукта от 8 сентября 2026 (задача 0042): «у клиента должна быть отдельная
 * кнопка дать доступ администратору» — позвонил в поддержку и дал, ни о чём
 * не спрошенный. Поэтому «Дать доступ администратору» есть всегда, а запрос
 * администратора — лишь строка над ней.
 *
 * <p><b>Срок одним списком из двух пунктов: «до отзыва» и «на несколько
 * часов».</b> Владелец назвал три — «навсегда, до отзыва, на определённое
 * количество часов», — но раз отозвать можно всегда, «навсегда» и «до отзыва»
 * ведут себя одинаково, а два пункта с одинаковым поведением читаются как
 * разница, которой нет. Так это и записано в задаче (раздел «Что из этого
 * следует»); выбор назван в PR.
 *
 * <p>Администратор в кабинете видит то же, но без кнопок: давать и отзывать
 * доступ — только владелец, иначе «только с разрешения владельца» перестало
 * бы быть правдой. Сервер отбивает его так же (`AdminAccessController`).
 */
export function AdminAccessPanel({ canChange, mayView = true }: {
  canChange: boolean;
  /**
   * Есть ли у вошедшего право «доступ администратора сервиса»
   * (`admin_access.manage`). Нет — состояние не грузится вовсе (сервер ответил бы
   * голым 403 «Недостаточно прав»), а раздел погашен с причиной видимым текстом
   * (решение владельца 23.10.2026, задача 0044, вопрос 3; живой прогон 24.10.2026).
   */
  mayView?: boolean;
}) {
  const [state, setState] = useState<AdminAccessState | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [term, setTerm] = useState<'revoke' | 'hours'>('revoke');
  const [hours, setHours] = useState('4');
  const mounted = useMounted();

  const reload = useCallback(() => {
    loadAdminAccess()
      .then((loaded) => {
        if (mounted.current) {
          setState(normalized(loaded));
          setError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Состояние доступа не загрузилось'));
      });
  }, [mounted]);

  useEffect(() => {
    if (mayView) reload();
  }, [reload, mayView]);

  const hoursValue = Number(hours);
  const hoursValid = Number.isInteger(hoursValue) && hoursValue >= 1 && hoursValue <= MAX_HOURS;

  return (
    <div className="card">
      <h3>Доступ администратора сервиса</h3>
      <p className="note">
        Администратор сервиса входит в ваш кабинет только с вашего разрешения.
        Всё, что он сделает, в журнале подписано им, а не вами, а его входы
        видны в журнале на вкладке «Входы».
      </p>

      {!mayView ? (
        <LockedAction permission="admin_access.manage" label="Дать доступ администратору" className="" />
      ) : (
      <>
      {error !== '' && <p className="note note--error">{error}</p>}

      {state === null ? (
        error !== '' ? null : <p className="note">Загружаем…</p>
      ) : (
        <>
          {state.request !== null && (
            <p className="note">
              {state.request.adminName} просит доступ — запрос от{' '}
              {momentText(state.request.requestedAt)}.
            </p>
          )}
          <p>
            {state.grant === null
              ? 'Доступ закрыт.'
              : state.grant.expiresAt === null
                ? 'Доступ открыт до отзыва.'
                : `Доступ открыт до ${momentText(state.grant.expiresAt)}.`}
          </p>

          {canChange && (
            <>
              <div className="row">
                <label className="field">
                  Срок
                  <select
                    value={term}
                    onChange={(e) => setTerm(e.target.value as 'revoke' | 'hours')}
                  >
                    <option value="revoke">До отзыва</option>
                    <option value="hours">На несколько часов</option>
                  </select>
                </label>
                {term === 'hours' && (
                  <label className="field">
                    Часов
                    <input
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={MAX_HOURS}
                      value={hours}
                      onChange={(e) => setHours(e.target.value)}
                    />
                  </label>
                )}
              </div>
              <div className="filter-row">
                <button
                  type="button"
                  disabled={busy || (term === 'hours' && !hoursValid)}
                  onClick={() => void act(() => grantAdminAccess(
                    term === 'hours' ? hoursValue : null), 'Доступ не открылся')}
                >
                  Дать доступ администратору
                </button>
                {state.grant !== null && (
                  <button
                    type="button"
                    className="button--ghost"
                    disabled={busy}
                    onClick={() => void act(revokeAdminAccess, 'Доступ не отозван')}
                  >
                    Отозвать доступ
                  </button>
                )}
                {state.request !== null && state.grant === null && (
                  <button
                    type="button"
                    className="button--ghost"
                    disabled={busy}
                    onClick={() => void act(declineAdminAccess, 'Запрос не отклонён')}
                  >
                    Отклонить запрос
                  </button>
                )}
              </div>
              {term === 'hours' && !hoursValid && (
                <p className="note">Часов — целое число от 1 до {MAX_HOURS}.</p>
              )}
            </>
          )}
        </>
      )}
      </>
      )}
    </div>
  );

  async function act(call: () => Promise<AdminAccessState>, fallback: string): Promise<void> {
    setBusy(true);
    try {
      const next = await call();
      if (mounted.current) {
        setState(normalized(next));
        setError('');
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, fallback));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

/**
 * Ответ сервера — обещание, а не гарантия (`docs/frontend-rules.md`, §3):
 * пропавшее поле читается как «нет», а не роняет экран сотрудников целиком.
 */
function normalized(state: Partial<AdminAccessState> | null): AdminAccessState {
  return { grant: state?.grant ?? null, request: state?.request ?? null };
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
