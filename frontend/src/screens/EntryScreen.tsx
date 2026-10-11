import { useEffect, useState } from 'react';
import { LoginScreen } from './LoginScreen';
import { RegistrationScreen } from './RegistrationScreen';

/** Адрес общей страницы и адрес её вкладки регистрации (задача 0265). */
export const LOGIN_PATH = '/login';
export const REGISTER_PATH = '/register';

export type EntryTab = 'login' | 'register';

const TITLES: Record<EntryTab, string> = {
  login: 'Вход — PartsFlow',
  register: 'Регистрация компании — PartsFlow',
};

/**
 * Вход и регистрация — одна страница с двумя вкладками (задача 0265, запрос
 * владельца 11 октября 2026): человек, пришедший без входа, не обязан знать
 * заранее, какой адрес для чего.
 *
 * <p><b>Вкладка регистрации монтируется при первом открытии и дальше
 * не снимается, а прячется.</b> Не монтировать сразу — потому что форма
 * на монтировании берёт задание и начинает подбирать решение: считать его
 * каждому, кто пришёл войти, значит греть телефон кладовщика ради чужой
 * формы. Не снимать при уходе — потому что снятая форма теряет набранное,
 * а вместе с ним ключ повтора и уже посчитанное решение. Вход дешёвый
 * и стоит смонтированным всегда — по той же причине набранное в нём
 * переживает переключение.
 *
 * <p><b>Вкладка отражается в адресе заменой записи истории.</b> Обновивший
 * страницу остаётся на той вкладке, которую видел, а «Назад» не ходит
 * между вкладками: это одна страница, а не две.
 */
export function EntryScreen({ initial }: { initial: EntryTab }) {
  const [tab, setTab] = useState<EntryTab>(initial);
  const [registrationOpened, setRegistrationOpened] = useState(initial === 'register');

  // Заголовок — по открытой вкладке. Свои заголовки экраны ставят при
  // монтировании, а смонтированы здесь оба; эффект родителя идёт после
  // эффектов детей, поэтому последнее слово за ним.
  useEffect(() => {
    document.title = TITLES[tab];
  }, [tab]);

  function open(next: EntryTab): void {
    if (next === 'register') setRegistrationOpened(true);
    setTab(next);
    const path = next === 'register' ? REGISTER_PATH : LOGIN_PATH;
    if (window.location.pathname !== path) {
      window.history.replaceState(window.history.state, '', path);
    }
  }

  return (
    <div className="entry">
      <div className="tabs entry__tabs" role="tablist" aria-label="Вход или регистрация">
        <button
          type="button"
          role="tab"
          id="entry-tab-login"
          aria-selected={tab === 'login'}
          aria-controls="entry-panel-login"
          className={tab === 'login' ? 'tab tab--active' : 'tab'}
          onClick={() => open('login')}
        >
          Вход
        </button>
        <button
          type="button"
          role="tab"
          id="entry-tab-register"
          aria-selected={tab === 'register'}
          aria-controls="entry-panel-register"
          className={tab === 'register' ? 'tab tab--active' : 'tab'}
          onClick={() => open('register')}
        >
          Регистрация
        </button>
      </div>

      <div role="tabpanel" id="entry-panel-login" aria-labelledby="entry-tab-login"
           hidden={tab !== 'login'}>
        <LoginScreen onRegister={() => open('register')} />
      </div>

      {registrationOpened && (
        <div role="tabpanel" id="entry-panel-register" aria-labelledby="entry-tab-register"
             hidden={tab !== 'register'}>
          <RegistrationScreen onSignIn={() => open('login')} />
        </div>
      )}
    </div>
  );
}
