import { useEffect, useState } from 'react';
import { useSession } from '../auth/SessionProvider';
import { useOutbox } from '../outbox/useOutbox';
import { ReferencePanel } from '../reference/ReferencePanel';
import { useReference } from '../reference/useReference';
import { warmUpDecoder } from '../scan/decoder';
import { useOnline } from '../shell/useOnline';
import { IntakeScreen } from './IntakeScreen';
import { DonorScreen } from './DonorScreen';
import { ImportScreen } from './ImportScreen';
import { InventoryScreen } from './InventoryScreen';
import { OutboxScreen } from './OutboxScreen';
import { SellerScreen } from './SellerScreen';
import { UnmatchedScreen } from './UnmatchedScreen';
import { unmatchedNames } from '../catalog/partNames';

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
/** Кто имеет право продавать. Тот же список стоит на сервере в @PreAuthorize. */
const SELLING_ROLES = ['OWNER', 'MANAGER', 'SELLER'];

/** Кто правит справочник наименований. Тот же список в @PreAuthorize. */
const NAMING_ROLES = ['OWNER', 'MANAGER'];

type Tab =
  | 'intake'
  | 'donor'
  | 'sales'
  | 'inventory'
  | 'outbox'
  | 'import'
  | 'names'
  | 'reference';

export function HomeScreen() {
  const { state, signOut } = useSession();
  const online = useOnline();
  const { status, refresh: refreshReference } = useReference();
  const outbox = useOutbox();
  const [tab, setTab] = useState<Tab>('intake');
  // Число на вкладке — единственное, что сообщает о накопившемся: сам список
  // владелец не откроет, пока не узнает, что там что-то есть. После импорта
  // склада там сразу сотня.
  const [unmatched, setUnmatched] = useState(0);

  // Запасной распознаватель тянем сразу после входа, пока связь заведомо есть:
  // первое сканирование случится в ангаре, где её уже не будет.
  useEffect(warmUpDecoder, []);

  const role = state.status === 'authenticated' ? state.me.role : null;

  useEffect(() => {
    // Один запрос за вход, и только тем, кто может разбирать: приёмщику
    // это число ничего не даёт, а без связи оно и не приедет.
    if (role === null || !NAMING_ROLES.includes(role) || !online) {
      return;
    }
    void unmatchedNames(0, 1)
      .then((page) => setUnmatched(page.total))
      // Молча: справочник — не то, ради чего стоит показывать ошибку
      // на весь экран сразу после входа.
      .catch(() => setUnmatched(0));
  }, [role, online]);

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
          className={tab === 'donor' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('donor')}
        >
          Машина
        </button>
        <button
          type="button"
          className={tab === 'sales' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('sales')}
        >
          Продажа
        </button>
        <button
          type="button"
          className={tab === 'inventory' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('inventory')}
        >
          Пересчёт
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
          className={tab === 'import' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('import')}
        >
          Загрузка
        </button>
        <button
          type="button"
          className={tab === 'names' ? 'tab tab--active' : 'tab'}
          onClick={() => setTab('names')}
        >
          Наименования{unmatched > 0 && ` · ${unmatched}`}
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
            onSend={(payload, title, photos) => void outbox.add('receipt', payload, title, photos)}
          />
        ) : (
          <p className="note">
            Справочники не загружены — приёмка невозможна. Откройте вкладку
            «Справочники».
          </p>
        ))}

      {tab === 'donor' &&
        (status.kind === 'ready' ? (
          <DonorScreen
            reference={status.reference}
            online={connected}
            onCreated={() => {
              // Справочники приёмки перечитаются сами — новая машина должна
              // появиться в списке на экране деталей.
              void refreshReference();
            }}
          />
        ) : (
          <p className="note">Справочники не загружены. Откройте вкладку «Справочники».</p>
        ))}

      {tab === 'sales' &&
        (connected ? (
          <SellerScreen canSell={SELLING_ROLES.includes(state.me.role)} />
        ) : (
          <p className="note note--error">
            Нет связи. Продажа без неё невозможна: остаток из кэша — это деталь,
            которой уже нет, а отложенная в телефоне сделка ничего не резервирует.
          </p>
        ))}

      {tab === 'inventory' &&
        (status.kind === 'ready' ? (
          <InventoryScreen
            reference={status.reference}
            onCount={(sessionId, line, qty, countedAt) =>
              void outbox.add(
                'count',
                { sessionId, partId: line.partId, qty, countedAt },
                `${line.title} · ${qty} шт`,
              )
            }
          />
        ) : (
          <p className="note">
            Справочники не загружены — сканировать ячейки будет нечем. Откройте
            вкладку «Справочники».
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

      {tab === 'import' &&
        (status.kind === 'ready' ? (
          <ImportScreen
            reference={status.reference}
            canImport={state.me.role === 'OWNER'}
          />
        ) : (
          <p className="note">Справочники не загружены — некуда класть склад.</p>
        ))}

      {tab === 'names' &&
        (connected ? (
          <UnmatchedScreen
            canManage={NAMING_ROLES.includes(state.me.role)}
            onTotalChanged={setUnmatched}
          />
        ) : (
          <p className="note note--error">
            Нет связи. Сопоставление переписывает заголовки всех позиций под
            написанием разом — вслепую из очереди такое не отправляют.
          </p>
        ))}

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
