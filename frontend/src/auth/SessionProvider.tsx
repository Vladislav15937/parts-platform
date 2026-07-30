import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, ensureCsrfToken } from '../api/client';
import * as auth from '../api/auth';
import type { Credentials, Me } from '../api/auth';

/**
 * Сессия приложения.
 *
 * <p><b>Приложение обязано открываться без связи.</b> Иначе приёмщик, у которого
 * в ангаре нет сети, видит экран входа и заперт снаружи — вместе с уже собранной
 * очередью отправки и загруженными справочниками. Работа встаёт при полностью
 * исправной системе.
 *
 * <p>Поэтому личность вошедшего запоминается локально, и при <b>временной</b>
 * ошибке (нет связи, сервер не отвечает) приложение открывается на ней,
 * пометив себя как работающее офлайн. Это не обход проверки: доступ к данным
 * даёт cookie сессии, а офлайн никаких серверных данных и нет. Как только связь
 * появится, запросы либо пройдут, либо вернут 401 — и тогда попросим войти
 * заново, не теряя очередь.
 *
 * <p>При 401 личность стирается: сессия действительно кончилась.
 */
const ME_KEY = 'partsflow.me';

type SessionState =
  | { status: 'checking' }
  | { status: 'anonymous'; reason?: string }
  /** @param offline личность восстановлена локально, сервер не подтверждал */
  | { status: 'authenticated'; me: Me; offline: boolean };

interface SessionApi {
  state: SessionState;
  signIn(credentials: Credentials): Promise<void>;
  signOut(): Promise<void>;
}

const SessionContext = createContext<SessionApi | null>(null);

function rememberMe(me: Me): void {
  try {
    localStorage.setItem(ME_KEY, JSON.stringify(me));
  } catch {
    // Приватный режим может запретить запись — офлайн-старт просто не сработает.
  }
}

function recallMe(): Me | null {
  try {
    const raw = localStorage.getItem(ME_KEY);
    return raw === null ? null : (JSON.parse(raw) as Me);
  } catch {
    return null;
  }
}

function forgetMe(): void {
  try {
    localStorage.removeItem(ME_KEY);
  } catch {
    // Нечего чистить — и ладно.
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: 'checking' });

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        await ensureCsrfToken();
        const current = await auth.me();
        if (cancelled) {
          return;
        }
        rememberMe(current);
        setState({ status: 'authenticated', me: current, offline: false });
      } catch (error) {
        if (cancelled) {
          return;
        }
        const transient = error instanceof ApiError && error.kind === 'transient';
        const remembered = transient ? recallMe() : null;

        if (remembered !== null) {
          // Связи нет, но входили здесь же — открываемся на локальной личности.
          setState({ status: 'authenticated', me: remembered, offline: true });
          return;
        }
        if (!transient) {
          // 401: сессия кончилась по-настоящему.
          forgetMe();
        }
        setState(
          transient
            ? { status: 'anonymous', reason: 'Нет связи. Войти можно, когда она появится.' }
            : { status: 'anonymous' },
        );
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (credentials: Credentials) => {
    await ensureCsrfToken();
    const current = await auth.login(credentials);
    rememberMe(current);
    setState({ status: 'authenticated', me: current, offline: false });
  }, []);

  const signOut = useCallback(async () => {
    try {
      await auth.logout();
    } finally {
      // Даже если выход не дошёл до сервера, локально считаем себя вышедшими:
      // иначе приёмщик остаётся в чужой смене.
      forgetMe();
      setState({ status: 'anonymous' });
    }
  }, []);

  return (
    <SessionContext.Provider value={{ state, signIn, signOut }}>{children}</SessionContext.Provider>
  );
}

export function useSession(): SessionApi {
  const context = useContext(SessionContext);
  if (context === null) {
    throw new Error('useSession вне SessionProvider');
  }
  return context;
}
