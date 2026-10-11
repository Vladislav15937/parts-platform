import { useEffect, useState } from 'react';
import type { FormEvent, MouseEvent } from 'react';
import { ApiError } from '../api/client';
import { useSession } from '../auth/SessionProvider';
import { useMounted } from '../ui/useMounted';

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
export const COMPANY_KEY = 'partsflow.company';

/**
 * @param onRegister переключить общую страницу на вкладку регистрации
 *     (задача 0265). Без него ссылка ведёт на {@code /register} обычной
 *     загрузкой — так она работает и открытой в новой вкладке.
 */
export function LoginScreen({ onRegister }: { onRegister?: () => void } = {}) {
  // Заголовок вкладки возвращается к входу: он следует за разделом, и после
  // потери сессии на экране входа осталось бы «Очередь отправки — PartsFlow».
  useEffect(() => {
    document.title = 'Вход — PartsFlow';
  }, []);

  const { signIn, state } = useSession();
  const [company, setCompany] = useState(() => localStorage.getItem(COMPANY_KEY) ?? '');
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await signIn({ company, login, password });
      localStorage.setItem(COMPANY_KEY, company);
    } catch (cause) {
      if (mounted.current) {
        setError(
          cause instanceof ApiError && cause.kind === 'transient'
            ? 'Нет связи с сервером'
            : 'Неверный код компании, логин или пароль',
        );
      }
    } finally {
      if (mounted.current) setBusy(false);
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

      <a href="/register" onClick={(e) => follow(e, onRegister)}>Зарегистрировать компанию</a>
    </form>
  );
}

/**
 * Ссылка между вкладками общей страницы: обычное нажатие переключает
 * вкладку без загрузки, а открытие в новой вкладке (с Ctrl, Cmd, Shift
 * или средней кнопкой) остаётся браузеру — адрес у ссылки настоящий.
 */
export function follow(event: MouseEvent<HTMLAnchorElement>, action: (() => void) | undefined): void {
  if (action === undefined) return;
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  action();
}
