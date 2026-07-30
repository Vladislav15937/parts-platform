import { useState } from 'react';
import { useSession } from '../auth/SessionProvider';
import { useOutbox } from '../outbox/useOutbox';
import { ReferencePanel } from '../reference/ReferencePanel';
import { useReference } from '../reference/useReference';
import { useOnline } from '../shell/useOnline';
import { IntakeScreen } from './IntakeScreen';
import { OutboxScreen } from './OutboxScreen';

/**
 * Оболочка после входа.
 *
 * <p>Признак связи и число неотправленных видны всегда, а не всплывают при
 * ошибке: приёмщик должен понимать, уходит его работа на сервер или
 * накапливается, — до того как накопит смену.
 *
 * <p>Роутера по-прежнему нет: три вкладки переключаются состоянием. Адреса
 * экранов приёмщику не нужны, ссылками он не делится.
 */
type Tab = 'intake' | 'outbox' | 'reference';

export function HomeScreen() {
  const { state, signOut } = useSession();
  const online = useOnline();
  const { status } = useReference();
  const outbox = useOutbox();
  const [tab, setTab] = useState<Tab>('intake');

  if (state.status !== 'authenticated') {
    return null;
  }

  // Личность, восстановленная локально, — тоже признак отсутствия связи,
  // и более достоверный, чем navigator.onLine: сервер только что не ответил.
  const connected = online && !state.offline;
  const unsent = outbox.records.length;

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

      <nav className="tabs">
        <button
          type="button"
          className={tab === 'intake' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('intake')}
        >
          Приёмка
        </button>
        <button
          type="button"
          className={tab === 'outbox' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('outbox')}
        >
          Очередь{unsent > 0 && ` · ${unsent}`}
        </button>
        <button
          type="button"
          className={tab === 'reference' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('reference')}
        >
          Справочники
        </button>
      </nav>

      {tab === 'intake' &&
        (status.kind === 'ready' ? (
          <IntakeScreen
            reference={status.reference}
            onSend={(payload, title) => void outbox.add('receipt', payload, title)}
          />
        ) : (
          <p className="note">
            Справочники не загружены — приёмка невозможна. Откройте вкладку
            «Справочники».
          </p>
        ))}

      {tab === 'outbox' && (
        <OutboxScreen
          records={outbox.records}
          needsSignIn={outbox.needsSignIn}
          onRetry={(id) => void outbox.retry(id)}
          onDrop={(id) => void outbox.drop(id)}
        />
      )}

      {tab === 'reference' && <ReferencePanel />}

      <button type="button" className="button--ghost" onClick={signOut}>
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
