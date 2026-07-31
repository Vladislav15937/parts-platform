import { SessionProvider, useSession } from './auth/SessionProvider';
import { LoginScreen } from './screens/LoginScreen';
import { SharedDealScreen } from './screens/SharedDealScreen';
import { HomeScreen } from './screens/HomeScreen';

/**
 * Роутера пока нет намеренно.
 *
 * <p>Экранов два, и переход между ними определяется не адресом, а тем, вошёл
 * ли человек. Ставить роутер до того, как появятся экраны приёмки, — это
 * зависимость под задачу, которой ещё нет.
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
