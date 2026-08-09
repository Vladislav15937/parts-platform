import { SessionProvider, useSession } from './auth/SessionProvider';
import { LoginScreen } from './screens/LoginScreen';
import { SharedDealScreen } from './screens/SharedDealScreen';
import { HomeScreen } from './screens/HomeScreen';

/**
 * Роутера нет намеренно.
 *
 * <p>Экранов под рельсом почти три десятка, но переход между ними — это
 * вкладка, а не адрес: приёмщик не набирает URL, а приложение обязано
 * открываться там, где его закрыли. Здесь решается только одно —
 * вошёл человек или нет.
 *
 * <p>Единственный разбор пути ниже — ссылка клиенту на его сделку: у него
 * учётной записи нет и не будет, и адрес тут единственный способ сказать,
 * какую сделку показать.
 */
export function App() {
  return (
    <SessionProvider>
      <Root />
    </SessionProvider>
  );
}

function Root() {
  const { state } = useSession();

  if (state.status === 'checking') {
    // Отдельное состояние, чтобы экран входа не мигал у уже вошедшего.
    return <div className="screen screen--center">Проверяем сессию…</div>;
  }
  // Ссылка на сделку открывается без входа: у покупателя учётной записи нет.
  // Проверяется раньше сессии — иначе продавец, открывший ссылку со своего
  // телефона, увидел бы рабочий экран вместо того, что видит клиент.
  const shared = /^\/s\/([^/]+)\/([^/]+)$/.exec(window.location.pathname);
  if (shared !== null && shared[1] !== undefined && shared[2] !== undefined) {
    return <SharedDealScreen company={shared[1]} token={shared[2]} />;
  }

  return state.status === 'authenticated' ? <HomeScreen /> : <LoginScreen />;
}
