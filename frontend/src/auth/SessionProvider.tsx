import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, ensureCsrfToken } from '../api/client';
import * as auth from '../api/auth';
import type { Credentials, Me } from '../api/auth';

/**
 * Сессия приложения.
 *
 * <p>При старте спрашиваем «кто я»: сессия могла остаться живой с прошлого
 * запуска, и заставлять приёмщика логиниться каждое утро — это минута на
 * человека в день и повод не пользоваться системой.
 *
 * <p>Состояние «проверяем» отделено от «не вошёл» намеренно: без него экран
 * входа мигает на каждом запуске у уже вошедшего.
 */
type SessionState =
  | { status: 'checking' }
  | { status: 'anonymous'; reason?: string }
  | { status: 'authenticated'; me: Me };

interface SessionApi {
  state: SessionState;
  signIn(credentials: Credentials): Promise<void>;
  signOut(): Promise<void>;
}

const SessionContext = createContext<SessionApi | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: 'checking' });

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        // Токен нужен до первого изменяющего запроса, включая вход.
        await ensureCsrfToken();
        const current = await auth.me();
        if (!cancelled) {
          setState({ status: 'authenticated', me: current });
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        // 401 при старте — норма: сессии просто нет. Нет связи — тоже
        // не повод показывать ошибку: приложение обязано открываться офлайн,
        // иначе очередь недоступна и работа приёмщика встаёт.
        const reason =
          error instanceof ApiError && error.kind === 'transient'
            ? 'Нет связи. Войти можно, когда она появится.'
            : undefined;
        setState(reason === undefined ? { status: 'anonymous' } : { status: 'anonymous', reason });
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (credentials: Credentials) => {
    await ensureCsrfToken();
    const current = await auth.login(credentials);
    setState({ status: 'authenticated', me: current });
  }, []);

  const signOut = useCallback(async () => {
    try {
      await auth.logout();
    } finally {
      // Даже если выход не дошёл до сервера, локально считаем себя вышедшими:
      // иначе приёмщик остаётся в чужой смене.
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
