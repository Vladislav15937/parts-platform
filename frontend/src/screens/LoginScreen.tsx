import { useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError } from '../api/client';
import { useSession } from '../auth/SessionProvider';

/**
 * Вход.
 *
 * <p>Три поля, потому что учётные записи живут в схеме арендатора и код компании
 * нужен до проверки пароля. Код запоминается в localStorage: на складском
 * телефоне компания одна и та же всегда, и набирать её каждый раз — раздражение
 * без причины.
 *
 * <p>Сообщение об ошибке одно на все случаи, как и на бэкенде: различать
 * «нет такой компании» и «неверный пароль» значит сделать форму входа
 * справочником действующих компаний.
 */
const COMPANY_KEY = 'partsflow.company';

export function LoginScreen() {
  const { signIn, state } = useSession();
  const [company, setCompany] = useState(() => localStorage.getItem(COMPANY_KEY) ?? '');
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await signIn({ company, login, password });
      localStorage.setItem(COMPANY_KEY, company);
    } catch (cause) {
      setError(
        cause instanceof ApiError && cause.kind === 'transient'
          ? 'Нет связи с сервером'
          : 'Неверный код компании, логин или пароль',
      );
    } finally {
      setBusy(false);
    }
  }

  const offlineNote = state.status === 'anonymous' ? state.reason : undefined;

  return (
    <form className="screen screen--narrow" onSubmit={submit}>
      <h1>Вход</h1>

      {offlineNote !== undefined && <p className="note">{offlineNote}</p>}

      <label>
        Компания
        <input
          value={company}
          onChange={(e) => setCompany(e.target.value)}
          autoCapitalize="none"
          autoCorrect="off"
          required
        />
      </label>

      <label>
        Логин
        <input
          value={login}
          onChange={(e) => setLogin(e.target.value)}
          autoCapitalize="none"
          autoCorrect="off"
          autoComplete="username"
          required
        />
      </label>

      <label>
        Пароль
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
        />
      </label>

      {error !== null && <p className="error">{error}</p>}

      <button type="submit" disabled={busy}>
        {busy ? 'Проверяем…' : 'Войти'}
      </button>
    </form>
  );
}
