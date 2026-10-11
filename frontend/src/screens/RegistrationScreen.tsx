import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError } from '../api/client';
import { fetchChallenge, registerCompany } from '../api/registration';
import type { Registered } from '../api/registration';
import { solveChallenge } from '../auth/proofOfWork';
import { COMPANY_KEY } from './LoginScreen';
import { useMounted } from '../ui/useMounted';

/**
 * Регистрация компании клиентом (задача 0184): открыть страницу, заполнить
 * форму и войти в свою компанию владельцем — не связываясь ни с кем.
 *
 * <p>Подтверждения почты пока нет — решение владельца продукта от 11 октября
 * 2026: компания работает сразу, а проверка адреса встанет отдельной задачей,
 * когда появится оператор писем. Экран про это молчит намеренно: обещать
 * письмо, которого никто не пришлёт, хуже, чем не обещать.
 *
 * <p><b>Три состояния до формы различимы.</b> «Грузим» — берём задание
 * у сервера; «закрыто» — потолок числа компаний, об этом человек узнаёт
 * сразу, а не после заполнения; «не смогли узнать» — сервера не слышно,
 * и это не то же самое, что «регистрация закрыта».
 *
 * <p><b>Что проверяется до отправки.</b> Код компании и пароль — теми же
 * правилами, что на сервере, и сказано это словами под полем, пока человек
 * ещё печатает. Занятый код и занятую почту знает только сервер, и его слова
 * показываются как есть.
 *
 * <p><b>Вычислительная проверка считается в фоне, пока заполняется форма</b>
 * ({@link solveChallenge}) — к нажатию кнопки решение, как правило, готово.
 *
 * <p><b>Повтор безопасен.</b> Ключ повтора рождается вместе с формой и не
 * меняется при повторных нажатиях: ответ не дошёл, человек нажал ещё раз —
 * вторая компания не появится, а ответ придёт тот же.
 */

/** Те же правила, что в {@code RegistrationService}: разойдясь, форма врала бы. */
const CODE_PATTERN = /^[a-z0-9][a-z0-9-]{1,30}$/;
const EMAIL_PATTERN = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/;
const MIN_PASSWORD = 8;
const MAX_PASSWORD_BYTES = 72;

export function codeProblem(code: string): string | null {
  if (code === '') return null;
  return CODE_PATTERN.test(code)
    ? null
    : 'Код компании — от 2 до 31 символа: латиница, цифры и дефис, начинается с буквы или цифры';
}

export function passwordProblem(password: string): string | null {
  if (password === '') return null;
  if (password.length < MIN_PASSWORD) {
    return `Пароль — не короче ${MIN_PASSWORD} символов`;
  }
  // Хеш считается по первым 72 байтам: длиннее — хвост пароля не работал бы.
  if (new TextEncoder().encode(password).length > MAX_PASSWORD_BYTES) {
    return 'Пароль слишком длинный: не больше 72 байт (русские буквы занимают по две — около 36 букв)';
  }
  return null;
}

export function emailProblem(email: string): string | null {
  if (email.trim() === '') return null;
  return EMAIL_PATTERN.test(email.trim()) ? null : 'Укажите почту так, как она пишется: имя@домен.ру';
}

/** Ключ повтора. `randomUUID` есть только в защищённом контексте — на http запасной путь. */
function newRequestId(): string {
  const crypto = globalThis.crypto;
  if (typeof crypto?.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

type Phase =
  | { kind: 'loading' }
  /** Потолок числа компаний: слова сервера, форма не показывается. */
  | { kind: 'closed'; message: string }
  /** Сервер не ответил: не «закрыто» и не «пусто». */
  | { kind: 'unreachable'; message: string }
  | { kind: 'form' }
  | { kind: 'done'; result: Registered; login: string };

/** Задание и его решение, считаемое в фоне. */
interface Proof {
  solution: Promise<{ challenge: string; solution: string }>;
}

export function RegistrationScreen() {
  useEffect(() => {
    document.title = 'Регистрация компании — PartsFlow';
  }, []);

  const mounted = useMounted();
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [companyName, setCompanyName] = useState('');
  const [companyCode, setCompanyCode] = useState('');
  const [email, setEmail] = useState('');
  const [ownerName, setOwnerName] = useState('');
  const [ownerLogin, setOwnerLogin] = useState('');
  const [ownerPassword, setOwnerPassword] = useState('');

  const requestId = useRef<string | null>(null);
  if (requestId.current === null) {
    requestId.current = newRequestId();
  }
  const proof = useRef<Proof | null>(null);

  /** Берёт задание и начинает считать; возвращает ошибку словами, если не вышло. */
  const prepare = useCallback(async (): Promise<string | 'closed' | null> => {
    try {
      const issued = await fetchChallenge();
      if (!mounted.current) return null;
      proof.current = {
        solution: solveChallenge(issued.challenge, issued.bits, () => !mounted.current)
          .then((solution) => ({ challenge: issued.challenge, solution })),
      };
      // Отказ вычисления (браузер не умеет, экран закрыли) показывается
      // тогда, когда на решение пришлось ждать, а не как необработанный отказ.
      proof.current.solution.catch(() => undefined);
      return null;
    } catch (cause) {
      if (!mounted.current) return null;
      if (cause instanceof ApiError && cause.status === 409) {
        setPhase({ kind: 'closed', message: cause.message });
        return 'closed';
      }
      return cause instanceof Error ? cause.message : 'Нет связи с сервером';
    }
  }, [mounted]);

  const load = useCallback(async () => {
    setPhase({ kind: 'loading' });
    const problem = await prepare();
    if (!mounted.current) return;
    if (problem === 'closed') return;
    if (problem !== null) {
      setPhase({ kind: 'unreachable', message: problem });
      return;
    }
    setPhase({ kind: 'form' });
  }, [prepare, mounted]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * Первое, что мешает отправить форму, — словами и тем же порядком полей,
   * что на экране. Проверка своя, а не браузерная ({@code noValidate}):
   * браузер скажет «заполните это поле» на языке своих настроек и покажет
   * пузырь поверх поля, а нам нужна строка, которую видно и читаемо.
   */
  function formProblem(): string | null {
    if (companyName.trim() === '') return 'Укажите название компании';
    if (companyCode.trim() === '') return 'Укажите код компании';
    const code = codeProblem(companyCode.trim());
    if (code !== null) return code;
    if (email.trim() === '') return 'Укажите почту';
    const mail = emailProblem(email);
    if (mail !== null) return mail;
    if (ownerLogin.trim() === '') return 'Укажите логин';
    if (ownerPassword === '') return `Пароль — не короче ${MIN_PASSWORD} символов`;
    return passwordProblem(ownerPassword);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setError(null);
    const problem = formProblem();
    if (problem !== null) {
      setError(problem);
      return;
    }

    setBusy(true);
    try {
      const code = companyCode.trim();
      const result = await send(code, false);
      if (!mounted.current) return;
      setPhase({ kind: 'done', result, login: ownerLogin.trim() });
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof Error ? cause.message : 'Не удалось зарегистрировать компанию');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  /**
   * Одна отправка; если сервер сказал, что проверка устарела или уже занята,
   * берёт новое задание и пробует ещё раз — один раз, без участия человека.
   */
  async function send(code: string, retried: boolean): Promise<Registered> {
    const current = proof.current;
    if (current === null) {
      throw new Error('Проверка «вы не робот» не подготовлена. Обновите страницу');
    }
    const solved = await current.solution;
    try {
      return await registerCompany({
        requestId: requestId.current ?? '',
        challenge: solved.challenge,
        solution: solved.solution,
        companyName: companyName.trim(),
        companyCode: code,
        email: email.trim(),
        ownerLogin: ownerLogin.trim(),
        ownerPassword,
        ownerName: ownerName.trim(),
      });
    } catch (cause) {
      if (!retried && cause instanceof ApiError && cause.kind === 'permanent'
        && cause.message.startsWith('Проверка')) {
        const problem = await prepare();
        if (problem !== null && problem !== 'closed') throw new Error(problem);
        if (problem === 'closed') throw cause;
        return send(code, true);
      }
      throw cause;
    }
  }

  if (phase.kind === 'loading') {
    return <p className="screen screen--narrow note">Загружаем…</p>;
  }

  if (phase.kind === 'closed') {
    return (
      <section className="screen screen--narrow">
        <h1>Регистрация компании</h1>
        <p className="error">{phase.message}</p>
        <a href="/">Войти в свою компанию</a>
      </section>
    );
  }

  if (phase.kind === 'unreachable') {
    return (
      <section className="screen screen--narrow">
        <h1>Регистрация компании</h1>
        <p className="error">{phase.message}</p>
        <button type="button" onClick={() => { void load(); }}>Повторить</button>
        <a href="/">Войти в свою компанию</a>
      </section>
    );
  }

  if (phase.kind === 'done') {
    const { result, login } = phase;
    return (
      <section className="screen screen--narrow">
        <h1>Компания зарегистрирована</h1>
        <p>
          «{result.companyName}» заведена. Чтобы войти, понадобятся код компании
          и логин — запишите их:
        </p>
        <p>
          Код компании: <strong>{result.companyCode}</strong>
          <br />
          Логин: <strong>{login}</strong>
        </p>
        <p className="note">Пароль — тот, что вы задали на этой странице.</p>
        <a
          className="button-link"
          href="/"
          onClick={() => {
            try {
              localStorage.setItem(COMPANY_KEY, result.companyCode);
            } catch {
              // Приватный режим может запретить запись — тогда код наберут руками.
            }
          }}
        >
          Войти
        </a>
      </section>
    );
  }

  return (
    <form className="screen screen--narrow" noValidate onSubmit={(e) => { void submit(e); }}>
      <h1>Регистрация компании</h1>
      <p className="note">
        Компания заводится сразу — войти можно через минуту.
      </p>

      <label>
        Название компании
        <input
          value={companyName}
          onChange={(e) => setCompanyName(e.target.value)}
          autoComplete="organization"
          maxLength={120}
        />
      </label>

      <label>
        Код компании
        <input
          value={companyCode}
          onChange={(e) => setCompanyCode(e.target.value.toLowerCase())}
          autoCapitalize="none"
          autoCorrect="off"
          maxLength={31}
          aria-describedby="code-hint"
        />
        <span id="code-hint" className={codeProblem(companyCode) === null ? 'note' : 'error'}>
          {codeProblem(companyCode)
            ?? 'Латиница, цифры и дефис, от 2 до 31 символа. По этому коду вы и ваши сотрудники входите в систему'}
        </span>
      </label>

      <label>
        Почта
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          autoComplete="email"
          autoCapitalize="none"
        />
        {emailProblem(email) !== null && <span className="error">{emailProblem(email)}</span>}
      </label>

      <label>
        Ваше имя (необязательно)
        <input
          value={ownerName}
          onChange={(e) => setOwnerName(e.target.value)}
          autoComplete="name"
          maxLength={120}
        />
      </label>

      <label>
        Логин
        <input
          value={ownerLogin}
          onChange={(e) => setOwnerLogin(e.target.value)}
          autoCapitalize="none"
          autoCorrect="off"
          autoComplete="username"
          maxLength={64}
        />
      </label>

      <label>
        Пароль
        <input
          type="password"
          value={ownerPassword}
          onChange={(e) => setOwnerPassword(e.target.value)}
          autoComplete="new-password"
        />
        <span className={passwordProblem(ownerPassword) === null ? 'note' : 'error'}>
          {passwordProblem(ownerPassword) ?? `Не короче ${MIN_PASSWORD} символов`}
        </span>
      </label>

      {error !== null && <p className="error">{error}</p>}

      <button type="submit" disabled={busy}>
        {busy ? 'Регистрируем…' : 'Зарегистрировать'}
      </button>

      <a href="/">Уже есть компания? Войти</a>
    </form>
  );
}
