import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, SESSION_LOST, ensureCsrfToken, refreshCsrfToken } from '../api/client';
import * as auth from '../api/auth';
import type { Credentials, Me } from '../api/auth';
import { scopeTo } from '../storage/tenantScope';

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
        // Кэши принадлежат компании: войдя другой на том же устройстве,
        // кладовщик видел бы её лист обхода, ячейки и машины. Очередь
        // при этом не трогаем — в ней несделанная работа приёмщика.
        await scopeTo(current.companySchema);
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

  /*
   * Сессия кончилась — просим войти заново, а не показываем «401» на каждом
   * экране.
   *
   * Сессия истекает по таймауту и умирает при выкладке — то есть регулярно.
   * До этого приложение оставалось «вошедшим»: шапка писала «владелец ·
   * на связи», рельс работал, а экраны один за другим встречали владельца
   * красной строкой «Запрос отклонён (401)». Догадаться по ней, что дело
   * в сессии, нельзя.
   *
   * Личность стирается — та же причина, что и при 401 на старте: сессия
   * кончилась по-настоящему, и офлайн-режим тут не при чём. Очередь
   * отправки при этом цела: она ждёт входа и уйдёт следующим проходом.
   */
  useEffect(() => {
    const onLost = () => {
      forgetMe();
      setState((was) => (was.status === 'authenticated'
        ? { status: 'anonymous', reason: 'Сессия кончилась — войдите заново.' }
        : was));
    };
    window.addEventListener(SESSION_LOST, onLost);
    return () => window.removeEventListener(SESSION_LOST, onLost);
  }, []);

  const signIn = useCallback(async (credentials: Credentials) => {
    /*
     * Токен берётся заново, а не «если его нет».
     *
     * Cookie с токеном переживает сессию, которой он принадлежал: утром
     * в браузере лежит вчерашний, `ensureCsrfToken` видит его и молчит,
     * а сервер такой вход отвергает. И отвергает **401 с пустым телом** —
     * ровно тем же ответом, что и неверный пароль: на входе человек ещё
     * анонимен, поэтому отказ CSRF уходит через точку входа
     * аутентификации, а не как 403. Значит ни человек, ни повтор
     * по 403 в `request` отличить одно от другого не могут — владелец
     * с верным паролем читает «неверный логин или пароль» и заперт
     * снаружи, пока не догадается почистить cookie.
     *
     * Лишний запрос тут ничего не стоит: вход и так создаёт сессию заново.
     */
    await refreshCsrfToken();
    const current = await auth.login(credentials);
    rememberMe(current);
    await scopeTo(current.companySchema);
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
