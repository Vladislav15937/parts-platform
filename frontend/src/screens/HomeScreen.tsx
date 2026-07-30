import { useSession } from '../auth/SessionProvider';
import { ReferencePanel } from '../reference/ReferencePanel';
import { useOnline } from '../shell/useOnline';

/**
 * Оболочка после входа.
 *
 * <p>Экранов приёмки здесь пока нет — это каркас. Что уже есть и должно быть
 * именно здесь: кто вошёл и есть ли связь. Признак связи виден всегда,
 * а не всплывает при ошибке: приёмщик должен понимать, уходит его работа
 * на сервер или накапливается, до того как накопит смену.
 */
export function HomeScreen() {
  const { state, signOut } = useSession();
  const online = useOnline();

  if (state.status !== 'authenticated') {
    return null;
  }

  // Личность, восстановленная локально, — тоже признак отсутствия связи,
  // и более достоверный, чем navigator.onLine: сервер только что не ответил.
  const connected = online && !state.offline;

  return (
    <div className="screen">
      <header className="header">
        <div>
          <strong>{state.me.displayName}</strong>
          <span className="muted"> · {roleName(state.me.role)}</span>
        </div>
        <span className={connected ? 'badge badge--online' : 'badge badge--offline'}>
          {connected ? 'на связи' : 'без связи'}
        </span>
      </header>

      {state.offline && (
        <p className="note">
          Работаем без связи. Вход подтвердится, когда сеть появится; собранное
          до тех пор не потеряется.
        </p>
      )}

      <ReferencePanel />

      <p className="note">
        Экраны приёмки — следующие шаги, см. <code>docs/pwa-intake-plan.md §6</code>.
      </p>

      <ul className="todo">
        <li>Выбор донора</li>
        <li>Приёмка детали</li>
        <li>Партия</li>
        <li>Очередь отправки</li>
        <li>Инвентаризация</li>
      </ul>

      <button type="button" onClick={signOut}>
        Выйти
      </button>
    </div>
  );
}

function roleName(role: string): string {
  switch (role) {
    case 'OWNER':
      return 'владелец';
    case 'MANAGER':
      return 'менеджер';
    case 'STOREKEEPER':
      return 'кладовщик';
    case 'SELLER':
      return 'продавец';
    default:
      return 'просмотр';
  }
}
