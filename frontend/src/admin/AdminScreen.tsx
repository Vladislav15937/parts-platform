import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { momentText } from '../organization/adminAccess';
import { useMounted } from '../ui/useMounted';
import { navigation } from './navigation';
import {
  adminLogin,
  adminLogout,
  adminMe,
  enterCabinet,
  leaveCabinet,
  remainingText,
  requestAccess,
  serviceTenants,
  type AdminMe,
  type TenantAccess,
} from './adminApi';

/** Адрес системы администрирования: своя страница со своим входом. */
export const ADMIN_PATH = '/admin';

type Who =
  | { kind: 'checking' }
  | { kind: 'anonymous'; reason?: string }
  | { kind: 'signed'; me: AdminMe };

/**
 * Система администрирования сервиса (задача 0042).
 *
 * <p><b>Отдельная страница, а не вкладка кабинета.</b> Решение владельца
 * продукта от 8 сентября 2026: «разборка выбирается только в административном
 * окне, живущем отдельно от всех разборок». Список клиентов сервиса не
 * появляется ни в одном кабинете — он здесь, за своим входом, и сервер
 * не отдаёт его никому, кроме администратора.
 *
 * <p>Войти в кабинет можно только при действующем разрешении владельца;
 * без него — попросить кнопкой («просит кнопкой, также может в разговоре»).
 * После входа приложение открывается кабинетом разборки, с именем
 * администратора в шапке; вернуться сюда — кнопкой там же.
 */
export function AdminScreen() {
  const [who, setWho] = useState<Who>({ kind: 'checking' });
  const mounted = useMounted();

  // Заголовок вкладки свой: в index.html прибита «Приёмка», и вкладка
  // администрирования рядом с кабинетом клиента называлась бы его разделом
  // (найдено живым прогоном).
  useEffect(() => {
    document.title = 'Администрирование сервиса — PartsFlow';
  }, []);

  useEffect(() => {
    adminMe()
      .then((me) => { if (mounted.current) setWho({ kind: 'signed', me }); })
      .catch((cause) => {
        if (!mounted.current) return;
        // «Не вошёл» и «не смогли узнать» — разные вещи: второе не повод
        // показывать форму входа как будто сессии нет.
        const transient = cause instanceof ApiError && cause.kind === 'transient';
        setWho(transient
          ? { kind: 'anonymous', reason: 'Сервер не отвечает — проверьте связь и обновите страницу.' }
          : { kind: 'anonymous' });
      });
  }, [mounted]);

  if (who.kind === 'checking') {
    return <div className="screen screen--center">Проверяем сессию…</div>;
  }
  if (who.kind === 'anonymous') {
    return (
      <AdminLogin
        reason={who.reason}
        onSignedIn={(me) => setWho({ kind: 'signed', me })}
      />
    );
  }
  return (
    <AdminHome
      me={who.me}
      onMe={(me) => setWho({ kind: 'signed', me })}
      onSignedOut={() => setWho({ kind: 'anonymous' })}
    />
  );
}

function AdminLogin({ reason, onSignedIn }: {
  reason: string | undefined;
  onSignedIn: (me: AdminMe) => void;
}) {
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(reason ?? '');
  const [busy, setBusy] = useState(false);
  const mounted = useMounted();

  return (
    <section className="screen screen--center">
      <h2>Администрирование сервиса</h2>
      <form
        className="card"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label className="field">
          Логин
          <input value={login} autoCapitalize="none" onChange={(e) => setLogin(e.target.value)} />
        </label>
        <label className="field">
          Пароль
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error !== '' && <p className="note note--error">{error}</p>}
        <button type="submit" disabled={busy || login.trim() === '' || password === ''}>
          {busy ? 'Входим…' : 'Войти'}
        </button>
      </form>
    </section>
  );

  async function submit(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      const me = await adminLogin(login.trim(), password);
      if (mounted.current) onSignedIn(me);
    } catch (cause) {
      if (!mounted.current) return;
      setError(cause instanceof ApiError && cause.kind === 'unauthenticated'
        ? 'Неверный логин или пароль'
        : describe(cause, 'Войти не вышло'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function AdminHome({ me, onMe, onSignedOut }: {
  me: AdminMe;
  onMe: (me: AdminMe) => void;
  onSignedOut: () => void;
}) {
  const [tenants, setTenants] = useState<TenantAccess[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const mounted = useMounted();
  const inside = me.visit !== null;

  const reload = useCallback(() => {
    if (inside) {
      // Из кабинета список не читается: сначала выйти из него.
      return;
    }
    serviceTenants()
      .then((found) => {
        if (mounted.current) {
          setTenants(found);
          setError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Список компаний не загрузился'));
      });
  }, [inside, mounted]);

  useEffect(reload, [reload]);

  return (
    <section className="screen screen--wide">
      <header className="filter-row">
        <h2>Администрирование сервиса</h2>
        <span className="muted">{me.displayName}</span>
        <button type="button" className="button--ghost" disabled={busy} onClick={() => void signOut()}>
          Выйти
        </button>
      </header>

      {error !== '' && <p className="note note--error">{error}</p>}

      {me.visit !== null ? (
        <div className="card">
          <p>
            Сейчас вы в кабинете «{me.visit.companyName ?? me.visit.code ?? 'компании'}».
          </p>
          <div className="filter-row">
            <button type="button" onClick={() => navigation.go('/')}>
              Вернуться в кабинет
            </button>
            <button type="button" className="button--ghost" disabled={busy} onClick={() => void leave()}>
              Выйти из кабинета
            </button>
          </div>
        </div>
      ) : tenants === null ? (
        error !== '' ? null : <p className="note">Загружаем…</p>
      ) : tenants.length === 0 ? (
        <p className="note">Компаний в ячейке нет.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Компания</th>
                <th>Код</th>
                <th>Доступ</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {tenants.map((tenant) => (
                <tr key={tenant.code}>
                  <td>{tenant.companyName}</td>
                  <td>{tenant.code}</td>
                  <td>{accessText(tenant)}</td>
                  <td>
                    {tenant.grant !== null ? (
                      <button type="button" disabled={busy} onClick={() => void enter(tenant.code)}>
                        Войти в кабинет
                      </button>
                    ) : tenant.requestedAt !== null ? null : (
                      <button
                        type="button"
                        className="button--ghost"
                        disabled={busy}
                        onClick={() => void ask(tenant.code)}
                      >
                        Запросить доступ
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );

  async function enter(code: string): Promise<void> {
    setBusy(true);
    try {
      await enterCabinet(code);
      // Кабинет — это приложение целиком, со своей сессией и своими
      // справочниками: открывается заново, а не подменяется здесь.
      navigation.go('/');
    } catch (cause) {
      if (mounted.current) {
        setError(describe(cause, 'Войти в кабинет не вышло'));
        setBusy(false);
        reload();
      }
    }
  }

  async function ask(code: string): Promise<void> {
    setBusy(true);
    try {
      await requestAccess(code);
      if (mounted.current) reload();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Запрос не отправлен'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function leave(): Promise<void> {
    setBusy(true);
    try {
      const back = await leaveCabinet();
      if (mounted.current) onMe(back);
    } catch (cause) {
      if (!mounted.current) return;
      if (cause instanceof ApiError && cause.kind === 'unauthenticated') {
        onSignedOut();
      } else {
        setError(describe(cause, 'Выйти из кабинета не вышло'));
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function signOut(): Promise<void> {
    setBusy(true);
    try {
      await adminLogout();
    } catch {
      // Сессия уже кончилась — выходить нечем, форма входа всё равно нужна.
    } finally {
      if (mounted.current) {
        setBusy(false);
        onSignedOut();
      }
    }
  }
}

/**
 * Состояние доступа словами. Срок — и моментом, и остатком: администратор
 * планирует работу по тому, сколько осталось (критерий 5 задачи 0042).
 */
export function accessText(tenant: TenantAccess, now: number = Date.now()): string {
  if (tenant.grant !== null) {
    return tenant.grant.expiresAt === null
      ? 'Открыт до отзыва'
      : `Открыт до ${momentText(tenant.grant.expiresAt)} · осталось ${remainingText(tenant.grant.expiresAt, now)}`;
  }
  if (tenant.requestedAt !== null) {
    return `Запрошен ${momentText(tenant.requestedAt)} — ждёт владельца`;
  }
  return 'Нет доступа';
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
