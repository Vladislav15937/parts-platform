import { useEffect } from 'react';
import { SessionProvider, useSession } from './auth/SessionProvider';
import { EntryScreen, LOGIN_PATH, REGISTER_PATH } from './screens/EntryScreen';
import { SharedDealScreen } from './screens/SharedDealScreen';
import { HomeScreen } from './screens/HomeScreen';
import { ADMIN_PATH, AdminScreen } from './admin/AdminScreen';

/**
 * Роутера нет намеренно.
 *
 * <p>Экранов под рельсом почти три десятка, но переход между ними — это
 * вкладка, а не адрес: приёмщик не набирает URL, а приложение обязано
 * открываться там, где его закрыли. Здесь решается только одно —
 * вошёл человек или нет.
 *
 * <p>Разбор пути ниже — для двух экранов без входа. Ссылка клиенту на его
 * сделку: у него учётной записи нет и не будет, и адрес тут единственный
 * способ сказать, какую сделку показать. И общая страница входа
 * и регистрации ({@code /login}, вкладка регистрации — {@code /register}):
 * регистрацию открывают по ссылке с сайта, и входить там некому.
 *
 * <p><b>Невошедшего с любого адреса уводит на {@code /login}</b> (задача 0265),
 * заменой записи истории, а не новой: «Назад» не должен возвращать на закрытый
 * адрес, откуда страница тут же уведёт снова. Вошедшего с адреса входа — так же
 * на {@code /}: иначе после входа в строке остался бы адрес формы, и обновление
 * страницы вело бы себя иначе, чем до выхода сессии. На исходный адрес после
 * входа не возвращаем: роутера нет, адрес раздела не выбирает, и вернуть туда
 * значило бы пообещать то, чего приложение не сделает.
 */
export function App() {
  // Система администрирования сервиса (задача 0042) — своя страница со своим
  // входом и своей сессией, вне всех кабинетов: решение владельца продукта
  // от 8 сентября 2026, «разборка выбирается только в административном окне,
  // живущем отдельно от всех разборок». Разбирается до сессии кабинета:
  // её проверка тут ни к чему, а у вошедшего администратора кабинета нет.
  if (window.location.pathname === ADMIN_PATH) {
    return <AdminScreen />;
  }
  return (
    <SessionProvider>
      <Root />
    </SessionProvider>
  );
}

function Root() {
  const { state } = useSession();
  const path = window.location.pathname;
  const shared = /^\/s\/([^/]+)\/([^/]+)$/.exec(path);
  const isShared = shared !== null;
  const entryPath = path === LOGIN_PATH || path === REGISTER_PATH;

  // Адрес правится после отрисовки, а не во время неё: отрисовка обязана
  // быть чистой, а StrictMode прогоняет её дважды.
  useEffect(() => {
    if (state.status === 'checking' || isShared) return;
    const target = state.status === 'authenticated'
      ? (entryPath ? '/' : null)
      : (entryPath ? null : LOGIN_PATH);
    if (target !== null) {
      window.history.replaceState(window.history.state, '', target);
    }
  }, [state.status, isShared, entryPath]);

  if (state.status === 'checking') {
    // Отдельное состояние, чтобы экран входа не мигал у уже вошедшего.
    return <div className="screen screen--center">Проверяем сессию…</div>;
  }
  // Ссылка на сделку открывается без входа: у покупателя учётной записи нет.
  // Проверяется раньше сессии — иначе продавец, открывший ссылку со своего
  // телефона, увидел бы рабочий экран вместо того, что видит клиент.
  if (shared !== null && shared[1] !== undefined && shared[2] !== undefined) {
    return <SharedDealScreen company={shared[1]} token={shared[2]} />;
  }

  // Вошедшему адрес входа или регистрации ничего не даёт — он видит свою
  // работу, а не форму для чужой компании.
  if (state.status === 'authenticated') {
    return <HomeScreen />;
  }
  return <EntryScreen initial={path === REGISTER_PATH ? 'register' : 'login'} />;
}
