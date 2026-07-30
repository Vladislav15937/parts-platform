import { SessionProvider, useSession } from './auth/SessionProvider';
import { LoginScreen } from './screens/LoginScreen';
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
  return state.status === 'authenticated' ? <HomeScreen /> : <LoginScreen />;
}
